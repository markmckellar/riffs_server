package com.mckcode.rifts.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

/**
 * What each player needs to hear about: a riff is waiting, the other player guessed, a round or game ended.
 * The client asks {@code GET /api/events?after=<seq>} every few seconds. Kept in memory only: after a restart the
 * client just reloads its games. (Later: Web Push so phones are told even when the page is closed.)
 */
@Service
public class EventService {

	public record Event(long seq, String type, String gameId, String text, long at) {
	}

	private static final int KEEP = 100;
	private final AtomicLong seq = new AtomicLong(System.currentTimeMillis());
	private final Map<String, Deque<Event>> byUser = new ConcurrentHashMap<>();

	public void publish(String userId, String type, String gameId, String text) {
		if (userId == null || userId.startsWith("bot:")) return;
		Deque<Event> q = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
		synchronized (q) {
			q.addLast(new Event(seq.incrementAndGet(), type, gameId, text, System.currentTimeMillis()));
			while (q.size() > KEEP)
				q.removeFirst();
		}
	}

	public List<Event> after(String userId, long after) {
		Deque<Event> q = byUser.get(userId);
		if (q == null) return List.of();
		synchronized (q) {
			List<Event> l = new ArrayList<>();
			for (Event e : q)
				if (e.seq() > after) l.add(e);
			return l;
		}
	}

	public long latest() {
		return seq.get();
	}
}
