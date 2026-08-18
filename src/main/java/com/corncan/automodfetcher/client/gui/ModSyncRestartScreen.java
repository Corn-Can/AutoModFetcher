package com.corncan.automodfetcher.client.gui;

import com.corncan.automodfetcher.client.GameRestarter;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Stops a player joining while mods this launch already removed are still loaded.
 *
 * <p>The gap this covers is not obvious and costs a whole session when it bites. Mod jars are
 * discovered and resolved before any loader hands out a hook, so the removals applied at
 * startup take effect on disk while the game carries on running the very code that was
 * deleted. The mods folder now matches the server; the running game does not. Left alone, the
 * player joins, gets dropped a second later by registry sync, and is told nothing — the mod
 * list they were just shown was correct, which is what makes it so hard to work out.
 *
 * <p>So this is the one place the mod says no rather than warning. There is nothing to
 * download and nothing to choose; one restart away is a working join, and any other button
 * leads back to the same disconnect.
 */
public class ModSyncRestartScreen extends Screen {
	public ModSyncRestartScreen() {
		super(Component.translatable("automodfetcher.restart.title"));
	}

	@Override
	protected void init() {
		int buttonY = this.height / 2 + 30;

		if (GameRestarter.isSupported()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.restart_now"), button -> restart())
					.bounds(this.width / 2 - 154, buttonY, 150, 20)
					.build());

			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.menu"), button -> onClose())
					.bounds(this.width / 2 + 4, buttonY, 150, 20)
					.build());

			return;
		}

		// Forge and NeoForge cannot rebuild their own command line, so quitting is the only
		// thing we can actually offer to do for them.
		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.complete.quit"),
				button -> this.minecraft.stop())
				.bounds(this.width / 2 - 154, buttonY, 150, 20)
				.build());

		this.addRenderableWidget(Button.builder(
				Component.translatable("automodfetcher.complete.menu"), button -> onClose())
				.bounds(this.width / 2 + 4, buttonY, 150, 20)
				.build());
	}

	private void restart() {
		if (!GameRestarter.restart()) {
			this.addRenderableWidget(Button.builder(
					Component.translatable("automodfetcher.complete.restart_failed"), button -> onClose())
					.bounds(this.width / 2 - 100, this.height / 2 + 54, 200, 20)
					.build());
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

		context.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
		context.drawCenteredString(this.font, Component.translatable("automodfetcher.restart.body"),
				this.width / 2, this.height / 2 - 16, 0xFFFFAA55);
		context.drawCenteredString(this.font, Component.translatable("automodfetcher.restart.why"),
				this.width / 2, this.height / 2, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(new TitleScreen());
		}
	}
}
