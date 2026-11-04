package com.mckcode.rifts.game;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mckcode.rifts.events.EventService;
import com.mckcode.rifts.music.MusicSense;
import com.mckcode.rifts.music.Note;
import com.mckcode.rifts.music.RiftRules;
import com.mckcode.rifts.store.JsonFiles;
import com.mckcode.rifts.user.User;
import com.mckcode.rifts.user.UserService;

/**
 * The game rules in action: starting games (with a person or a bot), sending riffs, guessing, hints, and turning
 * a game into what one player is allowed to see. All changes are saved to games.json at once.
 */
@Service
public class GameService {

	private final Path file;
	private final Map<String, Game> games;
	private final UserService users;
	private final EventService events;
	private final Random random = new Random();

	public GameService(@Value("${rifts.data-dir:local/data}") String dataDir, UserService users, EventService events) {
		this.file = Paths.get(dataDir, "games.json");
		this.games = new LinkedHashMap<>(JsonFiles.read(file, new TypeReference<Map<String, Game>>() {
		}, new LinkedHashMap<>()));
		this.users = users;
		this.events = events;
	}

	// ---- reading

	public synchronized Game get(String id) {
		Game g = games.get(id);
		if (g == null) throw error(HttpStatus.NOT_FOUND, "no such game");
		return g;
	}

	public synchronized List<Game> gamesOf(String userId) {
		return games.values().stream().filter(g -> g.players.contains(userId))
				.sorted(Comparator.comparing((Game g) -> !Game.ACTIVE.equals(g.status)).thenComparing(g -> -g.updatedAt)).toList();
	}

	/** Games where it's a bot's move, for {@link BotPlayer}. */
	public synchronized List<Game> botTurns() {
		List<Game> l = new ArrayList<>();
		for (Game g : games.values()) {
			if (!Game.ACTIVE.equals(g.status)) continue;
			Game.Round r = g.current();
			String mover = Game.Round.COMPOSING.equals(r.phase) ? r.composer : r.listener;
			if (mover.startsWith("bot:")) l.add(g);
		}
		return l;
	}

	// ---- starting

	/**
	 * A new game against {@code opponentId}, or, if that's null, quick match: someone online who isn't already
	 * playing you, otherwise a bot. The starter composes first.
	 */
	public synchronized Game start(String me, String opponentId) {
		String opp = opponentId;
		if (opp == null || opp.isBlank()) {
			List<User> free = users.all().stream()
					.filter(u -> !u.bot && !u.id.equals(me) && users.isOnline(u.id) && activeBetween(me, u.id) == null).toList();
			opp = !free.isEmpty() ? free.get(random.nextInt(free.size())).id
					: UserService.BOTS.get(random.nextInt(UserService.BOTS.size())).id;
		}
		if (opp.equals(me)) throw error(HttpStatus.BAD_REQUEST, "you can't play yourself");
		if (users.get(opp).isEmpty()) throw error(HttpStatus.NOT_FOUND, "no such player");
		Game existing = activeBetween(me, opp);
		if (existing != null) return existing;

		Game g = new Game();
		g.id = UUID.randomUUID().toString().substring(0, 8);
		g.createdAt = g.updatedAt = System.currentTimeMillis();
		g.players.add(me);
		g.players.add(opp);
		g.state.put(me, new Game.PlayerState());
		g.state.put(opp, new Game.PlayerState());
		newRound(g, me);
		games.put(g.id, g);
		save();
		events.publish(opp, "game_started", g.id, name(me) + " started a game with you.");
		return g;
	}

	private Game activeBetween(String a, String b) {
		return games.values().stream().filter(g -> Game.ACTIVE.equals(g.status) && g.players.contains(a) && g.players.contains(b))
				.findFirst().orElse(null);
	}

	private void newRound(Game g, String composer) {
		Game.Round r = new Game.Round();
		r.number = g.rounds.size() + 1;
		r.composer = composer;
		r.listener = g.other(composer);
		r.startedAt = System.currentTimeMillis();
		g.rounds.add(r);
	}

	// ---- composing

	/** The composer sends their riff. {@code tuneId} is set when it was picked from the library. */
	public synchronized Game submitRift(String me, String gameId, List<Note> notes, String tuneId) {
		Game g = active(gameId, me);
		Game.Round r = g.current();
		if (!me.equals(r.composer) || !Game.Round.COMPOSING.equals(r.phase)) throw error(HttpStatus.CONFLICT, "it isn't your turn to compose");
		String problem = RiftRules.problem(notes);
		if (problem != null) throw error(HttpStatus.BAD_REQUEST, problem);

		MusicSense.Evaluation ev = MusicSense.evaluate(notes);
		r.rift = List.copyOf(notes);
		r.length = RiftRules.pitches(notes).size();
		r.tuneId = tuneId == null || tuneId.isBlank() ? null : tuneId;
		r.score = ev.score();
		r.tier = ev.tier();
		r.revealed = new ArrayList<>();
		for (int i = 0; i < r.length; i++)
			r.revealed.add(i == 0 ? RiftRules.pitches(notes).get(0) : null);
		r.phase = Game.Round.GUESSING;
		reward(g, me, r);
		touch(g);
		events.publish(r.listener, "rift_ready", g.id, name(me) + " sent you a riff to guess.");
		return g;
	}

	/** Composer rewards for a riff that makes sense; library riffs get half points and no tokens. */
	private void reward(Game g, String composer, Game.Round r) {
		Game.PlayerState s = g.state.get(composer);
		boolean lib = r.tuneId != null;
		int pts = switch (r.tier) {
			case MusicSense.TIER_GOLD -> GameRules.POINTS_GOLD;
			case MusicSense.TIER_SILVER -> GameRules.POINTS_SILVER;
			case MusicSense.TIER_OK -> GameRules.POINTS_OK;
			default -> 0;
		};
		s.points += lib ? pts / 2 : pts;
		if (lib) return;
		boolean gold = MusicSense.TIER_GOLD.equals(r.tier);
		if (gold && r.length < GameRules.NOTES_FOR_EXTRA_STRING) {    // a short Gold riff earns what a Silver one does
			if (r.length >= GameRules.NOTES_FOR_HINT) s.hints++;
			return;
		}
		if (MusicSense.TIER_SILVER.equals(r.tier) && r.length >= GameRules.NOTES_FOR_HINT) s.hints++;
		if (gold) {
			s.extraStrings++;
			s.golds++;
			if (s.golds % GameRules.GOLDS_PER_ENCORE == 0 && s.encores < GameRules.MAX_ENCORES) s.encores++;
		}
	}

	// ---- guessing

	public synchronized Game guess(String me, String gameId, List<Integer> notes) {
		Game g = active(gameId, me);
		Game.Round r = listening(g, me);
		if (notes == null || notes.size() != r.length) throw error(HttpStatus.BAD_REQUEST, "a guess needs " + r.length + " notes");
		if (notes.stream().anyMatch(n -> n == null)) throw error(HttpStatus.BAD_REQUEST, "fill in every note");

		List<Integer> answer = RiftRules.pitches(r.rift);
		Game.Guess guess = new Game.Guess();
		guess.notes = List.copyOf(notes);
		guess.marks = new ArrayList<>();
		guess.hints = new ArrayList<>();
		guess.at = System.currentTimeMillis();
		boolean all = true;
		for (int i = 0; i < r.length; i++) {
			int a = answer.get(i), n = notes.get(i);
			String mark = n == a ? "exact" : Math.floorMod(n - a, 12) == 0 ? "octave" : Math.abs(n - a) <= GameRules.CLOSE ? "close" : "miss";
			guess.marks.add(mark);
			guess.hints.add("close".equals(mark) ? (a > n ? "up" : "down") : null);
			if (n == a) r.revealed.set(i, a);
			else all = false;
		}
		guess.correct = all;
		r.guesses.add(guess);

		String who = name(me);
		if (all) {
			events.publish(r.composer, "guess_made", g.id, who + " guessed your riff!");
			endRound(g, r, true);
		}
		else if (r.stringsLeft() <= 0) {
			events.publish(r.composer, "guess_made", g.id, who + " ran out of strings. Your riff stumped them!");
			endRound(g, r, false);
		}
		else {
			long exact = guess.marks.stream().filter("exact"::equals).count();
			events.publish(r.composer, "guess_made", g.id, who + " guessed " + exact + " of " + r.length + " notes ("
					+ r.stringsLeft() + " strings left).");
		}
		touch(g);
		return g;
	}

	/** Spend a Hint: reveal the first note the listener doesn't have yet. */
	public synchronized Game hint(String me, String gameId) {
		Game g = active(gameId, me);
		Game.Round r = listening(g, me);
		Game.PlayerState s = g.state.get(me);
		if (s.hints <= 0) throw error(HttpStatus.CONFLICT, "no hints left");
		int slot = r.revealed.indexOf(null);
		if (slot < 0) throw error(HttpStatus.CONFLICT, "you already know every note");
		r.revealed.set(slot, RiftRules.pitches(r.rift).get(slot));
		r.hintsUsed++;
		s.hints--;
		touch(g);
		return g;
	}

	/** Spend an Extra String: one more wrong guess allowed this round. */
	public synchronized Game extraString(String me, String gameId) {
		Game g = active(gameId, me);
		Game.Round r = listening(g, me);
		Game.PlayerState s = g.state.get(me);
		if (s.extraStrings <= 0) throw error(HttpStatus.CONFLICT, "no extra strings left");
		r.strings++;
		s.extraStrings--;
		touch(g);
		return g;
	}

	public synchronized Game resign(String me, String gameId) {
		Game g = active(gameId, me);
		g.resigned = me;
		finish(g, g.other(me));
		touch(g);
		return g;
	}

	private void endRound(Game g, Game.Round r, boolean solved) {
		r.phase = solved ? Game.Round.SOLVED : Game.Round.FAILED;
		r.endedAt = System.currentTimeMillis();
		Game.PlayerState ls = g.state.get(r.listener);
		if (solved) ls.points += GameRules.POINTS_GUESSED + GameRules.POINTS_PER_STRING_LEFT * r.stringsLeft();
		else ls.encores--;
		if (ls.encores <= 0) {
			finish(g, r.composer);
			return;
		}
		String text = solved ? "Round " + r.number + ": " + name(r.listener) + " got it." : "Round " + r.number + ": " + name(r.listener) + " lost an Encore.";
		events.publish(r.composer, "round_over", g.id, text + " Your turn to guess.");
		events.publish(r.listener, "round_over", g.id, text + " Your turn to compose.");
		newRound(g, r.listener);    // roles swap
	}

	private void finish(Game g, String winner) {
		g.status = Game.FINISHED;
		g.winner = winner;
		String loser = g.other(winner);
		users.get(winner).ifPresent(u -> {
			u.wins++;
			u.points += g.state.get(winner).points;
			users.update(u);
		});
		users.get(loser).ifPresent(u -> {
			u.losses++;
			u.points += g.state.get(loser).points;
			users.update(u);
		});
		events.publish(winner, "game_over", g.id, "You won against " + name(loser) + "!");
		events.publish(loser, "game_over", g.id, name(winner) + " won the game.");
	}

	private Game active(String gameId, String me) {
		Game g = get(gameId);
		if (!g.players.contains(me)) throw error(HttpStatus.FORBIDDEN, "not your game");
		if (!Game.ACTIVE.equals(g.status)) throw error(HttpStatus.CONFLICT, "the game is over");
		return g;
	}

	private Game.Round listening(Game g, String me) {
		Game.Round r = g.current();
		if (!me.equals(r.listener) || !Game.Round.GUESSING.equals(r.phase)) throw error(HttpStatus.CONFLICT, "it isn't your turn to guess");
		return r;
	}

	private void touch(Game g) {
		g.updatedAt = System.currentTimeMillis();
		save();
	}

	private void save() {
		JsonFiles.write(file, games);
	}

	private String name(String userId) {
		return users.get(userId).map(u -> u.name).orElse(userId);
	}

	private static ResponseStatusException error(HttpStatus status, String message) {
		return new ResponseStatusException(status, message);
	}

	// ---- what one player may see

	/**
	 * The game from {@code me}'s side. The listener gets the riff's notes (the phone plays them), but not the score;
	 * the composer sees everything. Finished rounds are open to both.
	 */
	public synchronized Map<String, Object> view(Game g, String me) {
		String opp = g.other(me);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", g.id);
		m.put("status", g.status);
		m.put("winner", g.winner);
		m.put("resigned", g.resigned);
		m.put("updatedAt", g.updatedAt);
		m.put("me", playerView(g, me));
		m.put("opponent", playerView(g, opp));
		List<Map<String, Object>> rounds = new ArrayList<>();
		for (Game.Round r : g.rounds)
			rounds.add(roundView(r, me));
		m.put("rounds", rounds);
		Game.Round cur = g.current();
		String turn = !Game.ACTIVE.equals(g.status) ? "over"
				: Game.Round.COMPOSING.equals(cur.phase) ? (me.equals(cur.composer) ? "compose" : "wait_compose")
				: (me.equals(cur.listener) ? "guess" : "wait_guess");
		m.put("turn", turn);
		return m;
	}

	/** A short line for the games list. */
	public synchronized Map<String, Object> summary(Game g, String me) {
		Map<String, Object> v = view(g, me);
		v.remove("rounds");
		v.put("round", g.current() == null ? 0 : g.current().number);
		return v;
	}

	private Map<String, Object> playerView(Game g, String id) {
		Game.PlayerState s = g.state.get(id);
		User u = users.get(id).orElse(null);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("name", u == null ? id : u.name);
		m.put("bot", u != null && u.bot);
		m.put("online", u != null && (u.bot || users.isOnline(id)));
		m.put("encores", s.encores);
		m.put("points", s.points);
		m.put("hints", s.hints);
		m.put("extraStrings", s.extraStrings);
		return m;
	}

	private Map<String, Object> roundView(Game.Round r, String me) {
		boolean composer = me.equals(r.composer);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("number", r.number);
		m.put("composer", r.composer);
		m.put("listener", r.listener);
		m.put("role", composer ? "composer" : "listener");
		m.put("phase", r.phase);
		m.put("length", r.length);
		m.put("strings", r.strings);
		m.put("stringsLeft", r.stringsLeft());
		m.put("revealed", r.revealed);
		m.put("guesses", r.guesses);
		m.put("hintsUsed", r.hintsUsed);
		m.put("fromLibrary", r.tuneId != null);
		if (r.rift != null) m.put("rift", r.rift);
		if (composer || r.over()) {
			m.put("score", r.score);
			m.put("tier", r.tier);
			m.put("tuneId", r.tuneId);
		}
		return m;
	}
}
