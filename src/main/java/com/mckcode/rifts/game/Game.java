package com.mckcode.rifts.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mckcode.rifts.music.Note;

/** One game between two players, as stored in games.json. Plain fields so Jackson can read and write it. */
public class Game {

	public static final String ACTIVE = "active", FINISHED = "finished";

	public String id;
	public long createdAt;
	public long updatedAt;
	public List<String> players = new ArrayList<>();
	public String status = ACTIVE;
	public String winner;
	/** Who resigned, if the game ended that way. */
	public String resigned;
	public Map<String, PlayerState> state = new LinkedHashMap<>();
	public List<Round> rounds = new ArrayList<>();

	public String other(String userId) {
		return players.get(0).equals(userId) ? players.get(1) : players.get(0);
	}

	public Round current() {
		return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
	}

	public static class PlayerState {
		public int encores = GameRules.START_ENCORES;
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
		public List<Note> rift;
		/** How many sounding notes (the length of every guess). */
		public int length;
		/** Library tune it came from, if the composer picked one. */
		public String tuneId;
		public int score;
		public String tier;
		public int strings = GameRules.STRINGS;
		/** Per note: the pitch the listener knows (the first note, exact guesses and hints), else null. */
		public List<Integer> revealed = new ArrayList<>();
		public List<Guess> guesses = new ArrayList<>();
		public int hintsUsed;
		public long startedAt;
		public long endedAt;

		public int wrong() {
			return (int) guesses.stream().filter(g -> !g.correct).count();
		}

		public int stringsLeft() {
			return strings - wrong();
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
		public boolean correct;
		public long at;
	}
}
