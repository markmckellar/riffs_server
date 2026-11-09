package com.mckcode.riffs.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mckcode.riffs.music.Note;

/** One game between two players, as stored in its own file (see GameStore). Plain fields so Jackson can read and write it. */
public class Game {

	/**
	 * invited: a challenge to a person, waiting for them to accept (the challenger may already write the first riff);
	 * declined: they said no; cancelled: the challenger withdrew it (hidden). Bot games start active.
	 */
	public static final String INVITED = "invited", ACTIVE = "active", FINISHED = "finished", DECLINED = "declined", CANCELLED = "cancelled";

	public String id;
	public long createdAt;
	public long updatedAt;
	public List<String> players = new ArrayList<>();
	public String status = ACTIVE;
	public String winner;
	/** Who resigned, if the game ended that way. */
	public String resigned;
	/** While invited: who was challenged, when, and whether by Quick match (then a bot takes over if they don't answer). */
	public String invited;
	public long invitedAt;
	public boolean quick;
	public Map<String, PlayerState> state = new LinkedHashMap<>();
	public List<Round> rounds = new ArrayList<>();

	/** Going on, or a challenge waiting for an answer. */
	public boolean open() {
		return ACTIVE.equals(status) || INVITED.equals(status);
	}

	public String other(String userId) {
		return players.get(0).equals(userId) ? players.get(1) : players.get(0);
	}

	public Round current() {
		return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
	}

	public static class PlayerState {
		/** Strings left on this player's guitar (for the whole game). Starts full, also for games saved before this. */
		public int strings = GameRules.STRINGS;
		public int points;
		public int hints;
		public int extraStrings;
		public int golds;
	}

	public static class Round {
		public static final String COMPOSING = "composing", GUESSING = "guessing", SOLVED = "solved", FAILED = "failed";

		public int number;
		public String composer;
		public String listener;
		public String phase = COMPOSING;
		public List<Note> riff;
		/** How many sounding notes (the length of every guess). */
		public int length;
		/** Library tune it came from, if the composer picked one. */
		public String tuneId;
		public int score;
		public String tier;
		/** Per note: the pitch the listener knows (the first note, exact guesses and hints), else null. */
		public List<Integer> revealed = new ArrayList<>();
		/** Per note: the length (beats) the listener knows, else null. Guesses must match lengths too. */
		public List<Double> revealedBeats = new ArrayList<>();
		public List<Guess> guesses = new ArrayList<>();
		public int hintsUsed;
		/** Notes revealed free by learner mode, and whether the listener had learner mode on while guessing. */
		public int freeNotes;
		public boolean learner;
		public long startedAt;
		public long endedAt;

		public int wrong() {
			return (int) guesses.stream().filter(g -> !g.correct).count();
		}

		/** Guesses saved against FAST_GUESSES, for the solving bonus. */
		public int fastBonus() {
			return Math.max(0, GameRules.FAST_GUESSES - wrong());
		}

		public boolean over() {
			return SOLVED.equals(phase) || FAILED.equals(phase);
		}
	}

	public static class Guess {
		public List<Integer> notes;
		/** Per note: exact, octave, close, miss. */
		public List<String> marks;
		/** Per note: "up" or "down" when close (which way the real note is), else null. */
		public List<String> hints;
		/** The lengths guessed (beats), and per note "ok" or "wrong". */
		public List<Double> beats;
		public List<String> lengths;
		public boolean correct;
		public long at;
	}
}
