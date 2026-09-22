package com.example.vanillakfc.listeners;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Checks that {@link UnbreakingListener}'s damage scaling puts the expected
 * durability loss per use on its intended curve.
 *
 * <p>Run with {@code ./gradlew unbreakingCheck}. Exits non-zero on failure.
 *
 * <p>This calls the production methods rather than reimplementing them, and
 * deliberately keeps its own independent copies of the two curves involved -
 * {@link #intendedChanceToDamage} and {@link #vanillaChanceToDamageOracle} -
 * written from the design and from {@code unbreaking.json} respectively. An
 * earlier version of this file took both from the production code, which made
 * the end-to-end comparison self-fulfilling: a wrong curve was used to
 * generate the input, to scale it, and to state the target, so it cancelled
 * out and the case passed while the listener was broken.
 *
 * <p>What this cannot reach is the part that needs a running server: the
 * {@code PlayerItemDamageEvent} plumbing in {@code onItemDamage}, and the
 * {@code #minecraft:enchantable/armor} tag lookup that decides which of the
 * two vanilla curves an item is on - the {@code armor} flag is passed in here
 * instead. So this verifies the arithmetic and the rounding, not the wiring.
 *
 * <p>There is no JUnit on the classpath (the build has no test dependencies),
 * hence a main method and hand-rolled assertions.
 */
public final class UnbreakingCurveCheck {

    private static final int USES_PER_CASE = 1_000_000;

    /**
     * How many standard errors a Monte Carlo mean may sit from its target.
     * Derived per case from the sample's own variance rather than fixed, so
     * the noisier cases (more damage points, so more spread) get the room they
     * need and the quiet ones stay tight. At 5 SE a spurious failure is about
     * a one-in-a-million event per case; an earlier fixed 0.006 was under 3 SE
     * for some cases and did flake.
     */
    private static final double SIGMA_ALLOWANCE = 5.0;

    private static int failures = 0;

    public static void main(String[] args) {
        checkCurvesAgainstOracle();
        checkMultipliers();
        checkRoundingKeepsItsAverage();
        checkCurve();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("All checks passed.");
    }

    /** This plugin's intended curve, stated independently of the production code. */
    private static double intendedChanceToDamage(int level) {
        return 3.0 / (3.0 + level);
    }

    /**
     * Vanilla's curve, transcribed independently from
     * {@code data/minecraft/enchantment/unbreaking.json}: a
     * {@code remove_binomial} striking each damage point with chance
     * numerator/denominator, where for armor that is (2 + 2(L-1)) / (10 +
     * 5(L-1)) and for everything else (1 + (L-1)) / (2 + (L-1)). Returned as
     * the chance a point survives.
     */
    private static double vanillaChanceToDamageOracle(boolean armor, int level) {
        double numerator = armor ? 2.0 + 2.0 * (level - 1) : 1.0 + (level - 1);
        double denominator = armor ? 10.0 + 5.0 * (level - 1) : 2.0 + (level - 1);
        return 1.0 - numerator / denominator;
    }

    /** The production curves must agree with the oracles above. */
    private static void checkCurvesAgainstOracle() {
        System.out.println("-- curves match their independent transcription --");
        for (int level = 1; level <= 3; level++) {
            expectExact("intended  lvl " + level,
                    UnbreakingListener.chanceToDamage(level), intendedChanceToDamage(level));
        }
        for (boolean armor : new boolean[] {false, true}) {
            for (int level = 1; level <= 3; level++) {
                expectExact((armor ? "vanilla armor lvl " : "vanilla tool  lvl ") + level,
                        UnbreakingListener.vanillaChanceToDamage(armor, level),
                        vanillaChanceToDamageOracle(armor, level));
            }
        }
    }

    /**
     * The multipliers are a closed form, so they get exact expectations rather
     * than a simulation. These are the numbers the design rests on, and the
     * ones quoted in GAMEPLAY_CHANGES.txt.
     */
    private static void checkMultipliers() {
        System.out.println();
        System.out.println("-- multipliers (exact) --");
        expectExact("tool  I  ", UnbreakingListener.damageMultiplier(false, 1), 1.5);
        expectExact("tool  II ", UnbreakingListener.damageMultiplier(false, 2), 1.8);
        expectExact("tool  III", UnbreakingListener.damageMultiplier(false, 3), 2.0);
        expectExact("armor I  ", UnbreakingListener.damageMultiplier(true, 1), 0.9375);
        expectExact("armor II ", UnbreakingListener.damageMultiplier(true, 2), 9.0 / 11.0);
        expectExact("armor III", UnbreakingListener.damageMultiplier(true, 3), 5.0 / 7.0);
    }

    /** Probabilistic rounding has to be unbiased, or it shifts the curve. */
    private static void checkRoundingKeepsItsAverage() {
        System.out.println();
        System.out.println("-- rounding is unbiased --");
        for (double value : new double[] {1.5, 1.8, 0.9375, 0.7143}) {
            double sum = 0.0;
            double sumSquares = 0.0;
            for (int i = 0; i < USES_PER_CASE; i++) {
                int rounded = UnbreakingListener.roundProbabilistically(value);
                sum += rounded;
                sumSquares += (double) rounded * rounded;
            }
            report(String.format(Locale.ROOT, "round(%.4f)", value), sum, sumSquares, value);
        }
    }

    /**
     * The end-to-end rate. Reproduces the three stages a real use goes
     * through: vanilla's remove_binomial over the damage points, the event
     * being skipped entirely when nothing survives it (confirmed in the server
     * bytecode - {@code iload 7 / ifle 95} jumps past the event when the
     * post-Unbreaking damage is <= 0), then the listener's own scaling.
     *
     * <p>Stages 1 and the target come from the oracles; only stage 2 is the
     * code under test.
     */
    private static void checkCurve() {
        for (boolean datapack : new boolean[] {true, false}) {
            System.out.println();
            System.out.println(datapack
                    ? "-- expected durability loss per use, DIRECT (datapack on) --"
                    : "-- expected durability loss per use, SCALED (datapack off, fallback) --");
            System.out.printf(Locale.ROOT, "%-18s %-5s %-4s %-10s %-10s %-10s %-8s%n",
                    "case", "lvl", "pts", "target", "measured", "±5se", "sd/use");

            runCase("tool", false, 1, datapack);
            runCase("sword block-break", false, 2, datapack);
            runCase("armor", true, 1, datapack);
            runCase("armor big hit", true, 3, datapack);
        }
    }

    private static void runCase(String label, boolean armor, int points, boolean datapackActive) {
        for (int level = 1; level <= 3; level++) {
            double survives = vanillaChanceToDamageOracle(armor, level);
            double sum = 0.0;
            double sumSquares = 0.0;

            for (int use = 0; use < USES_PER_CASE; use++) {
                int applied;
                if (datapackActive) {
                    // The datapack removed vanilla's reduction, so every use
                    // reaches the listener with its damage intact.
                    applied = UnbreakingListener.adjustedDamage(points, armor, level, true);
                } else {
                    // Stage 1: vanilla strikes each point independently.
                    int survived = 0;
                    for (int point = 0; point < points; point++) {
                        if (ThreadLocalRandom.current().nextDouble() < survives) {
                            survived++;
                        }
                    }
                    // Stage 2: no surviving damage means no event at all, so
                    // the listener never sees this use and it costs nothing.
                    applied = survived == 0
                            ? 0
                            : UnbreakingListener.adjustedDamage(survived, armor, level, false);
                }
                sum += applied;
                sumSquares += (double) applied * applied;
            }

            String name = String.format(Locale.ROOT, "%-18s %-5d %-4d", label, level, points);
            report(name, sum, sumSquares, points * intendedChanceToDamage(level));
        }
    }

    /**
     * Compares a sample mean against its target using the sample's own
     * standard error, so the allowance scales with how noisy the case
     * actually is instead of being guessed at.
     */
    private static void report(String label, double sum, double sumSquares, double target) {
        double mean = sum / USES_PER_CASE;
        double variance = Math.max(0.0, sumSquares / USES_PER_CASE - mean * mean);
        double standardError = Math.sqrt(variance / USES_PER_CASE);
        double allowance = SIGMA_ALLOWANCE * standardError;

        boolean ok = Math.abs(mean - target) <= allowance;
        if (!ok) failures++;
        // Per-use standard deviation is printed alongside because it, not the
        // mean, is what the datapack path exists to improve - both paths hit
        // the same target, and the spread is the whole difference.
        System.out.printf(Locale.ROOT, "%-18s %-10.4f %-10.4f %-10.4f %-8.4f %s%n",
                label, target, mean, allowance, Math.sqrt(variance), ok ? "ok" : "FAILED");
    }

    private static void expectExact(String label, double actual, double expected) {
        boolean ok = Math.abs(actual - expected) < 1e-12;
        if (!ok) failures++;
        System.out.printf(Locale.ROOT, "%-22s %-10.6f expected %-10.6f %s%n",
                label, actual, expected, ok ? "ok" : "FAILED");
    }

    private UnbreakingCurveCheck() {
    }
}
