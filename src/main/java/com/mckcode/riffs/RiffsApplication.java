package com.mckcode.riffs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RiffsApplication {

	/** The game's name, shown on the sign-in page and in alerts. */
	public static final String GAME_NAME = "Riff Rivals";

	public static void main(String[] args) {
		SpringApplication.run(RiffsApplication.class, args);
	}
}
