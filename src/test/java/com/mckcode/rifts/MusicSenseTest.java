package com.mckcode.rifts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mckcode.rifts.library.TuneLibrary;
import com.mckcode.rifts.music.MusicSense;
import com.mckcode.rifts.music.Note;

class MusicSenseTest {

	static List<Note> notes(String s) {
		return Arrays.stream(s.trim().split("\\s+")).map(Note::parse).toList();
	}

	static int score(String s) {
		return MusicSense.evaluate(notes(s)).score();
	}

	@Test
	void familiarTunesScoreMusical() {
		assertTrue(score("C4:1 C4:1 G4:1 G4:1 A4:1 A4:1 G4:2") >= 60, "twinkle");
		assertTrue(score("E4:1 E4:1 F4:1 G4:1 G4:1 F4:1 E4:1 D4:1") >= 60, "ode to joy");
		assertTrue(score("G4:0.5 G4:0.5 G4:0.5 D#4:2 F4:0.5 F4:0.5 F4:0.5 D4:2") >= 60, "beethoven 5");
		assertTrue(score("C4:1 D4:1 E4:1 C4:1 C4:1 D4:1 E4:1 C4:1") >= 60, "frere jacques");
	}

	@Test
	void randomLeapsScoreAsNoise() {
		assertTrue(score("C4:1 B4:1 D#4:1 F#5:1 C#4:1 D5:1") < 40);
		assertTrue(score("C4:0.25 F#4:1.5 C5:0.75 F#4:0.5 G5:3") < 40);
	}

	@Test
	void onePitchIsNotATune() {
		assertTrue(score("C4:1 C4:1 C4:1 C4:1") < 40);
	}

	@Test
	void mostLibraryFragmentsAreMusical() {
		List<TuneLibrary.Fragment> f = new TuneLibrary().fragments();
		long musical = f.stream().filter(x -> x.score() >= 60).count();
		assertTrue(f.size() > 40, "fragments: " + f.size());
		assertTrue(musical >= f.size() * 0.75, musical + " of " + f.size() + " score 60+");
	}
}
