package com.mckcode.riffs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.mckcode.riffs.events.EventService;
import com.mckcode.riffs.game.Game;
import com.mckcode.riffs.game.GameRules;
import com.mckcode.riffs.game.GameService;
import com.mckcode.riffs.music.RiffRules;
import com.mckcode.riffs.user.UserService;

class GameServiceTest {

	GameService games;
	EventService events;
	final String a = "dev:ann", b = "dev:bob";

	@BeforeEach
	void setUp() throws Exception {
		String dir = Files.createTempDirectory("riffs").toString();
		UserService users = new UserService(dir);
		users.signedIn(a, "Ann", null);
		users.signedIn(b, "Bob", null);
		events = new EventService();
		games = new GameService(dir, users, events);
	}

	@Test
	void aRoundFromRiffToGuess() {
		Game g = games.start(a, b);
		assertEquals(a, g.current().composer);
		assertThrows(ResponseStatusException.class, () -> games.submitRiff(b, g.id, MusicSenseTest.notes("C4:1 D4:1 E4:1 C4:1"), null));
		assertThrows(ResponseStatusException.class, () -> games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 D4:1 E4:1"), null));

		games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2"), null);
		Game.Round r = g.current();
		assertEquals(7, r.length);
		assertEquals(60, r.revealed.get(0));    // the first note is given
		assertNull(r.revealed.get(1));
		assertTrue(events.after(b, 0).stream().anyMatch(e -> e.type().equals("riff_ready")));

		// the listener can't see the score
		@SuppressWarnings("unchecked")
		Map<String, Object> listenerRound = ((List<Map<String, Object>>) games.view(g, b).get("rounds")).get(0);
		assertFalse(listenerRound.containsKey("score"));

		games.guess(b, g.id, List.of(60, 60, 67, 66, 81, 69, 67));
		Game.Guess first = r.guesses.get(0);
		assertEquals(List.of("exact", "exact", "exact", "close", "octave", "exact", "exact"), first.marks);
		assertEquals("up", first.hints.get(3));
		assertEquals(GameRules.STRINGS - 1, r.stringsLeft());

		games.guess(b, g.id, List.of(60, 60, 67, 67, 69, 69, 67));
		assertEquals(Game.Round.SOLVED, r.phase);
		assertEquals(2, g.rounds.size());
		assertEquals(b, g.current().composer);    // roles swap
	}

	@Test
	void runningOutOfStringsCostsAnEncoreAndThreeEndTheGame() {
		Game g = games.start(a, b);
		for (int round = 0; round < 6 && Game.ACTIVE.equals(g.status); round++) {
			Game.Round r = g.current();
			games.submitRiff(r.composer, g.id, MusicSenseTest.notes("E4:1 D4:1 C4:1 D4:1 E4:1 E4:1 E4:2"), null);
			if (r.listener.equals(b)) // Bob always fails, Ann always gets it first time
				for (int i = 0; i < GameRules.STRINGS; i++)
					games.guess(b, g.id, List.of(64, 72, 72, 72, 72, 72, 72));
			else games.guess(a, g.id, List.of(64, 62, 60, 62, 64, 64, 64));
		}
		assertEquals(Game.FINISHED, g.status);
		assertEquals(a, g.winner);
		assertEquals(0, g.state.get(b).encores);
	}

	@Test
	void goldRiffsEarnTokensButLibraryRiffsDont() {
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2"), "twinkle");
		assertEquals(0, g.state.get(a).hints + g.state.get(a).extraStrings);
		assertTrue(RiffRules.MAX_NOTES >= 7);
	}

	@Test
	void shortGoldRiffsEarnNoExtraString() {
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 D4:1 E4:1 C4:1"), null);
		assertEquals(0, g.state.get(a).extraStrings);
		Game g2 = games.start(b, "bot:robo_riff");
		games.submitRiff(b, g2.id, MusicSenseTest.notes("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2"), null);
		assertEquals(1, g2.state.get(b).extraStrings);
	}
}
