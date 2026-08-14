package com.corncan.automodfetcher.client.gui;

import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.GameRestarter;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * The end of the flow. Newly downloaded jars are on disk but Fabric already finished scanning
 * the mods folder this launch, so they only take effect after a restart.
 */
@Environment(EnvType.CLIENT)
public class ModSyncCompleteScreen extends Screen {
	private final DownloadSession session;

	private boolean restartFailed;

	public ModSyncCompleteScreen(DownloadSession session) {
		super(Text.translatable("automodfetcher.complete.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		int buttonY = this.height / 2 + 40;

		if (GameRestarter.isSupported()) {
			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.complete.restart_now"), button -> restartNow())
					.dimensions(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.complete.quit"), button -> quit())
					.dimensions(this.width / 2 + 4, buttonY, 150, 20)
					.build());
		} else {
			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.complete.quit"), button -> quit())
					.dimensions(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.complete.menu"), button -> {
						if (this.client != null) {
							this.client.setScreen(new TitleScreen());
						}
					})
					.dimensions(this.width / 2 + 4, buttonY, 150, 20)
					.build());
		}
	}

	private void quit() {
		if (this.client != null) {
			this.client.scheduleStop();
		}
	}

	/** Only close this instance once the new one is actually up. */
	private void restartNow() {
		if (GameRestarter.restart()) {
			quit();
		} else {
			restartFailed = true;
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		int centerY = this.height / 2 - 40;

		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, centerY, 0xFFFFFFFF);

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("automodfetcher.complete.installed", session.successCount()),
				this.width / 2, centerY + 18, 0xFF55FF55);

		int line = centerY + 30;

		if (session.deletionCount() > 0) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.complete.removed", session.deletionCount()),
					this.width / 2, line, 0xFFA0A0A0);
			line += 12;
		}

		if (session.failureCount() > 0) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.complete.failed", session.failureCount()),
					this.width / 2, line, 0xFFFF5555);
			line += 12;
		}

		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("automodfetcher.complete.restart"),
				this.width / 2, line + 8, 0xFFFFD966);

		if (restartFailed) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable("automodfetcher.complete.restart_failed"),
					this.width / 2, line + 20, 0xFFFF5555);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
