package com.mckcode.rifts.game;

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

import com.mckcode.rifts.library.TuneLibrary;
import com.mckcode.rifts.music.RiftRules;

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

	public BotPlayer(GameService games, TuneLibrary library, @Value("${rifts.bot.min-delay:3}") int minDelay,
			@Value("${rifts.bot.max-delay:8}") int maxDelay) {
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
			int len = RiftRules.MIN_NOTES + random.nextInt(RiftRules.MAX_NOTES - RiftRules.MIN_NOTES + 1);
			TuneLibrary.Fragment f = library.randomFragment(random, len);
			games.submitRift(r.composer, g.id, f.notes(), f.tuneId());
		}
		else {
			games.guess(r.listener, g.id, guess(r, random));
		}
	}

	/** A guess that fits every mark so far. */
	static List<Integer> guess(Game.Round r, Random random) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < r.length; i++) {
			if (r.revealed.get(i) != null) {
				out.add(r.revealed.get(i));
				continue;
			}
			List<Integer> cands = new ArrayList<>();
			for (int p = RiftRules.LOWEST; p <= RiftRules.HIGHEST; p++)
				if (fits(p, i, r.guesses)) cands.add(p);
			int prev = out.isEmpty() ? r.revealed.get(0) : out.get(out.size() - 1);
			if (cands.isEmpty()) {
				out.add(prev);
				continue;
			}
			if (random.nextInt(4) == 0) {    // now and then a wild guess, so bots can lose
				out.add(cands.get(random.nextInt(cands.size())));
				continue;
			}
			cands.sort((a, b) -> Integer.compare(Math.abs(a - prev), Math.abs(b - prev)));
			int pick = Math.min(cands.size() - 1, random.nextInt(Math.min(3, cands.size())));
			out.add(cands.get(pick));
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
