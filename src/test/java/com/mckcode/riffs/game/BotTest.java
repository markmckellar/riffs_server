package com.mckcode.riffs.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mckcode.riffs.events.EventService;
import com.mckcode.riffs.library.TuneLibrary;
import com.mckcode.riffs.user.Bots;
import com.mckcode.riffs.user.UserService;

class BotTest {

	@Test
	void quickMatchAlwaysFindsABotEvenWithNobodyElseAround() throws Exception {
		String dir = Files.createTempDirectory("riffs").toString();
		UserService users = new UserService(dir);
		users.signedIn("dev:solo", "Solo", null);
		GameService games = new GameService(dir, users, new EventService());
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < Bots.ALL.size() + 5; i++) {    // more games than there are bots
			Game g = games.start("dev:solo", null);
			assertTrue(Bots.isBot(g.other("dev:solo")));
			ids.add(g.id);
		}
		assertEquals(Bots.ALL.size() + 5, ids.size(), "every Quick match is a new game");
	}

	@Test
	void easyBotsLoseMoreThanHardOnes() throws Exception {
		TuneLibrary lib = new TuneLibrary();
		double easy = solveRate(lib, Bots.EASY), medium = solveRate(lib, Bots.MEDIUM), hard = solveRate(lib, Bots.HARD);
		System.out.printf("bot solve rates (library riffs, 6 strings): easy %.0f%%, medium %.0f%%, hard %.0f%%%n",
				easy * 100, medium * 100, hard * 100);
		assertTrue(easy < medium && medium < hard, easy + " " + medium + " " + hard);
	}

	/** Share of riffs a bot of this level guesses within 6 strings, over library riffs of its own lengths. */
	private static double solveRate(TuneLibrary lib, Bots.Level level) throws Exception {
		String dir = Files.createTempDirectory("riffs").toString();
		UserService users = new UserService(dir);
		users.signedIn("dev:c", "Composer", null);
		GameService games = new GameService(dir, users, new EventService());
		Random random = new Random(42);
		String bot = Bots.ALL.stream().filter(b -> b.level() == level).findFirst().get().id();
		int solved = 0, rounds = 400, wrong = 0;
		for (int i = 0; i < rounds; i++) {
			Game g = games.start("dev:c", bot);
			int len = level.minNotes() + random.nextInt(level.maxNotes() - level.minNotes() + 1);
			games.submitRiff("dev:c", g.id, lib.randomFragment(random, len).notes(), null);
			Game.Round r = g.current();
			while (Game.Round.GUESSING.equals(r.phase))
				games.guess(bot, g.id, BotPlayer.guess(r, random, level));
			if (Game.Round.SOLVED.equals(r.phase) && r.wrong() < 6) solved++;    // within 6 guesses
			wrong += r.wrong();
			if (Game.ACTIVE.equals(g.status)) games.resign("dev:c", g.id);
		}
		System.out.printf("  %s: %.1f strings snapped per riff on average%n", level.name(), wrong / (double) rounds);
		return solved / (double) rounds;
	}
}
