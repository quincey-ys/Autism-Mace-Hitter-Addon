package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.RegistryListSetting;
import autismclient.api.module.SimpleModule;
import autismclient.util.AutismCombatClicker;
import autismclient.util.AutismHandArbiter;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismKillAuraRotation;
import autismclient.util.AutismRotationUtil;
import autismclient.util.AutismSilentAim;
import com.autismdev.macehitter.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** QYS Addon: situational mace combat plus a separate silent Hitflick module. */
public final class QysAutismAddon extends SimpleAddon {
    // Keep the legacy Fabric ID so AUTISM Client retains addon identity/config ownership.
    public static final String ID = "autism-mace-hitter";

    private int delayCounter;
    private Entity currentTarget;
    private MaceModule maceModule;
    private int lastQueuedMaceAttackTick = Integer.MIN_VALUE;
    private int pendingRestoreSlot = -1;
    private int pendingRestoreFromSlot = -1;
    private int pendingRestoreTick = Integer.MIN_VALUE;
    private static int lastClaimedAttackTick = Integer.MIN_VALUE;

    /** Prevents independent addon modules from issuing multiple attacks in one client tick. */
    public static boolean claimAttackTick(int tick) {
        if (lastClaimedAttackTick == tick) return false;
        lastClaimedAttackTick = tick;
        return true;
    }

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
        MaceModule mace = new MaceModule();
        maceModule = mace;
        mace
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
            .addChoice("targeting", "Targeting", "FOV",
                "Type", "HP", "Distance", "FOV", "Hurt Time", "Age")
            .addInt("fov", "FOV", 180, 10, 360, 10)
            .addBool("teams-check", "Ignore Teammates", true)
            .onDisabled(this::onMaceDisabled);

        registerModule(mace);
        registerModule(new CombatModule());
        registerModule(new MusicPlayerModule());

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

    /**
     * Uses the same registry-backed entity picker as AUTISM KillAura.
     * Keeping the list on this module lets users whitelist exact mob types.
     */
    private final class MaceModule extends SimpleModule implements AutismSilentAim.Owner {
        private String cachedEntityListSource = "";
        private Set<String> cachedEntityIds = Set.of();
        private boolean rotationActive;

        MaceModule() {
            super(
                "mace-hitter",
                "Mace",
                "Situational mace combat using AUTISM KillAura's silent rotation and queued hit pipeline."
            );
            add(RegistryListSetting.entityTypes("entities", "Entities", "minecraft:player")
                .group("Target")
                .description("Entity types that Mace may target. Uses the same registry picker as KillAura.")
                .build());
        }

        @Override
        public void preMovementTick() {
            onMaceTick(this);
        }

        @Override
        public void tick() {
            if (!isEnabled() && MC.player != null) {
                processPendingRestore(MC, MC.player);
                if (id().equals(AutismKillAuraRotation.currentOwner())) {
                    AutismKillAuraRotation.update(id(), MC.player);
                    rotationActive = id().equals(AutismKillAuraRotation.currentOwner())
                        && AutismKillAuraRotation.hasCurrentRotation();
                }
            }
        }

        @Override
        public boolean ticksWhenDisabled() {
            return true;
        }

        @Override
        public boolean hasDisabledTickWork() {
            return pendingRestoreSlot >= 0
                || id().equals(AutismKillAuraRotation.currentOwner())
                    && AutismKillAuraRotation.hasCurrentRotation();
        }

        @Override
        public boolean silentCorrectionApplies() {
            return rotationActive && (isEnabled() || AutismKillAuraRotation.isWindingDown());
        }

        void setRotationActive(boolean active) {
            rotationActive = active;
        }

        Set<String> entityIds() {
            List<String> selected = list("entities");
            String source = String.join("|", selected);
            if (source.equals(cachedEntityListSource)) return cachedEntityIds;

            Set<String> normalized = new LinkedHashSet<>();
            for (String entry : selected) {
                if (entry == null) continue;
                String value = entry.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) continue;
                normalized.add(value);
                int separator = value.indexOf(':');
                if (separator >= 0 && separator + 1 < value.length()) {
                    normalized.add(value.substring(separator + 1));
                }
            }
            cachedEntityListSource = source;
            cachedEntityIds = Set.copyOf(normalized);
            return cachedEntityIds;
        }
    }

    private void onMaceDisabled(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (module != null) AutismKillAuraRotation.beginWindDown(module.id());
        if (player != null && pendingRestoreSlot >= 0
            && player.getInventory().getSelectedSlot() == pendingRestoreFromSlot) {
            restoreSlot(mc, player, pendingRestoreSlot);
        }
        if (player == null || player.getInventory().getSelectedSlot() != pendingRestoreFromSlot
            || player.getInventory().getSelectedSlot() == pendingRestoreSlot) {
            clearPendingRestore();
        }
        releaseJump(mc);
        clearSpearTracking();
        currentTarget = null;
        delayCounter = 0;
        lastQueuedMaceAttackTick = Integer.MIN_VALUE;
        cancelStun(mc, player);
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
            clearPendingRestore();
            AutismKillAuraRotation.beginWindDown(module.id());
            return;
        }

        processPendingRestore(mc, player);

        boolean activelyChargingSpear = module.getBool("spear-double-pop")
            && player.isUsingItem()
            && isSpear(player.getMainHandItem())
            && isSpear(player.getUseItem());

        // Do not inject jump input into an active kinetic spear charge.
        if (!activelyChargingSpear && module.getBool("auto-jump") && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else if (!activelyChargingSpear && player.onGround()) {
            mc.options.keyJump.setDown(false);
        }

        if (stunTarget != null) {
            tickStunSlam(mc, player, module);
            return;
        }

        if (activelyChargingSpear) {
            Entity spearTarget = findSpearChargeTarget(mc, player, module);
            currentTarget = spearTarget;
            if (spearTarget != null) updateMaceRotation(player, spearTarget, module);
            else stopMaceRotation(player, module);
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
            stopMaceRotation(player, module);
            return;
        }

        updateMaceRotation(player, target, module);

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        int originalSlot = player.getInventory().getSelectedSlot();
        int maceSlot = findMaceSlot(player);
        int axeSlot = findAxeSlot(player);

        // Shield users get the axe first. The queued hit is delivered through
        // the same input pipeline used by AUTISM KillAura.
        if (isBlocking(target) && module.getBool("shield-stun") && axeSlot >= 0 && maceSlot >= 0) {
            if (axeSlot != originalSlot) {
                if (player.getAttackStrengthScale(0.5F) >= 0.9F) {
                    selectSlot(mc, player, axeSlot, module);
                }
                return;
            }
            if (queueMaceAttack(mc, player, target, module, false)) {
                stunTarget = target;
                stunMaceSlot = maceSlot;
                stunReturnSlot = originalSlot;
                stunTicks = 0;
                delayCounter = module.getInt("delay");
            }
            return;
        }

        // Breach swap is a sword-sourced attack paired with a Breach mace.
        // The source weapon's ready attack window authorizes the same-tick swap.
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
                if (performAttributeSwap(mc, player, target, breachSlot, originalSlot,
                    module.getBool("restore-slot"), module)) {
                    delayCounter = module.getInt("delay");
                }
                return;
            }
        }

        // Generic mace attribute swap is only attempted on an actual falling smash.
        if (module.getBool("mace-attribute-swap")
            && isSmashReady(player, module)
            && player.getAttackStrengthScale(0.5F) >= 0.9F
            && maceSlot >= 0
            && maceSlot != originalSlot) {
            if (performAttributeSwap(mc, player, target, maceSlot, originalSlot,
                module.getBool("restore-slot"), module)) {
                delayCounter = module.getInt("delay");
            }
            return;
        }

        boolean requireMace = module.getBool("require-mace");
        boolean autoEquipMace = module.getBool("auto-swap");
        if (requireMace && maceSlot < 0) {
            releaseJump(mc);
            return;
        }
        if (requireMace && !autoEquipMace
            && !(player.getMainHandItem().getItem() instanceof MaceItem)) {
            releaseJump(mc);
            return;
        }

        // Match KillAura's weapon preparation: select the mace only after a
        // real target ray exists, then let its own cooldown fill before queuing.
        if (autoEquipMace && maceSlot >= 0 && maceSlot != originalSlot) {
            if (module.getBool("falling-only") && !isFalling(player)) return;
            if (player.getAttackStrengthScale(0.5F) < 0.9F) return;
            if (findAttackHit(mc, player, target, module) == null) return;
            selectSlot(mc, player, maceSlot, module);
            return;
        }

        if (module.getBool("falling-only") && !isFalling(player)) return;
        if (queueMaceAttack(mc, player, target, module, false)) {
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
        if (entity == player || entity.isSpectator() || entity.isRemoved()) return false;
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) return false;
        if (!EntitySelector.CAN_BE_PICKED.test(entity) || entity.hasPassenger(player)) return false;
        if (!matchesEntity(module, entity)) return false;

        if (entity instanceof Player other) {
            if (other.isSleeping()) return false;
            if (module.getBool("teams-check") && player.isAlliedTo(other)) return false;
        }

        if (crosshairAngleToEntity(player, entity) > module.getInt("fov") * 0.5D) return false;
        return module.getBool("ignore-walls") || player.hasLineOfSight(entity);
    }

    private boolean matchesEntity(SimpleModule module, Entity entity) {
        Set<String> ids = module instanceof MaceModule mace ? mace.entityIds() : Set.of("minecraft:player");
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase(Locale.ROOT);
        int separator = id.indexOf(':');
        return ids.contains(id) || separator >= 0 && ids.contains(id.substring(separator + 1));
    }

    private double crosshairAngleToEntity(LocalPlayer player, Entity entity) {
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(player.getEyePosition());
        if (toTarget.lengthSqr() <= 1.0E-12D) return 0.0D;
        double dot = player.getLookAngle().normalize().dot(toTarget.normalize());
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private Comparator<LivingEntity> targetComparator(LocalPlayer player, SimpleModule module) {
        Comparator<LivingEntity> distance =
            Comparator.comparingDouble((LivingEntity entity) -> player.distanceToSqr(entity));
        return switch (module.getChoice("targeting")) {
            case "HP" -> Comparator.comparingDouble(LivingEntity::getHealth).thenComparing(distance);
            case "Distance" -> distance;
            case "FOV" -> Comparator.comparingDouble(
                (LivingEntity entity) -> crosshairAngleToEntity(player, entity)).thenComparing(distance);
            case "Hurt Time" -> Comparator.comparingInt(
                (LivingEntity entity) -> entity.hurtTime).thenComparing(distance);
            case "Age" -> Comparator.comparingInt((LivingEntity entity) -> -entity.tickCount)
                .thenComparing(distance);
            default -> Comparator.comparingInt((LivingEntity entity) -> typeWeight(player, entity))
                .thenComparing(distance);
        };
    }

    private int typeWeight(LocalPlayer player, LivingEntity entity) {
        if (entity instanceof Player) return 0;
        if (entity instanceof Enemy) return 1;
        if (entity instanceof NeutralMob neutral) {
            EntityReference<LivingEntity> angerTarget = neutral.getPersistentAngerTarget();
            if (angerTarget != null && angerTarget.matches(player)) return 2;
        }
        return Integer.MAX_VALUE;
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

        updateMaceRotation(player, spearChargeTarget, module);
        if (performAttributeSwap(
            mc, player, spearChargeTarget, maceSlot, spearChargeSlot,
            module.getBool("restore-slot"), module
        )) {
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

        updateMaceRotation(player, stunTarget, module);

        if (stunMaceSlot < 0 || stunMaceSlot > 8
            || !(player.getInventory().getItem(stunMaceSlot).getItem() instanceof MaceItem)) {
            cancelStun(mc, player);
            return;
        }

        if (!isSmashReady(player, module)) return;

        // The axe setup is complete. The source axe's ready window authorizes
        // the same-tick mace swap; queue the follow-up through KillAura.
        if (player.getInventory().getSelectedSlot() != stunMaceSlot) {
            if (player.getAttackStrengthScale(0.5F) < 0.9F) return;
            if (!selectSlot(mc, player, stunMaceSlot, module)) return;
        }

        if (queueMaceAttack(mc, player, stunTarget, module, true)) {
            if (module.getBool("restore-slot")) {
                scheduleSlotRestore(player, stunReturnSlot);
            }
            stunTarget = null;
            stunMaceSlot = -1;
            stunReturnSlot = -1;
            stunTicks = 0;
            delayCounter = module.getInt("delay");
        }
    }

    private Entity findTarget(Minecraft mc, SimpleModule module) {
        LocalPlayer player = mc.player;
        double range = module.getDouble("range");
        List<LivingEntity> candidates = new ArrayList<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !isValidTarget(player, entity, module)) {
                continue;
            }
            if (player.distanceTo(entity) <= range) {
                candidates.add(living);
            }
        }

        candidates.sort(targetComparator(player, module));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean performAttributeSwap(
        Minecraft mc, LocalPlayer player, Entity target,
        int swapSlot, int originalSlot, boolean restore, SimpleModule module
    ) {
        // Change the held stack before the keybind attack is queued so the server
        // evaluates the mace attributes on the hit. Restore on the next tick,
        // never in the same input frame.
        if (swapSlot < 0 || swapSlot > 8) return false;
        if (!selectSlot(mc, player, swapSlot, module)) return false;
        boolean queued = queueMaceAttack(mc, player, target, module, true);
        if (queued && restore) {
            scheduleSlotRestore(player, originalSlot);
        } else if (!queued && restore) {
            restoreSlot(mc, player, originalSlot);
        }
        return queued;
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

    private AutismRotationUtil.Rotation updateMaceRotation(
        LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (!module.getBool("rotate")) {
            stopMaceRotation(player, module);
            return currentMaceRotation(player, module);
        }
        AutismRotationUtil.Rotation desired = AutismRotationUtil.lookingAt(
            target.getBoundingBox().getCenter(), player.getEyePosition());
        AutismKillAuraRotation.setTarget(
            module.id(), AutismKillAuraRotation.PRIORITY_KILL_AURA + 1, desired);
        AutismKillAuraRotation.update(
            module.id(), player, AutismKillAuraRotation.TURN_SPEED, AutismKillAuraRotation.TURN_SPEED);

        boolean ownsRotation = module.id().equals(AutismKillAuraRotation.currentOwner())
            && AutismKillAuraRotation.hasCurrentRotation();
        if (maceModule != null) maceModule.setRotationActive(ownsRotation);
        return ownsRotation ? AutismKillAuraRotation.getCurrentRotation() : null;
    }

    private void stopMaceRotation(LocalPlayer player, SimpleModule module) {
        if (module.id().equals(AutismKillAuraRotation.currentOwner())) {
            AutismKillAuraRotation.beginWindDown(module.id());
            AutismKillAuraRotation.update(module.id(), player);
            boolean active = module.id().equals(AutismKillAuraRotation.currentOwner())
                && AutismKillAuraRotation.hasCurrentRotation();
            if (maceModule != null) maceModule.setRotationActive(active);
        } else if (maceModule != null) {
            maceModule.setRotationActive(false);
        }
    }

    private AutismRotationUtil.Rotation currentMaceRotation(LocalPlayer player, SimpleModule module) {
        AutismRotationUtil.Rotation current = AutismKillAuraRotation.getCurrentRotation();
        return current != null ? current : AutismRotationUtil.playerRotation(player);
    }

    private EntityHitResult findAttackHit(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (target == null || !target.isAlive() || mc.level == null) return null;
        Entity camera = mc.getCameraEntity();
        if (camera == null || camera != player) return null;

        AutismRotationUtil.Rotation rotation;
        if (module.getBool("rotate")) {
            if (!module.id().equals(AutismKillAuraRotation.currentOwner())) return null;
            rotation = AutismKillAuraRotation.getCurrentRotation();
            if (rotation == null) return null;
        } else {
            rotation = currentMaceRotation(player, module);
        }

        double interactionRange = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        double range = Math.min(module.getDouble("range"), interactionRange);
        if (range <= 0.0D) return null;

        Vec3 eyes = camera.getEyePosition();
        Vec3 direction = Vec3.directionFromRotation(rotation.pitch(), rotation.yaw());
        Vec3 end = eyes.add(direction.scale(range));
        AABB search = camera.getBoundingBox()
            .expandTowards(direction.scale(range)).inflate(1.0D, 1.0D, 1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
            camera, eyes, end, search, EntitySelector.CAN_BE_PICKED.and(entity -> entity == target),
            range * range);
        if (hit == null || hit.getEntity() != target) return null;

        AttackRange attackRange = player.getMainHandItem().get(DataComponents.ATTACK_RANGE);
        if (attackRange == null) attackRange = AttackRange.defaultFor(player);
        if (!attackRange.isInRange(player, hit.getLocation())) return null;

        if (!module.getBool("ignore-walls")) {
            HitResult wall = mc.level.clip(new ClipContext(
                eyes, hit.getLocation(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, camera));
            if (wall.getType() != HitResult.Type.MISS) return null;
        }
        return hit;
    }

    private boolean queueMaceAttack(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module, boolean bypassCooldown
    ) {
        if (mc.gameMode == null || mc.getConnection() == null || mc.gui == null
            || mc.gui.screen() != null || mc.gui.overlay() != null
            || player.isSpectator() || player.isDeadOrDying() || player.isUsingItem()) return false;

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || !stack.isItemEnabled(mc.level.enabledFeatures())
            || player.cannotAttackWithItem(stack, 0)) return false;
        if (!bypassCooldown && player.getAttackStrengthScale(0.5F) < 0.9F) return false;

        EntityHitResult hit = findAttackHit(mc, player, target, module);
        if (hit == null || lastQueuedMaceAttackTick == player.tickCount) return false;
        if (!claimAttackTick(player.tickCount)) return false;

        if (!AutismHandArbiter.beginHandPacketGroup(module.id())) return false;
        try {
            syncSelectedSlot(mc);
        } finally {
            AutismHandArbiter.endHandPacketGroup(module.id());
        }

        if (!AutismCombatClicker.queueAttack(hit)) return false;
        player.resetAttackStrengthTicker();
        lastQueuedMaceAttackTick = player.tickCount;
        return true;
    }

    private boolean selectSlot(Minecraft mc, LocalPlayer player, int slot, SimpleModule module) {
        if (slot < 0 || slot > 8) return false;
        if (player.getInventory().getSelectedSlot() == slot) return true;
        if (!AutismHandArbiter.beginHandPacketGroup(module.id())) return false;
        try {
            AutismInventoryHelper.selectHotbarSlot(mc, slot);
        } finally {
            AutismHandArbiter.endHandPacketGroup(module.id());
        }
        return player.getInventory().getSelectedSlot() == slot;
    }

    private void scheduleSlotRestore(LocalPlayer player, int returnSlot) {
        if (returnSlot < 0 || returnSlot > 8) return;
        pendingRestoreSlot = returnSlot;
        pendingRestoreFromSlot = player.getInventory().getSelectedSlot();
        pendingRestoreTick = player.tickCount;
    }

    private void processPendingRestore(Minecraft mc, LocalPlayer player) {
        if (pendingRestoreSlot < 0 || player.tickCount <= pendingRestoreTick) return;
        int selected = player.getInventory().getSelectedSlot();
        if (selected != pendingRestoreFromSlot) {
            clearPendingRestore();
            return;
        }
        if (maceModule == null || !AutismHandArbiter.beginHandPacketGroup(maceModule.id())) return;
        try {
            AutismInventoryHelper.restoreHotbarSlot(mc, pendingRestoreSlot);
            clearPendingRestore();
        } finally {
            AutismHandArbiter.endHandPacketGroup(maceModule.id());
        }
    }

    private void clearPendingRestore() {
        pendingRestoreSlot = -1;
        pendingRestoreFromSlot = -1;
        pendingRestoreTick = Integer.MIN_VALUE;
    }

    private void manualSlam(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || maceModule == null) return;
        Entity target = currentTarget != null && currentTarget.isAlive() ? currentTarget : null;
        if (target == null || player.distanceTo(target) > 6.0D) return;
        updateMaceRotation(player, target, maceModule);
        if (queueMaceAttack(mc, player, target, maceModule, false)) {
            delayCounter = maceModule.getInt("delay");
        }
    }

    private void syncSelectedSlot(Minecraft mc) {
        if (mc.gameMode != null) {
            ((MultiPlayerGameModeInvoker) mc.gameMode).qys$ensureHasSentCarriedItem();
        }
    }

    private void restoreSlot(Minecraft mc, LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8 || maceModule == null) return;
        if (!AutismHandArbiter.beginHandPacketGroup(maceModule.id())) return;
        try {
            AutismInventoryHelper.restoreHotbarSlot(mc, slot);
        } finally {
            AutismHandArbiter.endHandPacketGroup(maceModule.id());
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
