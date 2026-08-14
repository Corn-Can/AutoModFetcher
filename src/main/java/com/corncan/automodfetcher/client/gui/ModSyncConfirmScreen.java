package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.DownloadSession;
import com.corncan.automodfetcher.client.SyncPlan;
import com.corncan.automodfetcher.network.ModEntry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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
		if (!plan.downloads().isEmpty() || !plan.deletions().isEmpty()) {
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
			// Nothing we are allowed to install, so the only useful action is to back out.
			this.addDrawableChild(ButtonWidget.builder(
					Text.translatable("automodfetcher.confirm.back"), button -> close())
					.dimensions(this.width / 2 - 75, buttonY, 150, 20)
					.build());
		}
	}

	private List<LineList.Line> buildLines() {
		List<LineList.Line> lines = new ArrayList<>();

		if (!plan.downloads().isEmpty()) {
			lines.add(new LineList.Line(
					Text.translatable("automodfetcher.confirm.section.download", plan.downloads().size()),
					LineList.Line.WHITE));

			for (ModEntry entry : plan.downloads()) {
				lines.add(new LineList.Line(Text.literal("  " + entry.fileName()), LineList.Line.GREEN));
				lines.add(new LineList.Line(Text.translatable("automodfetcher.confirm.file_detail",
						Sizes.format(entry.size()), ClientConfig.hostOf(entry.url())), LineList.Line.GREY));
			}

			lines.add(new LineList.Line(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.blocked().isEmpty()) {
			lines.add(new LineList.Line(
					Text.translatable("automodfetcher.confirm.section.blocked", plan.blocked().size()),
					LineList.Line.RED));

			for (SyncPlan.Blocked blocked : plan.blocked()) {
				lines.add(new LineList.Line(Text.literal("  " + blocked.entry().fileName()), LineList.Line.RED));
				lines.add(new LineList.Line(Text.translatable(blocked.reasonKey(),
						ClientConfig.hostOf(blocked.entry().url())), LineList.Line.GREY));
			}

			lines.add(new LineList.Line(Text.translatable("automodfetcher.confirm.blocked_hint",
					ClientConfig.FILE_NAME), LineList.Line.GREY));
			lines.add(new LineList.Line(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.manual().isEmpty()) {
			lines.add(new LineList.Line(
					Text.translatable("automodfetcher.confirm.section.manual", plan.manual().size()),
					LineList.Line.YELLOW));

			for (String fileName : plan.manual()) {
				lines.add(new LineList.Line(Text.literal("  " + fileName), LineList.Line.YELLOW));
			}

			lines.add(new LineList.Line(Text.empty(), LineList.Line.GREY));
		}

		if (!plan.deletions().isEmpty()) {
			lines.add(new LineList.Line(
					Text.translatable("automodfetcher.confirm.section.remove", plan.deletions().size()),
					LineList.Line.WHITE));

			for (String fileName : plan.deletions()) {
				lines.add(new LineList.Line(Text.literal("  " + fileName), LineList.Line.GREY));
			}
		}

		return lines;
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
		}

		list.render(context, this.textRenderer);
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
