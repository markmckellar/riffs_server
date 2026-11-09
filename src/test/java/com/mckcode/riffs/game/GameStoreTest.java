package com.mckcode.riffs.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mckcode.riffs.events.EventService;
import com.mckcode.riffs.store.JsonFiles;
import com.mckcode.riffs.user.UserService;

class GameStoreTest {

	static final long DAY = 24 * 3600 * 1000L;

	Path dir;
	UserService users;
	final String a = "dev:ann", b = "dev:bob";

	@BeforeEach
	void setUp() throws Exception {
		dir = Files.createTempDirectory("riffs-store");
		users = new UserService(dir.toString());
		users.signedIn(a, "Ann", null);
		users.signedIn(b, "Bob", null);
	}

	GameService service() {
		return new GameService(dir.toString(), users, new EventService());
	}

	Game game(String id, long createdAt, String status) {
		Game g = new Game();
		g.id = id;
		g.createdAt = g.updatedAt = createdAt;
		g.status = status;
		g.players.addAll(List.of(a, b));
		g.state.put(a, new Game.PlayerState());
		g.state.put(b, new Game.PlayerState());
		Game.Round r = new Game.Round();
		r.number = 1;
		r.composer = a;
		r.listener = b;
		g.rounds.add(r);
		return g;
	}

	@Test
	void eachGameIsItsOwnFileInItsDaysFolder() throws Exception {
		Game g = service().start(a, b);
		String today = LocalDate.now(ZoneOffset.UTC).toString();
		assertTrue(g.id.startsWith(today.replace("-", "") + "-"), g.id);
		assertTrue(Files.exists(dir.resolve("games").resolve(today).resolve(g.id + ".json")));
		assertTrue(Files.exists(dir.resolve("games/open").resolve(g.id)));
		assertEquals(List.of(g.id), Files.readAllLines(dir.resolve("games/players/dev_ann.txt")));

		// a restart reads it back; once it's over, it's no longer marked open
		GameService again = service();
		Game back = again.get(g.id);
		assertEquals(Game.INVITED, back.status);
		assertEquals(List.of(back), again.gamesOf(b));
		again.accept(b, g.id);
		again.resign(a, g.id);
		assertFalse(Files.exists(dir.resolve("games/open").resolve(g.id)));
		assertEquals(Game.FINISHED, service().get(g.id).status);
	}

	@Test
	void gamesJsonIsMovedIntoDayFolders() throws Exception {
		long old = System.currentTimeMillis() - 100 * DAY;
		Game finished = game("aaaa1111", old, Game.FINISHED);
		Game going = game("bbbb2222", old, Game.ACTIVE);
		JsonFiles.write(dir.resolve("games.json"), Map.of(finished.id, finished, going.id, going));

		GameService s = service();
		assertFalse(Files.exists(dir.resolve("games.json")));
		assertTrue(Files.exists(dir.resolve("games.json.migrated")));
		// old ids keep working (links on phones), even for a finished game too old to be kept in memory
		assertNotNull(s.get("aaaa1111"));
		assertEquals(2, s.gamesOf(a).size());
		// the game still going is open after a restart, so it's in memory however old it is
		assertEquals(going.id, s.openGameBetween(a, b));
		assertEquals(going.id, service().openGameBetween(a, b));
	}

	@Test
	void oldFinishedGamesAreReadFromDiskWhenAskedFor() {
		GameStore store = new GameStore(dir, 30);
		long old = System.currentTimeMillis() - 100 * DAY;
		String id = GameStore.newId(old);
		store.add(game(id, old, Game.FINISHED));

		GameStore fresh = new GameStore(dir, 30);
		assertTrue(fresh.openGames().isEmpty());
		assertEquals(id, fresh.get(id).id);
		assertEquals(1, fresh.gamesOf(b).size());
		assertNull(fresh.get("20200101-deadbeef"));
		assertNull(fresh.get("../../etc/passwd"));
	}
}
