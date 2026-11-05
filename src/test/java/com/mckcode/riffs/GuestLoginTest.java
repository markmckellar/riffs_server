package com.mckcode.riffs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/** The public-server setup: guest sign-in on, test sign-in off. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"riffs.auth.guest-login=true", "riffs.auth.dev-login=false",
		"riffs.data-dir=target/test-data/guest", "riffs.auth.session-key-file=target/test-data/guest/session.key" })
class GuestLoginTest {

	@Autowired
	TestRestTemplate http;

	@Test
	@SuppressWarnings("rawtypes")
	void twoGuestsWithTheSameNameAreDifferentPlayers() {
		ResponseEntity<Map> a = http.postForEntity("/api/auth/guest", Map.of("name", "Mark"), Map.class);
		ResponseEntity<Map> b = http.postForEntity("/api/auth/guest", Map.of("name", "Mark"), Map.class);
		assertEquals(200, a.getStatusCodeValue());
		assertTrue(((String) a.getBody().get("id")).startsWith("guest:"));
		assertNotEquals(a.getBody().get("id"), b.getBody().get("id"));

		// the cookie signs the guest in for the game API
		HttpHeaders h = new HttpHeaders();
		h.add("Cookie", a.getHeaders().getFirst("Set-Cookie").split(";")[0]);
		ResponseEntity<String> games = http.exchange("/api/games", HttpMethod.GET, new HttpEntity<>(h), String.class);
		assertEquals(200, games.getStatusCodeValue());
	}

	@Test
	@SuppressWarnings("rawtypes")
	void testSignInIsOff() {
		assertEquals(403, http.postForEntity("/api/auth/dev", Map.of("name", "Mark"), Map.class).getStatusCodeValue());
		assertEquals(401, http.getForEntity("/api/games", String.class).getStatusCodeValue());
	}

	@Test
	void thePageIsAlwaysCheckedAndBuiltFilesAreKept() {
		assertEquals("no-cache", http.getForEntity("/", String.class).getHeaders().getCacheControl());
		assertEquals("public, max-age=31536000, immutable",
				http.getForEntity("/assets/anything.js", String.class).getHeaders().getCacheControl());
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void signedInPlayersCarryTheirAttributes() {
		ResponseEntity<Map> me = http.postForEntity("/api/auth/guest", Map.of("name", "Tester"), Map.class);
		assertEquals(java.util.List.of(), me.getBody().get("attributes"));    // a new player has none
	}
}
