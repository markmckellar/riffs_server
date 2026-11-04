package com.mckcode.rifts.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The sign-in cookie (same design as cmo_server): the player's id and until when, signed with a secret only this
 * server has, so it can't be forged. Nothing is stored server-side. HttpOnly, SameSite=Lax, Secure over HTTPS.
 */
public class Sessions {

	public static final String COOKIE = "rifts_session";
	private static final ObjectMapper JSON = new ObjectMapper();

	private final byte[] key;
	private final Duration lifetime;

	public Sessions(Path keyFile, Duration lifetime) {
		this.key = loadOrCreateKey(keyFile);
		this.lifetime = lifetime;
	}

	public Sessions(byte[] key, Duration lifetime) {
		this.key = key;
		this.lifetime = lifetime;
	}

	private static byte[] loadOrCreateKey(Path file) {
		try {
			if (Files.exists(file)) {
				byte[] k = Base64.getDecoder().decode(Files.readString(file).trim());
				if (k.length >= 32) return k;
				throw new IllegalStateException(file + " holds a key shorter than 32 bytes");
			}
			byte[] k = new byte[32];
			new SecureRandom().nextBytes(k);
			Files.createDirectories(file.toAbsolutePath().getParent());
			Files.writeString(file, Base64.getEncoder().encodeToString(k) + "\n");
			try {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
			}
			catch (UnsupportedOperationException ignored) {
			}
			return k;
		}
		catch (IOException e) {
			throw new IllegalStateException("session key file " + file + ": " + e, e);
		}
	}

	public String create(String userId) {
		try {
			String body = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
					Map.of("u", userId, "x", Instant.now().plus(lifetime).getEpochSecond())));
			return body + "." + sign(body);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** The player id this cookie value proves, if genuine and not expired. */
	public Optional<String> read(String value) {
		if (value == null) return Optional.empty();
		int dot = value.indexOf('.');
		if (dot <= 0) return Optional.empty();
		String body = value.substring(0, dot);
		if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),
				value.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return Optional.empty();
		try {
			Map<?, ?> m = JSON.readValue(Base64.getUrlDecoder().decode(body), Map.class);
			if (Instant.now().getEpochSecond() > ((Number) m.get("x")).longValue()) return Optional.empty();
			return Optional.of((String) m.get("u"));
		}
		catch (Exception e) {
			return Optional.empty();
		}
	}

	public Optional<String> read(HttpServletRequest request) {
		if (request.getCookies() == null) return Optional.empty();
		for (Cookie c : request.getCookies())
			if (COOKIE.equals(c.getName())) return read(c.getValue());
		return Optional.empty();
	}

	public void set(HttpServletRequest request, HttpServletResponse response, String value, boolean clear) {
		StringBuilder h = new StringBuilder(COOKIE).append('=').append(clear ? "" : value)
				.append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=").append(clear ? 0 : lifetime.getSeconds());
		if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) h.append("; Secure");
		response.addHeader("Set-Cookie", h.toString());
	}

	private String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
