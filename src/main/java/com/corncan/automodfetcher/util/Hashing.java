package com.corncan.automodfetcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {
	private static final int BUFFER_SIZE = 64 * 1024;

	/** Bytes CurseForge strips before fingerprinting: tab, LF, CR and space. */
	private static final int WS_TAB = 9;
	private static final int WS_LF = 10;
	private static final int WS_CR = 13;
	private static final int WS_SPACE = 32;

	private Hashing() {
	}

	public record FileHashes(String sha1, String sha512, long size) {
	}

	/** Reads the file once and produces both digests the manifest needs. */
	public static FileHashes hash(Path path) throws IOException {
		MessageDigest sha1 = digest("SHA-1");
		MessageDigest sha512 = digest("SHA-512");
		long size = 0;

		byte[] buffer = new byte[BUFFER_SIZE];

		try (InputStream in = Files.newInputStream(path)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				sha1.update(buffer, 0, read);
				sha512.update(buffer, 0, read);
				size += read;
			}
		}

		return new FileHashes(hex(sha1.digest()), hex(sha512.digest()), size);
	}

	public static String sha512(Path path) throws IOException {
		MessageDigest sha512 = digest("SHA-512");
		byte[] buffer = new byte[BUFFER_SIZE];

		try (InputStream in = Files.newInputStream(path)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				sha512.update(buffer, 0, read);
			}
		}

		return hex(sha512.digest());
	}

	/**
	 * CurseForge's file fingerprint: MurmurHash2 (seed 1) over the file with all
	 * whitespace bytes removed. Streams the file twice because the algorithm seeds
	 * itself with the post-filter length.
	 */
	public static long curseForgeFingerprint(Path path) throws IOException {
		long length = countSignificantBytes(path);

		if (length > Integer.MAX_VALUE) {
			throw new IOException("File too large to fingerprint: " + path);
		}

		return murmur2(path, (int) length);
	}

	private static long countSignificantBytes(Path path) throws IOException {
		long count = 0;
		byte[] buffer = new byte[BUFFER_SIZE];

		try (InputStream in = Files.newInputStream(path)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					if (!isStripped(buffer[i])) {
						count++;
					}
				}
			}
		}

		return count;
	}

	@SuppressWarnings("fallthrough")
	private static long murmur2(Path path, int length) throws IOException {
		final int m = 0x5bd1e995;
		final int r = 24;

		int h = 1 ^ length;

		// Rolling 4-byte block assembled from the filtered byte stream.
		int block = 0;
		int blockLength = 0;

		byte[] buffer = new byte[BUFFER_SIZE];

		try (InputStream in = Files.newInputStream(path)) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				for (int i = 0; i < read; i++) {
					byte value = buffer[i];

					if (isStripped(value)) {
						continue;
					}

					block |= (value & 0xFF) << (blockLength * 8);
					blockLength++;

					if (blockLength == 4) {
						int k = block;
						k *= m;
						k ^= k >>> r;
						k *= m;

						h *= m;
						h ^= k;

						block = 0;
						blockLength = 0;
					}
				}
			}
		}

		// Tail: the same intentional fallthrough as the reference implementation.
		switch (blockLength) {
			case 3:
				h ^= ((block >>> 16) & 0xFF) << 16;
				// fall through
			case 2:
				h ^= ((block >>> 8) & 0xFF) << 8;
				// fall through
			case 1:
				h ^= block & 0xFF;
				h *= m;
				break;
			default:
				break;
		}

		h ^= h >>> 13;
		h *= m;
		h ^= h >>> 15;

		return h & 0xFFFFFFFFL;
	}

	private static boolean isStripped(byte value) {
		return value == WS_TAB || value == WS_LF || value == WS_CR || value == WS_SPACE;
	}

	private static MessageDigest digest(String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(algorithm + " is required by the Java platform", e);
		}
	}

	public static String hex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);

		for (byte value : bytes) {
			builder.append(Character.forDigit((value >> 4) & 0xF, 16));
			builder.append(Character.forDigit(value & 0xF, 16));
		}

		return builder.toString();
	}
}
