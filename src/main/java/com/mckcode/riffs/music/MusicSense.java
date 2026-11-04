package com.mckcode.riffs.music;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores how much a riff "makes musical sense", 0–100, with the reasons. The rules and their points are explained
 * in research/musical_sense_heuristic.md (goals repo); keep the two in step.
 */
public final class MusicSense {

	private static final int[] MAJOR = { 0, 2, 4, 5, 7, 9, 11 };
	/** Natural minor plus the raised 7th (harmonic minor's leading tone), both common in simple minor tunes. */
	private static final int[] MINOR = { 0, 2, 3, 5, 7, 8, 10, 11 };
	private static final String[] PC = { "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B" };

	/** One rule's result: its points (negative for a penalty) and a sentence for the player. */
	public record Reason(String rule, int points, String text) {
	}

	/** The score, its tier, the key it seems to be in, and why. */
	public record Evaluation(int score, String tier, String key, List<Reason> reasons) {
	}

	public static final String TIER_NOISE = "noise", TIER_OK = "ok", TIER_SILVER = "musical", TIER_GOLD = "catchy";

	private MusicSense() {
	}

	public static String tier(int score) {
		if (score >= 80) return TIER_GOLD;
		if (score >= 60) return TIER_SILVER;
		if (score >= 40) return TIER_OK;
		return TIER_NOISE;
	}

	public static Evaluation evaluate(List<Note> notes) {
		List<Reason> reasons = new ArrayList<>();
		List<Integer> p = new ArrayList<>();
		List<Double> len = new ArrayList<>();
		double totalBeats = 0;
		for (Note n : notes) {
			totalBeats += n.b();
			if (!n.isRest()) {
				p.add(n.m());
				len.add(n.b());
			}
		}
		if (p.size() < 2) return new Evaluation(0, TIER_NOISE, "", List.of(new Reason("notes", 0, "Needs at least two notes.")));

		List<Integer> iv = new ArrayList<>();
		for (int i = 1; i < p.size(); i++)
			iv.add(p.get(i) - p.get(i - 1));

		// 1. key fit, and 2. cadence
		int bestTonic = 0, bestCount = -1;
		boolean bestMinor = false;
		int firstPc = Math.floorMod(p.get(0), 12), lastPc = Math.floorMod(p.get(p.size() - 1), 12);
		for (int minor = 0; minor < 2; minor++)
			for (int tonic = 0; tonic < 12; tonic++) {
				int[] scale = minor == 1 ? MINOR : MAJOR;
				int count = 0;
				for (int m : p)
					if (contains(scale, Math.floorMod(m - tonic, 12))) count++;
				boolean better = count > bestCount
						|| (count == bestCount && tieRank(tonic, lastPc, firstPc) > tieRank(bestTonic, lastPc, firstPc));
				if (better) {
					bestCount = count;
					bestTonic = tonic;
					bestMinor = minor == 1;
				}
			}
		double share = bestCount / (double) p.size();
		String key = PC[bestTonic] + (bestMinor ? " minor" : " major");
		long distinctPcs = p.stream().map(m -> Math.floorMod(m, 12)).distinct().count();
		// two or fewer different notes fit almost any key, so that proves little
		int keyPts = share < 0.75 ? 0 : (int) Math.round(25 * share * Math.min(1.0, distinctPcs / 3.0));
		reasons.add(new Reason("key", keyPts, share >= 0.999 ? "Every note fits " + key + "."
				: Math.round(share * 100) + "% of the notes fit " + key + "."));

		int degree = Math.floorMod(lastPc - bestTonic, 12);
		int cadPts = degree == 0 ? 15 : (degree == 7 || degree == 3 || degree == 4) ? 8 : 0;
		reasons.add(new Reason("cadence", cadPts, degree == 0 ? "Ends on the home note (" + PC[bestTonic] + "), so it sounds finished."
				: cadPts > 0 ? "Ends on a note of the home chord." : "Ends away from home, so it sounds unfinished."));

		// 3. steps
		long steps = iv.stream().filter(i -> Math.abs(i) <= 2).count();
		double stepShare = steps / (double) iv.size();
		int stepPts = stepShare >= 0.5 && stepShare <= 0.9 ? 15 : stepShare > 0.9 ? 10
				: stepShare < 0.25 ? -5 : (int) Math.round(15 * stepShare / 0.5);
		reasons.add(new Reason("steps", stepPts, Math.round(stepShare * 100) + "% of moves are small steps"
				+ (stepPts == 15 ? " (a good mix of steps and leaps)." : stepShare > 0.9 ? " (a leap or two adds interest)." : " (lots of jumps).")));

		// 4. range
		int span = p.stream().max(Integer::compare).get() - p.stream().min(Integer::compare).get();
		int rangePts = span <= 9 ? 10 : span <= 12 ? 7 : span <= 16 ? 3 : 0;
		reasons.add(new Reason("range", rangePts, rangePts >= 7 ? "Easy to sing (spans " + span + " semitones)."
				: "Wide range (" + span + " semitones)."));

		// 5. contour
		int changes = 0, lastSign = 0;
		for (int i : iv) {
			int s = Integer.signum(i);
			if (s != 0) {
				if (lastSign != 0 && s != lastSign) changes++;
				lastSign = s;
			}
		}
		int contourPts = changes > 0 ? 8 : 0;
		reasons.add(new Reason("contour", contourPts, changes > 0 ? "The line rises and falls." : "Only goes one way, more a scale than a tune."));

		// 6. gap-fill: a leap is followed (at its next real move) by a move the other way
		int leaps = 0, filled = 0;
		for (int k = 0; k < iv.size(); k++) {
			if (Math.abs(iv.get(k)) < 5) continue;
			int next = 0;
			for (int j = k + 1; j < iv.size() && next == 0; j++)
				next = iv.get(j);
			if (next == 0 && k == iv.size() - 1) continue;    // the last move: nothing to answer it
			leaps++;
			if (next != 0 && Integer.signum(next) != Integer.signum(iv.get(k))) filled++;
		}
		int gapPts = leaps == 0 ? 7 : (int) Math.round(7.0 * filled / leaps);
		reasons.add(new Reason("gapfill", gapPts, leaps == 0 ? "No big leaps to recover from."
				: filled + " of " + leaps + " leaps turn back afterwards."));

		// 7. repetition: a 2-move pattern comes back later
		int repPts = 0;
		for (int a = 0; a + 1 < iv.size() && repPts < 8; a++)
			for (int b = a + 2; b + 1 < iv.size(); b++) {
				if (iv.get(a).equals(iv.get(b)) && iv.get(a + 1).equals(iv.get(b + 1))) {
					repPts = 8;
					break;
				}
				if (Integer.signum(iv.get(a)) == Integer.signum(iv.get(b)) && Integer.signum(iv.get(a + 1)) == Integer.signum(iv.get(b + 1))
						&& (iv.get(a) != 0 || iv.get(a + 1) != 0)) repPts = Math.max(repPts, 4);
			}
		reasons.add(new Reason("repetition", repPts, repPts == 8 ? "A little pattern repeats, which makes it catchy."
				: repPts == 4 ? "A shape comes back." : "No repeated pattern."));

		// 8. rhythm fits whole bars
		boolean bars = wholeMultiple(totalBeats, 4) || wholeMultiple(totalBeats, 3) || wholeMultiple(totalBeats, 2);
		reasons.add(new Reason("bars", bars ? 6 : 0, bars ? "The rhythm fills whole bars." : "The rhythm doesn't fill whole bars."));

		// 9. strong ending
		double mean = len.stream().mapToDouble(Double::doubleValue).average().orElse(1);
		boolean strong = len.get(len.size() - 1) >= mean - 1e-9;
		reasons.add(new Reason("ending", strong ? 6 : 0, strong ? "Ends on a long note." : "Ends on a short note."));

		// penalties
		int tritones = (int) iv.stream().filter(i -> Math.abs(i) == 6).count();
		if (tritones > 0) reasons.add(new Reason("tritone", -4 * tritones, "Tritone jump (an awkward interval)."));
		int huge = (int) iv.stream().filter(i -> Math.abs(i) > 12).count();
		if (huge > 0) reasons.add(new Reason("bigleap", -6 * huge, "Jumps of more than an octave."));
		int run = 0, worstRun = 0;
		for (int i : iv) {
			run = Math.abs(i) == 1 ? run + 1 : 0;
			worstRun = Math.max(worstRun, run);
		}
		if (worstRun >= 3) reasons.add(new Reason("chromatic", -5, "A run of half steps (sounds chromatic)."));
		if (!notes.isEmpty() && notes.get(0).isRest()) reasons.add(new Reason("leadrest", -4, "Starts with a rest."));
		long kinds = len.stream().map(d -> Math.round(d * 100)).distinct().count();
		if (kinds > 3) reasons.add(new Reason("rhythmmix", -3, "Many different note lengths."));
		if (span == 0) reasons.add(new Reason("onepitch", 0, "Only one pitch: a rhythm, not a tune."));
		int same = 1, worstSame = 1;
		for (int i : iv) {
			same = i == 0 ? same + 1 : 1;
			worstSame = Math.max(worstSame, same);
		}
		if (worstSame >= 4 && span > 0) reasons.add(new Reason("samenote", -5, "The same note " + worstSame + " times in a row."));

		int score = Math.max(0, Math.min(100, reasons.stream().mapToInt(Reason::points).sum()));
		if (span == 0) score = Math.min(score, 15);
		return new Evaluation(score, tier(score), key, reasons);
	}

	/**
	 * Among keys that fit equally, prefer one where the first and last notes are the home note (2 each) or in the
	 * home chord (1 each). Tunes usually start on the home chord, so "C C G G A A G" is C major ending on the 5th,
	 * not G major ending at home. (Judging only by the last note would hand every riff the cadence points.)
	 */
	private static int tieRank(int tonic, int lastPc, int firstPc) {
		return chordRank(tonic, firstPc) + chordRank(tonic, lastPc);
	}

	private static int chordRank(int tonic, int pc) {
		int d = Math.floorMod(pc - tonic, 12);
		return d == 0 ? 3 : (d == 7 || d == 4 || d == 3) ? 1 : 0;
	}

	private static boolean contains(int[] a, int v) {
		for (int x : a)
			if (x == v) return true;
		return false;
	}

	private static boolean wholeMultiple(double beats, int bar) {
		double q = beats / bar;
		return beats > 0 && Math.abs(q - Math.round(q)) < 1e-6;
	}
}
