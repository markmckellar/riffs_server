package com.mckcode.rifts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RiftsApplication {

	/** The game's working name, shown in alerts and logs. It may change; see research/naming.md in the goals repo. */
	public static final String GAME_NAME = "Riff Rivals";

	public static void main(String[] args) {
		SpringApplication.run(RiftsApplication.class, args);
	}
}
