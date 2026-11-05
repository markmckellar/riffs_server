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
