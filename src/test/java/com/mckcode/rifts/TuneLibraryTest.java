package com.mckcode.rifts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mckcode.rifts.library.TuneLibrary;
import com.mckcode.rifts.music.RiftRules;

class TuneLibraryTest {

	@Test
	void everyTuneGivesLegalFragments() {
		TuneLibrary lib = new TuneLibrary();
		for (TuneLibrary.Tune t : lib.tunes())
			assertTrue(lib.fragmentsOf(t.id()).size() > 0, t.id() + " has no riff-sized fragment");
		for (TuneLibrary.Fragment f : lib.fragments())
			assertNull(RiftRules.problem(f.notes()), f.tuneId() + "@" + f.start());
	}

	@Test
	void twinkleIsCutAtPhraseBoundaries() {
		TuneLibrary.Fragment first = new TuneLibrary().fragmentsOf("twinkle").get(0);
		assertEquals(0, first.start());
		assertEquals(7, first.noteCount());    // "twinkle twinkle little star", not into "how I wonder"
	}
}
