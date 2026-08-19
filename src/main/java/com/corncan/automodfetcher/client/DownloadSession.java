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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOps;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Downloads a plan's files on background threads and reports progress the GUI can poll.
 *
 * <p>A file only reaches {@code mods/} after its SHA-512 matches what the server said, so a
 * truncated or tampered download can never become an installed mod. That holds for a bundle
 * twice over: the zip is checked as a whole before it is opened, and every jar taken out of it
 * is checked again on the way to the mods folder.
 */
public class DownloadSession {
	private static final int MAX_REDIRECTS = 5;
	private static final int BUFFER_SIZE = 64 * 1024;

	public enum Status {
		PENDING, DOWNLOADING, EXTRACTING, DONE, FAILED
	}

	/** One line the progress screen can draw. Members of a bundle are drawn under it. */
	public record Row(String key, String label, boolean nested) {
	}

	/**
	 * Anything that can be fetched: one mod jar, or one bundle zip.
	 *
	 * <p>They arrive by the same route and are checked the same way, so the fetching itself has
	 * no reason to know which it is holding.
	 */
	private record Target(String key, String url, long size, String sha512, byte[] data) {
		static Target of(ModEntry entry) {
			return new Target(entry.fileName(), entry.url(), entry.size(), entry.sha512(), NO_DATA);
		}

		static Target of(ModBundle bundle) {
			return new Target(keyOf(bundle), bundle.url(), bundle.size(), bundle.sha512(), bundle.data());
		}
	}

	private static final byte[] NO_DATA = new byte[0];

	/**
	 * Puts a bundle that arrived with the manifest where the rest of the code expects to find
	 * it, so verification, extraction and failure all work exactly as they do for a download.
	 */
	private String writeEmbedded(Target target, Path temp) throws Exception {
		if (target.data().length != target.size()) {
			throw new IOException("Expected " + target.size() + " bytes but the manifest carried "
					+ target.data().length);
		}

		Files.write(temp, target.data());
		progress.put(target.key(), target.size());

		return Hashing.hex(java.security.MessageDigest.getInstance("SHA-512").digest(target.data()));
	}

	private final SyncPlan plan;
	private final ClientConfig config;
	private final SourcePolicy policy;
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

	public DownloadSession(SyncPlan plan, ClientConfig config, SourcePolicy policy) {
		this.plan = plan;
		this.config = config;
		this.policy = policy;

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

		for (Row row : rows()) {
			statuses.put(row.key(), Status.PENDING);
		}
	}

	/** Every line to draw, bundles followed by the files they carry. */
	public List<Row> rows() {
		List<Row> rows = new ArrayList<>();

		for (ModEntry entry : plan.downloads()) {
			rows.add(new Row(entry.fileName(), entry.fileName(), false));
		}

		for (ModBundle bundle : plan.bundles()) {
			rows.add(new Row(keyOf(bundle), displayNameOf(bundle), false));

			for (BundledMod mod : bundle.contents()) {
				rows.add(new Row(mod.fileName(), mod.fileName(), true));
			}
		}

		return rows;
	}

	private static String keyOf(ModBundle bundle) {
		// Keyed by hash rather than by URL so nothing a server sends can collide with a jar's
		// name and quietly take over its row.
		return "bundle:" + bundle.sha512().substring(0, Math.min(16, bundle.sha512().length()));
	}

	/** The zip's own name where the URL offers a sane one, for display only. */
	private static String displayNameOf(ModBundle bundle) {
		try {
			String path = URI.create(bundle.url()).getPath();

			if (path != null) {
				String last = path.substring(path.lastIndexOf('/') + 1);

				if (last.toLowerCase(Locale.ROOT).endsWith(".zip") && last.length() <= 64) {
					return last;
				}
			}
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Could not name the bundle from its URL", e);
		}

		return "bundle.zip";
	}

	public void start() {
		int tasks = plan.downloads().size() + plan.bundles().size();
		remaining.set(tasks);

		if (tasks == 0) {
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
			submit(() -> runOne(Target.of(entry), temp -> installMod(entry, temp), entry.fileName()));
		}

		for (ModBundle bundle : plan.bundles()) {
			submit(() -> runOne(Target.of(bundle), temp -> extract(bundle, temp), keyOf(bundle)));
		}
	}

	private void submit(Runnable task) {
		executor.submit(() -> {
			task.run();

			if (remaining.decrementAndGet() == 0) {
				complete();
			}
		});
	}

	/** Refusing a host is a decision, not a fault, so it is never worth trying again. */
	private static final class BlockedHostException extends IOException {
		BlockedHostException(String message) {
			super(message);
		}
	}

	/**
	 * The file is already there and the running game is holding it open.
	 *
	 * <p>Windows will not let anyone replace an open jar, and nothing about that changes while
	 * the game is up — so retrying is three ways of writing down the same failure. The
	 * download itself succeeded; only the last step could not happen yet.
	 */
	private static final class FileInUseException extends IOException {
		FileInUseException(String fileName) {
			super("already installed and in use; it will be replaced on the next launch: " + fileName);
		}
	}

	/** What to do with the verified bytes once they are on disk. */
	@FunctionalInterface
	private interface Installer {
		void accept(Path temp) throws Exception;
	}

	private void runOne(Target target, Installer installer, String key) {
		int attempts = 1 + Math.max(0, config.downloadRetries);

		for (int attempt = 1; attempt <= attempts; attempt++) {
			if (cancelled) {
				setStatus(key, Status.FAILED, "cancelled");
				return;
			}

			setStatus(key, Status.DOWNLOADING, null);

			try {
				Path temp = partFileFor(target);
				String sha512 = target.data().length > 0
						? writeEmbedded(target, temp)
						: fetch(target, temp);

				if (!sha512.equalsIgnoreCase(target.sha512())) {
					// A mismatch means the bytes on disk are wrong, so resuming from them would
					// only preserve the mistake. Start the next attempt clean.
					deleteQuietly(temp);
					throw new IOException("Checksum mismatch");
				}

				installer.accept(temp);
				return;
			} catch (BlockedHostException e) {
				deleteQuietly(partFileFor(target));
				fail(key, e);
				return;
			} catch (FileInUseException e) {
				// The part file stays: the bytes are good, and the next launch can finish
				// the job without fetching them again.
				fail(key, e);
				return;
			} catch (Exception e) {
				if (attempt == attempts) {
					// Only now is the partial file worthless: up to here it was the head start
					// for the next attempt.
					deleteQuietly(partFileFor(target));
					progress.remove(target.key());
					fail(key, e);
					return;
				}

				AutoModFetcher.LOGGER.warn("Attempt {} of {} for {} failed ({}); retrying",
						attempt, attempts, key, e.getMessage());

				if (!backOff(attempt)) {
					setStatus(key, Status.FAILED, "cancelled");
					return;
				}
			}
		}
	}

	private void installMod(ModEntry entry, Path temp) throws Exception {
		move(temp, entry.fileName(), entry.sha512());
		progress.put(entry.fileName(), entry.size());
		setStatus(entry.fileName(), Status.DONE, null);
	}

	/**
	 * Unpacks a verified zip into the mods folder.
	 *
	 * <p>Entries are looked up by the names the manifest promised, never by walking whatever
	 * the zip declares it contains. Those names have already been through
	 * {@link SyncPlanner#isSafeFileName}, so there is no path for a crafted entry name to
	 * write outside {@code mods/} — the archive's own table of contents is never consulted.
	 */
	private void extract(ModBundle bundle, Path zipFile) throws Exception {
		String bundleKey = keyOf(bundle);
		setStatus(bundleKey, Status.EXTRACTING, null);

		int installed = 0;
		int failed = 0;

		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			for (BundledMod mod : bundle.contents()) {
				if (cancelled) {
					setStatus(mod.fileName(), Status.FAILED, "cancelled");
					failed++;
					continue;
				}

				try {
					extractOne(zip, mod);
					installed++;
				} catch (Exception e) {
					failed++;
					fail(mod.fileName(), e);
				}
			}
		}

		deleteQuietly(zipFile);

		AutoModFetcher.LOGGER.info("Unpacked {} of {} mod(s) from the bundle", installed,
				bundle.contents().size());

		// Never rethrown, however badly this went. The zip arrived and opened, so whatever
		// stopped a member — a locked jar, a missing entry — will stop it again just as surely
		// on a second download of the very same bytes. The retry button offers a fresh attempt
		// if the player wants one; three automatic ones would only be slower.
		setStatus(bundleKey, failed > 0 ? Status.FAILED : Status.DONE,
				failed > 0 ? failed + " file(s) could not be installed" : null);
	}

	private void extractOne(ZipFile zip, BundledMod mod) throws Exception {
		setStatus(mod.fileName(), Status.EXTRACTING, null);

		ZipEntry entry = zip.getEntry(mod.fileName());

		if (entry == null) {
			throw new IOException("The bundle does not contain " + mod.fileName());
		}

		Path temp = ModPaths.downloadTempDir().resolve(mod.fileName() + ".part");
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		long written = 0;

		try (InputStream in = zip.getInputStream(entry);
				OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;

			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
				written += read;

				// The manifest states an exact size, so a member that keeps going is either
				// wrong or trying to fill the disk. Either way there is nothing to wait for.
				if (written > mod.size()) {
					throw new IOException("Entry is larger than the manifest declared");
				}
			}
		} catch (Exception e) {
			deleteQuietly(temp);
			throw e;
		}

		if (written != mod.size()) {
			deleteQuietly(temp);
			throw new IOException("Expected " + mod.size() + " bytes but got " + written);
		}

		String sha512 = Hashing.hex(digest.digest());

		if (!sha512.equalsIgnoreCase(mod.sha512())) {
			deleteQuietly(temp);
			throw new IOException("Checksum mismatch");
		}

		move(temp, mod.fileName(), sha512);
		setStatus(mod.fileName(), Status.DONE, null);
	}

	/** The last step for every file, however it arrived: staged, verified, then in place. */
	private void move(Path temp, String fileName, String sha512) throws IOException {
		Path target = ModPaths.modsDir().resolve(fileName);

		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			if (Files.exists(target)) {
				throw new FileInUseException(fileName);
			}

			throw e;
		}

		synchronized (completed) {
			completed.put(fileName, sha512.toLowerCase(Locale.ROOT));
		}

		AutoModFetcher.LOGGER.info("Installed {}", fileName);
	}

	private Path partFileFor(Target target) {
		// A bundle's key is not a file name, so it is reduced to one that is.
		String name = target.key().startsWith("bundle:")
				? target.key().replace(':', '-') + ".zip"
				: target.key();

		return ModPaths.downloadTempDir().resolve(name + ".part");
	}

	private void fail(String key, Exception cause) {
		setStatus(key, Status.FAILED, cause.getMessage() != null ? cause.getMessage() : cause.toString());
		AutoModFetcher.LOGGER.warn("Failed to install {}", key, cause);
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
	private String fetch(Target target, Path temp) throws Exception {
		String url = target.url();
		String startUrl = url;

		// Whatever a previous attempt managed to save. Asking for the rest turns a failure at
		// 90% into a short top-up instead of starting the whole file again.
		long alreadyHave = partialSize(temp, target.size());

		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			// The first hop must be allowed outright; later ones are judged by where the
			// download started, so a granted host can hand off to its own signed CDN address.
			boolean allowed = hop == 0 ? policy.isAllowed(url) : policy.mayFollowFrom(startUrl, url);

			if (!allowed) {
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
				AutoModFetcher.LOGGER.info("Resuming {} from byte {}", target.key(), alreadyHave);
				return streamToFile(target, response.body(), temp, alreadyHave);
			}

			if (code == 200) {
				// The host ignored the range and is sending the whole thing, so the head start
				// is worthless and keeping it would corrupt the file.
				return streamToFile(target, response.body(), temp, 0);
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
	private String streamToFile(Target target, InputStream body, Path temp, long resumeFrom) throws Exception {
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
				progress.put(target.key(), written);

				// The manifest states an exact size, so anything longer is already wrong.
				if (written > target.size()) {
					throw new IOException("File is larger than the manifest declared");
				}
			}
		}

		if (written != target.size()) {
			throw new IOException("Expected " + target.size() + " bytes but got " + written);
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

		if (!cancelled && !plan.foreign().isEmpty()) {
			queueDisables();
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

	/**
	 * Sets the player's own mods aside, on the same deferred schedule as removals.
	 *
	 * <p>Nothing is deleted and nothing is recorded as ours: these are files we did not
	 * install, and the only claim we have on them is the one the player just granted on the
	 * confirm screen. {@code PendingOpsApplier} moves them to a folder they can drag back from.
	 */
	private void queueDisables() {
		PendingOps ops = PendingOps.load();
		List<String> merged = new ArrayList<>(ops.disable);

		for (String fileName : plan.foreign()) {
			if (!merged.contains(fileName)) {
				merged.add(fileName);
			}
		}

		ops.disable = merged;
		ops.save();
	}

	private void setStatus(String key, Status status, String reason) {
		synchronized (statuses) {
			statuses.put(key, status);

			if (reason != null) {
				failureReasons.put(key, reason);
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

	public Status statusOf(String key) {
		synchronized (statuses) {
			return statuses.getOrDefault(key, Status.PENDING);
		}
	}

	public String failureReasonOf(String key) {
		synchronized (statuses) {
			return failureReasons.get(key);
		}
	}

	public int successCount() {
		synchronized (completed) {
			return completed.size();
		}
	}

	public int failureCount() {
		synchronized (statuses) {
			// Bundle rows mirror their members, so counting them too would report one failure
			// as two.
			return (int) statuses.entrySet().stream()
					.filter(entry -> !entry.getKey().startsWith("bundle:"))
					.filter(entry -> entry.getValue() == Status.FAILED)
					.count();
		}
	}

	public int deletionCount() {
		return plan.deletions().size();
	}

	public int disabledCount() {
		return plan.foreign().size();
	}

	public ClientConfig config() {
		return config;
	}

	public SourcePolicy policy() {
		return policy;
	}

	/** Anything that did not finish, whether it failed or was never reached. */
	public boolean hasUnfinishedWork() {
		SyncPlan left = remainingWork();
		return !left.downloads().isEmpty() || !left.bundles().isEmpty();
	}

	/**
	 * What is left to do, as a plan a fresh session can run.
	 *
	 * <p>Files already installed are dropped so a retry does not fetch them twice. Removals
	 * are carried over because a cancelled session never queued them. Consent is not: the
	 * player granted those hosts to get here, and asking again on a retry would punish them
	 * for a flaky connection.
	 */
	public SyncPlan remainingWork() {
		List<ModEntry> unfinished = plan.downloads().stream()
				.filter(entry -> statusOf(entry.fileName()) != Status.DONE)
				.toList();

		// A bundle is worth fetching again only for the members that are still missing.
		List<ModBundle> bundles = new ArrayList<>();

		for (ModBundle bundle : plan.bundles()) {
			List<BundledMod> missing = bundle.contents().stream()
					.filter(mod -> statusOf(mod.fileName()) != Status.DONE)
					.toList();

			if (!missing.isEmpty()) {
				bundles.add(new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), missing, bundle.data()));
			}
		}

		return new SyncPlan(unfinished, List.copyOf(bundles), List.of(), plan.deletions(), List.of(),
				List.of(), plan.foreign());
	}
}
