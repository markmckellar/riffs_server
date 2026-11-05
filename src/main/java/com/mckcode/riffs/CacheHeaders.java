package com.mckcode.riffs;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caching for the web client. The page itself (index.html) must be checked every time, or phones keep showing an old
 * version after a deploy (Chrome guesses how long to reuse a page that says nothing). The built files in /assets/
 * have a fingerprint in their names that changes with every build, so they can be kept for a year.
 */
@Component
public class CacheHeaders extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		String path = req.getRequestURI();
		if (path.startsWith("/assets/")) res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
		else if (path.equals("/") || path.endsWith(".html")) res.setHeader("Cache-Control", "no-cache");
		chain.doFilter(req, res);
	}
}
