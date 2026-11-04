package com.mckcode.riffs.game;

/** The tunable numbers of the game (research/rewards_and_rules.md explains them). */
public final class GameRules {

	/** Lives, shown as "Encores". */
	public static final int START_ENCORES = 3;
	public static final int MAX_ENCORES = 5;
	/** Wrong guesses allowed per riff, drawn as guitar strings (hangman's 6). */
	public static final int STRINGS = 6;
	/** A guessed note within this many semitones is "close". */
	public static final int CLOSE = 2;

	public static final int POINTS_OK = 10, POINTS_SILVER = 25, POINTS_GOLD = 50;
	public static final int POINTS_GUESSED = 20, POINTS_PER_STRING_LEFT = 10;
	/** Short riffs are easy to guess, so tokens need a longer riff: a Hint 5+ notes, an Extra String 6+. */
	public static final int NOTES_FOR_HINT = 5, NOTES_FOR_EXTRA_STRING = 6;
	/** Every this-many Gold riffs in a game earns an Encore. */
	public static final int GOLDS_PER_ENCORE = 3;

	private GameRules() {
	}
}
