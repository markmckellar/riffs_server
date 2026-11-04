package com.mckcode.riffs.game;

import java.util.ArrayList;
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
import com.mckcode.riffs.user.User;
import com.mckcode.riffs.user.UserService;

/** The game API. Everything is JSON; see the README for the list. */
@RestController
public class GameController {

	public record RiffBody(List<Note> notes, String tuneId) {
	}

	public record GuessBody(List<Integer> notes) {
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

	@GetMapping("/api/players")
	public List<Map<String, Object>> players(@RequestAttribute(AuthFilter.USER_ID) String me) {
		List<Map<String, Object>> l = new ArrayList<>();
		for (User u : users.all()) {
			if (u.id.equals(me)) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", u.id);
			m.put("name", u.name);
			m.put("bot", u.bot);
			m.put("online", u.bot || users.isOnline(u.id));
			m.put("wins", u.wins);
			m.put("losses", u.losses);
			l.add(m);
		}
		return l;
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

	@PostMapping("/api/games/{id}/riff")
	public Map<String, Object> riff(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id, @RequestBody RiffBody body) {
		return games.view(games.submitRiff(me, id, body.notes(), body.tuneId()), me);
	}

	@PostMapping("/api/games/{id}/guess")
	public Map<String, Object> guess(@RequestAttribute(AuthFilter.USER_ID) String me, @PathVariable String id, @RequestBody GuessBody body) {
		return games.view(games.guess(me, id, body.notes()), me);
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
				"lowest", RiffRules.LOWEST, "highest", RiffRules.HIGHEST, "lengths", RiffRules.LENGTHS,
				"strings", GameRules.STRINGS, "encores", GameRules.START_ENCORES);
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
