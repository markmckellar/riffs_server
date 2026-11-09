package com.mckcode.riffs.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mckcode.riffs.auth.AuthFilter;
import com.mckcode.riffs.events.EventService;
import com.mckcode.riffs.library.TuneLibrary;
import com.mckcode.riffs.music.MusicSense;
import com.mckcode.riffs.music.Note;
import com.mckcode.riffs.music.RiffRules;
import com.mckcode.riffs.user.Bots;
import com.mckcode.riffs.user.User;
import com.mckcode.riffs.user.UserService;

/** The game API. Everything is JSON; see the README for the list. */
@RestController
public class GameController {

	public record RiffBody(List<Note> notes, String tuneId) {
	}

	public record GuessBody(List<Integer> notes, List<Double> beats) {
	}

	public record StartBody(String opponentId) {
	}

	private final GameService games;
	private final UserService users;
	private final EventService events;
	private final TuneLibrary library;

	public GameController(GameService games, UserService users, EventService events, TuneLibrary library) {
		this.games = games;
		this.users = users;
		this.events = events;
		this.library = library;
	}

	/** The home screen's people: who's online now (people only; bots are always there) and everyone you've played. */
	@GetMapping("/api/home")
	public Map<String, Object> home(@RequestAttribute(AuthFilter.USER_ID) String me) {
		List<Map<String, Object>> online = new ArrayList<>();
		for (User u : users.all())
			if (!u.bot && !u.id.equals(me) && users.isOnline(u.id)) online.add(entry(u, me));
		List<Map<String, Object>> past = new ArrayList<>();
		games.pastOpponents(me).forEach((id, when) -> users.get(id).ifPresent(u -> {
			Map<String, Object> m = entry(u, me);
			m.put("lastPlayed", when);
			past.add(m);
		}));
		// online first, then the latest game
		past.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("online")));
		return Map.of("online", online, "past", past);
	}

	/** Find players by name (people and bots), for challenging someone you haven't played yet. */
	@GetMapping("/api/players")
	public List<Map<String, Object>> players(@RequestAttribute(AuthFilter.USER_ID) String me, @RequestParam(defaultValue = "") String q) {
		String needle = q.trim().toLowerCase();
		if (needle.isEmpty()) return List.of();
		return users.all().stream().filter(u -> !u.id.equals(me) && u.name != null && u.name.toLowerCase().contains(needle))
				.limit(20).map(u -> entry(u, me)).toList();
	}

	/** One player for a list: online, record, and the game you already have with them (if any). */
	private Map<String, Object> entry(User u, String me) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", u.id);
		m.put("name", u.name);
		m.put("bot", u.bot);
		Bots.find(u.id).ifPresent(b -> {
			m.put("level", b.level().name());
			m.put("about", b.about());
		});
		m.put("online", u.bot || users.isOnline(u.id));
		m.put("wins", u.wins);
		m.put("losses", u.losses);
		m.put("game", games.openGameBetween(me, u.id));
		return m;
	}

	@GetMapping("/api/games")
	public List<Map<String, Object>> myGames(@RequestAttribute(AuthFilter.USER_ID) String me) {
		return games.gamesOf(me).stream().map(g -> games.summary(g, me)).toList();
	}

	@PostMapping("/api/games")
	public Map<String, Object> start(@RequestAttribute(AuthFilter.USER_ID) String me, @RequestBody(required = false) StartBody body) {
		return games.view(games.start(me, body == null ? null : body.opponentId()), me);
	}

	@GetMapping("/api/games/{id}")
	public Map<String, Object> game(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		Game g = games.get(id);
		if (!g.players.contains(me)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your game");
		return games.view(g, me);
	}

	@PostMapping("/api/games/{id}/accept")
	public Map<String, Object> accept(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		return games.view(games.accept(me, id), me);
	}

	@PostMapping("/api/games/{id}/decline")
	public Map<String, Object> decline(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		Game g = games.decline(me, id);
		// a Quick match challenge went to a bot: it's no longer this player's game
		return g.players.contains(me) ? games.view(g, me) : Map.of("id", g.id, "status", Game.CANCELLED);
	}

	/** A declined (or unanswered) challenge: play it with a bot instead. */
	@PostMapping("/api/games/{id}/bot")
	public Map<String, Object> bot(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		return games.view(games.playBot(me, id), me);
	}

	@PostMapping("/api/games/{id}/riff")
	public Map<String, Object> riff(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id, @RequestBody RiffBody body) {
		return games.view(games.submitRiff(me, id, body.notes(), body.tuneId()), me);
	}

	@PostMapping("/api/games/{id}/guess")
	public Map<String, Object> guess(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id, @RequestBody GuessBody body) {
		return games.view(games.guess(me, id, body.notes(), body.beats()), me);
	}

	@PostMapping("/api/games/{id}/hint")
	public Map<String, Object> hint(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		return games.view(games.hint(me, id), me);
	}

	@PostMapping("/api/games/{id}/extra-string")
	public Map<String, Object> extraString(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		return games.view(games.extraString(me, id), me);
	}

	@PostMapping("/api/games/{id}/resign")
	public Map<String, Object> resign(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id) {
		return games.view(games.resign(me, id), me);
	}

	/** Score a riff while composing (nothing is saved). */
	@PostMapping("/api/riffs/evaluate")
	public Map<String, Object> evaluate(@RequestBody RiffBody body) {
		Map<String, Object> m = new LinkedHashMap<>();
		MusicSense.Evaluation ev = MusicSense.evaluate(body.notes() == null ? List.of() : body.notes());
		m.put("score", ev.score());
		m.put("tier", ev.tier());
		m.put("key", ev.key());
		m.put("reasons", ev.reasons());
		m.put("problem", RiffRules.problem(body.notes()));
		return m;
	}

	/** The limits the client's staff editor needs. */
	@GetMapping("/api/riffs/rules")
	public Map<String, Object> rules() {
		return Map.of("minNotes", RiffRules.MIN_NOTES, "maxNotes", RiffRules.MAX_NOTES, "maxRests", RiffRules.MAX_RESTS,
				"lowest", RiffRules.LOWEST, "highest", RiffRules.HIGHEST, "maxSpan", RiffRules.MAX_SPAN, "lengths", RiffRules.LENGTHS,
				"strings", GameRules.STRINGS);
	}

	@GetMapping("/api/library")
	public List<Map<String, Object>> library() {
		return library.tunes().stream().map(library::describe).toList();
	}

	@GetMapping("/api/library/{tuneId}/fragments")
	public List<TuneLibrary.Fragment> fragments(@PathVariable String tuneId) {
		return library.fragmentsOf(tuneId);
	}

	@GetMapping("/api/events")
	public Map<String, Object> events(@RequestAttribute(AuthFilter.USER_ID) String me, @RequestParam(defaultValue = "0") long after) {
		List<EventService.Event> l = events.after(me, after);
		long seq = l.isEmpty() ? Math.max(after, 0) : l.get(l.size() - 1).seq();
		if (after == 0) seq = events.latest();    // first call: start from now
		return Map.of("seq", seq, "events", after == 0 ? List.of() : l);
	}
}
