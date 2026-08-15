package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.PendingDiagnosis;
import com.corncan.automodfetcher.client.SyncPlan;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.util.ModPaths;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Shown when a player who joined without every mod is dropped shortly afterwards.
 *
 * <p>The server's own message for this is useless — the failure surfaces as an exception in
 * another mod's packet handler and the player just sees "Disconnected". We knew what was
 * missing before they connected, so this says it plainly and hands over the links.
 *
 * <p>It only offers an explanation, never a certainty: the drop could have been a kick, a
 * ban or a dead connection. The server's own words are shown above ours for that reason.
 */
@Environment(EnvType.CLIENT)
public class ModSyncDisconnectScreen extends Screen {
	private static final int REASON_TOP = 34;
	private static final int REASON_LINE_HEIGHT = 10;

	/** Server messages run long — an unhandled exception's text is often a full paragraph. */
	private static final int REASON_MAX_LINES = 3;

	private final PendingDiagnosis diagnosis;
	private final Component serverReason;
	private final LineList list = new LineList();

	private MultiLineLabel reasonLines = MultiLineLabel.EMPTY;
	private int causeY;

	public ModSyncDisconnectScreen(PendingDiagnosis diagnosis, Component serverReason) {
		super(Component.translatable("automodfetcher.kicked.title"));
		this.diagnosis = diagnosis;
		this.serverReason = serverReason;
	}

	@Override
	protected void init() {
		int listWidth = Math.min(360, this.width - 60);
		int listX = (this.width - listWidth) / 2;

		// Wrapped rather than drawn as one line: the reason is whatever the server sent, and
		// a raw exception message runs straight off both edges of the window.
		reasonLines = MultiLineLabel.create(this.font,
				Component.translatable("automodfetcher.kicked.server_said", serverReason),
				listWidth, REASON_MAX_LINES);

		causeY = REASON_TOP + reasonLines.getLineCount() * REASON_LINE_HEIGHT + 6;

		int listTop = causeY + 16;
		int listBottom = this.height - (diagnosis.manual().isEmpty() ? 46 : 70);

		list.setBounds(listX, listTop, listWidth, listBottom - listTop);
		list.setLines(buildLines());

		if (!diagnosis.manual().isEmpty()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.confirm.open_folder"),
					button -> Util.getPlatform().openFile(ModPaths.modsDir().toFile()))
					.bounds(this.width / 2 - 100, this.height - 60, 200, 20)
					.build());
		}

		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.confirm.back"), button -> onClose())
				.bounds(this.width / 2 - 75, this.height - 36, 150, 20)
				.build());
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		if (!diagnosis.manual().isEmpty()) {
			lines.add(LineList.Line.of(Component.translatable("automodfetcher.kicked.missing"), LineList.Line.YELLOW));

			for (ManualEntry entry : diagnosis.manual()) {
				lines.add(LineList.Line.of(Component.literal("  " + entry.fileName()), LineList.Line.YELLOW));

				if (entry.hasPage()) {
					lines.add(LineList.Line.link(
							Component.translatable("automodfetcher.confirm.open_page", entry.pageUrl()),
							entry.pageUrl()));
				}
			}
		}

		if (!diagnosis.blocked().isEmpty()) {
			lines.add(LineList.Line.of(Component.empty(), LineList.Line.GREY));
			lines.add(LineList.Line.of(
					Component.translatable("automodfetcher.confirm.section.blocked", diagnosis.blocked().size()),
					LineList.Line.RED));

			for (SyncPlan.Blocked blocked : diagnosis.blocked()) {
				lines.add(LineList.Line.of(Component.literal("  " + blocked.entry().fileName()), LineList.Line.RED));
				lines.add(LineList.Line.of(Component.translatable(blocked.reasonKey(),
						ClientConfig.hostOf(blocked.entry().url())), LineList.Line.GREY));
			}
		}

		return lines;
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);

		// The server's words first: ours are a hypothesis about them, not a replacement.
		reasonLines.renderCentered(context, this.width / 2, REASON_TOP, REASON_LINE_HEIGHT, 0xFFA0A0A0);

		context.drawCenteredString(this.font,
				Component.translatable("automodfetcher.kicked.likely_cause"), this.width / 2, causeY, 0xFFFFD966);

		list.render(context, this.font, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		String url = list.urlAt(mouseX, mouseY);

		if (url != null && this.minecraft != null) {
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

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (list.mouseScrolled(amount)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new TitleScreen());
		}
	}
}
