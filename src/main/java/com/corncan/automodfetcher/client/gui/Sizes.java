package com.corncan.automodfetcher.client.gui;

import java.util.Locale;

public final class Sizes {
	private Sizes() {
	}

	public static String format(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}

		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		}

		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
