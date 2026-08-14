package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * A plain scrolling list of text lines.
 *
 * <p>Vanilla's entry list widgets are built around selectable rows; all these screens need
 * is clipped, scrollable text, which is far less machinery.
 */
@Environment(EnvType.CLIENT)
public class LineList {
	private static final int LINE_HEIGHT = 12;

	public record Line(Text text, int color) {
		public static final int WHITE = 0xFFFFFFFF;
		public static final int GREY = 0xFFA0A0A0;
		public static final int RED = 0xFFFF5555;
		public static final int YELLOW = 0xFFFFD966;
		public static final int GREEN = 0xFF55FF55;
	}

	private final List<Line> lines = new ArrayList<>();

	private int x;
	private int y;
	private int width;
	private int height;
	private double scroll;

	public void setBounds(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public void setLines(List<Line> newLines) {
		lines.clear();
		lines.addAll(newLines);
		scroll = Math.min(scroll, maxScroll());
	}

	public void render(DrawContext context, TextRenderer textRenderer) {
		context.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0x60000000);
		context.enableScissor(x, y, x + width, y + height);

		int index = 0;

		for (Line line : lines) {
			int lineY = y + index * LINE_HEIGHT - (int) scroll;
			index++;

			if (lineY + LINE_HEIGHT < y || lineY > y + height) {
				continue;
			}

			context.drawTextWithShadow(textRenderer, line.text(), x, lineY, line.color());
		}

		context.disableScissor();

		if (maxScroll() > 0) {
			renderScrollbar(context);
		}
	}

	private void renderScrollbar(DrawContext context) {
		int trackX = x + width + 1;
		int thumbHeight = Math.max(16, (int) ((float) height * height / (lines.size() * LINE_HEIGHT)));
		int travel = height - thumbHeight;
		int thumbY = y + (int) (travel * (scroll / maxScroll()));

		context.fill(trackX, y, trackX + 2, y + height, 0x40FFFFFF);
		context.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xC0FFFFFF);
	}

	public boolean mouseScrolled(double verticalAmount) {
		if (maxScroll() <= 0) {
			return false;
		}

		scroll = Math.max(0, Math.min(maxScroll(), scroll - verticalAmount * LINE_HEIGHT * 2));
		return true;
	}

	private double maxScroll() {
		return Math.max(0, lines.size() * LINE_HEIGHT - height);
	}
}
