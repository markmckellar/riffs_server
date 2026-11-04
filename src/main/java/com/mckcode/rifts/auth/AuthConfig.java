package com.mckcode.rifts.auth;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mckcode.rifts.user.UserService;

/**
 * Sign-in settings: {@code rifts.auth.google-client-id} (Google sign-in; the same kind of web client as cmo's
 * "Home Cams web", see the README), {@code rifts.auth.dev-login} (name-only sign-in for local testing),
 * {@code rifts.auth.session-key-file}, {@code rifts.auth.session-days}.
 */
@Configuration
public class AuthConfig {

	private static final Logger Log = LoggerFactory.getLogger(AuthConfig.class);

	private final String googleClientId;
	private final boolean devLogin;
	private final String sessionKeyFile;
	private final int sessionDays;

	public AuthConfig(@Value("${rifts.auth.google-client-id:}") String googleClientId,
			@Value("${rifts.auth.dev-login:false}") boolean devLogin,
			@Value("${rifts.auth.session-key-file:local/session.key}") String sessionKeyFile,
			@Value("${rifts.auth.session-days:60}") int sessionDays) {
		this.googleClientId = googleClientId.trim();
		this.devLogin = devLogin;
		this.sessionKeyFile = sessionKeyFile;
		this.sessionDays = sessionDays;
		if (devLogin) Log.warn("rifts.auth.dev-login=true: anyone can sign in by typing a name. Local testing only.");
		if (this.googleClientId.isEmpty()) Log.warn("rifts.auth.google-client-id is not set: Google sign-in is off");
	}

	public String googleClientId() {
		return googleClientId;
	}

	public boolean devLogin() {
		return devLogin;
	}

	@Bean
	public Sessions sessions() {
		return new Sessions(Paths.get(sessionKeyFile), Duration.ofDays(sessionDays));
	}

	@Bean
	public GoogleSignIn googleSignIn() {
		if (googleClientId.isEmpty()) return credential -> Optional.empty();
		return new GoogleSignIn.Real(googleClientId);
	}

	@Bean
	public FilterRegistrationBean<AuthFilter> authFilter(UserService users) {
		FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>(new AuthFilter(sessions(), users));
		reg.addUrlPatterns("/api/*");
		reg.setName("riftsAuthFilter");
		return reg;
	}
}
