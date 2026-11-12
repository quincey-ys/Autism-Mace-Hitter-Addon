package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.SimpleModule;
import com.autismdev.macehitter.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

/** QYS Addon: situational spear, axe-stun, and mace combat. */
public final class QysAutismAddon extends SimpleAddon {
    // Keep the legacy Fabric ID so the host still recognizes the addon and its saved settings.
    public static final String ID = "autism-mace-hitter";

    private int delayCounter;
    private Entity currentTarget;

    // Stun Slam is deliberately staged. Sending two attacks in one tick can make
    // the server reject the second hit due to its attack-strength cooldown.
    private Entity stunTarget;
    private int stunMaceSlot = -1;
    private int stunReturnSlot = -1;
    private int stunTicks;

    public QysAutismAddon() {
        super(ApiVersion.CURRENT, "com.autismdev.macehitter");
    }

    @Override
    protected void initialize() {
        SimpleModule mace = new SimpleModule(
            "mace-hitter",
            "Mace",
            "Situational mace combat: axe stun slam, spear-reach swap, and mace attribute swap."
        )
            .addDouble("range", "Normal Range", 3.2, 1.0, 6.0, 0.1)
            .addInt("delay", "Attack Delay (ticks)", 0, 0, 20, 1)
            .addBool("rotate", "Rotate to Target", true)
            .addBool("auto-swap", "Auto Equip Mace", true)
            .addBool("auto-jump", "Auto Jump", true)
            .addBool("falling-only", "Only Attack While Falling", false)
            // Retain this old setting ID to preserve existing user configurations.
            .addBool("shield-stun", "Stun Slam (Axe → Mace)", true)
            .addBool("mace-attribute-swap", "Mace Attribute Swap", false)
            .addBool("spear-mace-swap", "Spear Reach → Mace Swap", false)
            .addDouble("spear-range", "Spear Reach", 4.5, 3.2, 6.0, 0.1)
            .addDouble("min-fall-distance", "Minimum Smash Fall", 1.5, 0.5, 6.0, 0.1)
            .addBool("restore-slot", "Restore Original Slot After Combo", true)
            .addBool("require-mace", "Require Mace Available", false)
            .addBool("ignore-walls", "Ignore Walls", false)
            .addChoice("target-mode", "Target Mode", "Players", "Players", "Everything")
            .addBool("teams-check", "Ignore Teammates", true)
            .onTick(this::onTick)
            .onDisabled(this::onDisabled);

        registerModule(mace);

        registerSimpleAction(
            "slam-now",
            "Slam Now",
            "Immediately attacks the current Mace target",
            "mace",
            this::manualSlam
        );
    }

    private void onDisabled(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        releaseJump(mc);
        cancelStun(mc, mc.player);
        currentTarget = null;
        delayCounter = 0;
    }

    private void onTick(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
            releaseJump(mc);
            currentTarget = null;
            stunTarget = null;
            stunMaceSlot = -1;
            stunReturnSlot = -1;
            stunTicks = 0;
            return;
        }

        if (module.getBool("auto-jump") && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false);
        }

        if (stunTarget != null) {
            tickStunSlam(mc, player, module);
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

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        int originalSlot = player.getInventory().selected;
        int maceSlot = findMaceSlot(player);
        int axeSlot = findAxeSlot(player);
        double distance = player.distanceTo(target);

        // Axe first to disable a raised shield, then wait for a real falling
        // smash window before the mace hit. No double attack packet burst.
        if (isBlocking(target) && module.getBool("shield-stun") && axeSlot >= 0 && maceSlot >= 0) {
            if (player.getAttackStrengthScale(0.5F) < 0.9F) {
                return;
            }
            selectSlot(player, axeSlot);
            mc.gameMode.attack(player, target);
            stunTarget = target;
            stunMaceSlot = maceSlot;
            stunReturnSlot = originalSlot;
            stunTicks = 0;
            selectSlot(player, maceSlot);
            return;
        }

        // A spear held in the main hand supplies the extended reach for this
        // same-tick mace swap. Only use this outside ordinary melee range.
        if (module.getBool("spear-mace-swap")
            && isSpear(player.getMainHandItem())
            && distance > module.getDouble("range")
            && distance <= module.getDouble("spear-range")
            && maceSlot >= 0
            && maceSlot != originalSlot) {
            performAttributeSwap(mc, player, target, maceSlot, originalSlot,
                module.getBool("restore-slot"));
            delayCounter = module.getInt("delay");
            return;
        }

        // Attribute swap is only attempted once a genuine mace smash is ready.
        if (module.getBool("mace-attribute-swap")
            && isSmashReady(player, module)
            && maceSlot >= 0
            && maceSlot != originalSlot) {
            performAttributeSwap(mc, player, target, maceSlot, originalSlot,
                module.getBool("restore-slot"));
            delayCounter = module.getInt("delay");
            return;
        }

        boolean maceReady = ensureMace(player, module);
        if (module.getBool("require-mace") && !maceReady) {
            releaseJump(mc);
            return;
        }

        if (!module.getBool("falling-only") || isFalling(player)) {
            mc.gameMode.attack(player, target);
            delayCounter = module.getInt("delay");
        }
    }

    private void tickStunSlam(Minecraft mc, LocalPlayer player, SimpleModule module) {
        stunTicks++;
        if (stunTarget == null || !stunTarget.isAlive()
            || player.distanceTo(stunTarget) > module.getDouble("range")
            || stunTicks > 100) {
            cancelStun(mc, player);
            return;
        }

        if (module.getBool("rotate")) {
            rotateTo(player, stunTarget);
        }

        if (stunMaceSlot < 0 || stunMaceSlot > 8
            || !(player.getInventory().getItem(stunMaceSlot).getItem() instanceof MaceItem)) {
            cancelStun(mc, player);
            return;
        }

        // Keep the mace selected while its attack cooldown recovers.
        selectSlot(player, stunMaceSlot);
        boolean smashReady = isSmashReady(player, module);
        boolean cooldownReady = player.getAttackStrengthScale(0.5F) >= 0.9F;
        if (!smashReady || !cooldownReady) {
            return;
        }

        mc.gameMode.attack(player, stunTarget);
        if (module.getBool("restore-slot")) {
            restoreSlot(mc, player, stunReturnSlot);
        }
        stunTarget = null;
        stunMaceSlot = -1;
        stunReturnSlot = -1;
        stunTicks = 0;
        delayCounter = module.getInt("delay");
    }

    private Entity findTarget(Minecraft mc, SimpleModule module) {
        LocalPlayer player = mc.player;
        double range = module.getDouble("range");
        if (module.getBool("spear-mace-swap") && isSpear(player.getMainHandItem())) {
            range = Math.max(range, module.getDouble("spear-range"));
        }

        boolean playersOnly = module.getChoice("target-mode").equalsIgnoreCase("Players");
        boolean ignoreWalls = module.getBool("ignore-walls");
        boolean teamsCheck = module.getBool("teams-check");

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
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

            double distance = player.distanceTo(entity);
            if (distance > range || distance >= bestDistance) {
                continue;
            }
            if (!ignoreWalls && !player.hasLineOfSight(entity)) {
                continue;
            }
            best = entity;
            bestDistance = distance;
        }
        return best;
    }

    private void performAttributeSwap(
        Minecraft mc, LocalPlayer player, Entity target,
        int swapSlot, int originalSlot, boolean restore
    ) {
        selectSlot(player, swapSlot);
        mc.gameMode.attack(player, target);
        if (restore) {
            restoreSlot(mc, player, originalSlot);
        }
    }

    private void restoreSlot(Minecraft mc, LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8) return;
        selectSlot(player, slot);
        if (mc.gameMode != null) {
            ((MultiPlayerGameModeInvoker) mc.gameMode).qys$ensureHasSentCarriedItem();
        }
    }

    private void cancelStun(Minecraft mc, LocalPlayer player) {
        if (player != null && stunReturnSlot >= 0 && stunReturnSlot <= 8) {
            restoreSlot(mc, player, stunReturnSlot);
        }
        stunTarget = null;
        stunMaceSlot = -1;
        stunReturnSlot = -1;
        stunTicks = 0;
    }

    private void selectSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8 || player.getInventory().selected == slot) return;
        player.getInventory().selected = slot;
        player.getInventory().setChanged();
    }

    private int findMaceSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) {
            return player.getInventory().selected;
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof MaceItem) return i;
        }
        return -1;
    }

    private int findAxeSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof AxeItem) {
            return player.getInventory().selected;
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private boolean ensureMace(LocalPlayer player, SimpleModule module) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) return true;
        if (!module.getBool("auto-swap")) return false;
        int slot = findMaceSlot(player);
        if (slot < 0) return false;
        selectSlot(player, slot);
        return true;
    }

    private boolean isSpear(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
    }

    private boolean isBlocking(Entity target) {
        return target instanceof LivingEntity living && living.isUsingItem() && living.isBlocking();
    }

    private boolean isFalling(LocalPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        return !player.onGround() && motion.y < 0.0;
    }

    private boolean isSmashReady(LocalPlayer player, SimpleModule module) {
        return isFalling(player) && player.fallDistance >= module.getDouble("min-fall-distance");
    }

    private void rotateTo(LocalPlayer player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double dy = target.getEyeY() - player.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(dy, horizontal) * 180.0 / Math.PI));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private void manualSlam(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) return;
        Entity target = currentTarget != null && currentTarget.isAlive() ? currentTarget : null;
        if (target != null && player.distanceTo(target) <= 6.0) {
            rotateTo(player, target);
            mc.gameMode.attack(player, target);
        }
    }

    private void releaseJump(Minecraft mc) {
        mc.options.keyJump.setDown(false);
    }
}
