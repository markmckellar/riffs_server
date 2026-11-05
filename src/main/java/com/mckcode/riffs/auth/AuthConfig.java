package com.mckcode.riffs.auth;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mckcode.riffs.user.UserService;

/**
 * Sign-in settings: {@code riffs.auth.google-client-id} (Google sign-in; the same kind of web client as cmo's
 * "Home Cams web", see the README), {@code riffs.auth.dev-login} (name-only sign-in for local testing: the same name is the same account),
 * {@code riffs.auth.guest-login} (type a name, get a new account of your own; safe on a public server),
 * {@code riffs.auth.session-key-file}, {@code riffs.auth.session-days}.
 */
@Configuration
public class AuthConfig {

	private static final Logger Log = LoggerFactory.getLogger(AuthConfig.class);

	private final String googleClientId;
	private final boolean devLogin;
	private final boolean guestLogin;
	private final String sessionKeyFile;
	private final int sessionDays;

	public AuthConfig(@Value("${riffs.auth.google-client-id:}") String googleClientId,
			@Value("${riffs.auth.dev-login:false}") boolean devLogin,
			@Value("${riffs.auth.guest-login:false}") boolean guestLogin,
			@Value("${riffs.auth.session-key-file:local/session.key}") String sessionKeyFile,
			@Value("${riffs.auth.session-days:60}") int sessionDays) {
		this.googleClientId = googleClientId.trim();
		this.devLogin = devLogin;
		this.guestLogin = guestLogin;
		this.sessionKeyFile = sessionKeyFile;
		this.sessionDays = sessionDays;
		if (devLogin) Log.warn("riffs.auth.dev-login=true: anyone can sign in by typing a name. Local testing only.");
		if (this.googleClientId.isEmpty()) Log.warn("riffs.auth.google-client-id is not set: Google sign-in is off");
	}

	public String googleClientId() {
		return googleClientId;
	}

	public boolean devLogin() {
		return devLogin;
	}

	public boolean guestLogin() {
		return guestLogin;
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
		reg.setName("riffsAuthFilter");
		return reg;
	}
}
