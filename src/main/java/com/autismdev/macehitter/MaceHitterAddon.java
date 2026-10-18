package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.SimpleModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

/**
 * KillAura-style module that slams targets with a mace.
 *
 * Design:
 *  - Finds the closest valid target inside reach every tick.
 *  - Swaps to the mace in the hotbar automatically.
 *  - Holds jump so the player is airborne, then attacks the moment they are
 *    falling: a falling mace hit is a smash attack with bonus damage and no
 *    fall damage. The attack cooldown is intentionally ignored - every tick
 *    the target is in reach (and attack conditions pass) it swings.
 *  - If the target is blocking with a shield it is treated as a priority
 *    "stun slam": mace smashes disable shields, so it attacks immediately
 *    even when not falling, breaking the block.
 *  - Rotations are applied so the swing is server-legal.
 */
public final class MaceHitterAddon extends SimpleAddon {
    public static final String ID = "autism-mace-hitter";

    private int delayCounter;
    private Entity currentTarget;

    public MaceHitterAddon() {
        super(ApiVersion.CURRENT, "com.autismdev.macehitter");
    }

    @Override
    protected void initialize() {
        SimpleModule maceHitter = new SimpleModule(
            "mace-hitter",
            "Mace Hitter",
            "KillAura that slams targets with mace smashes - no cooldown waiting"
        )
            .addDouble("range", "Range", 3.2, 1.0, 6.0, 0.1)
            .addInt("delay", "Delay (ticks)", 0, 0, 20, 1)
            .addBool("rotate", "Rotate", true)
            .addBool("auto-swap", "Auto Swap Mace", true)
            .addBool("auto-jump", "Auto Jump", true)
            .addBool("falling-only", "Smash Only While Falling", false)
            .addBool("shield-stun", "Stun Shield Blockers", true)
            .addBool("require-mace", "Require Mace In Hand", false)
            .addBool("ignore-walls", "Ignore Walls", false)
            .addChoice("target-mode", "Target Mode", "Players", "Players", "Everything")
            .addBool("teams-check", "Ignore Teammates", true)
            .onTick(this::onTick);

        registerModule(maceHitter);

        // Macro editor action: forces one slam attempt right now.
        registerSimpleAction(
            "slam-now",
            "Slam Now",
            "Instantly attacks the best mace target in reach",
            "mace",
            this::manualSlam
        );
    }

    private void onTick(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.isAlive()) {
            releaseJump(mc);
            currentTarget = null;
            return;
        }

        Entity target = findTarget(mc, module);
        currentTarget = target;

        if (target == null) {
            releaseJump(mc);
            return;
        }

        if (module.getBool("rotate")) {
            rotateTo(player, target);
        }

        boolean blocking = isBlocking(target);

        // Equip the mace. If auto-swap is off we just attack with whatever
        // is currently held; with require-mace on, no mace means no attack.
        boolean maceReady = ensureMace(player, module);
        if (module.getBool("require-mace") && !maceReady) {
            releaseJump(mc);
            return;
        }

        // Keep the player bouncing so smashes keep landing.
        if (module.getBool("auto-jump") && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false);
        }

        // Attack conditions. Shield blockers are always hit (stun slam),
        // otherwise respect the falling-only setting.
        boolean shouldAttack;
        if (blocking && module.getBool("shield-stun")) {
            shouldAttack = true; // break the shield instantly, no setup
        } else if (module.getBool("falling-only")) {
            shouldAttack = isFalling(player);
        } else {
            shouldAttack = true;
        }

        // Cooldown intentionally not consulted - the module name is the spec.
        if (shouldAttack && delayCounter <= 0) {
            mc.gameMode.attack(player, target);
            delayCounter = module.getInt("delay");
        }

        if (delayCounter > 0) {
            delayCounter--;
        }
    }

    private Entity findTarget(Minecraft mc, SimpleModule module) {
        LocalPlayer player = mc.player;
        double range = module.getDouble("range");
        boolean playersOnly = module.getChoice("target-mode").equalsIgnoreCase("Players");
        boolean ignoreWalls = module.getBool("ignore-walls");
        boolean teamsCheck = module.getBool("teams-check");

        Entity best = null;
        double bestDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == player || entity.isSpectator()) {
                continue;
            }
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (playersOnly && !(entity instanceof Player)) {
                continue;
            }
            if (entity instanceof Player other && teamsCheck && player.isAlliedTo(other)) {
                continue;
            }
            double dist = player.distanceTo(entity);
            if (dist > range || dist >= bestDist) {
                continue;
            }
            if (!ignoreWalls && !player.hasLineOfSight(entity)) {
                continue;
            }
            best = entity;
            bestDist = dist;
        }

        return best;
    }

    /**
     * Puts a mace in the main hand if one is in the hotbar.
     *
     * @return true if a mace is held after this call.
     */
    private boolean ensureMace(LocalPlayer player, SimpleModule module) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) {
            return true;
        }
        if (!module.getBool("auto-swap")) {
            return false;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof MaceItem) {
                player.getInventory().selected = i;
                player.getInventory().setChanged();
                return true;
            }
        }

        return false;
    }

    private boolean isBlocking(Entity target) {
        return target instanceof LivingEntity living && living.isUsingItem() && living.isBlocking();
    }

    private boolean isFalling(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        return !player.onGround() && motion.y < 0.0;
    }

    private void rotateTo(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double dy = target.getEyeY() - player.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(dy, horizontal) * 180.0 / Math.PI));

        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private void manualSlam(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        Entity target = currentTarget != null && currentTarget.isAlive()
            ? currentTarget
            : null;

        if (target != null && player.distanceTo(target) <= 6.0) {
            rotateTo(player, target);
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void releaseJump(Minecraft mc) {
        mc.options.keyJump.setDown(false);
    }

    public Entity getCurrentTarget() {
        return currentTarget;
    }
}
