package com.example.vanillakfc.listeners;

import org.bukkit.World;

/**
 * Day and night by the clock alone, independent of the weather.
 *
 * <p>{@link World#isDayTime()} can't be used for this. In 26.3 it is
 * {@code skyDarken < 4} - a measure of how bright the sky is - and rain and
 * thunder dim the sky on top of the time of day. A thunderstorm at noon
 * reads as night; rain at dusk makes night arrive early, and the rain
 * stopping makes it "day" again for a moment before the real nightfall.
 * Anything keyed off the day/night edge saw those as genuine sunrises and
 * nightfalls, which is how a sleep streak got reset by rain clearing up.
 *
 * <p>The bounds are where a <em>clear</em> sky crosses that same brightness
 * threshold, so in fair weather this agrees exactly with what the game
 * itself calls night. They come from the overworld's {@code day} timeline:
 * the sky-light multiplier eases linearly from 1.0 at tick 11867 down to
 * 0.2667 at 13670, holds, then climbs back from 22330 to 1.0 at 24133.
 * Brightness drops out of "bright outside" once sky light is no longer above
 * 11 of 15 (a multiplier of 0.7333), which lands on ticks 12523 and 23478.
 */
final class WorldClock {

    static final long NIGHT_START = 12523L;
    static final long NIGHT_END = 23478L;

    private WorldClock() {
    }

    /**
     * Whether this world has a day/night cycle at all. The Nether and End run
     * on a fixed clock with no sky, so "nightfall" there is meaningless.
     */
    static boolean hasDayNightCycle(World world) {
        return world.hasSkyLight() && !world.isFixedTime();
    }

    static boolean isNight(World world) {
        long time = world.getTime();
        return time >= NIGHT_START && time < NIGHT_END;
    }
}
