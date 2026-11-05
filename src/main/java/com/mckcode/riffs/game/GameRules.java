package com.mckcode.riffs.game;

/** The tunable numbers of the game (research/rewards_and_rules.md explains them). */
public final class GameRules {

	/**
	 * Each player's guitar for the whole game (a 12-string guitar): every wrong guess snaps a string, strings don't
	 * come back between riffs, and the player whose last string snaps loses. About as long as the old 3 lives × 6.
	 */
	public static final int STRINGS = 12;
	/** Points for solving a riff: POINTS_GUESSED + POINTS_PER_STRING_LEFT for each of the first FAST_GUESSES not needed. */
	public static final int FAST_GUESSES = 6;
	/** A guessed note within this many semitones is "close". */
	public static final int CLOSE = 2;

	public static final int POINTS_OK = 10, POINTS_SILVER = 25, POINTS_GOLD = 50;
	public static final int POINTS_GUESSED = 20, POINTS_PER_STRING_LEFT = 10;
	/** Short riffs are easy to guess, so tokens need a longer riff: a Hint 5+ notes, an Extra String 6+. */
	public static final int NOTES_FOR_HINT = 5, NOTES_FOR_EXTRA_STRING = 6;
	/** Learner mode: a free note after every this-many wrong guesses in a round. */
	public static final int LEARNER_FREE_NOTE_EVERY = 2;

	private GameRules() {
	}
}
