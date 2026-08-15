package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.network.ModEntry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class ModSyncProgressScreen extends Screen {
	private final DownloadSession session;
	private final List<ModEntry> entries;
	private final LineList list = new LineList();

	public ModSyncProgressScreen(DownloadSession session, List<ModEntry> entries) {
		super(Text.translatable("automodfetcher.progress.title"));
		this.session = session;
		this.entries = entries;
	}

	@Override
	protected void init() {
		int listTop = 86;
		int listBottom = this.height - 46;
		int listWidth = Math.min(360, this.width - 60);
		int listX = (this.width - listWidth) / 2;

		list.setBounds(listX, listTop, listWidth, listBottom - listTop);

		this.addDrawableChild(ButtonWidget.builder(
				Text.translatable("automodfetcher.progress.cancel"), button -> session.cancel())
				.dimensions(this.width / 2 - 75, this.height - 36, 150, 20)
				.build());
	}

	@Override
	public void tick() {
		if (session.isFinished() && this.client != null) {
			this.client.setScreen(new ModSyncCompleteScreen(session));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFFFF);

		long total = Math.max(1, session.totalBytes());
		long done = Math.min(session.downloadedBytes(), total);
		float fraction = (float) done / total;

		int barWidth = Math.min(360, this.width - 60);
		int barX = (this.width - barWidth) / 2;
		int barY = 42;

		context.fill(barX, barY, barX + barWidth, barY + 10, 0xFF303030);
		context.fill(barX, barY, barX + (int) (barWidth * fraction), barY + 10, 0xFF55C355);

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("automodfetcher.progress.bytes",
						Sizes.format(done), Sizes.format(session.totalBytes())),
				this.width / 2, barY + 16, 0xFFA0A0A0);

		// A bar alone cannot tell someone whether to wait or walk away.
		long perSecond = session.bytesPerSecond();

		if (perSecond > 0) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.progress.rate",
							Sizes.format(perSecond), formatRemaining(session.secondsRemaining())),
					this.width / 2, barY + 28, 0xFFA0A0A0);
		}

		list.setLines(buildLines());
		list.render(context, this.textRenderer, mouseX, mouseY);
	}

	/** Rounded generously: a countdown that claims precision it does not have reads as broken. */
	private String formatRemaining(long seconds) {
		if (seconds < 0) {
			return "--";
		}

		if (seconds < 60) {
			return Math.max(1, seconds) + "s";
		}

		return (seconds / 60) + "m " + (seconds % 60) + "s";
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		for (ModEntry entry : entries) {
			DownloadSession.Status status = session.statusOf(entry.fileName());
			int color = switch (status) {
				case DONE -> LineList.Line.GREEN;
				case FAILED -> LineList.Line.RED;
				case DOWNLOADING -> LineList.Line.WHITE;
				case PENDING -> LineList.Line.GREY;
			};

			lines.add(LineList.Line.of(Text.translatable(
					"automodfetcher.progress.entry." + status.name().toLowerCase(java.util.Locale.ROOT),
					entry.fileName()), color));

			String reason = session.failureReasonOf(entry.fileName());

			if (status == DownloadSession.Status.FAILED && reason != null) {
				lines.add(LineList.Line.of(Text.literal("    " + reason), LineList.Line.GREY));
			}
		}

		return lines;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (list.mouseScrolled(amount)) {
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, amount);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Escaping mid-download would leave half-written files with no screen to report them.
		return false;
	}
}
