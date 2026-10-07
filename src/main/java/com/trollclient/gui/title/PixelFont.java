package com.trollclient.gui.title;

import java.util.HashMap;
import java.util.Map;

/** 5x7 glyphs (same drawings as the terminal font) for building chunky pixel logos. */
public final class PixelFont {
	private static final Map<Character, String[]> GLYPHS = new HashMap<>();

	static {
		put('A', ".###.|#...#|#...#|#####|#...#|#...#|#...#");
		put('B', "####.|#...#|#...#|####.|#...#|#...#|####.");
		put('C', ".###.|#...#|#....|#....|#....|#...#|.###.");
		put('D', "####.|#...#|#...#|#...#|#...#|#...#|####.");
		put('E', "#####|#....|#....|####.|#....|#....|#####");
		put('F', "#####|#....|#....|####.|#....|#....|#....");
		put('G', ".###.|#...#|#....|#.###|#...#|#...#|.####");
		put('H', "#...#|#...#|#...#|#####|#...#|#...#|#...#");
		put('I', "###|.#.|.#.|.#.|.#.|.#.|###");
		put('J', "..###|...#.|...#.|...#.|#..#.|#..#.|.##..");
		put('K', "#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#");
		put('L', "#....|#....|#....|#....|#....|#....|#####");
		put('M', "#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#");
		put('N', "#...#|#...#|##..#|#.#.#|#..##|#...#|#...#");
		put('O', ".###.|#...#|#...#|#...#|#...#|#...#|.###.");
		put('P', "####.|#...#|#...#|####.|#....|#....|#....");
		put('Q', ".###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#");
		put('R', "####.|#...#|#...#|####.|#.#..|#..#.|#...#");
		put('S', ".####|#....|#....|.###.|....#|....#|####.");
		put('T', "#####|..#..|..#..|..#..|..#..|..#..|..#..");
		put('U', "#...#|#...#|#...#|#...#|#...#|#...#|.###.");
		put('V', "#...#|#...#|#...#|#...#|#...#|.#.#.|..#..");
		put('W', "#...#|#...#|#...#|#.#.#|#.#.#|#.#.#|.#.#.");
		put('X', "#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#");
		put('Y', "#...#|#...#|.#.#.|..#..|..#..|..#..|..#..");
		put('Z', "#####|....#|...#.|..#..|.#...|#....|#####");
		put(' ', "..|..|..|..|..|..|..");
	}

	private static void put(char c, String rows) {
		GLYPHS.put(c, rows.split("\\|"));
	}

	private PixelFont() {
	}

	/** A lit pixel of the rendered text, in glyph-pixel units. */
	public record Pixel(int x, int y) {
	}

	public static java.util.List<Pixel> layout(String text) {
		java.util.List<Pixel> out = new java.util.ArrayList<>();
		int cx = 0;
		for (char ch : text.toUpperCase().toCharArray()) {
			String[] rows = GLYPHS.getOrDefault(ch, GLYPHS.get(' '));
			for (int y = 0; y < rows.length; y++) {
				for (int x = 0; x < rows[y].length(); x++) {
					if (rows[y].charAt(x) == '#') {
						out.add(new Pixel(cx + x, y));
					}
				}
			}
			cx += rows[0].length() + 1;
		}
		return out;
	}

	public static int width(String text) {
		int w = 0;
		for (char ch : text.toUpperCase().toCharArray()) {
			w += GLYPHS.getOrDefault(ch, GLYPHS.get(' '))[0].length() + 1;
		}
		return Math.max(0, w - 1);
	}
}
