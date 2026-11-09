package com.mckcode.riffs.game;

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
import com.mckcode.riffs.events.EventService;
import com.mckcode.riffs.music.MusicSense;
import com.mckcode.riffs.music.Note;
import com.mckcode.riffs.music.RiffRules;
import com.mckcode.riffs.store.JsonFiles;
import com.mckcode.riffs.user.Bots;
import com.mckcode.riffs.user.User;
import com.mckcode.riffs.user.UserService;

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

	public GameService(@Value("${riffs.data-dir:local/data}") String dataDir, UserService users, EventService events) {
		this.file = Paths.get(dataDir, "games.json");
		this.games = new LinkedHashMap<>(JsonFiles.read(file, new TypeReference<Map<String, Game>>() {
		}, new LinkedHashMap<>()));
		this.users = users;
		this.events = events;
		// games saved when guitars had more strings: cap them at today's number
		for (Game g : games.values())
			for (Game.PlayerState s : g.state.values())
				s.strings = Math.min(s.strings, GameRules.STRINGS);
	}

	// ---- reading

	public synchronized Game get(String id) {
		Game g = games.get(id);
		if (g == null) throw error(HttpStatus.NOT_FOUND, "no such game");
		return g;
	}

	public synchronized List<Game> gamesOf(String userId) {
		return games.values().stream().filter(g -> g.players.contains(userId) && !Game.CANCELLED.equals(g.status))
				.sorted(Comparator.comparing((Game g) -> !g.open()).thenComparing(g -> -g.updatedAt)).toList();
	}

	/** Everyone {@code userId} has had a game with (people and bots), the latest game first, with when it was. */
	public synchronized Map<String, Long> pastOpponents(String userId) {
		Map<String, Long> m = new LinkedHashMap<>();
		for (Game g : gamesOf(userId).stream().sorted(Comparator.comparing((Game g) -> -g.updatedAt)).toList()) {
			if (Game.DECLINED.equals(g.status) || Game.INVITED.equals(g.status)) continue;    // never played
			m.putIfAbsent(g.other(userId), g.updatedAt);
		}
		return m;
	}

	/** The id of a game between the two that is going on or waiting to be accepted, or null. */
	public synchronized String openGameBetween(String a, String b) {
		Game g = activeBetween(a, b);
		return g == null ? null : g.id;
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
	 * playing you, otherwise a bot. The starter composes first. A game with a person starts as a challenge
	 * ({@link Game#INVITED}) that they accept or decline; the starter can write the first riff meanwhile. A Quick match
	 * challenge goes to a bot if it isn't accepted within {@link GameRules#QUICK_INVITE_MS}.
	 */
	public synchronized Game start(String me, String opponentId) {
		String opp = opponentId;
		boolean quick = opp == null || opp.isBlank();
		if (quick) opp = quickMatch(me);
		if (opp.equals(me)) throw error(HttpStatus.BAD_REQUEST, "you can't play yourself");
		if (users.get(opp).isEmpty()) throw error(HttpStatus.NOT_FOUND, "no such player");
		// challenging someone you're already playing opens that game; Quick match always starts a new one
		Game existing = activeBetween(me, opp);
		if (existing != null && !quick) return existing;

		Game g = new Game();
		g.id = UUID.randomUUID().toString().substring(0, 8);
		g.createdAt = g.updatedAt = System.currentTimeMillis();
		g.players.add(me);
		g.players.add(opp);
		g.state.put(me, new Game.PlayerState());
		g.state.put(opp, new Game.PlayerState());
		newRound(g, me);
		if (!Bots.isBot(opp)) {
			g.status = Game.INVITED;
			g.invited = opp;
			g.invitedAt = g.createdAt;
			g.quick = quick;
		}
		games.put(g.id, g);
		save();
		if (!Bots.isBot(opp)) events.publish(opp, "challenge", g.id, name(me) + " challenged you to a game.");
		return g;
	}

	/** The challenged player says yes: the game is on (if the riff is already written, it's theirs to guess). */
	public synchronized Game accept(String me, String gameId) {
		Game g = invitation(gameId, me);
		g.status = Game.ACTIVE;
		g.invited = null;
		touch(g);
		events.publish(g.other(me), "challenge_accepted", g.id, name(me) + " accepted your challenge!");
		return g;
	}

	/** The challenged player says no. A Quick match then goes to a bot; a challenge by name ends as declined. */
	public synchronized Game decline(String me, String gameId) {
		Game g = invitation(gameId, me);
		String starter = g.other(me);
		if (g.quick) {
			String bot = toBot(g);
			events.publish(starter, "opponent_changed", g.id, name(me) + " can't play right now, so " + name(bot) + " is playing you instead.");
		}
		else {
			g.status = Game.DECLINED;
			events.publish(starter, "challenge_declined", g.id, name(me) + " declined your challenge.");
		}
		touch(g);
		return g;
	}

	/** The starter of a challenge that was declined (or isn't answered yet) plays it with a bot instead. */
	public synchronized Game playBot(String me, String gameId) {
		Game g = get(gameId);
		if (!g.players.contains(me)) throw error(HttpStatus.FORBIDDEN, "not your game");
		boolean waiting = Game.INVITED.equals(g.status) && !me.equals(g.invited);
		boolean declined = Game.DECLINED.equals(g.status);
		if (!waiting && !declined) throw error(HttpStatus.CONFLICT, "this game already has an opponent");
		if (declined) g.invited = g.other(me);
		toBot(g);
		touch(g);
		return g;
	}

	/** Quick match challenges nobody accepted in time go to a bot (checked every few seconds by {@link BotPlayer}). */
	public synchronized void expireInvites() {
		long now = System.currentTimeMillis();
		for (Game g : games.values()) {
			if (!Game.INVITED.equals(g.status) || !g.quick || now - g.invitedAt < GameRules.QUICK_INVITE_MS) continue;
			String was = g.invited, starter = g.other(was);
			String bot = toBot(g);
			events.publish(starter, "opponent_changed", g.id, name(was) + " didn't answer, so " + name(bot) + " is playing you instead.");
			touch(g);
		}
	}

	/** Put a bot in the challenged player's seat (nothing has been played against them yet) and start the game. */
	private String toBot(Game g) {
		String was = g.invited, starter = g.other(was);
		List<Bots.Bot> friendly = Bots.ALL.stream().filter(b -> b.level() != Bots.HARD).toList();
		List<Bots.Bot> free = friendly.stream().filter(b -> activeBetween(starter, b.id()) == null).toList();
		List<Bots.Bot> from = free.isEmpty() ? friendly : free;
		String bot = from.get(random.nextInt(from.size())).id();
		g.players.set(g.players.indexOf(was), bot);
		g.state.put(bot, g.state.remove(was));
		for (Game.Round r : g.rounds) {
			if (was.equals(r.composer)) r.composer = bot;
			if (was.equals(r.listener)) r.listener = bot;
		}
		g.status = Game.ACTIVE;
		g.invited = null;
		g.quick = false;
		return bot;
	}

	private Game invitation(String gameId, String me) {
		Game g = get(gameId);
		if (!Game.INVITED.equals(g.status) || !me.equals(g.invited)) throw error(HttpStatus.CONFLICT, "there's no challenge to answer");
		return g;
	}

	/**
	 * Someone online who isn't already playing you; otherwise an easy or medium bot you aren't playing yet; otherwise
	 * any easy or medium bot (bots can play many games at once). So there is always an opponent, even with nobody else signed up.
	 */
	private String quickMatch(String me) {
		List<User> people = users.all().stream()
				.filter(u -> !u.bot && !u.id.equals(me) && users.isOnline(u.id) && activeBetween(me, u.id) == null
						&& !hasOpenInvite(u.id)).toList();
		if (!people.isEmpty()) return people.get(random.nextInt(people.size())).id;
		// hard bots only when picked by name: a random opponent shouldn't be one that wins 97% of the time
		List<Bots.Bot> friendly = Bots.ALL.stream().filter(b -> b.level() != Bots.HARD).toList();
		List<Bots.Bot> freeBots = friendly.stream().filter(b -> activeBetween(me, b.id()) == null).toList();
		List<Bots.Bot> from = freeBots.isEmpty() ? friendly : freeBots;
		return from.get(random.nextInt(from.size())).id();
	}

	/** A game between the two that's on, or a challenge between them waiting for an answer. */
	private Game activeBetween(String a, String b) {
		return games.values().stream().filter(g -> g.open() && g.players.contains(a) && g.players.contains(b))
				.findFirst().orElse(null);
	}

	/** Already asked by Quick match and not answered yet: don't pile more on. */
	private boolean hasOpenInvite(String userId) {
		return games.values().stream().anyMatch(g -> Game.INVITED.equals(g.status) && g.quick && userId.equals(g.invited));
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
	public synchronized Game submitRiff(String me, String gameId, List<Note> notes, String tuneId) {
		Game g = active(gameId, me);
		Game.Round r = g.current();
		if (!me.equals(r.composer) || !Game.Round.COMPOSING.equals(r.phase)) throw error(HttpStatus.CONFLICT, "it isn't your turn to compose");
		notes = RiffRules.evenly(notes);    // no rhythm: every note one beat
		String problem = RiffRules.problem(notes);
		if (problem != null) throw error(HttpStatus.BAD_REQUEST, problem);

		MusicSense.Evaluation ev = MusicSense.evaluate(notes);
		r.riff = List.copyOf(notes);
		r.length = RiffRules.pitches(notes).size();
		r.tuneId = tuneId == null || tuneId.isBlank() ? null : tuneId;
		r.score = ev.score();
		r.tier = ev.tier();
		r.revealed = new ArrayList<>();
		r.revealedBeats = new ArrayList<>();
		List<Double> lengths = beatsOf(notes);
		for (int i = 0; i < r.length; i++) {    // the first note is given: pitch and length
			r.revealed.add(i == 0 ? RiffRules.pitches(notes).get(0) : null);
			r.revealedBeats.add(i == 0 ? lengths.get(0) : null);
		}
		r.phase = Game.Round.GUESSING;
		reward(g, me, r);
		touch(g);
		// a challenge not accepted yet: the challenge notice is enough, they find the riff when they accept
		if (Game.ACTIVE.equals(g.status)) events.publish(r.listener, "riff_ready", g.id, name(me) + " sent you a riff to guess.");
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
		}
	}

	// ---- guessing

	/** The lengths of a riff's sounding notes: one beat each (also for riffs saved when lengths still varied). */
	private static List<Double> beatsOf(List<com.mckcode.riffs.music.Note> notes) {
		return notes.stream().filter(n -> !n.isRest()).map(n -> RiffRules.BEAT).toList();
	}

	/** Rounds saved before lengths counted have no revealedBeats: start it (the first note's length given). */
	private static void ensureBeats(Game.Round r) {
		if (r.revealedBeats != null && r.revealedBeats.size() == r.length) return;
		r.revealedBeats = new ArrayList<>();
		for (int i = 0; i < r.length; i++)
			r.revealedBeats.add(i == 0 ? beatsOf(r.riff).get(0) : null);
	}

	/**
	 * A guess: a pitch and a length for every note. A note is right only when both match. Each pitch is marked exact /
	 * octave / close (with which way) / miss, and each length ok / wrong; right pitches and right lengths stay known.
	 */
	public synchronized Game guess(String me, String gameId, List<Integer> notes, List<Double> beats) {
		Game g = active(gameId, me);
		Game.Round r = listening(g, me);
		if (notes == null || notes.size() != r.length) throw error(HttpStatus.BAD_REQUEST, "a guess needs " + r.length + " notes");
		if (notes.stream().anyMatch(n -> n == null)) throw error(HttpStatus.BAD_REQUEST, "fill in every note");
		// no rhythm any more: lengths are optional and every note counts as one beat
		if (beats == null || beats.size() != r.length || beats.stream().anyMatch(b -> b == null))
			beats = java.util.Collections.nCopies(r.length, RiffRules.BEAT);
		else beats = beats.stream().map(b -> RiffRules.BEAT).toList();
		ensureBeats(r);

		List<Integer> answer = RiffRules.pitches(r.riff);
		List<Double> answerBeats = beatsOf(r.riff);
		Game.Guess guess = new Game.Guess();
		guess.notes = List.copyOf(notes);
		guess.beats = List.copyOf(beats);
		guess.marks = new ArrayList<>();
		guess.hints = new ArrayList<>();
		guess.lengths = new ArrayList<>();
		guess.at = System.currentTimeMillis();
		boolean all = true;
		for (int i = 0; i < r.length; i++) {
			int a = answer.get(i), n = notes.get(i);
			String mark = n == a ? "exact" : Math.floorMod(n - a, 12) == 0 ? "octave" : Math.abs(n - a) <= GameRules.CLOSE ? "close" : "miss";
			guess.marks.add(mark);
			guess.hints.add("close".equals(mark) ? (a > n ? "up" : "down") : null);
			boolean lengthOk = Math.abs(beats.get(i) - answerBeats.get(i)) < 1e-6;
			guess.lengths.add(lengthOk ? "ok" : "wrong");
			if (n == a) r.revealed.set(i, a);
			if (lengthOk) r.revealedBeats.set(i, answerBeats.get(i));
			if (n != a || !lengthOk) all = false;
		}
		guess.correct = all;
		r.guesses.add(guess);
		Game.PlayerState ls = g.state.get(me);
		if (!all) ls.strings--;    // a wrong guess snaps a string of the listener's guitar, for good
		boolean learner = users.get(me).map(u -> u.learner).orElse(false);
		if (learner) r.learner = true;
		// learner mode: every few wrong guesses, the next unknown note is given free
		if (!all && learner && ls.strings > 0 && r.wrong() % GameRules.LEARNER_FREE_NOTE_EVERY == 0) {
			int slot = firstUnknown(r);
			if (slot >= 0) {
				r.revealed.set(slot, answer.get(slot));
				r.revealedBeats.set(slot, answerBeats.get(slot));
				r.freeNotes++;
				if (firstUnknown(r) < 0) all = true;    // that was the last one: nothing left to guess
			}
		}

		String who = name(me);
		if (all) {
			events.publish(r.composer, "guess_made", g.id, who + " guessed your riff!");
			endRound(g, r, true);
		}
		else if (ls.strings <= 0) {
			events.publish(r.composer, "guess_made", g.id, who + " snapped their last string on your riff!");
			endRound(g, r, false);
		}
		else {
			long exact = guess.marks.stream().filter("exact"::equals).count();
			events.publish(r.composer, "guess_made", g.id, who + " guessed " + exact + " of " + r.length + " notes ("
					+ ls.strings + " strings left).");
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
		ensureBeats(r);
		int slot = firstUnknown(r);
		if (slot < 0) throw error(HttpStatus.CONFLICT, "you already know every note");
		r.revealed.set(slot, RiffRules.pitches(r.riff).get(slot));
		r.revealedBeats.set(slot, beatsOf(r.riff).get(slot));
		r.hintsUsed++;
		s.hints--;
		touch(g);
		return g;
	}

	/** The first note whose pitch or length the listener doesn't know yet, or -1. */
	private static int firstUnknown(Game.Round r) {
		for (int i = 0; i < r.length; i++)
			if (r.revealed.get(i) == null || r.revealedBeats.get(i) == null) return i;
		return -1;
	}

	/** Spend an Extra String: put a snapped string back on your guitar (up to a full guitar). */
	public synchronized Game extraString(String me, String gameId) {
		Game g = active(gameId, me);
		listening(g, me);
		Game.PlayerState s = g.state.get(me);
		if (s.extraStrings <= 0) throw error(HttpStatus.CONFLICT, "no extra strings left");
		if (s.strings >= GameRules.STRINGS) throw error(HttpStatus.CONFLICT, "your guitar has all its strings");
		s.strings++;
		s.extraStrings--;
		touch(g);
		return g;
	}

	public synchronized Game resign(String me, String gameId) {
		Game g = get(gameId);
		if (Game.INVITED.equals(g.status) && g.players.contains(me)) {    // withdrawing a challenge, or saying no to one
			if (me.equals(g.invited)) return decline(me, gameId);
			g.status = Game.CANCELLED;
			touch(g);
			return g;
		}
		g = active(gameId, me);
		g.resigned = me;
		finish(g, g.other(me));
		touch(g);
		return g;
	}

	private void endRound(Game g, Game.Round r, boolean solved) {
		r.phase = solved ? Game.Round.SOLVED : Game.Round.FAILED;
		r.endedAt = System.currentTimeMillis();
		Game.PlayerState ls = g.state.get(r.listener);
		if (solved) {
			int pts = GameRules.POINTS_GUESSED + GameRules.POINTS_PER_STRING_LEFT * r.fastBonus();
			ls.points += r.learner ? pts / 2 : pts;    // learner mode: half points
		}
		if (ls.strings <= 0) {    // the last string snapped: game over
			finish(g, r.composer);
			return;
		}
		String text = "Round " + r.number + ": " + name(r.listener) + " got it.";
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
		if (Game.INVITED.equals(g.status)) {    // the challenger may write the first riff while waiting
			if (me.equals(g.invited)) throw error(HttpStatus.CONFLICT, "accept the challenge first");
			return g;
		}
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
		m.put("invited", g.invited);
		m.put("quick", g.quick);
		m.put("me", playerView(g, me));
		m.put("opponent", playerView(g, opp));
		List<Map<String, Object>> rounds = new ArrayList<>();
		for (Game.Round r : g.rounds)
			rounds.add(roundView(r, me));
		m.put("rounds", rounds);
		Game.Round cur = g.current();
		String turn = Game.INVITED.equals(g.status) && me.equals(g.invited) ? "invited"
				: Game.DECLINED.equals(g.status) ? "declined"
				: !g.open() ? "over"
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
		Bots.find(id).ifPresent(b -> m.put("level", b.level().name()));
		m.put("learner", u != null && u.learner);
		m.put("online", u != null && (u.bot || users.isOnline(id)));
		m.put("strings", s.strings);
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
		m.put("wrong", r.wrong());
		m.put("revealed", r.revealed);
		if (r.riff != null) ensureBeats(r);
		m.put("revealedBeats", r.revealedBeats);
		m.put("guesses", r.guesses);
		m.put("hintsUsed", r.hintsUsed);
		m.put("freeNotes", r.freeNotes);
		m.put("learner", r.learner);
		m.put("fromLibrary", r.tuneId != null);
		if (r.riff != null) m.put("riff", r.riff);
		if (composer || r.over()) {
			m.put("score", r.score);
			m.put("tier", r.tier);
			m.put("tuneId", r.tuneId);
		}
		return m;
	}
}
