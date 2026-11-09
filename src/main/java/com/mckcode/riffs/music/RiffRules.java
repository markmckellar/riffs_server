package com.mckcode.riffs.music;

import java.util.List;

/** What a riff may contain (see research/music_structure.md for why 4–8 notes). */
public final class RiffRules {

	public static final int MIN_NOTES = 4;
	public static final int MAX_NOTES = 8;
	public static final int MAX_RESTS = 2;
	/**
	 * Lowest and highest pitches a riff may use: E3 to G♯5, a guitar's frets 0–4 in standard tuning, as written (guitar
	 * music is written an octave above how it sounds). Instruments with a smaller range (bass, piano keys sized for a
	 * phone) show a riff moved to fit them (the client's ts/instrument.ts), so a riff must fit the smallest one.
	 */
	public static final int LOWEST = 52;
	public static final int HIGHEST = 80;
	/** Lowest to highest note of one riff at most an octave and a fifth: the bass's frets 0–4 and the piano's C4–G5. */
	public static final int MAX_SPAN = 19;
	/**
	 * Every note (and rest) is one beat: riffs are a plain sequence of notes, no rhythm (Mark: "only use full notes").
	 * Riffs that arrive with other lengths are evened out by {@link #evenly}.
	 */
	public static final double BEAT = 1;
	public static final double[] LENGTHS = { BEAT };

	private RiffRules() {
	}

	/** Why these notes aren't a valid riff, or null if they are. */
	public static String problem(List<Note> notes) {
		if (notes == null || notes.isEmpty()) return "a riff needs notes";
		long sounding = notes.stream().filter(n -> !n.isRest()).count();
		long rests = notes.size() - sounding;
		if (sounding < MIN_NOTES || sounding > MAX_NOTES)
			return "a riff has " + MIN_NOTES + " to " + MAX_NOTES + " notes, not " + sounding;
		if (rests > MAX_RESTS) return "a riff has at most " + MAX_RESTS + " rests";
		int lo = notes.stream().filter(n -> !n.isRest()).mapToInt(Note::m).min().orElse(0);
		int hi = notes.stream().filter(n -> !n.isRest()).mapToInt(Note::m).max().orElse(0);
		if (hi - lo > MAX_SPAN)
			return "from the lowest note to the highest is at most an octave and a fifth (" + Note.pitchName(lo) + " to "
					+ Note.pitchName(hi) + " is too far)";
		for (Note n : notes) {
			if (!n.isRest() && (n.m() < LOWEST || n.m() > HIGHEST))
				return Note.pitchName(n.m()) + " is outside the staff (" + Note.pitchName(LOWEST) + "–" + Note.pitchName(HIGHEST) + ")";
			boolean ok = false;
			for (double l : LENGTHS)
				if (Math.abs(l - n.b()) < 1e-6) ok = true;
			if (!ok) return "note length " + n.b() + " beats isn't allowed";
		}
		return null;
	}

	/** The same notes and rests, each one beat long. */
	public static List<Note> evenly(List<Note> notes) {
		return notes == null ? null : notes.stream().map(n -> new Note(n.m(), BEAT)).toList();
	}

	public static List<Integer> pitches(List<Note> notes) {
		return notes.stream().filter(n -> !n.isRest()).map(Note::m).toList();
	}
}
