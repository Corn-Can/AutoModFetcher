package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOpsApplier;
import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.ClientSession;
import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.LauncherDetection;
import com.corncan.automodfetcher.client.SkipDecisions;
import com.corncan.automodfetcher.client.SourcePolicy;
import com.corncan.automodfetcher.client.SyncPlan;
import com.corncan.automodfetcher.client.TrustedServers;
import com.corncan.automodfetcher.client.TrustedSources;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.util.ModPaths;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Asks the player before anything is written to their mods folder.
 *
 * <p>The source host is shown for every file on purpose: agreeing here means letting a server
 * put code on this machine, and the player should be able to see where it comes from.
 *
 * <p>When a file comes from somewhere other than the platform CDNs, that is said first and
 * plainly. Pressing the button is how the host is granted — the agreement and the permission
 * are the same act, rather than a warning the player is left to act on afterwards by editing a
 * config file.
 */
public class ModSyncConfirmScreen extends Screen {
	private final SyncPlan plan;
	private final ClientConfig config;
	private final SourcePolicy policy;
	private final LineList list = new LineList();

	private boolean sharedInstall;
	private Checkbox rememberChoice;

	/**
	 * Bundled files the player has ticked off, for this attempt only.
	 *
	 * <p>Only bundle members can be declined. Everything else on this screen comes from a
	 * platform the author published to, and picking through those one at a time is how a
	 * dependency gets left out and a game stops starting. What arrives in an operator's own
	 * zip is the part with no such backing, so it is the part worth being able to refuse —
	 * and refusing it is the only useful answer to a file this screen has just marked as
	 * also being published somewhere the player could have got it from instead.
	 */
	private final Set<String> skipped = new java.util.HashSet<>();

	/**
	 * The key of the select-all row. A colon can never appear in a file name — the name guard
	 * rejects it — so this cannot collide with one.
	 */
	private static final String ALL_KEY = "::all";

	public ModSyncConfirmScreen(SyncPlan plan, ClientConfig config, SourcePolicy policy) {
		super(Component.translatable("automodfetcher.confirm.title"));
		this.plan = plan;
		this.config = config;
		this.policy = policy;
	}

	@Override
	protected void init() {
		sharedInstall = LauncherDetection.sharesTheDefaultInstall();

		int listTop = sharedInstall ? 60 : 52;
		boolean showFolderButton = !plan.manual().isEmpty();
		int listBottom = this.height - (showFolderButton || plan.isFullyAutomatic() ? 76 : 52);
		int listWidth = Math.min(360, this.width - 60);
		int listX = (this.width - listWidth) / 2;

		list.setBounds(listX, listTop, listWidth, listBottom - listTop);
		list.setLines(buildLines());

		// Knowing which folder is one thing; finding it is another, and players on the vanilla
		// launcher have no app to open it for them.
		if (showFolderButton) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.open_folder"), button -> openModsFolder())
					.bounds(this.width / 2 - 100, this.height - 64, 200, 20)
					.build());
		}

		int buttonY = this.height - 40;

		// Offered rather than assumed. Agreeing once is not the same as agreeing always, and
		// quietly turning the first into the second is not ours to do.
		if (plan.isFullyAutomatic()) {
			//? if >=1.20.5 {
			/*rememberChoice = this.addRenderableWidget(Checkbox
					.builder(Component.translatable("automodfetcher.confirm.remember"), this.font)
					.pos(this.width / 2 - 100, this.height - 64)
					.maxWidth(200)
					.selected(false)
					.build());
			*///?} else {
			rememberChoice = this.addRenderableWidget(new Checkbox(this.width / 2 - 100,
					this.height - 64, 200, 20,
					Component.translatable("automodfetcher.confirm.remember"), false));
			//?}
		}

		// Removals alone are still work to apply, so the action button must appear for them too.
		if (plan.hasActionableWork()) {
			// Three different asks, and the strongest one has to win: agreeing to an
			// unrecognised source is not the same promise as "download and install".
			Component acceptLabel;

			if (!plan.hostsNeedingConsent().isEmpty()) {
				acceptLabel = Component.translatable("automodfetcher.confirm.accept_untrusted");
			} else if (plan.downloads().isEmpty() && plan.bundles().isEmpty()) {
				acceptLabel = Component.translatable("automodfetcher.confirm.apply");
			} else {
				acceptLabel = Component.translatable("automodfetcher.confirm.accept");
			}

			// Three ways out, and the one that matters most is the one that was missing:
			// joining regardless. Declining used to mean "cancel", which returns to the server
			// list and puts this identical screen back up on the next attempt — no answer the
			// player could give ever got them in. This mod exists to remove that wall, not to
			// become it, and the roadmap already says as much about mods nobody can install:
			// blocking only traps them. Not every mod on a server is needed to play on it, and
			// nothing here can tell which — on Forge no jar even declares which side it is
			// for — so the judgement belongs to the person whose game it is. If the server
			// really does need one of them, the server refuses in its own words, and since the
			// diagnosis is now armed on every join those words get explained rather than
			// dumped raw.
			this.addRenderableWidget(Button.builder(acceptLabel, button -> startDownload())
					.bounds(this.width / 2 - 154, buttonY, 100, 20)
					.build());

			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.connect_anyway"),
					button -> connectAnyway())
					.bounds(this.width / 2 - 50, buttonY, 100, 20)
					.build());

			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.cancel"), button -> onClose())
					.bounds(this.width / 2 + 54, buttonY, 100, 20)
					.build());
		} else {
			// Nothing here is ours to install. Showing the list is still worth doing — it is
			// how the player learns what to fetch — but it must never become a dead end, so
			// the way onward is always offered.
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.connect_anyway"), button -> connectAnyway())
					.bounds(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.back"), button -> onClose())
					.bounds(this.width / 2 + 4, buttonY, 150, 20)
					.build());
		}
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		// First, because it changes what everything below it means. A player who reads no
		// further than the top of this list has still read the part that matters.
		if (!plan.hostsNeedingConsent().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.untrusted"), LineList.Line.RED));

			for (String host : plan.hostsNeedingConsent()) {
				lines.add(LineList.Line.of(
						Component.translatable("automodfetcher.confirm.untrusted_host", host),
						LineList.Line.YELLOW));
			}

			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.untrusted_warning"), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		for (ModBundle bundle : plan.bundles()) {
			lines.add(LineList.Line.of(bundle.isEmbedded()
					? Component.translatable("automodfetcher.confirm.section.bundle_direct",
							bundle.contents().size())
					: Component.translatable("automodfetcher.confirm.section.bundle",
							bundle.contents().size(), ClientConfig.hostOf(bundle.url())),
					LineList.Line.WHITE));

			// Fifty rows is not a list anyone ticks one at a time; the common answers are all
			// and none, and the individual rows are for the exception.
			lines.add(LineList.Line.toggle(Component.translatable(skipped.isEmpty()
					? "automodfetcher.confirm.bundle_skip_all"
					: "automodfetcher.confirm.bundle_skip_none"), LineList.Line.LINK, ALL_KEY));

			for (BundledMod mod : bundle.contents()) {
				String page = plan.bundledPages().get(mod.fileName());
				boolean skip = skipped.contains(mod.fileName());

				// Marked, not accused. A mod ends up in a bundle because no platform carries
				// this exact build, and an operator's own patched copy is the ordinary reason
				// for that. It is also what a tampered copy of a published mod looks like from
				// here, and the two are indistinguishable to us — but not to the person reading.
				lines.add(LineList.Line.toggle(
						Component.literal((skip ? "  [ ] " : "  [x] ") + mod.fileName()),
						skip ? LineList.Line.GREY : (page == null ? LineList.Line.GREEN : LineList.Line.YELLOW),
						mod.fileName()));

				if (page != null) {
					lines.add(LineList.Line.of(
							Component.translatable("automodfetcher.confirm.bundle_also_published"),
							LineList.Line.GREY));
					lines.add(LineList.Line.link(
							Component.translatable("automodfetcher.confirm.open_page", page), page));
				}
			}

			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.bundle_skip_hint"), LineList.Line.GREY));

			lines.add(LineList.Line.of(Component.translatable(bundle.isEmbedded()
					? "automodfetcher.confirm.bundle_detail_direct"
					: "automodfetcher.confirm.bundle_detail",
					Sizes.format(bundle.size())), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.downloads().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.download", plan.downloads().size()),
					LineList.Line.WHITE));

			for (ModEntry entry : plan.downloads()) {
				lines.add(LineList.Line.of(Component.literal("  " + entry.fileName()), LineList.Line.GREEN));
				lines.add(LineList.Line.of(Component.translatable("automodfetcher.confirm.file_detail",
						Sizes.format(entry.size()), ClientConfig.hostOf(entry.url())), LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.blocked().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.blocked", plan.blocked().size()),
					LineList.Line.RED));

			for (SyncPlan.Blocked blocked : plan.blocked()) {
				lines.add(LineList.Line.of(Component.literal("  " + blocked.entry().fileName()), LineList.Line.RED));
				lines.add(LineList.Line.of(Component.translatable(blocked.reasonKey(),
						ClientConfig.hostOf(blocked.entry().url())), LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.manual().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.manual", plan.manual().size()),
					LineList.Line.YELLOW));

			for (ManualEntry entry : plan.manual()) {
				lines.add(LineList.Line.of(Component.literal("  " + entry.fileName()), LineList.Line.YELLOW));

				// Otherwise this reads as being asked for a file already sitting in the folder.
				// It is the same name and a different build, and the one they have is the one
				// the server refuses — so saying nothing here leaves them stuck on a screen
				// that looks wrong rather than one telling them what to change.
				if (plan.replaces().contains(entry.fileName())) {
					lines.add(LineList.Line.of(
							Component.translatable("automodfetcher.confirm.manual.replaces"),
							LineList.Line.RED));
				}

				// A file name alone leaves the player guessing what to search for.
				if (entry.hasPage()) {
					lines.add(LineList.Line.link(
							Component.translatable("automodfetcher.confirm.open_page", entry.pageUrl()),
							entry.pageUrl()));
				}
			}

			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		// Ahead of everything else and in red, because it is the only entry on this screen
		// that predicts a game which will not start rather than one that is merely incomplete.
		if (!plan.missingDependencies().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.missing_dependency",
							plan.missingDependencies().size()),
					LineList.Line.RED));

			for (SyncPlan.MissingDependency missing : plan.missingDependencies()) {
				lines.add(LineList.Line.of(Component.literal("  " + missing.modId()), LineList.Line.RED));
				lines.add(LineList.Line.of(
						Component.translatable("automodfetcher.confirm.needed_by",
								String.join(", ", missing.neededBy())),
						LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.missing_dependency_hint"),
					LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.reenable().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.reenable",
							plan.reenable().size()),
					LineList.Line.WHITE));

			for (String fileName : plan.reenable()) {
				lines.add(LineList.Line.of(Component.literal("  " + fileName), LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.reenable_hint"), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.foreign().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.foreign", plan.foreign().size()),
					LineList.Line.YELLOW));

			for (String fileName : plan.foreign()) {
				lines.add(LineList.Line.of(Component.literal("  " + fileName), LineList.Line.YELLOW));
			}

			// Says where they go, because "we will move your mods" is only acceptable if the
			// player can see it is reversible before they agree to it.
			lines.add(LineList.Line.of(Component.translatable("automodfetcher.confirm.foreign_hint",
					PendingOpsApplier.disabledDir().getFileName().toString()), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		// Above the "join anyway" note, because it is the one case where joining anyway does
		// not mean a smaller game — it means one that will not start.
		if (!plan.neededByLocal().isEmpty()) {
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.translatable(
					"automodfetcher.confirm.section.needed_by_local", plan.neededByLocal().size()),
					LineList.Line.RED));

			for (String fileName : plan.neededByLocal()) {
				lines.add(LineList.Line.of(Component.literal("  " + fileName), LineList.Line.RED));
			}

			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.needed_by_local_hint"),
					LineList.Line.GREY));
		}

		// Said last, next to the button that acts on it.
		if (!plan.downloads().isEmpty() || !plan.bundles().isEmpty()) {
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.connect_anyway_hint"),
					LineList.Line.GREY));
		}

		if (!plan.deletions().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.remove", plan.deletions().size()),
					LineList.Line.WHITE));

			for (String fileName : plan.deletions()) {
				lines.add(LineList.Line.of(Component.literal("  " + fileName), LineList.Line.GREY));
			}
		}

		return lines;
	}

	/**
	 * Record the decision, then reconnect. Remembering it matters more than the reconnect:
	 * without that, coming back through the multiplayer list later hits the same wall.
	 */
	private void connectAnyway() {
		SkipDecisions decisions = SkipDecisions.load();
		decisions.accept(ClientSession.serverKey(), plan.declineSignature());
		ClientSession.rememberDiagnosis(plan);

		ServerData server = ClientSession.lastServer();

		if (this.minecraft == null || server == null) {
			// Nothing to reconnect to from here, but the decision is saved, so joining from
			// the server list will now go straight through.
			onClose();
			return;
		}

		//? if >=1.20.5 {
		/*ConnectScreen.startConnecting(new TitleScreen(), this.minecraft,
				ServerAddress.parseString(server.ip), server, false, null);
		*///?} else {
		ConnectScreen.startConnecting(new TitleScreen(), this.minecraft,
				ServerAddress.parseString(server.ip), server, false);
		//?}
	}

	/**
	 * The plan minus whatever the player ticked off.
	 *
	 * <p>A bundle whose every member was declined is dropped whole: the zip is one download,
	 * and fetching sixty megabytes to unpack nothing out of it helps nobody.
	 */
	private SyncPlan withoutSkipped() {
		if (skipped.isEmpty()) {
			return plan;
		}

		List<ModBundle> kept = new ArrayList<>();

		for (ModBundle bundle : plan.bundles()) {
			List<BundledMod> wanted = bundle.contents().stream()
					.filter(mod -> !skipped.contains(mod.fileName()))
					.toList();

			if (!wanted.isEmpty()) {
				kept.add(new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), wanted,
						bundle.data()));
			}
		}

		AutoModFetcher.LOGGER.info("Skipping {} bundled file(s) at the player's request: {}",
				skipped.size(), String.join(", ", skipped));

		return new SyncPlan(plan.downloads(), List.copyOf(kept), plan.blocked(), plan.deletions(),
				plan.manual(), plan.hostsNeedingConsent(), plan.foreign(), plan.missingDependencies(),
				plan.reenable(), plan.bundledPages(), plan.neededByLocal(), plan.replaces());
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return list.mouseDragged(mouseY) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		list.mouseReleased();
		return super.mouseReleased(mouseX, mouseY, button);
	}

	private void click() {
		if (this.minecraft != null) {
			this.minecraft.getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
		}
	}

	private void openModsFolder() {
		Util.getPlatform().openFile(ModPaths.modsDir().toFile());
	}

	private void startDownload() {
		if (rememberChoice != null && rememberChoice.selected()) {
			TrustedServers.load().trust(ClientSession.serverKey());
		}

		// Pressing the button is the grant. It is recorded for this server only, and never
		// added to allowedDomains, which would hand the host to every other server too.
		SourcePolicy granted = policy;

		if (!plan.hostsNeedingConsent().isEmpty()) {
			TrustedSources.load().grant(ClientSession.serverKey(), plan.hostsNeedingConsent());
			granted = policy.plus(Set.copyOf(plan.hostsNeedingConsent()));
		}

		DownloadSession session = new DownloadSession(withoutSkipped(), config, granted);
		session.start();

		if (this.minecraft != null) {
			this.minecraft.setScreen(new ModSyncProgressScreen(session));
		}
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		// 1.20.1's Screen#render only draws the widgets; the backdrop is ours to draw.
		//? if >=1.20.2 {
		/*this.renderBackground(context, mouseX, mouseY, delta);
		*///?} else {
		this.renderBackground(context);
		//?}
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);

		if (plan.incomingFileCount() > 0) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.confirm.subtitle",
							Sizes.format(plan.totalDownloadBytes())),
					this.width / 2, 32, 0xFFA0A0A0);
		} else {
			// Joining without a mod the server needs ends in an immediate, unexplained drop.
			// Saying so here is the only warning the player will get.
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.confirm.connect_anyway_warning"),
					this.width / 2, 32, 0xFFFF9955);
		}

		if (sharedInstall) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.confirm.shared_install"),
					this.width / 2, 46, 0xFFFF9955);
		}

		list.render(context, this.font, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (list.mouseClicked(mouseX, mouseY)) {
			return true;
		}

		String toggled = list.toggleAt(mouseX, mouseY);

		if (toggled != null) {
			if (ALL_KEY.equals(toggled)) {
				if (skipped.isEmpty()) {
					plan.bundles().forEach(bundle ->
							bundle.contents().forEach(mod -> skipped.add(mod.fileName())));
				} else {
					skipped.clear();
				}
			} else if (!skipped.remove(toggled)) {
				skipped.add(toggled);
			}

			// Hand-drawn rows are not widgets, so nothing plays the click for them. Silence on
			// a control that visibly changed is the part that feels broken.
			click();
			list.setLines(buildLines());
			return true;
		}

		String url = list.urlAt(mouseX, mouseY);

		if (url != null && this.minecraft != null) {
			// Route through the vanilla confirmation so opening a browser is never a surprise,
			// and so the player sees the address before they go there.
			this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
				if (confirmed) {
					Util.getPlatform().openUri(url);
				}

				this.minecraft.setScreen(this);
			}, url, false));

			return true;
		}

		return super.mouseClicked(mouseX, mouseY, button);
	}

	//? if >=1.20.2 {
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (list.mouseScrolled(vertical)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}
	*///?} else {
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (list.mouseScrolled(amount)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, amount);
	}
	//?}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new TitleScreen());
		}
	}
}
