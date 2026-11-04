package com.mckcode.rifts.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Checks the token the "Sign in with Google" button hands the browser: signed by Google, meant for this app
 * (our client ID), not expired, with a verified email. Google's public keys are fetched and cached by the
 * library. An interface so tests can stand in for Google.
 */
public interface GoogleSignIn {

	/** Who signed in, as Google vouches: email (lower case), display name, Google's account id. */
	record GoogleUser(String email, String name, String subject) {
	}

	/** The person this token proves, or empty if it doesn't check out. */
	Optional<GoogleUser> check(String credential);

	/** The real check against Google. */
	class Real implements GoogleSignIn {

		private static final Logger Log = LoggerFactory.getLogger(GoogleSignIn.class);

		private final GoogleIdTokenVerifier verifier;

		public Real(String clientId) {
			this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
					.setAudience(List.of(clientId))
					.build();
		}

		@Override
		public Optional<GoogleUser> check(String credential) {
			if (credential == null || credential.isBlank()) return Optional.empty();
			try {
				GoogleIdToken token = verifier.verify(credential);
				if (token == null) return Optional.empty();
				GoogleIdToken.Payload p = token.getPayload();
				if (p.getEmail() == null || !Boolean.TRUE.equals(p.getEmailVerified())) return Optional.empty();
				Object name = p.get("name");
				return Optional.of(new GoogleUser(p.getEmail().trim().toLowerCase(Locale.ROOT),
						name == null ? p.getEmail() : name.toString(), p.getSubject()));
			}
			catch (Exception e) {
				Log.warn("Google sign-in token not accepted: {}", e.toString());
				return Optional.empty();
			}
		}
	}
}
