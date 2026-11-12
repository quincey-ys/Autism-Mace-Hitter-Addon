package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.SimpleModule;
import com.autismdev.macehitter.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** QYS Addon: situational mace combat plus a separate silent Hitflick module. */
public final class QysAutismAddon extends SimpleAddon {
    // Keep the legacy Fabric ID so AUTISM Client retains addon identity/config ownership.
    public static final String ID = "autism-mace-hitter";

    private int delayCounter;
    private Entity currentTarget;

    // Stun Slam is staged: axe shield-disable, then a real falling mace smash.
    private Entity stunTarget;
    private int stunMaceSlot = -1;
    private int stunReturnSlot = -1;
    private int stunTicks;

    // Double Pop is armed only while the main-hand spear is actively charging.
    // A single confirmed kinetic hit consumes the current charge sequence.
    private Entity spearChargeTarget;
    private int spearChargeSlot = -1;
    private int spearLastHurtTime;
    private float spearLastHealth;
    private float spearLastAbsorption;
    private boolean spearChargeConsumed;

    public QysAutismAddon() {
        super(ApiVersion.CURRENT, "com.autismdev.macehitter");
    }

    @Override
    protected void initialize() {
        SimpleModule mace = new SimpleModule(
            "mace-hitter",
            "Mace",
            "Situational mace combat: Stun Slam, Breach swap, native spear-charge follow-up, and mace smash swap."
        )
            .addDouble("range", "Normal Range", 3.2, 1.0, 6.0, 0.1)
            .addInt("delay", "Attack Delay (ticks)", 0, 0, 20, 1)
            .addBool("rotate", "Rotate to Target", true)
            .addBool("auto-swap", "Auto Equip Mace", true)
            .addBool("auto-jump", "Auto Jump", true)
            .addBool("falling-only", "Only Attack While Falling", false)
            // Keep the old setting ID for existing configurations.
            .addBool("shield-stun", "Stun Slam (Axe → Mace)", true)
            .addBool("mace-attribute-swap", "Mace Attribute Swap", false)
            .addBool("breach-swap", "Sword → Breach Mace Swap", false)
            .addInt("breach-min-armor", "Breach Target Armor", 8, 1, 20, 1)
            .addBool("spear-double-pop", "Spear Charge → Mace Double Pop", false)
            .addDouble("min-fall-distance", "Minimum Smash Fall", 1.5, 0.5, 6.0, 0.1)
            .addBool("restore-slot", "Restore Original Slot After Combo", true)
            .addBool("require-mace", "Require Mace Available", false)
            .addBool("ignore-walls", "Ignore Walls", false)
            .addChoice("target-mode", "Target Mode", "Players", "Players", "Everything")
            .addBool("teams-check", "Ignore Teammates", true)
            .onTick(this::onMaceTick)
            .onDisabled(this::onMaceDisabled);

        registerModule(mace);

        SimpleModule hitflick = new SimpleModule(
            "hitflick",
            "Hitflick",
            "Silently flicks server yaw around an attack to redirect sprint or Knockback hit displacement."
        )
            .addChoice("direction", "Direction", "Alternate",
                "Left", "Right", "Alternate", "Strafe", "Strafe Inverted")
            .addDouble("flick-angle", "Flick Angle", 30.0, 5.0, 90.0, 1.0)
            .addBool("require-sprint", "Require Sprint", false)
            .addInt("cooldown-ms", "Cooldown (ms)", 100, 0, 1000, 10)
            .addInt("chance", "Chance (%)", 100, 0, 100, 1)
            .onTick(HitflickController::configure)
            .onDisabled(module -> HitflickController.disable());

        registerModule(hitflick);

        registerSimpleAction(
            "slam-now",
            "Slam Now",
            "Immediately attacks the current Mace target",
            "mace",
            this::manualSlam
        );
    }

    private void onMaceDisabled(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        releaseJump(mc);
        cancelStun(mc, mc.player);
        clearSpearTracking();
        currentTarget = null;
        delayCounter = 0;
    }

    private void onMaceTick(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
            releaseJump(mc);
            clearSpearTracking();
            stunTarget = null;
            stunMaceSlot = -1;
            stunReturnSlot = -1;
            stunTicks = 0;
            currentTarget = null;
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

        boolean activelyChargingSpear = module.getBool("spear-double-pop")
            && player.isUsingItem()
            && isSpear(player.getMainHandItem())
            && isSpear(player.getUseItem());

        if (activelyChargingSpear) {
            Entity spearTarget = findSpearChargeTarget(mc, player, module);
            currentTarget = spearTarget;
            if (!spearChargeConsumed) {
                updateSpearCharge(mc, player, spearTarget, module);
            }
            // While spear is charging, the Double Pop controller owns this tick.
            // Never run ordinary Mace attacks or ordinary target scans here.
            return;
        }

        // Releasing the spear charge (or switching away from it) disarms the combo.
        // Spear reach is never used by idle Mace targeting.
        clearSpearTracking();

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

        int originalSlot = player.getInventory().getSelectedSlot();
        int maceSlot = findMaceSlot(player);
        int axeSlot = findAxeSlot(player);

        // Shield users get the axe first. The follow-up mace hit waits for a
        // true falling smash window instead of issuing two same-tick attacks.
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
            syncSelectedSlot(mc);
            return;
        }

        // Breach swap is a sword-sourced attack paired with a Breach mace.
        // Only attempt it against armored players and within ordinary melee range.
        if (module.getBool("breach-swap")
            && target instanceof Player armoredTarget
            && armoredTarget.getArmorValue() >= module.getInt("breach-min-armor")
            && isSword(player.getMainHandItem())
            && player.getAttackStrengthScale(0.5F) >= 0.9F
            && player.distanceTo(target) <= Math.min(module.getDouble("range"), 3.2)
            && hasLineOfSight(player, target)
        ) {
            int breachSlot = findBreachMaceSlot(mc, player);
            if (breachSlot >= 0 && breachSlot != originalSlot) {
                performAttributeSwap(mc, player, target, breachSlot, originalSlot,
                    module.getBool("restore-slot"));
                delayCounter = module.getInt("delay");
                return;
            }
        }

        // Generic mace attribute swap is only attempted on an actual falling smash.
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

    /**
     * Finds the entity actually in the held spear's native AttackRange ray.
     * This uses the item component rather than a hard-coded 4.5-block sphere.
     */
    private Entity findSpearChargeTarget(Minecraft mc, LocalPlayer player, SimpleModule module) {
        ItemStack spear = player.getMainHandItem();
        AttackRange range = spear.get(DataComponents.ATTACK_RANGE);
        if (range == null) {
            range = AttackRange.defaultFor(player);
        }

        HitResult hit = range.getClosesetHit(player, 1.0F,
            entity -> isValidTarget(player, entity, module));
        if (hit instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            return range.isInRange(player, target.getBoundingBox(), 0.0D) ? target : null;
        }
        return null;
    }

    private boolean isValidTarget(LocalPlayer player, Entity entity, SimpleModule module) {
        if (entity == player || entity.isSpectator()) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (module.getChoice("target-mode").equalsIgnoreCase("Players")
            && !(entity instanceof Player)) return false;
        if (entity instanceof Player other && module.getBool("teams-check")
            && player.isAlliedTo(other)) return false;
        return module.getBool("ignore-walls") || player.hasLineOfSight(entity);
    }

    /**
     * Tracks the target while the spear is actually being charged. When a
     * health/hurt-time update confirms a kinetic spear hit, it immediately
     * swaps to the mace, attacks, and restores the spear in the same tick.
     */
    private void updateSpearCharge(
        Minecraft mc, LocalPlayer player, Entity selectedTarget, SimpleModule module
    ) {
        if (spearChargeTarget == null) {
            if (selectedTarget == null) return;
            spearChargeTarget = selectedTarget;
            spearChargeSlot = player.getInventory().getSelectedSlot();
            spearLastHurtTime = selectedTarget instanceof LivingEntity living ? living.hurtTime : 0;
            spearLastHealth = selectedTarget instanceof LivingEntity living ? living.getHealth() : 0.0F;
            spearLastAbsorption = selectedTarget instanceof LivingEntity living
                ? living.getAbsorptionAmount() : 0.0F;
            return;
        }

        if (!spearChargeTarget.isAlive()
            || player.getInventory().getSelectedSlot() != spearChargeSlot
            || !isSpear(player.getMainHandItem())) {
            return;
        }
        if (!(spearChargeTarget instanceof LivingEntity living)) return;

        float health = living.getHealth();
        float absorption = living.getAbsorptionAmount();
        boolean hitConfirmed = living.hurtTime > spearLastHurtTime
            || health + absorption < spearLastHealth + spearLastAbsorption - 0.01F;

        spearLastHurtTime = living.hurtTime;
        spearLastHealth = health;
        spearLastAbsorption = absorption;
        if (!hitConfirmed) return;

        // Consume this charge even when no mace is available; never repeat the
        // same spear contact as a second combo attempt.
        spearChargeConsumed = true;
        int maceSlot = findMaceSlot(player);
        if (maceSlot < 0 || maceSlot == spearChargeSlot) return;

        ItemStack spear = player.getMainHandItem();
        AttackRange spearRange = spear.get(DataComponents.ATTACK_RANGE);
        if (spearRange == null) {
            spearRange = AttackRange.defaultFor(player);
        }

        // Only use spear reach if the target is inside this spear's actual item
        // reach. The following mace attack uses the same-tick attribute swap.
        if (!spearRange.isInRange(player, spearChargeTarget.getBoundingBox(), 0.0D)
            || (!module.getBool("ignore-walls") && !player.hasLineOfSight(spearChargeTarget))) {
            return;
        }

        if (module.getBool("rotate")) {
            rotateTo(player, spearChargeTarget);
        }

        performAttributeSwap(
            mc, player, spearChargeTarget, maceSlot, spearChargeSlot,
            module.getBool("restore-slot")
        );
        delayCounter = module.getInt("delay");
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
        boolean ignoreWalls = module.getBool("ignore-walls");

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!isValidTarget(player, entity, module)) {
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
        // Same-tick item attribute swap: equip the mace, let vanilla attack send
        // the selected-slot update before the attack packet, then restore source.
        selectSlot(player, swapSlot);
        mc.gameMode.attack(player, target);
        if (restore) {
            restoreSlot(mc, player, originalSlot);
        }
    }

    private int findMaceSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) {
            return player.getInventory().getSelectedSlot();
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof MaceItem) return i;
        }
        return -1;
    }

    private int findBreachMaceSlot(Minecraft mc, LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() instanceof MaceItem && enchantmentLevel(mc, stack, Enchantments.BREACH) > 0) {
                return i;
            }
        }
        return -1;
    }

    private int enchantmentLevel(Minecraft mc, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        try {
            if (mc.level == null || stack == null || stack.isEmpty()) return 0;
            var holder = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
            return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int findAxeSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof AxeItem) {
            return player.getInventory().getSelectedSlot();
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

    private boolean isSword(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_sword");
    }

    private boolean isBlocking(Entity target) {
        return target instanceof LivingEntity living && living.isUsingItem() && living.isBlocking();
    }

    private boolean hasLineOfSight(LocalPlayer player, Entity target) {
        return player.hasLineOfSight(target);
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

    private void selectSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8 || player.getInventory().getSelectedSlot() == slot) return;
        player.getInventory().setSelectedSlot(slot);
        player.getInventory().setChanged();
    }

    private void syncSelectedSlot(Minecraft mc) {
        if (mc.gameMode != null) {
            ((MultiPlayerGameModeInvoker) mc.gameMode).qys$ensureHasSentCarriedItem();
        }
    }

    private void restoreSlot(Minecraft mc, LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8) return;
        selectSlot(player, slot);
        syncSelectedSlot(mc);
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

    private void clearSpearTracking() {
        spearChargeTarget = null;
        spearChargeSlot = -1;
        spearLastHurtTime = 0;
        spearLastHealth = 0.0F;
        spearLastAbsorption = 0.0F;
        spearChargeConsumed = false;
    }

    private void releaseJump(Minecraft mc) {
        mc.options.keyJump.setDown(false);
    }
}
