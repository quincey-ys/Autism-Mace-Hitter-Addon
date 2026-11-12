package com.autismdev.macehitter;

import autismclient.api.module.SimpleModule;
import net.minecraft.util.Mth;

import java.util.concurrent.ThreadLocalRandom;

/** Runtime settings and attack-time yaw offset for the Hitflick module. */
public final class HitflickController {
    private static volatile boolean enabled;
    private static volatile String direction = "Alternate";
    private static volatile float angle = 30.0F;
    private static volatile boolean requireSprint;
    private static volatile int cooldownMs = 100;
    private static volatile int chance = 100;

    private static long lastFlickNanos;
    private static boolean alternateRight;

    private HitflickController() {}

    public static void configure(SimpleModule module) {
        enabled = true;
        direction = module.getChoice("direction");
        angle = (float) module.getDouble("flick-angle");
        requireSprint = module.getBool("require-sprint");
        cooldownMs = module.getInt("cooldown-ms");
        chance = module.getInt("chance");
    }

    public static void disable() {
        enabled = false;
    }

    /**
     * Returns the temporary server-yaw offset for this attack, or null when
     * this attack should use the player's normal yaw.
     */
    public static Float getAttackYawOffset(boolean sprinting, float currentYaw) {
        if (!enabled || (requireSprint && !sprinting)) {
            return null;
        }

        long now = System.nanoTime();
        if (now - lastFlickNanos < cooldownMs * 1_000_000L) {
            return null;
        }
        if (chance <= 0 || ThreadLocalRandom.current().nextInt(100) >= chance) {
            return null;
        }

        float sign;
        switch (direction) {
            case "Left" -> sign = -1.0F;
            case "Right" -> sign = 1.0F;
            case "Alternate" -> {
                alternateRight = !alternateRight;
                sign = alternateRight ? 1.0F : -1.0F;
            }
            default -> sign = 1.0F;
        }

        lastFlickNanos = now;
        return Mth.wrapDegrees(sign * angle);
    }
}
