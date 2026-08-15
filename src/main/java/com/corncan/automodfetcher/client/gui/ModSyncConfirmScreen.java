package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.ClientNetworking;
import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.LauncherDetection;
import com.corncan.automodfetcher.client.SkipDecisions;
import com.corncan.automodfetcher.client.SyncPlan;
import com.corncan.automodfetcher.client.TrustedServers;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.util.ModPaths;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * Asks the player before anything is written to their mods folder.
 *
 * <p>The source host is shown for every file on purpose: agreeing here means letting a server
 * put code on this machine, and the player should be able to see where it comes from.
 */
@Environment(EnvType.CLIENT)
public class ModSyncConfirmScreen extends Screen {
	private final SyncPlan plan;
	private final ClientConfig config;
	private final LineList list = new LineList();

	private boolean sharedInstall;
	private Checkbox rememberChoice;

	public ModSyncConfirmScreen(SyncPlan plan, ClientConfig config) {
		super(Component.translatable("automodfetcher.confirm.title"));
		this.plan = plan;
		this.config = config;
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
			Component acceptLabel = plan.downloads().isEmpty()
					? Component.translatable("automodfetcher.confirm.apply")
					: Component.translatable("automodfetcher.confirm.accept");

			this.addRenderableWidget(Button.builder(acceptLabel, button -> startDownload())
					.bounds(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.cancel"), button -> onClose())
					.bounds(this.width / 2 + 4, buttonY, 150, 20)
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

			lines.add(LineList.Line.of(Component.translatable("automodfetcher.confirm.blocked_hint",
					ClientConfig.FILE_NAME), LineList.Line.GREY));
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
		}

		if (!plan.manual().isEmpty()) {
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.manual", plan.manual().size()),
					LineList.Line.YELLOW));

			for (ManualEntry entry : plan.manual()) {
				lines.add(LineList.Line.of(Component.literal("  " + entry.fileName()), LineList.Line.YELLOW));

				// A file name alone leaves the player guessing what to search for.
				if (entry.hasPage()) {
					lines.add(LineList.Line.link(
							Component.translatable("automodfetcher.confirm.open_page", entry.pageUrl()),
							entry.pageUrl()));
				}
			}

			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
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
		decisions.accept(ClientNetworking.serverKey(), plan.unavailableSignature());
		ClientNetworking.rememberDiagnosis(plan);

		ServerData server = ClientNetworking.lastServer();

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

	private void openModsFolder() {
		Util.getPlatform().openFile(ModPaths.modsDir().toFile());
	}

	private void startDownload() {
		if (rememberChoice != null && rememberChoice.selected()) {
			TrustedServers.load().trust(ClientNetworking.serverKey());
		}

		DownloadSession session = new DownloadSession(plan, config);
		session.start();

		if (this.minecraft != null) {
			this.minecraft.setScreen(new ModSyncProgressScreen(session, plan.downloads()));
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

		if (!plan.downloads().isEmpty()) {
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
