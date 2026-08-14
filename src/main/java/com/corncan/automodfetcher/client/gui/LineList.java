package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * A plain scrolling list of text lines, some of which can be links.
 *
 * <p>Vanilla's entry list widgets are built around selectable rows; all these screens need
 * is clipped, scrollable text, which is far less machinery.
 */
@Environment(EnvType.CLIENT)
public class LineList {
	private static final int LINE_HEIGHT = 12;

	/** @param url when set, the line is clickable and drawn as a link */
	public record Line(Text text, int color, String url) {
		public static final int WHITE = 0xFFFFFFFF;
		public static final int GREY = 0xFFA0A0A0;
		public static final int RED = 0xFFFF5555;
		public static final int YELLOW = 0xFFFFD966;
		public static final int GREEN = 0xFF55FF55;
		public static final int LINK = 0xFF6AB7FF;
		public static final int LINK_HOVER = 0xFFB0DDFF;

		public static Line of(Text text, int color) {
			return new Line(text, color, null);
		}

		public static Line link(Text text, String url) {
			return new Line(text, LINK, url);
		}

		public boolean isLink() {
			return url != null && !url.isBlank();
		}
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

	public void render(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY) {
		context.fill(x - 4, y - 4, x + width + 4, y + height + 4, 0x60000000);
		context.enableScissor(x, y, x + width, y + height);

		int hovered = indexAt(mouseX, mouseY);
		int index = 0;

		for (Line line : lines) {
			int lineY = y + index * LINE_HEIGHT - (int) scroll;
			boolean isHovered = index == hovered;
			index++;

			if (lineY + LINE_HEIGHT < y || lineY > y + height) {
				continue;
			}

			int color = line.isLink() && isHovered ? Line.LINK_HOVER : line.color();
			context.drawTextWithShadow(textRenderer, line.text(), x, lineY, color);

			if (line.isLink()) {
				int underlineY = lineY + 9;
				int textWidth = textRenderer.getWidth(line.text());
				context.fill(x, underlineY, x + textWidth, underlineY + 1, color);
			}
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

	/** The URL of the line under the cursor, or null. */
	public String urlAt(double mouseX, double mouseY) {
		int index = indexAt(mouseX, mouseY);

		if (index < 0) {
			return null;
		}

		Line line = lines.get(index);
		return line.isLink() ? line.url() : null;
	}

	private int indexAt(double mouseX, double mouseY) {
		if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
			return -1;
		}

		int index = (int) ((mouseY - y + scroll) / LINE_HEIGHT);
		return index >= 0 && index < lines.size() ? index : -1;
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
