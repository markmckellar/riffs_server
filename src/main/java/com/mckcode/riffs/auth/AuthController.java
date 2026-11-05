package com.mckcode.riffs.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mckcode.riffs.user.User;
import com.mckcode.riffs.user.UserService;

/** Signing in (Google, or a typed name on a test server) and out, and "who am I". */
@RestController
public class AuthController {

	private static final Logger Log = LoggerFactory.getLogger(AuthController.class);

	private final AuthConfig config;
	private final Sessions sessions;
	private final GoogleSignIn google;
	private final UserService users;
	/** The game's display name (riffs.game-name), sent to the client so it's set in one place. */
	private final String gameName;

	public AuthController(AuthConfig config, Sessions sessions, GoogleSignIn google, UserService users,
			@Value("${riffs.game-name}") String gameName) {
		this.gameName = gameName;
		this.config = config;
		this.sessions = sessions;
		this.google = google;
		this.users = users;
	}

	@GetMapping("/api/health")
	public Map<String, Object> health() {
		return Map.of("ok", true, "game", gameName);
	}

	/** How to sign in on this server, and who is signed in (if anyone). */
	@GetMapping("/api/auth/config")
	public Map<String, Object> getConfig(HttpServletRequest request) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("game", gameName);
		m.put("googleClientId", config.googleClientId());
		m.put("devLogin", config.devLogin());
		m.put("guestLogin", config.guestLogin());
		Optional<User> me = sessions.read(request).flatMap(users::get);
		m.put("me", me.map(AuthController::me).orElse(null));
		return m;
	}

	@PostMapping("/api/auth/google")
	public Map<String, Object> google(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) {
		GoogleSignIn.GoogleUser g = google.check(body == null ? null : body.get("credential"))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google sign-in didn't check out"));
		User u = users.signedIn("g:" + g.subject(), g.name(), g.email());
		sessions.set(req, res, sessions.create(u.id), false);
		Log.info("signed in with Google: {} ({})", u.name, u.email);
		return me(u);
	}

	/** Test servers only: sign in as whoever you type. */
	@PostMapping("/api/auth/dev")
	public Map<String, Object> dev(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) {
		if (!config.devLogin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "name-only sign-in is off on this server");
		String name = body == null || body.get("name") == null ? "" : body.get("name").trim();
		if (name.isEmpty() || name.length() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type a name (up to 30 letters)");
		String id = "dev:" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
		User u = users.signedIn(id, name, null);
		sessions.set(req, res, sessions.create(u.id), false);
		return me(u);
	}

	/**
	 * Guest sign-in: the name is only what others see. Every guest gets a new random account, so typing someone
	 * else's name doesn't make you them (unlike the test sign-in). The cookie keeps this phone signed in.
	 */
	@PostMapping("/api/auth/guest")
	public Map<String, Object> guest(@RequestBody Map<String, String> body, HttpServletRequest req, HttpServletResponse res) {
		if (!config.guestLogin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "guest sign-in is off on this server");
		String name = body == null || body.get("name") == null ? "" : body.get("name").trim();
		if (name.isEmpty() || name.length() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type a name (up to 30 letters)");
		byte[] r = new byte[9];
		RANDOM.nextBytes(r);
		String id = "guest:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(r);
		User u = users.signedIn(id, name, null);
		sessions.set(req, res, sessions.create(u.id), false);
		Log.info("guest signed in: {} ({})", u.name, u.id);
		return me(u);
	}

	private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

	@PostMapping("/api/auth/logout")
	public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse res) {
		sessions.set(req, res, null, true);
		return Map.of("signedOut", true);
	}

	/** The player's own settings; for now just learner mode. */
	@PostMapping("/api/me/settings")
	public Map<String, Object> settings(@org.springframework.web.bind.annotation.RequestAttribute(AuthFilter.USER_ID) String me,
			@RequestBody Map<String, Object> body) {
		Object learner = body == null ? null : body.get("learner");
		if (!(learner instanceof Boolean b)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "learner must be true or false");
		return me(users.setLearner(me, b));
	}

	static Map<String, Object> me(User u) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", u.id);
		m.put("name", u.name);
		m.put("email", u.email);
		m.put("wins", u.wins);
		m.put("losses", u.losses);
		m.put("points", u.points);
		m.put("learner", u.learner);
		return m;
	}
}
