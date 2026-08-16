package com.corncan.automodfetcher.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOps;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Downloads a plan's files on background threads and reports progress the GUI can poll.
 *
 * <p>A file only reaches {@code mods/} after its SHA-512 matches what the server said, so a
 * truncated or tampered download can never become an installed mod.
 */
public class DownloadSession {
	private static final int MAX_REDIRECTS = 5;
	private static final int BUFFER_SIZE = 64 * 1024;

	public enum Status {
		PENDING, DOWNLOADING, DONE, FAILED
	}

	private final SyncPlan plan;
	private final ClientConfig config;
	private final HttpClient http;
	private final ExecutorService executor;

	private final Map<String, Status> statuses = new LinkedHashMap<>();
	private final Map<String, String> failureReasons = new LinkedHashMap<>();
	private final Map<String, String> completed = new LinkedHashMap<>();

	/**
	 * Bytes secured per file, rather than one running total.
	 *
	 * <p>A total that only ever counts upwards breaks the moment an attempt fails or resumes:
	 * the retry counts the same bytes again and the bar runs past the end. Recording each
	 * file's position lets an attempt simply overwrite its own figure.
	 */
	private final Map<String, Long> progress = new ConcurrentHashMap<>();

	private final RateMeter rate = new RateMeter();
	private final AtomicInteger remaining = new AtomicInteger();

	private final AtomicBoolean finishing = new AtomicBoolean();

	private volatile boolean cancelled;
	private volatile boolean finished;

	public DownloadSession(SyncPlan plan, ClientConfig config) {
		this.plan = plan;
		this.config = config;

		// Redirects are followed by hand so every hop can be re-checked against the allow list.
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(20))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();

		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "AutoModFetcher-download");
			thread.setDaemon(true);
			return thread;
		};

		int threads = Math.max(1, Math.min(config.maxConcurrentDownloads, 8));
		this.executor = Executors.newFixedThreadPool(threads, factory);

		for (ModEntry entry : plan.downloads()) {
			statuses.put(entry.fileName(), Status.PENDING);
		}
	}

	public void start() {
		remaining.set(plan.downloads().size());

		if (plan.downloads().isEmpty()) {
			// A removal-only plan still has state to write, so keep it off the render thread.
			executor.submit(this::complete);
			return;
		}

		try {
			Files.createDirectories(ModPaths.downloadTempDir());
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not create the download folder", e);
		}

		for (ModEntry entry : plan.downloads()) {
			executor.submit(() -> {
				runOne(entry);

				if (remaining.decrementAndGet() == 0) {
					complete();
				}
			});
		}
	}

	/** Refusing a host is a decision, not a fault, so it is never worth trying again. */
	private static final class BlockedHostException extends IOException {
		BlockedHostException(String message) {
			super(message);
		}
	}

	private void runOne(ModEntry entry) {
		int attempts = 1 + Math.max(0, config.downloadRetries);

		for (int attempt = 1; attempt <= attempts; attempt++) {
			if (cancelled) {
				setStatus(entry, Status.FAILED, "cancelled");
				return;
			}

			setStatus(entry, Status.DOWNLOADING, null);

			try {
				install(entry);
				return;
			} catch (BlockedHostException e) {
				deleteQuietly(partFileFor(entry));
				fail(entry, e);
				return;
			} catch (Exception e) {
				if (attempt == attempts) {
					// Only now is the partial file worthless: up to here it was the head start
					// for the next attempt.
					deleteQuietly(partFileFor(entry));
					progress.remove(entry.fileName());
					fail(entry, e);
					return;
				}

				AutoModFetcher.LOGGER.warn("Attempt {} of {} for {} failed ({}); retrying",
						attempt, attempts, entry.fileName(), e.getMessage());

				if (!backOff(attempt)) {
					setStatus(entry, Status.FAILED, "cancelled");
					return;
				}
			}
		}
	}

	private void install(ModEntry entry) throws Exception {
		Path temp = partFileFor(entry);
		String sha512 = fetch(entry, temp);

		if (!sha512.equalsIgnoreCase(entry.sha512())) {
			// A mismatch means the bytes on disk are wrong, so resuming from them would only
			// preserve the mistake. Start the next attempt clean.
			deleteQuietly(temp);
			throw new IOException("Checksum mismatch");
		}

		Files.move(temp, ModPaths.modsDir().resolve(entry.fileName()), StandardCopyOption.REPLACE_EXISTING);

		synchronized (completed) {
			completed.put(entry.fileName(), sha512.toLowerCase(Locale.ROOT));
		}

		progress.put(entry.fileName(), entry.size());
		setStatus(entry, Status.DONE, null);
		AutoModFetcher.LOGGER.info("Installed {}", entry.fileName());
	}

	private Path partFileFor(ModEntry entry) {
		return ModPaths.downloadTempDir().resolve(entry.fileName() + ".part");
	}

	private void fail(ModEntry entry, Exception cause) {
		setStatus(entry, Status.FAILED, cause.getMessage() != null ? cause.getMessage() : cause.toString());
		AutoModFetcher.LOGGER.warn("Failed to download {}", entry.fileName(), cause);
	}

	/** @return false if the wait was cut short by a cancel */
	private boolean backOff(int attempt) {
		try {
			Thread.sleep(Math.min(8000L, 1000L * (1L << (attempt - 1))));
			return !cancelled;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			AutoModFetcher.LOGGER.debug("Could not clean up {}", path, e);
		}
	}

	/** Streams one file to disk and returns its SHA-512, computed as it is written. */
	private String fetch(ModEntry entry, Path temp) throws Exception {
		String url = entry.url();

		// Whatever a previous attempt managed to save. Asking for the rest turns a failure at
		// 90% into a short top-up instead of starting the whole file again.
		long alreadyHave = partialSize(temp, entry.size());

		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (!config.isAllowed(url)) {
				throw new BlockedHostException("Blocked host: " + ClientConfig.hostOf(url));
			}

			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", AutoModFetcher.userAgent())
					.timeout(Duration.ofMinutes(5))
					.GET();

			if (alreadyHave > 0) {
				builder.header("Range", "bytes=" + alreadyHave + "-");
			}

			HttpResponse<InputStream> response = http.send(builder.build(),
					HttpResponse.BodyHandlers.ofInputStream());
			int code = response.statusCode();

			if (code >= 300 && code < 400) {
				Optional<String> location = response.headers().firstValue("location");
				response.body().close();

				if (location.isEmpty()) {
					throw new IOException("HTTP " + code + " without a Location header");
				}

				url = URI.create(url).resolve(location.get()).toString();
				continue;
			}

			if (code == 206) {
				AutoModFetcher.LOGGER.info("Resuming {} from byte {}", entry.fileName(), alreadyHave);
				return streamToFile(entry, response.body(), temp, alreadyHave);
			}

			if (code == 200) {
				// The host ignored the range and is sending the whole thing, so the head start
				// is worthless and keeping it would corrupt the file.
				return streamToFile(entry, response.body(), temp, 0);
			}

			response.body().close();
			throw new IOException("HTTP " + code);
		}

		throw new IOException("Too many redirects");
	}

	private long partialSize(Path temp, long expected) {
		try {
			if (!Files.isRegularFile(temp)) {
				return 0;
			}

			long size = Files.size(temp);

			// A part at or beyond full size is not a head start, it is a leftover to discard.
			return size > 0 && size < expected ? size : 0;
		} catch (IOException e) {
			return 0;
		}
	}

	/**
	 * @param resumeFrom bytes already on disk to keep and append to; 0 to start the file over
	 */
	private String streamToFile(ModEntry entry, InputStream body, Path temp, long resumeFrom) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		byte[] buffer = new byte[BUFFER_SIZE];
		long written = resumeFrom;

		// The hash covers the whole file, so what is already on disk has to go through the
		// digest before the new bytes do.
		if (resumeFrom > 0) {
			try (InputStream existing = Files.newInputStream(temp)) {
				long fed = 0;
				int read;

				while (fed < resumeFrom && (read = existing.read(buffer, 0,
						(int) Math.min(buffer.length, resumeFrom - fed))) != -1) {
					digest.update(buffer, 0, read);
					fed += read;
				}
			}
		}

		StandardOpenOption mode = resumeFrom > 0
				? StandardOpenOption.APPEND
				: StandardOpenOption.TRUNCATE_EXISTING;

		try (InputStream in = body;
				OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
						mode, StandardOpenOption.WRITE)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				if (cancelled) {
					throw new IOException("Cancelled");
				}

				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);

				written += read;
				progress.put(entry.fileName(), written);

				// The manifest states an exact size, so anything longer is already wrong.
				if (written > entry.size()) {
					throw new IOException("File is larger than the manifest declared");
				}
			}
		}

		if (written != entry.size()) {
			throw new IOException("Expected " + entry.size() + " bytes but got " + written);
		}

		return Hashing.hex(digest.digest());
	}

	private void complete() {
		// Cancelling drops queued tasks, so the per-task countdown may never reach zero and
		// this can also arrive from cancel(). Whichever gets here first wins.
		if (!finishing.compareAndSet(false, true)) {
			return;
		}

		executor.shutdown();

		Map<String, String> installedNow;

		synchronized (completed) {
			installedNow = Map.copyOf(completed);
		}

		if (!installedNow.isEmpty()) {
			InstalledState state = InstalledState.load();
			state.installed.putAll(installedNow);
			state.save();

			ClientModIndex.recordDownloaded(installedNow);
		}

		if (!cancelled && !plan.deletions().isEmpty()) {
			queueDeletions();
		}

		cleanUpTempDir();

		finished = true;
	}

	/** Leaving the staging folder behind would put a stray directory inside {@code mods/}. */
	private void cleanUpTempDir() {
		Path tempDir = ModPaths.downloadTempDir();

		try (java.util.stream.Stream<Path> leftovers = Files.list(tempDir)) {
			for (Path leftover : leftovers.toList()) {
				Files.deleteIfExists(leftover);
			}

			Files.deleteIfExists(tempDir);
		} catch (IOException e) {
			AutoModFetcher.LOGGER.debug("Could not clean up {}", tempDir, e);
		}
	}

	/**
	 * Removals are deferred: the jars are loaded right now, so deleting them would fail on
	 * Windows. {@code PendingOpsApplier} takes care of them during the next launch.
	 */
	private void queueDeletions() {
		PendingOps ops = PendingOps.load();
		List<String> merged = new ArrayList<>(ops.delete);

		InstalledState state = InstalledState.load();

		for (String fileName : plan.deletions()) {
			if (!merged.contains(fileName)) {
				merged.add(fileName);
			}

			state.installed.remove(fileName);
		}

		ops.delete = merged;
		ops.save();
		state.save();
	}

	private void setStatus(ModEntry entry, Status status, String reason) {
		synchronized (statuses) {
			statuses.put(entry.fileName(), status);

			if (reason != null) {
				failureReasons.put(entry.fileName(), reason);
			}
		}
	}

	public void cancel() {
		cancelled = true;
		executor.shutdownNow();
		complete();
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public long downloadedBytes() {
		long total = 0;

		for (long value : progress.values()) {
			total += value;
		}

		return total;
	}

	/** Bytes per second, smoothed; zero until there is enough to say. */
	public long bytesPerSecond() {
		return rate.sample(downloadedBytes());
	}

	/** Seconds left at the current rate, or -1 when that cannot be estimated yet. */
	public long secondsRemaining() {
		long perSecond = bytesPerSecond();

		if (perSecond <= 0) {
			return -1;
		}

		return Math.max(0, (totalBytes() - downloadedBytes()) / perSecond);
	}

	public long totalBytes() {
		return plan.totalDownloadBytes();
	}

	public Status statusOf(String fileName) {
		synchronized (statuses) {
			return statuses.getOrDefault(fileName, Status.PENDING);
		}
	}

	public String failureReasonOf(String fileName) {
		synchronized (statuses) {
			return failureReasons.get(fileName);
		}
	}

	public int successCount() {
		synchronized (completed) {
			return completed.size();
		}
	}

	public int failureCount() {
		synchronized (statuses) {
			return (int) statuses.values().stream().filter(status -> status == Status.FAILED).count();
		}
	}

	public int deletionCount() {
		return plan.deletions().size();
	}

	public ClientConfig config() {
		return config;
	}

	/** Anything that did not finish, whether it failed or was never reached. */
	public boolean hasUnfinishedWork() {
		return !remainingWork().downloads().isEmpty();
	}

	/**
	 * What is left to do, as a plan a fresh session can run.
	 *
	 * <p>Files already installed are dropped so a retry does not fetch them twice. Removals
	 * are carried over because a cancelled session never queued them.
	 */
	public SyncPlan remainingWork() {
		List<ModEntry> unfinished = plan.downloads().stream()
				.filter(entry -> statusOf(entry.fileName()) != Status.DONE)
				.toList();

		return new SyncPlan(unfinished, List.of(), plan.deletions(), List.of());
	}
}
