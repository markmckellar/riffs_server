package com.mckcode.riffs.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mckcode.riffs.library.TuneLibrary;
import com.mckcode.riffs.music.RiffRules;
import com.mckcode.riffs.user.Bots;

/**
 * Plays the bots' turns. A bot composes by picking a phrase from the public-domain library, and guesses by
 * keeping, for each note, the pitches that still fit all the feedback so far, then choosing one close to its
 * neighbour (melodies mostly move by step). It waits a few seconds first, so it feels like a person.
 */
@Component
public class BotPlayer {

	private static final Logger Log = LoggerFactory.getLogger(BotPlayer.class);

	private final GameService games;
	private final TuneLibrary library;
	private final Random random = new Random();
	private final int minDelay, maxDelay;
	/** game id + turn → when the bot may move. */
	private final Map<String, Long> due = new ConcurrentHashMap<>();

	public BotPlayer(GameService games, TuneLibrary library, @Value("${riffs.bot.min-delay:3}") int minDelay,
			@Value("${riffs.bot.max-delay:8}") int maxDelay) {
		this.games = games;
		this.library = library;
		this.minDelay = minDelay;
		this.maxDelay = Math.max(minDelay, maxDelay);
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 2000)
	public void tick() {
		long now = System.currentTimeMillis();
		for (Game g : games.botTurns()) {
			Game.Round r = g.current();
			String key = g.id + "/" + r.number + "/" + r.phase + "/" + r.guesses.size();
			long when = due.computeIfAbsent(key, k -> now + 1000L * (minDelay + random.nextInt(maxDelay - minDelay + 1)));
			if (now < when) continue;
			due.remove(key);
			try {
				move(g);
			}
			catch (Exception e) {
				Log.warn("bot move in game {} failed: {}", g.id, e.getMessage());
			}
		}
	}

	void move(Game g) {
		Game.Round r = g.current();
		if (Game.Round.COMPOSING.equals(r.phase)) {
			Bots.Level lv = level(r.composer);
			int len = lv.minNotes() + random.nextInt(lv.maxNotes() - lv.minNotes() + 1);
			TuneLibrary.Fragment f = library.randomFragment(random, len);
			games.submitRiff(r.composer, g.id, f.notes(), f.tuneId());
		}
		else {
			BotGuess b = guess(r, random, level(r.listener));
			games.guess(r.listener, g.id, b.notes(), b.beats());
		}
	}

	private static Bots.Level level(String botId) {
		return Bots.find(botId).map(Bots.Bot::level).orElse(Bots.MEDIUM);
	}

	/** A bot's guess: a pitch and a length for every note. */
	record BotGuess(List<Integer> notes, List<Double> beats) {
	}

	/** Lengths in the order a bot tries them: the most common in melodies first. */
	private static final double[] LENGTH_ORDER = { 1, 0.5, 2, 1.5, 4, 3 };

	/** A guess that fits every mark so far; how careful it is depends on the bot's level. */
	static BotGuess guess(Game.Round r, Random random, Bots.Level level) {
		List<Integer> out = new ArrayList<>();
		List<Game.Guess> remembered = level.memory() <= 0 || r.guesses.size() <= level.memory() ? r.guesses
				: r.guesses.subList(r.guesses.size() - level.memory(), r.guesses.size());
		for (int i = 0; i < r.length; i++) {
			if (r.revealed.get(i) != null) {
				out.add(r.revealed.get(i));
				continue;
			}
			List<Integer> cands = new ArrayList<>();
			for (int p = RiffRules.LOWEST; p <= RiffRules.HIGHEST; p++)
				if (fits(p, i, remembered)) cands.add(p);
			int prev = out.isEmpty() ? r.revealed.get(0) : out.get(out.size() - 1);
			if (cands.isEmpty()) {
				out.add(prev);
				continue;
			}
			if (random.nextDouble() < level.wildness()) {    // now and then a wild guess, so bots can lose
				out.add(cands.get(random.nextInt(cands.size())));
				continue;
			}
			cands.sort((a, b) -> Integer.compare(Math.abs(a - prev), Math.abs(b - prev)));
			int pick = random.nextInt(Math.min(level.choices(), cands.size()));
			out.add(cands.get(pick));
		}
		return new BotGuess(out, guessLengths(r, random, level, remembered));
	}

	/** For each note: its known length, else one not yet marked wrong (common lengths first, sloppier at lower levels). */
	private static List<Double> guessLengths(Game.Round r, Random random, Bots.Level level, List<Game.Guess> remembered) {
		List<Double> out = new ArrayList<>();
		for (int i = 0; i < r.length; i++) {
			Double known = r.revealedBeats != null && i < r.revealedBeats.size() ? r.revealedBeats.get(i) : null;
			if (known != null) {
				out.add(known);
				continue;
			}
			List<Double> cands = new ArrayList<>();
			for (double l : LENGTH_ORDER) {
				boolean ruledOut = false;
				for (Game.Guess g : remembered)
					if (g.lengths != null && "wrong".equals(g.lengths.get(i)) && Math.abs(g.beats.get(i) - l) < 1e-6) ruledOut = true;
				if (!ruledOut) cands.add(l);
			}
			if (cands.isEmpty()) cands.add(1.0);
			if (random.nextDouble() < level.wildness()) out.add(cands.get(random.nextInt(cands.size())));
			else out.add(cands.get(random.nextInt(Math.min(level.choices(), cands.size()))));
		}
		return out;
	}

	private static boolean fits(int p, int slot, List<Game.Guess> guesses) {
		for (Game.Guess g : guesses) {
			int n = g.notes.get(slot);
			switch (g.marks.get(slot)) {
				case "exact" -> {
					if (p != n) return false;
				}
				case "octave" -> {
					if (p == n || Math.floorMod(p - n, 12) != 0) return false;
				}
				case "close" -> {
					boolean up = "up".equals(g.hints.get(slot));
					if (up ? !(p > n && p - n <= GameRules.CLOSE) : !(p < n && n - p <= GameRules.CLOSE)) return false;
				}
				default -> {
					if (Math.abs(p - n) <= GameRules.CLOSE || Math.floorMod(p - n, 12) == 0) return false;
				}
			}
		}
		return true;
	}
}
