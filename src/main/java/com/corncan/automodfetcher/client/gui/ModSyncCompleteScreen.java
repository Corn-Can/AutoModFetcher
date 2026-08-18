package com.corncan.automodfetcher.client.gui;

import com.corncan.automodfetcher.PendingOps;
import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.SyncPlan;

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
public class ModSyncCompleteScreen extends Screen {
	private final DownloadSession session;

	private boolean removalPending;


	public ModSyncCompleteScreen(DownloadSession session) {
		super(Component.translatable("automodfetcher.complete.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		int primaryY = this.height / 2 + 30;
		int secondaryY = this.height / 2 + 54;

		removalPending = PendingOps.load().hasWork();

		// Closing is the only thing offered, on every loader. Relaunching the game from inside
		// the game was never dependable — a launcher that supervises its children defeats it,
		// and Forge and NeoForge never kept the arguments to try — and once removals are
		// finished off after this process dies, a replacement started from in here would simply
		// outrun them. What is left is honest and the same everywhere: close, then reopen.
		if (session.hasUnfinishedWork()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.retry"), button -> retry())
					.bounds(this.width / 2 - 100, primaryY, 200, 20)
					.build());
		}

		this.addRenderableWidget(Button.builder(
				Component.translatable(removalPending
						? "automodfetcher.complete.quit_to_finish"
						: "automodfetcher.complete.quit"), button -> quit())
				.bounds(this.width / 2 - 154, secondaryY, 150, 20)
				.build());

		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.complete.menu"), button -> onClose())
				.bounds(this.width / 2 + 4, secondaryY, 150, 20)
				.build());
	}

	private void retry() {
		SyncPlan remaining = session.remainingWork();
		DownloadSession next = new DownloadSession(remaining, session.config(), session.policy());
		next.start();

		if (this.minecraft != null) {
			this.minecraft.setScreen(new ModSyncProgressScreen(next));
		}
	}

	private void quit() {
		if (this.minecraft != null) {
			this.minecraft.stop();
		}
	}

	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		//? if >=1.20.2 {
		/*this.renderBackground(context, mouseX, mouseY, delta);
		*///?} else {
		this.renderBackground(context);
		//?}
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

		if (session.disabledCount() > 0) {
			context.drawCenteredString(this.font,
					Component.translatable("automodfetcher.complete.disabled", session.disabledCount()),
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
		Component closing;

		if (session.hasUnfinishedWork()) {
			closing = Component.translatable("automodfetcher.complete.incomplete");
		} else if (removalPending) {
			// The removal happens as this closes, so "restart" would understate what the
			// player has to do and overstate what has already happened.
			closing = Component.translatable("automodfetcher.complete.close_to_finish");
		} else {
			closing = Component.translatable("automodfetcher.complete.restart");
		}

		context.drawCenteredString(this.font, closing, this.width / 2, line + 8, 0xFFFFD966);
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
