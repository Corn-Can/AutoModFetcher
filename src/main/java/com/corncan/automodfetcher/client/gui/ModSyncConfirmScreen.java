package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.ClientNetworking;
import com.corncan.automodfetcher.client.SkipDecisions;
import com.corncan.automodfetcher.client.SyncPlan;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModEntry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

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

	public ModSyncConfirmScreen(SyncPlan plan, ClientConfig config) {
		super(Text.translatable("automodfetcher.confirm.title"));
		this.plan = plan;
		this.config = config;
	}

	@Override
	protected void init() {
		int listTop = 52;
		int listBottom = this.height - 52;
		int listWidth = Math.min(360, this.width - 60);
		int listX = (this.width - listWidth) / 2;

		list.setBounds(listX, listTop, listWidth, listBottom - listTop);
		list.setLines(buildLines());

		int buttonY = this.height - 40;

		// Removals alone are still work to apply, so the action button must appear for them too.
		if (plan.hasActionableWork()) {
			Text acceptLabel = plan.downloads().isEmpty()
					? Text.translatable("automodfetcher.confirm.apply")
					: Text.translatable("automodfetcher.confirm.accept");

			this.addDrawableChild(ButtonWidget.builder(acceptLabel, button -> startDownload())
					.dimensions(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.confirm.cancel"), button -> close())
					.dimensions(this.width / 2 + 4, buttonY, 150, 20)
					.build());
		} else {
			// Nothing here is ours to install. Showing the list is still worth doing — it is
			// how the player learns what to fetch — but it must never become a dead end, so
			// the way onward is always offered.
			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.confirm.connect_anyway"), button -> connectAnyway())
					.dimensions(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.confirm.back"), button -> close())
					.dimensions(this.width / 2 + 4, buttonY, 150, 20)
					.build());
		}
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		if (!plan.downloads().isEmpty()) {
			lines.add(LineList.Line.of(
					Text.translatable("automodfetcher.confirm.section.download", plan.downloads().size()),
					LineList.Line.WHITE));

			for (ModEntry entry : plan.downloads()) {
				lines.add(LineList.Line.of(Text.literal("  " + entry.fileName()), LineList.Line.GREEN));
				lines.add(LineList.Line.of(Text.translatable("automodfetcher.confirm.file_detail",
						Sizes.format(entry.size()), ClientConfig.hostOf(entry.url())), LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.blocked().isEmpty()) {
			lines.add(LineList.Line.of(
					Text.translatable("automodfetcher.confirm.section.blocked", plan.blocked().size()),
					LineList.Line.RED));

			for (SyncPlan.Blocked blocked : plan.blocked()) {
				lines.add(LineList.Line.of(Text.literal("  " + blocked.entry().fileName()), LineList.Line.RED));
				lines.add(LineList.Line.of(Text.translatable(blocked.reasonKey(),
						ClientConfig.hostOf(blocked.entry().url())), LineList.Line.GREY));
			}

			lines.add(LineList.Line.of(Text.translatable("automodfetcher.confirm.blocked_hint",
					ClientConfig.FILE_NAME), LineList.Line.GREY));
			lines.add(LineList.Line.of(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.manual().isEmpty()) {
			lines.add(LineList.Line.of(
					Text.translatable("automodfetcher.confirm.section.manual", plan.manual().size()),
					LineList.Line.YELLOW));

			for (ManualEntry entry : plan.manual()) {
				lines.add(LineList.Line.of(Text.literal("  " + entry.fileName()), LineList.Line.YELLOW));

				// A file name alone leaves the player guessing what to search for.
				if (entry.hasPage()) {
					lines.add(LineList.Line.link(
							Text.translatable("automodfetcher.confirm.open_page", entry.pageUrl()),
							entry.pageUrl()));
				}
			}

			lines.add(LineList.Line.of(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.deletions().isEmpty()) {
			lines.add(LineList.Line.of(
					Text.translatable("automodfetcher.confirm.section.remove", plan.deletions().size()),
					LineList.Line.WHITE));

			for (String fileName : plan.deletions()) {
				lines.add(LineList.Line.of(Text.literal("  " + fileName), LineList.Line.GREY));
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

		ServerInfo server = ClientNetworking.lastServer();

		if (this.client == null || server == null) {
			// Nothing to reconnect to from here, but the decision is saved, so joining from
			// the server list will now go straight through.
			close();
			return;
		}

		ConnectScreen.connect(new TitleScreen(), this.client, ServerAddress.parse(server.address), server, false);
	}

	private void startDownload() {
		DownloadSession session = new DownloadSession(plan, config);
		session.start();

		if (this.client != null) {
			this.client.setScreen(new ModSyncProgressScreen(session, plan.downloads()));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 1.20.1's Screen#render only draws the widgets; the backdrop is ours to draw.
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFFFF);

		if (!plan.downloads().isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.confirm.subtitle",
							Sizes.format(plan.totalDownloadBytes())),
					this.width / 2, 32, 0xFFA0A0A0);
		} else {
			// Joining without a mod the server needs ends in an immediate, unexplained drop.
			// Saying so here is the only warning the player will get.
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.confirm.connect_anyway_warning"),
					this.width / 2, 32, 0xFFFF9955);
		}

		list.render(context, this.textRenderer, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		String url = list.urlAt(mouseX, mouseY);

		if (url != null && this.client != null) {
			// Route through the vanilla confirmation so opening a browser is never a surprise,
			// and so the player sees the address before they go there.
			this.client.setScreen(new ConfirmLinkScreen(confirmed -> {
				if (confirmed) {
					Util.getOperatingSystem().open(url);
				}

				this.client.setScreen(this);
			}, url, false));

			return true;
		}

		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (list.mouseScrolled(amount)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void close() {
		if (this.client != null) {
			this.client.setScreen(new TitleScreen());
		}
	}
}
