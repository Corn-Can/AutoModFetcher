package com.corncan.automodfetcher.client.gui;

import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.GameRestarter;
import com.corncan.automodfetcher.client.SyncPlan;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * The end of the flow. Newly downloaded jars are on disk but Fabric already finished scanning
 * the mods folder this launch, so they only take effect after a restart.
 *
 * <p>Leaving the game is never the only way out of here. An earlier version replaced "back to
 * menu" with "restart now", which reads fine after a clean install and traps the player after
 * a cancelled or failed one: restarting achieves nothing, and there was nowhere else to go.
 */
@Environment(EnvType.CLIENT)
public class ModSyncCompleteScreen extends Screen {
	private final DownloadSession session;

	private boolean restartFailed;

	public ModSyncCompleteScreen(DownloadSession session) {
		super(Component.translatable("automodfetcher.complete.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		int primaryY = this.height / 2 + 30;
		int secondaryY = this.height / 2 + 54;

		// The useful first action depends on how this ended: unfinished work wants another
		// go, a clean install wants the game restarted so the mods actually load.
		if (session.hasUnfinishedWork()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.retry"), button -> retry())
					.bounds(this.width / 2 - 100, primaryY, 200, 20)
					.build());
		} else if (GameRestarter.isSupported()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.restart_now"), button -> restartNow())
					.bounds(this.width / 2 - 100, primaryY, 200, 20)
					.build());
		}

		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.complete.quit"), button -> quit())
				.bounds(this.width / 2 - 154, secondaryY, 150, 20)
				.build());

		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.complete.menu"), button -> onClose())
				.bounds(this.width / 2 + 4, secondaryY, 150, 20)
				.build());
	}

	private void retry() {
		SyncPlan remaining = session.remainingWork();
		DownloadSession next = new DownloadSession(remaining, session.config());
		next.start();

		if (this.minecraft != null) {
			this.minecraft.setScreen(new ModSyncProgressScreen(next, remaining.downloads()));
		}
	}

	private void quit() {
		if (this.minecraft != null) {
			this.minecraft.stop();
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
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		this.renderBackground(context);
		super.render(context, mouseX, mouseY, delta);

		int centerY = this.height / 2 - 50;

		context.drawCenteredString(this.font, this.title, this.width / 2, centerY, 0xFFFFFFFF);

		context.drawCenteredString(this.font,
				Component.translatable("automodfetcher.complete.installed", session.successCount()),
				this.width / 2, centerY + 18, 0xFF55FF55);

		int line = centerY + 30;

		if (session.deletionCount() > 0) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.complete.removed", session.deletionCount()),
					this.width / 2, line, 0xFFA0A0A0);
			line += 12;
		}

		if (session.failureCount() > 0) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.complete.failed", session.failureCount()),
					this.width / 2, line, 0xFFFF5555);
			line += 12;
		}

		// Restarting only helps once everything is in place; saying so avoids sending someone
		// off to relaunch into exactly the same missing mods.
		Component closing = session.hasUnfinishedWork()
				? Component.translatable("automodfetcher.complete.incomplete")
				: Component.translatable("automodfetcher.complete.restart");

		context.drawCenteredString(this.font, closing, this.width / 2, line + 8, 0xFFFFD966);

		if (restartFailed) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.complete.restart_failed"),
					this.width / 2, line + 20, 0xFFFF5555);
		}
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new TitleScreen());
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
