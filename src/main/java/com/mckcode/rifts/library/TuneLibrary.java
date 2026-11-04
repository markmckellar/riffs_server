package com.mckcode.rifts.library;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mckcode.rifts.music.MusicSense;
import com.mckcode.rifts.music.Note;
import com.mckcode.rifts.music.RiftRules;

/**
 * The public-domain tunes in tunes.json, and the riff-sized pieces cut from them at phrase boundaries
 * (research/music_structure.md, "Choosing musical pieces of a song"). Bots compose from these, and players can
 * pick one instead of writing their own.
 */
@Component
public class TuneLibrary {

	public record Tune(String id, String title, String source, int year, String meter, List<Note> notes) {
	}

	/** A riff-sized piece of a tune: which tune, which notes, how it scores, and whether it ends a whole phrase. */
	public record Fragment(String tuneId, String title, int start, List<Note> notes, int score, boolean phraseEnd) {
		public int noteCount() {
			return (int) notes.stream().filter(n -> !n.isRest()).count();
		}
	}

	/** A sub-phrase: the notes between two '/' marks, with where its bar lines fall. */
	private record Unit(int start, List<Note> notes, List<Integer> barEnds, boolean phraseEnd) {
	}

	private final List<Tune> tunes = new ArrayList<>();
	private final List<Fragment> fragments = new ArrayList<>();

	public TuneLibrary() {
		try (InputStream in = getClass().getResourceAsStream("/tunes.json")) {
			JsonNode root = new ObjectMapper().readTree(in);
			for (JsonNode t : root.get("tunes")) {
				String id = t.get("id").asText(), title = t.get("title").asText();
				List<Unit> units = parseUnits(t.get("notes").asText(), id);
				List<Note> all = new ArrayList<>();
				units.forEach(u -> all.addAll(u.notes()));
				tunes.add(new Tune(id, title, t.get("source").asText(), t.get("year").asInt(), t.get("meter").asText(), all));
				fragments.addAll(cut(id, title, units));
			}
		}
		catch (Exception e) {
			throw new IllegalStateException("tunes.json: " + e.getMessage(), e);
		}
	}

	public List<Tune> tunes() {
		return tunes;
	}

	public List<Fragment> fragments() {
		return fragments;
	}

	/** A random fragment with this many notes (or any length if 0), favouring the better-scoring ones. */
	public Fragment randomFragment(Random random, int noteCount) {
		List<Fragment> pool = fragments.stream().filter(f -> noteCount == 0 || f.noteCount() == noteCount).toList();
		if (pool.isEmpty()) pool = fragments;
		List<Fragment> good = pool.stream().filter(f -> f.score() >= 60).toList();
		List<Fragment> from = good.isEmpty() ? pool : good;
		return from.get(random.nextInt(from.size()));
	}

	public List<Fragment> fragmentsOf(String tuneId) {
		return fragments.stream().filter(f -> f.tuneId().equals(tuneId))
				.sorted(Comparator.comparingInt(Fragment::start).thenComparingInt(Fragment::noteCount)).toList();
	}

	public Map<String, Object> describe(Tune t) {
		return Map.of("id", t.id(), "title", t.title(), "source", t.source(), "year", t.year(), "meter", t.meter(),
				"fragments", fragmentsOf(t.id()).size());
	}

	private static List<Unit> parseUnits(String text, String id) {
		List<Unit> units = new ArrayList<>();
		List<Note> cur = new ArrayList<>();
		List<Integer> bars = new ArrayList<>();
		int index = 0, start = 0;
		for (String tok : text.trim().split("\\s+")) {
			switch (tok) {
				case "|" -> bars.add(cur.size());
				case "/", "//" -> {
					if (!cur.isEmpty()) units.add(new Unit(start, cur, bars, tok.equals("//")));
					cur = new ArrayList<>();
					bars = new ArrayList<>();
					start = index;
				}
				default -> {
					try {
						cur.add(Note.parse(tok));
					}
					catch (RuntimeException e) {
						throw new IllegalArgumentException(id + ": bad token '" + tok + "'");
					}
					index++;
				}
			}
		}
		if (!cur.isEmpty()) units.add(new Unit(start, cur, bars, true));
		return units;
	}

	/** Every run of whole sub-phrases that makes a legal riff; a sub-phrase too long by itself is cut at a bar line. */
	private static List<Fragment> cut(String id, String title, List<Unit> units) {
		List<Fragment> out = new ArrayList<>();
		for (int i = 0; i < units.size(); i++) {
			List<Note> acc = new ArrayList<>();
			for (int j = i; j < units.size(); j++) {
				acc.addAll(units.get(j).notes());
				List<Note> trimmed = trimRests(acc);
				if (sounding(trimmed) > RiftRules.MAX_NOTES) {
					if (j == i) {    // one long sub-phrase: its opening bars
						Unit u = units.get(i);
						for (int k = u.barEnds().size() - 1; k >= 0; k--) {
							List<Note> head = trimRests(u.notes().subList(0, u.barEnds().get(k)));
							if (sounding(head) <= RiftRules.MAX_NOTES) {
								add(out, id, title, u.start(), head, false);
								break;
							}
						}
					}
					break;
				}
				add(out, id, title, units.get(i).start(), trimmed, units.get(j).phraseEnd());
			}
		}
		return out;
	}

	private static void add(List<Fragment> out, String id, String title, int start, List<Note> notes, boolean phraseEnd) {
		if (RiftRules.problem(notes) != null) return;
		out.add(new Fragment(id, title, start, List.copyOf(notes), MusicSense.evaluate(notes).score(), phraseEnd));
	}

	private static List<Note> trimRests(List<Note> notes) {
		int end = notes.size();
		while (end > 0 && notes.get(end - 1).isRest())
			end--;
		return new ArrayList<>(notes.subList(0, end));
	}

	private static int sounding(List<Note> notes) {
		return (int) notes.stream().filter(n -> !n.isRest()).count();
	}
}
