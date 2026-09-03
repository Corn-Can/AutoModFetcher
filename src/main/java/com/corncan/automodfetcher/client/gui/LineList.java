package com.corncan.automodfetcher.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A plain scrolling list of text lines, some of which can be links.
 *
 * <p>Vanilla's entry list widgets are built around selectable rows; all these screens need
 * is clipped, scrollable text, which is far less machinery.
 *
 * <p>A line can also be a toggle. That is the smallest thing that answers "let me decline
 * just this one file", and it is deliberately the same shape as the link support already
 * here: the list reports which key was clicked, and the screen owns what that means and how
 * the state is drawn.
 */
public class LineList {
	private static final int LINE_HEIGHT = 12;

	/**
	 * @param url       when set, the line is clickable and drawn as a link
	 * @param toggleKey when set, the line is clickable and reports this key
	 */
	public record Line(Component text, int color, String url, String toggleKey) {
		public static final int WHITE = 0xFFFFFFFF;
		public static final int GREY = 0xFFA0A0A0;
		public static final int RED = 0xFFFF5555;
		public static final int YELLOW = 0xFFFFD966;
		public static final int GREEN = 0xFF55FF55;
		public static final int LINK = 0xFF6AB7FF;
		public static final int LINK_HOVER = 0xFFB0DDFF;

		public static Line of(Component text, int color) {
			return new Line(text, color, null, null);
		}

		public static Line link(Component text, String url) {
			return new Line(text, LINK, url, null);
		}

		public static Line toggle(Component text, int color, String key) {
			return new Line(text, color, null, key);
		}

		public boolean isLink() {
			return url != null && !url.isBlank();
		}

		public boolean isToggle() {
			return toggleKey != null && !toggleKey.isBlank();
		}
	}

	private final List<Line> lines = new ArrayList<>();

	private static final int SCROLLBAR_WIDTH = 3;

	/**
	 * How far past the drawn bar still counts as grabbing it.
	 *
	 * <p>Three pixels is a fine thing to look at and a poor thing to hit, especially at GUI
	 * scale 1. Drawing thin and grabbing wide is what every toolkit does; the alternative is a
	 * control that looks draggable and misses most of the time.
	 *
	 * <p>Only outwards. Widening leftwards would take the last few pixels of every row, and a
	 * click at the right-hand end of a file name would scroll the list instead of ticking the
	 * box that was plainly under the cursor. The margin on that side is the one-pixel gap the
	 * bar already leaves.
	 */
	private static final int SCROLLBAR_GRAB_MARGIN = 8;

	private static final int MIN_THUMB_HEIGHT = 16;

	private int x;
	private int y;
	private int width;
	private int height;
	private double scroll;

	/** Set while the player is holding the scrollbar thumb. */
	private boolean dragging;

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

	public void render(GuiGraphics context, Font font, int mouseX, int mouseY) {
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

			int color = isHovered && (line.isLink() || line.isToggle())
					? (line.isLink() ? Line.LINK_HOVER : Line.WHITE)
					: line.color();
			context.drawString(font, line.text(), x, lineY, color);

			if (line.isLink()) {
				int underlineY = lineY + 9;
				int textWidth = font.width(line.text());
				context.fill(x, underlineY, x + textWidth, underlineY + 1, color);
			}
		}

		context.disableScissor();

		if (maxScroll() > 0) {
			renderScrollbar(context);
		}
	}

	private void renderScrollbar(GuiGraphics context) {
		int trackX = scrollbarX();
		int thumbHeight = thumbHeight();
		int thumbY = y + (int) ((height - thumbHeight) * (scroll / maxScroll()));

		context.fill(trackX, y, trackX + SCROLLBAR_WIDTH, y + height, 0x40FFFFFF);
		context.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
				dragging ? 0xFFFFFFFF : 0xC0FFFFFF);
	}

	private int scrollbarX() {
		return x + width + 1;
	}

	private int thumbHeight() {
		return Math.max(MIN_THUMB_HEIGHT,
				(int) ((float) height * height / (lines.size() * LINE_HEIGHT)));
	}

	/**
	 * Grabs the scrollbar, if that is what was clicked.
	 *
	 * <p>A scrollbar that is drawn and cannot be dragged is worse than none: it says the list
	 * works one way and then only works another. Clicking the track jumps there, which is what
	 * every other scrollbar in the game does.
	 *
	 * @return whether the click was the scrollbar's
	 */
	public boolean mouseClicked(double mouseX, double mouseY) {
		if (maxScroll() <= 0) {
			return false;
		}

		if (mouseX < x + width || mouseX > scrollbarX() + SCROLLBAR_WIDTH + SCROLLBAR_GRAB_MARGIN
				|| mouseY < y || mouseY > y + height) {
			return false;
		}

		dragging = true;
		scrollTo(mouseY);
		return true;
	}

	public boolean mouseDragged(double mouseY) {
		if (!dragging) {
			return false;
		}

		scrollTo(mouseY);
		return true;
	}

	public void mouseReleased() {
		dragging = false;
	}

	/** Puts the middle of the thumb where the cursor is, clamped to the track. */
	private void scrollTo(double mouseY) {
		int travel = height - thumbHeight();

		if (travel <= 0) {
			return;
		}

		double offset = mouseY - y - thumbHeight() / 2.0;
		scroll = Math.max(0, Math.min(maxScroll(), maxScroll() * (offset / travel)));
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

	/** The toggle key of the line under the cursor, or null. */
	public String toggleAt(double mouseX, double mouseY) {
		int index = indexAt(mouseX, mouseY);

		if (index < 0) {
			return null;
		}

		Line line = lines.get(index);
		return line.isToggle() ? line.toggleKey() : null;
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
