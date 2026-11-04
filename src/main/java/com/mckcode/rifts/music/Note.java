package com.mckcode.rifts.music;

import java.util.Locale;

/**
 * One note or rest. {@code m} is the MIDI pitch (60 = middle C, C4), or null for a rest. {@code b} is the length in
 * beats (1 = a quarter note). Short field names keep the JSON small: {@code {"m":60,"b":1}}.
 */
public record Note(Integer m, double b) {

	private static final String[] NAMES = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

	public boolean isRest() {
		return m == null;
	}

	/** "C4", "F#5", or "R" for a rest. */
	public String name() {
		return m == null ? "R" : pitchName(m);
	}

	public static String pitchName(int midi) {
		return NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
	}

	/** "C4", "Eb4", "F#5" → MIDI number. */
	public static int parsePitch(String s) {
		String t = s.trim();
		int pc = switch (Character.toUpperCase(t.charAt(0))) {
			case 'C' -> 0;
			case 'D' -> 2;
			case 'E' -> 4;
			case 'F' -> 5;
			case 'G' -> 7;
			case 'A' -> 9;
			case 'B' -> 11;
			default -> throw new IllegalArgumentException("not a note: " + s);
		};
		int i = 1;
		while (i < t.length() && (t.charAt(i) == '#' || t.charAt(i) == 'b')) {
			pc += t.charAt(i) == '#' ? 1 : -1;
			i++;
		}
		int octave = Integer.parseInt(t.substring(i));
		return 12 * (octave + 1) + pc;
	}

	/** "E4:1.5" or "R:0.5" → a Note. */
	public static Note parse(String token) {
		String[] p = token.split(":");
		if (p.length != 2) throw new IllegalArgumentException("note must look like E4:1, not " + token);
		double beats = Double.parseDouble(p[1]);
		if (p[0].toUpperCase(Locale.ROOT).equals("R")) return new Note(null, beats);
		return new Note(parsePitch(p[0]), beats);
	}
}
