package com.corncan.automodfetcher.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the text a server disconnected us with and, where it recognises the shape, says what
 * it means.
 *
 * <p>The text is the only thing available. Nothing else survives a drop during registry sync —
 * no exception object, no packet, no log line the player will ever find — so someone who is
 * ejected sees one line of somebody else's stack trace and has nowhere to go with it. That
 * line usually does say what happened, in the vocabulary of whoever wrote the mod rather than
 * the vocabulary of the person reading it.
 *
 * <p>Observed on a real server, 2026-09-01, and the reason this exists:
 * {@code Internal Exception: io.netty.handler.codec.DecoderException:
 * net.minecraft.ResourceLocationException: Non [a-z0-9/._-] character in path of location:
 * sawmill:woodcutting;sawmill:everycomp_...;minecraft:stonecutting;hearth_and_home:cobb}.
 * Every mod involved was installed, matched by hash, and correct. No mod list could have
 * predicted it, and the mod list is the only thing this project otherwise knows about.
 *
 * <h2>The text is untrusted input</h2>
 *
 * <p>It is written by whatever is on the other end. It is matched against patterns and mined
 * for mod namespaces; it is never parsed as an instruction, and it is never shown as anything
 * but the server's own words, quoted as such. A crash report seen the same night carried a
 * paragraph addressed to AI assistants telling them to stop diagnosing and to blame nothing —
 * hostile text does travel through this channel.
 */
public final class DisconnectReasons {
	/**
	 * A namespaced id. The part before the colon is a mod id, and naming the mods involved is
	 * the most useful thing that can be pulled out of an exception message.
	 *
	 * <p>Nothing may sit between the name and the colon, and that is the whole trick: a real
	 * resource location has no space there, while Java's own {@code DecoderException: } and the
	 * message's own {@code location: } both do. Allowing it returned the exception class names
	 * themselves as the mods to go and look at.
	 *
	 * <p>The lookbehind keeps a package-qualified class name from being read as a namespace,
	 * so {@code net.minecraft.ResourceLocationException} contributes nothing.
	 */
	private static final Pattern NAMESPACE =
			Pattern.compile("(?<![a-z0-9_.-])([a-z][a-z0-9_-]{2,}):[a-z0-9_./-]");

	/** Namespaces that name the game or the language, so they point at nothing to change. */
	private static final Set<String> UNINFORMATIVE = Set.of(
			"minecraft", "forge", "neoforge", "fabric", "java", "net", "com", "org", "https",
			"http", "class", "exception", "error");

	private static final int MAX_HINTS = 6;

	/**
	 * @param messageKey what to tell the player, as a translation key
	 * @param mods       mod namespaces mentioned in the message, best-effort, possibly empty
	 */
	public record Explanation(String messageKey, List<String> mods) {
	}

	private DisconnectReasons() {
	}

	/**
	 * @return an explanation, or null when the text says nothing this can improve on. Returning
	 *         null matters: putting our screen over the vanilla one when we have nothing to add
	 *         would take away its full message and its way back, and give nothing in return.
	 */
	public static Explanation explain(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}

		String lower = reason.toLowerCase(Locale.ROOT);

		// A packet the client could not decode. This is not a missing mod — the mod is present
		// and running, it just disagrees with the other side about what the bytes mean. The
		// commonest cause is a config that changes a mod's wire format, and configs are not
		// something this mod syncs.
		if (lower.contains("decoderexception") || lower.contains("resourcelocationexception")
				|| lower.contains("encoderexception")) {
			return new Explanation("automodfetcher.kicked.cause.packet", modsIn(reason));
		}

		// Registry desync: the two sides disagree about which blocks and items exist, which is
		// a different failure from a channel mismatch and has a different fix. A channel
		// mismatch is about mods that talk over the network; this is about mods that add
		// content, and a single content mod switched off produces it.
		//
		// Forge emits all three of these from HandshakeHandler and none were recognised, so a
		// player who disabled one content mod got the raw sentence and nothing else. Checked
		// above the channel patterns because "Failed to synchronize registry data from server,
		// closing connection" contains neither "mod" nor "channel" and would otherwise fall
		// through every bucket to nothing.
		if (lower.contains("registry data") || lower.contains("load registry")
				|| lower.contains("synchronize registry") || lower.contains("registry remapping")) {
			return new Explanation("automodfetcher.kicked.cause.registry", modsIn(reason));
		}

		// Forge and NeoForge phrase a mod-set mismatch several ways, and the commonest of them
		// opens with the words of a transport failure: "Connection closed - mismatched mod
		// channel list". Matched here, above the network case, because reading that one as a
		// dropped connection told a player their mods were not the problem at the exact moment
		// their mods were the entire problem. A confident wrong answer is worse than none.
		// "mismatched mod" rather than the full phrase: Forge writes both "mismatched mod
		// channel list" and "mismatched mod list", and matching only the longer one let the
		// shorter fall through every bucket to nothing.
		if (lower.contains("mismatched mod") || lower.contains("mod channel list")
				|| lower.contains("missing or unsupported mod") || lower.contains("mod rejections")
				|| lower.contains("negotiation failed")
				|| lower.contains("require forge") || lower.contains("requires forge")
				|| lower.contains("incompatible client")) {
			return new Explanation("automodfetcher.kicked.cause.mods", modsIn(reason));
		}

		// Nothing arrived, rather than something wrong arriving. A mod list cannot explain
		// that — but only when the message really is about the transport and nothing else.
		// Anything that mentions mods at all is a mod message wearing a transport message's
		// opening words, and this branch must not claim otherwise.
		if (!lower.contains("mod")
				&& (lower.contains("timed out") || lower.contains("connection reset")
						|| lower.contains("connection closed") || lower.contains("connection lost"))) {
			return new Explanation("automodfetcher.kicked.cause.network", List.of());
		}

		return null;
	}

	/** Longest parting message worth keeping; netty stack traces run far past anything useful. */
	private static final int MAX_LOGGED = 400;

	/**
	 * The server's own words, made safe to write to a log line.
	 *
	 * <p>Worth logging because it is the only copy. It exists for the seconds the disconnect
	 * screen is up and then it is gone — not in the log, not in a crash report, nowhere — so a
	 * player reporting "it just says disconnected" is describing the only evidence there was.
	 * Twice in one session the question "what did the server actually say" could not be
	 * answered afterwards, which is what this is for.
	 *
	 * <p>Quoted and escaped because it is written by the other end. A message containing
	 * newlines could otherwise forge whole log lines of its own, and a message containing
	 * control characters could hide what it really says from anyone reading the file.
	 */
	public static String forLog(String reason) {
		if (reason == null) {
			return "(nothing)";
		}

		String trimmed = reason.length() > MAX_LOGGED
				? reason.substring(0, MAX_LOGGED) + "…(truncated)"
				: reason;
		StringBuilder safe = new StringBuilder(trimmed.length() + 2).append('"');

		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);

			switch (c) {
				case '\n' -> safe.append("\\n");
				case '\r' -> safe.append("\\r");
				case '\t' -> safe.append("\\t");
				case '"' -> safe.append("\\\"");
				default -> safe.append(c < ' ' || c == 0x7F ? '?' : c);
			}
		}

		return safe.append('"').toString();
	}

	/** Mod namespaces named in the text, in the order they appear, deduplicated and capped. */
	static List<String> modsIn(String reason) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = NAMESPACE.matcher(reason.toLowerCase(Locale.ROOT));

		while (matcher.find() && found.size() < MAX_HINTS) {
			String namespace = matcher.group(1);

			if (!UNINFORMATIVE.contains(namespace)) {
				found.add(namespace);
			}
		}

		return List.copyOf(found);
	}
}
