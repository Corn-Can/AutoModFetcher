package com.corncan.automodfetcher.client;

/**
 * A smoothed download rate, safe to ask for as often as a screen redraws.
 *
 * <p>Two things make a naive figure useless. Sampling every frame divides a handful of bytes
 * by a few milliseconds and produces wild numbers, so samples are spaced out. And an average
 * taken from the start of the session keeps reporting a healthy speed long after a transfer
 * has stalled, so recent samples are weighted far more heavily than old ones.
 */
public class RateMeter {
	private static final long SAMPLE_INTERVAL_MILLIS = 400;
	private static final double SMOOTHING = 0.4;

	private long lastSampleAt;
	private long lastBytes;
	private double smoothed;

	/** @param totalBytes bytes secured so far; call freely, it decides when to resample */
	public synchronized long sample(long totalBytes) {
		long now = System.currentTimeMillis();

		if (lastSampleAt == 0) {
			lastSampleAt = now;
			lastBytes = totalBytes;
			return 0;
		}

		long elapsed = now - lastSampleAt;

		if (elapsed < SAMPLE_INTERVAL_MILLIS) {
			return Math.round(smoothed);
		}

		// A retry can rewind a file's position, so guard against a negative delta.
		double instant = Math.max(0, totalBytes - lastBytes) * 1000.0 / elapsed;
		smoothed = smoothed == 0 ? instant : smoothed * (1 - SMOOTHING) + instant * SMOOTHING;

		lastSampleAt = now;
		lastBytes = totalBytes;

		return Math.round(smoothed);
	}
}
