package com.mckcode.riffs.user;

import java.util.List;
import java.util.Optional;

/**
 * The built-in bot players. There is always a bot free for a game: Quick match uses one when no person is online,
 * and a bot can play any number of games at once.
 *
 * <p>The level sets how a bot plays:
 * <ul>
 * <li><b>wildness</b>: how often a guessed note is a random one that still fits the feedback, instead of the
 * likeliest one (higher = more mistakes = easier to beat)
 * <li><b>choices</b>: how many of the likeliest notes it picks from (1 = always the likeliest)
 * <li><b>memory</b>: how many of its latest guesses' feedback it remembers (0 = all of them)
 * <li><b>minNotes/maxNotes</b>: how long the riffs it sends are (shorter = easier to guess)
 * </ul>
 * Ids never change (games refer to them); names and blurbs can.
 */
public final class Bots {

	public record Level(String name, double wildness, int choices, int memory, int minNotes, int maxNotes) {
	}

	public static final Level EASY = new Level("easy", 0.6, 5, 1, 4, 5);
	public static final Level MEDIUM = new Level("medium", 0.35, 3, 2, 5, 7);
	public static final Level HARD = new Level("hard", 0.08, 1, 0, 6, 8);

	public record Bot(String id, String name, Level level, String about) {
	}

	public static final List<Bot> ALL = List.of(
			// easy: short riffs, sloppy guesses
			new Bot("bot:hum_bug", "Hum Bug", EASY, "Hums along. Gets the tune about half the time."),
			new Bot("bot:lil_staccato", "Lil' Staccato", EASY, "Short notes, short attention span."),
			new Bot("bot:b_flat", "B. Flat", EASY, "Always a little bit off."),
			new Bot("bot:rest_assured", "Rest Assured", EASY, "Very relaxed. Maybe too relaxed."),
			new Bot("bot:polly_rhythm", "Polly Rhythm", EASY, "Loves a beat, forgets the notes."),
			// medium
			new Bot("bot:robo_riff", "Robo Riff", MEDIUM, "Beep boop. Plays it by the book."),
			new Bot("bot:tin_pan", "Tin Pan", MEDIUM, "Knows a thousand old songs."),
			new Bot("bot:treble_maker", "Treble Maker", MEDIUM, "Up to no good, up in the high notes."),
			new Bot("bot:clef_hanger", "Clef Hanger", MEDIUM, "Leaves you waiting for the last note."),
			new Bot("bot:fret_knot", "Fret Knot", MEDIUM, "Guitar bot. Strings get tangled sometimes."),
			new Bot("bot:chord_blimey", "Chord Blimey", MEDIUM, "Cheerful, British, surprisingly good."),
			// hard: long riffs, sharp ears
			new Bot("bot:metronome", "Metronome Mo", HARD, "Never misses a beat. Rarely misses a note."),
			new Bot("bot:captain_crescendo", "Captain Crescendo", HARD, "Starts quiet. Finishes loud."),
			new Bot("bot:major_scales", "Major Scales", HARD, "Practised every scale, every day."),
			new Bot("bot:sir_tunes_a_lot", "Sir Tunes-a-Lot", HARD, "Has perfect pitch and won't stop mentioning it."));

	private Bots() {
	}

	public static Optional<Bot> find(String id) {
		return ALL.stream().filter(b -> b.id().equals(id)).findFirst();
	}

	public static boolean isBot(String id) {
		return id != null && id.startsWith("bot:");
	}
}
