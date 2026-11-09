package com.mckcode.riffs.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mckcode.riffs.store.JsonFiles;

/**
 * Games on disk, one file per game, in a folder per day (the day the game started, UTC):
 *
 * <pre>
 * games/2026-11-09/20261109-a3f9c2e1.json   one game
 * games/open/20261109-a3f9c2e1              empty marker: this game is going on or waiting for an answer
 * games/players/&lt;userId&gt;.txt                the ids of every game that player has been in, one per line
 * </pre>
 *
 * A new game's id starts with its day, so its file is found without searching. Saving a move writes only that
 * game's file, however many games there are.
 *
 * In memory: every open game and every game from the last {@code keepDays} days. Older finished games stay on disk
 * and are read when someone asks for them (their game list or a link). Not thread safe: {@link GameService} calls
 * it while holding its own lock.
 */
public class GameStore {

	private static final DateTimeFormatter ID_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final Pattern DATED_ID = Pattern.compile("(\\d{8})-[0-9a-f]+");
	/** Ids from before day folders (8 hex digits), and anything a player might send. */
	private static final Pattern SAFE_ID = Pattern.compile("[0-9a-zA-Z-]{1,40}");

	private final Path dir, openDir, playersDir;
	private final int keepDays;
	private final Map<String, Game> games = new LinkedHashMap<>();
	private final Set<String> open = new LinkedHashSet<>();
	private final Map<String, Set<String>> byPlayer = new LinkedHashMap<>();
	/** Ids looked for on disk and not found, so a bad link isn't searched for again and again. */
	private final Set<String> missing = new LinkedHashSet<>();

	public GameStore(Path dataDir, int keepDays) {
		this.dir = dataDir.resolve("games");
		this.openDir = dir.resolve("open");
		this.playersDir = dir.resolve("players");
		this.keepDays = keepDays;
		migrate(dataDir.resolve("games.json"));
		loadRecent();
	}

	public static String newId(long createdAt) {
		return day(createdAt).format(ID_DAY) + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	// ---- reading

	/** The game, from memory or disk, or null. */
	public Game get(String id) {
		Game g = games.get(id);
		if (g != null || id == null || !SAFE_ID.matcher(id).matches() || missing.contains(id)) return g;
		Path f = find(id);
		if (f == null) {
			if (missing.size() > 10_000) missing.clear();
			missing.add(id);
			return null;
		}
		g = read(f);
		games.put(g.id, g);
		return g;
	}

	/** The games still going on or waiting for an answer. */
	public List<Game> openGames() {
		return open.stream().map(games::get).toList();
	}

	/** Every game the player has been in (some may since have gone to a bot: check {@code players}). */
	public List<Game> gamesOf(String userId) {
		List<Game> l = new ArrayList<>();
		for (String id : idsOf(userId)) {
			Game g = get(id);
			if (g != null) l.add(g);
		}
		return l;
	}

	// ---- writing

	/** A new game: its file, its open marker and both players' lists. */
	public void add(Game g) {
		games.put(g.id, g);
		for (String p : g.players)
			addPlayer(g, p);
		save(g);
	}

	/** Someone new in the game (a bot taking a seat). */
	public void addPlayer(Game g, String userId) {
		if (idsOf(userId).add(g.id)) append(playersFile(userId), g.id + "\n");
	}

	public void save(Game g) {
		JsonFiles.write(fileOf(g.id, g.createdAt), g);
		Path marker = openDir.resolve(g.id);
		try {
			if (g.open()) {
				if (open.add(g.id)) {
					Files.createDirectories(openDir);
					if (!Files.exists(marker)) Files.createFile(marker);
				}
			}
			else if (open.remove(g.id)) Files.deleteIfExists(marker);
		}
		catch (IOException e) {
			throw new UncheckedIOException("can't mark " + g.id + " open or closed", e);
		}
	}

	// ---- files

	private Path fileOf(String id, long createdAt) {
		return dir.resolve(day(createdAt).toString()).resolve(id + ".json");
	}

	/** A dated id names its folder; an id from before day folders means looking through them (rare: old links). */
	private Path find(String id) {
		var m = DATED_ID.matcher(id);
		if (m.matches()) {
			LocalDate d = LocalDate.parse(m.group(1), ID_DAY);
			Path f = dir.resolve(d.toString()).resolve(id + ".json");
			return Files.exists(f) ? f : null;
		}
		for (Path dayDir : dayDirs()) {
			Path f = dayDir.resolve(id + ".json");
			if (Files.exists(f)) return f;
		}
		return null;
	}

	private Set<String> idsOf(String userId) {
		return byPlayer.computeIfAbsent(userId, u -> {
			Set<String> ids = new LinkedHashSet<>();
			Path f = playersFile(u);
			if (Files.exists(f)) {
				try {
					for (String line : Files.readAllLines(f))
						if (!line.isBlank()) ids.add(line.trim());
				}
				catch (IOException e) {
					throw new UncheckedIOException("can't read " + f, e);
				}
			}
			return ids;
		});
	}

	/** User ids look like "google:123", "guest:ab12" or "bot:mia": keep them file-name safe. */
	private Path playersFile(String userId) {
		return playersDir.resolve(userId.replaceAll("[^0-9A-Za-z_.-]", "_") + ".txt");
	}

	/** The day folders, newest first. */
	private List<Path> dayDirs() {
		if (!Files.isDirectory(dir)) return List.of();
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> Files.isDirectory(p) && p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}"))
					.sorted((x, y) -> y.getFileName().compareTo(x.getFileName())).toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException("can't list " + dir, e);
		}
	}

	private void loadRecent() {
		String since = LocalDate.now(ZoneOffset.UTC).minusDays(keepDays).toString();
		for (Path dayDir : dayDirs()) {
			if (dayDir.getFileName().toString().compareTo(since) < 0) break;
			try (Stream<Path> s = Files.list(dayDir)) {
				s.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
					Game g = read(p);
					games.put(g.id, g);
				});
			}
			catch (IOException e) {
				throw new UncheckedIOException("can't list " + dayDir, e);
			}
		}
		if (Files.isDirectory(openDir)) {
			try (Stream<Path> s = Files.list(openDir)) {
				for (Path p : s.toList()) {
					String id = p.getFileName().toString();
					Game g = get(id);
					if (g != null && g.open()) open.add(id);
					else Files.deleteIfExists(p);    // a marker left behind
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException("can't list " + openDir, e);
			}
		}
	}

	/** games.json (everything in one file, before 2026-11-09) becomes one file per game; it's kept as games.json.migrated. */
	private void migrate(Path oldFile) {
		if (!Files.exists(oldFile)) return;
		Map<String, Game> old = JsonFiles.read(oldFile, new TypeReference<Map<String, Game>>() {
		}, Map.of());
		for (Game g : old.values())
			add(g);
		try {
			Files.move(oldFile, oldFile.resolveSibling("games.json.migrated"), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e) {
			throw new UncheckedIOException("can't rename " + oldFile, e);
		}
		games.clear();    // loadRecent reads them back like any other start
		open.clear();
		byPlayer.clear();
	}

	private static Game read(Path f) {
		Game g = JsonFiles.read(f, new TypeReference<Game>() {
		}, null);
		// games saved when guitars had more strings: cap them at today's number
		for (Game.PlayerState s : g.state.values())
			s.strings = Math.min(s.strings, GameRules.STRINGS);
		return g;
	}

	private static void append(Path f, String text) {
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e) {
			throw new UncheckedIOException("can't write " + f, e);
		}
	}

	private static LocalDate day(long millis) {
		return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
	}
}
