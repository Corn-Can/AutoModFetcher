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
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

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
	private final Text serverReason;
	private final LineList list = new LineList();

	private MultilineText reasonLines = MultilineText.EMPTY;
	private int causeY;

	public ModSyncDisconnectScreen(PendingDiagnosis diagnosis, Text serverReason) {
		super(Text.translatable("automodfetcher.kicked.title"));
		this.diagnosis = diagnosis;
		this.serverReason = serverReason;
	}

	@Override
	protected void init() {
		int listWidth = Math.min(360, this.width - 60);
		int listX = (this.width - listWidth) / 2;

		// Wrapped rather than drawn as one line: the reason is whatever the server sent, and
		// a raw exception message runs straight off both edges of the window.
		reasonLines = MultilineText.create(this.textRenderer,
				Text.translatable("automodfetcher.kicked.server_said", serverReason),
				listWidth, REASON_MAX_LINES);

		causeY = REASON_TOP + reasonLines.count() * REASON_LINE_HEIGHT + 6;

		int listTop = causeY + 16;
		int listBottom = this.height - (diagnosis.manual().isEmpty() ? 46 : 70);

		list.setBounds(listX, listTop, listWidth, listBottom - listTop);
		list.setLines(buildLines());

		if (!diagnosis.manual().isEmpty()) {
			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.confirm.open_folder"),
					button -> Util.getOperatingSystem().open(ModPaths.modsDir().toFile()))
					.dimensions(this.width / 2 - 100, this.height - 60, 200, 20)
					.build());
		}

		this.addDrawableChild(ButtonWidget.builder(
				Text.translatable("automodfetcher.confirm.back"), button -> close())
				.dimensions(this.width / 2 - 75, this.height - 36, 150, 20)
				.build());
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		if (!diagnosis.manual().isEmpty()) {
			lines.add(LineList.Line.of(Text.translatable("automodfetcher.kicked.missing"), LineList.Line.YELLOW));

			for (ManualEntry entry : diagnosis.manual()) {
				lines.add(LineList.Line.of(Text.literal("  " + entry.fileName()), LineList.Line.YELLOW));

				if (entry.hasPage()) {
					lines.add(LineList.Line.link(
							Text.translatable("automodfetcher.confirm.open_page", entry.pageUrl()),
							entry.pageUrl()));
				}
			}
		}

		if (!diagnosis.blocked().isEmpty()) {
			lines.add(LineList.Line.of(Text.empty(), LineList.Line.GREY));
			lines.add(LineList.Line.of(
					Text.translatable("automodfetcher.confirm.section.blocked", diagnosis.blocked().size()),
					LineList.Line.RED));

			for (SyncPlan.Blocked blocked : diagnosis.blocked()) {
				lines.add(LineList.Line.of(Text.literal("  " + blocked.entry().fileName()), LineList.Line.RED));
				lines.add(LineList.Line.of(Text.translatable(blocked.reasonKey(),
						ClientConfig.hostOf(blocked.entry().url())), LineList.Line.GREY));
			}
		}

		return lines;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFFFF);

		// The server's words first: ours are a hypothesis about them, not a replacement.
		reasonLines.drawCenterWithShadow(context, this.width / 2, REASON_TOP, REASON_LINE_HEIGHT, 0xFFA0A0A0);

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("automodfetcher.kicked.likely_cause"), this.width / 2, causeY, 0xFFFFD966);

		list.render(context, this.textRenderer, mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		String url = list.urlAt(mouseX, mouseY);

		if (url != null && this.client != null) {
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
