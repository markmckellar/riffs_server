package com.mckcode.riffs.auth;

import java.io.IOException;
import java.util.Optional;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.mckcode.riffs.user.UserService;

/**
 * Every {@code /api/} call needs a signed-in player, except signing in itself and the health check. The player's
 * id is put on the request as {@link #USER_ID} for the controllers. Each call also marks the player as online.
 */
public class AuthFilter extends OncePerRequestFilter {

	public static final String USER_ID = "riffs.userId";

	private final Sessions sessions;
	private final UserService users;

	public AuthFilter(Sessions sessions, UserService users) {
		this.sessions = sessions;
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		String path = req.getRequestURI().substring(req.getContextPath().length());
		boolean open = path.startsWith("/api/auth/") || path.equals("/api/health") || "OPTIONS".equals(req.getMethod());
		Optional<String> id = sessions.read(req).filter(u -> users.get(u).isPresent());
		if (id.isPresent()) {
			req.setAttribute(USER_ID, id.get());
			users.seen(id.get());
		}
		else if (!open) {
			res.setStatus(401);
			res.setContentType("application/json");
			res.getWriter().write("{\"error\":\"sign in first\"}");
			return;
		}
		chain.doFilter(req, res);
	}
}
