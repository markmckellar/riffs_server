package com.mckcode.rifts.user;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mckcode.rifts.store.JsonFiles;

/** The players (users.json), the built-in bots, and who has been seen lately ("online"). */
@Service
public class UserService {

	/** Seen within this long counts as online. The client polls every few seconds while open. */
	public static final long ONLINE_MS = 60_000;

	public static final List<User> BOTS = List.of(
			new User("bot:robo_riff", "Robo Riff (bot)", null, true),
			new User("bot:tin_pan", "Tin Pan (bot)", null, true),
			new User("bot:metronome", "Metronome Mo (bot)", null, true));

	private final Path file;
	private final Map<String, User> users;
	private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

	public UserService(@Value("${rifts.data-dir:local/data}") String dataDir) {
		this.file = Paths.get(dataDir, "users.json");
		this.users = new LinkedHashMap<>(JsonFiles.read(file, new TypeReference<Map<String, User>>() {
		}, new LinkedHashMap<>()));
		for (User b : BOTS)
			users.putIfAbsent(b.id, b);
	}

	public synchronized Optional<User> get(String id) {
		return Optional.ofNullable(users.get(id));
	}

	/** The user for this sign-in, created on first sign-in; the name and email are refreshed each time. */
	public synchronized User signedIn(String id, String name, String email) {
		User u = users.get(id);
		if (u == null) {
			u = new User(id, name, email, false);
			users.put(id, u);
		}
		else {
			u.name = name;
			u.email = email;
		}
		save();
		seen(id);
		return u;
	}

	public void seen(String id) {
		lastSeen.put(id, System.currentTimeMillis());
	}

	public boolean isOnline(String id) {
		Long t = lastSeen.get(id);
		return t != null && System.currentTimeMillis() - t < ONLINE_MS;
	}

	public synchronized List<User> all() {
		List<User> l = new ArrayList<>(users.values());
		l.sort(Comparator.comparing((User u) -> u.bot).thenComparing(u -> !isOnline(u.id)).thenComparing(u -> u.name.toLowerCase()));
		return l;
	}

	public synchronized void update(User u) {
		users.put(u.id, u);
		save();
	}

	private void save() {
		JsonFiles.write(file, users);
	}
}
