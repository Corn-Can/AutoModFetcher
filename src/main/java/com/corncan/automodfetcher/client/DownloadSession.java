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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOps;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.ModPaths;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Downloads a plan's files on background threads and reports progress the GUI can poll.
 *
 * <p>A file only reaches {@code mods/} after its SHA-512 matches what the server said, so a
 * truncated or tampered download can never become an installed mod.
 */
@Environment(EnvType.CLIENT)
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

	private final AtomicLong downloadedBytes = new AtomicLong();
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

	private void runOne(ModEntry entry) {
		if (cancelled) {
			setStatus(entry, Status.FAILED, "cancelled");
			return;
		}

		setStatus(entry, Status.DOWNLOADING, null);

		Path temp = ModPaths.downloadTempDir().resolve(entry.fileName() + ".part");

		try {
			String sha512 = fetch(entry, temp);

			if (!sha512.equalsIgnoreCase(entry.sha512())) {
				throw new IOException("Checksum mismatch");
			}

			Path target = ModPaths.modsDir().resolve(entry.fileName());
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

			synchronized (completed) {
				completed.put(entry.fileName(), sha512.toLowerCase(Locale.ROOT));
			}

			setStatus(entry, Status.DONE, null);
			AutoModFetcher.LOGGER.info("Installed {}", entry.fileName());
		} catch (Exception e) {
			setStatus(entry, Status.FAILED, e.getMessage() != null ? e.getMessage() : e.toString());
			AutoModFetcher.LOGGER.warn("Failed to download {}", entry.fileName(), e);

			try {
				Files.deleteIfExists(temp);
			} catch (IOException suppressed) {
				AutoModFetcher.LOGGER.debug("Could not clean up {}", temp, suppressed);
			}
		}
	}

	/** Streams one file to disk and returns its SHA-512, computed as it is written. */
	private String fetch(ModEntry entry, Path temp) throws Exception {
		String url = entry.url();

		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			if (!config.isAllowed(url)) {
				throw new IOException("Blocked host: " + ClientConfig.hostOf(url));
			}

			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", AutoModFetcher.userAgent())
					.timeout(Duration.ofMinutes(5))
					.GET()
					.build();

			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
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

			if (code != 200) {
				response.body().close();
				throw new IOException("HTTP " + code);
			}

			return streamToFile(entry, response.body(), temp);
		}

		throw new IOException("Too many redirects");
	}

	private String streamToFile(ModEntry entry, InputStream body, Path temp) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		byte[] buffer = new byte[BUFFER_SIZE];
		long written = 0;

		try (InputStream in = body;
				OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				if (cancelled) {
					throw new IOException("Cancelled");
				}

				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);

				written += read;
				downloadedBytes.addAndGet(read);

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
		return downloadedBytes.get();
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
}
