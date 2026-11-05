package com.mckcode.riffs.user;

/** A player. Ids: "g:<google subject>" for Google sign-ins, "dev:<name>" for test sign-ins, "bot:<name>" for bots. */
public class User {
	public String id;
	public String name;
	public String email;
	public boolean bot;
	public long createdAt;
	public int wins;
	public int losses;
	public int points;
	/** Learner mode: ear-training helpers when guessing (research/ear_training.md). On for new players. */
	public boolean learner;
	/**
	 * Tags for this player, set on the server only (edit users.json, or add an admin tool later), e.g. "developer"
	 * (sees the hidden riff faintly when guessing, for testing). See {@link #DEVELOPER}.
	 */
	public java.util.List<String> attributes = new java.util.ArrayList<>();

	public static final String DEVELOPER = "developer";

	public boolean has(String attribute) {
		return attributes != null && attributes.contains(attribute);
	}

	public User() {
	}

	public User(String id, String name, String email, boolean bot) {
		this.id = id;
		this.name = name;
		this.email = email;
		this.bot = bot;
		this.learner = !bot;
		this.createdAt = System.currentTimeMillis();
	}
}
