package com.mckcode.riffs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

	/** the lengths of both test riffs: six quarters and a half */
	static final List<Double> BEATS = List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 2.0);

	GameService games;
	EventService events;
	UserService users;
	final String a = "dev:ann", b = "dev:bob";

	@BeforeEach
	void setUp() throws Exception {
		String dir = Files.createTempDirectory("riffs").toString();
		users = new UserService(dir);
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

		games.guess(b, g.id, List.of(60, 60, 67, 66, 81, 69, 67), BEATS);
		Game.Guess first = r.guesses.get(0);
		assertEquals(List.of("exact", "exact", "exact", "close", "octave", "exact", "exact"), first.marks);
		assertEquals("up", first.hints.get(3));
		assertEquals(GameRules.STRINGS - 1, g.state.get(b).strings);    // a wrong guess snaps one of Bob's strings

		games.guess(b, g.id, List.of(60, 60, 67, 67, 69, 69, 67), BEATS);
		assertEquals(Game.Round.SOLVED, r.phase);
		assertEquals(2, g.rounds.size());
		assertEquals(b, g.current().composer);    // roles swap
	}

	static final List<Integer> WRONG = List.of(64, 72, 72, 72, 72, 72, 72), RIGHT = List.of(64, 62, 60, 62, 64, 64, 64);
	static final String RIFF = "E4:1 D4:1 C4:1 D4:1 E4:1 E4:1 E4:2";

	@Test
	void stringsLastTheWholeGame() {
		users.setLearner(b, false);
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes(RIFF), null);
		games.guess(b, g.id, WRONG, BEATS);
		games.guess(b, g.id, WRONG, BEATS);
		games.guess(b, g.id, RIGHT, BEATS);    // round 1: Bob snaps 2 strings, then gets it
		games.submitRiff(b, g.id, MusicSenseTest.notes(RIFF), null);
		games.guess(a, g.id, RIGHT, BEATS);    // round 2: Ann gets it first time
		assertEquals(3, g.current().number);
		assertEquals(GameRules.STRINGS - 2, g.state.get(b).strings);    // Bob's strings didn't come back
		assertEquals(GameRules.STRINGS, g.state.get(a).strings);
	}

	@Test
	void theLastStringSnappingEndsTheGame() {
		users.setLearner(b, false);
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes(RIFF), null);
		for (int i = 0; i < GameRules.STRINGS; i++)
			games.guess(b, g.id, WRONG, BEATS);
		assertEquals(Game.FINISHED, g.status);
		assertEquals(a, g.winner);
		assertEquals(0, g.state.get(b).strings);
		assertEquals(Game.Round.FAILED, g.rounds.get(0).phase);
	}

	@Test
	void anExtraStringPutsOneBack() {
		users.setLearner(b, false);
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes(RIFF), null);
		games.guess(b, g.id, WRONG, BEATS);
		g.state.get(b).extraStrings = 1;
		games.extraString(b, g.id);
		assertEquals(GameRules.STRINGS, g.state.get(b).strings);
		g.state.get(b).extraStrings = 1;
		assertThrows(ResponseStatusException.class, () -> games.extraString(b, g.id));    // already full
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

	@Test
	void learnersGetAFreeNoteEveryTwoMissesAndHalfPoints() {
		Game g = games.start(a, b);    // new players start in learner mode
		games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2"), null);
		Game.Round r = g.current();
		List<Integer> wrong = List.of(60, 72, 72, 72, 72, 72, 72);
		games.guess(b, g.id, wrong, BEATS);
		assertEquals(0, r.freeNotes);
		games.guess(b, g.id, wrong, BEATS);
		assertEquals(1, r.freeNotes);
		assertEquals(60, r.revealed.get(1));    // the next unknown note, given free
		games.guess(b, g.id, List.of(60, 60, 67, 67, 69, 69, 67), BEATS);
		assertEquals(Game.Round.SOLVED, r.phase);
		assertEquals((GameRules.POINTS_GUESSED + GameRules.POINTS_PER_STRING_LEFT * 4) / 2, g.state.get(b).points);
	}

	@Test
	void noFreeNotesWithLearnerModeOff() {
		users.setLearner(b, false);
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2"), null);
		for (int i = 0; i < 4; i++)
			games.guess(b, g.id, List.of(60, 72, 72, 72, 72, 72, 72), BEATS);
		assertEquals(0, g.current().freeNotes);
		assertFalse(g.current().learner);
	}

	@Test
	void notesAreOneBeatAndOnlyPitchesCount() {
		users.setLearner(b, false);
		Game g = games.start(a, b);
		games.submitRiff(a, g.id, MusicSenseTest.notes(RIFF), null);    // RIFF ends with a half note
		Game.Round r = g.current();
		assertTrue(r.riff.stream().allMatch(n -> n.b() == 1.0));        // evened out: every note one beat
		games.guess(b, g.id, RIGHT, List.of(4.0, 4.0, 4.0, 4.0, 4.0, 4.0, 4.0));    // lengths sent are ignored
		assertEquals(Game.Round.SOLVED, r.phase);
		Game g2 = games.start(b, "bot:robo_riff");
		games.submitRiff(b, g2.id, MusicSenseTest.notes(RIFF), null);
		assertDoesNotThrow(() -> games.guess("bot:robo_riff", g2.id, WRONG, null));    // no lengths needed
	}
}
