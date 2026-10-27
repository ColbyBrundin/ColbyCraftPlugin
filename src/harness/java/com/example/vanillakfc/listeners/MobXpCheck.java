package com.example.vanillakfc.listeners;

import java.util.Locale;

/**
 * Checks that {@link MobXpListener}'s boost lands on its intended amounts -
 * 1.5x, times Wisdom's 1.15 / 1.30 / 1.50 - and that its rounding keeps that
 * exactly on average while only ever landing on the two whole numbers either
 * side of the exact amount.
 *
 * <p>Run with {@code ./gradlew mobXpCheck}. Exits non-zero on failure.
 *
 * <p>This calls the production code, but states the targets itself (from the
 * design, in {@link #INTENDED}) rather than reading the listener's constants,
 * so a wrong multiplier fails here instead of being checked against itself.
 * What it can't reach is the {@code EntityDeathEvent} wiring and the Wisdom
 * lookup on the killer's weapon - that the event's amount is what drops was
 * confirmed separately against the server code.
 */
public final class MobXpCheck {

    // Total multiplier by Wisdom level, as designed.
    private static final double[] INTENDED = {1.5, 1.5 * 1.15, 1.5 * 1.30, 1.5 * 1.50};
    private static final int ROLLS_PER_CASE = 400_000;
    // Standard errors a sample mean may sit from its target - a spurious
    // failure at 5 is about one in a million per case.
    private static final double TOLERANCE_SE = 5.0;

    public static void main(String[] args) {
        // Vanilla's common drops - 1-5 for most mobs, 10 for blazes and
        // guardians, 50 for the Wither - plus the dragon's 500 and 12000, and
        // 40, where 1.725x is exactly 69 and naive doubles land on 68.999...
        int[] vanillaXp = {1, 2, 3, 4, 5, 7, 10, 12, 20, 40, 50, 500, 12000};
        boolean ok = true;
        for (int level = 0; level < INTENDED.length; level++) {
            for (int xp : vanillaXp) {
                ok &= check(xp, level);
            }
        }
        // Above III counts as III.
        ok &= MobXpListener.boostedXp(20, 5) == MobXpListener.boostedXp(20, 3);
        System.out.println(ok ? "All cases pass." : "FAILED");
        if (!ok) System.exit(1);
    }

    private static boolean check(int xp, int level) {
        double exact = MobXpListener.boostedXp(xp, level);
        // Rounded to 6 places so the design's own double arithmetic doesn't
        // put an exactly-whole target a hair below the whole number.
        double target = Math.round(xp * INTENDED[level] * 1e6) / 1e6;
        int low = (int) Math.floor(target), high = (int) Math.ceil(target);

        double sum = 0, sumSq = 0;
        boolean outOfRange = false;
        for (int i = 0; i < ROLLS_PER_CASE; i++) {
            int dropped = MobXpListener.roundProbabilistically(exact);
            if (dropped != low && dropped != high) outOfRange = true;
            sum += dropped;
            sumSq += (double) dropped * dropped;
        }
        double mean = sum / ROLLS_PER_CASE;
        double variance = Math.max(0, sumSq / ROLLS_PER_CASE - mean * mean);
        double se = Math.sqrt(variance / ROLLS_PER_CASE);
        // A whole-number target has no spread at all, so it must be exact.
        boolean meanOk = se == 0 ? mean == target : Math.abs(mean - target) <= TOLERANCE_SE * se;
        boolean pass = meanOk && !outOfRange;
        if (!pass || xp == 5 || xp == 40) {
            System.out.println(String.format(Locale.ROOT, "%s  Wisdom %d, vanilla %5d -> mean %10.4f (target %9.3f, only %d/%d: %s)",
                    pass ? "PASS" : "FAIL", level, xp, mean, target, low, high, outOfRange ? "no" : "yes"));
        }
        return pass;
    }
}
