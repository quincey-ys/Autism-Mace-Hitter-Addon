package com.autismdev.macehitter;

import autismclient.api.module.RegistryListSetting;
import autismclient.api.module.SimpleModule;
import com.autismdev.macehitter.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.KeyMapping;
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
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Independent combat utility module. It shares no active combo state with Mace:
 * tracked blocking, shield breaking, smart smash, spear countering and weapon
 * selection are all controlled by this module's own settings.
 */
public final class CombatModule extends SimpleModule {
    private final Map<Integer, BlockingTrack> blockingTracks = new HashMap<>();
    private final Map<Integer, SpearThreatTrack> spearThreatTracks = new HashMap<>();
    private String cachedEntityListSource = "";
    private Set<String> cachedEntityIds = Set.of();

    private long lastShieldBreakNanos;
    private long lastSpearCounterNanos;
    private long lastOptimizerSwitchNanos;
    private int lastSmashFallTick = Integer.MIN_VALUE;
    private boolean smashUsedThisFall;

    private KeyMapping counterMoveKey;
    private boolean counterMoveWasDown;
    private int counterAssistTicks;
    private int counterReturnSlot = -1;
    private int counterSelectedSlot = -1;

    public CombatModule() {
        super(
            "combat",
            "Combat",
            "Independent combat utilities: Shield Breaker, Smart Smash, Spear Counter, Weapon Optimizer and tracked blocking."
        );

        add(RegistryListSetting.entityTypes("entities", "Entities", "minecraft:player")
            .group("Target")
            .description("Entity types the Combat module may track or target. Uses AUTISM's registry-backed entity picker.")
            .build());

        addBool("track-blocking", "Track Blocking", true)
            .addBool("shield-breaker", "Shield Breaker", false)
            .addInt("shield-cooldown-ms", "Shield Breaker Cooldown (ms)", 500, 0, 3000, 50)
            .addInt("shield-disabled-ticks", "Shield Disabled Window (ticks)", 100, 20, 200, 5)
            .addDouble("shield-reach", "Shield Breaker Reach", 3.2, 2.0, 5.0, 0.1)
            .addChoice("shield-return", "Return Weapon", "Previous",
                "Previous", "Sword", "Mace", "Spear", "Best Weapon")
            .addBool("smart-smash", "Smart Smash", false)
            .addDouble("smash-min-fall", "Smart Smash Minimum Fall", 1.5, 0.5, 8.0, 0.1)
            .addDouble("smash-range", "Smart Smash Target Range", 3.2, 1.0, 6.0, 0.1)
            .addBool("smash-restore-slot", "Restore Slot After Smash", false)
            .addBool("spear-counter", "Spear Counter", false)
            .addDouble("counter-range", "Spear Counter Range", 8.0, 3.0, 16.0, 0.5)
            .addDouble("counter-facing-angle", "Attacker Facing Angle", 55.0, 10.0, 120.0, 5.0)
            .addChoice("counter-action", "Counter Action", "Backstep",
                "Backstep", "Strafe Left", "Strafe Right", "Switch Sword", "Switch Axe", "Switch + Backstep")
            .addInt("counter-duration", "Counter Duration (ticks)", 4, 1, 15, 1)
            .addInt("counter-cooldown-ms", "Counter Cooldown (ms)", 600, 0, 3000, 50)
            .addBool("weapon-optimizer", "Weapon Optimizer", false)
            .addInt("armor-threshold", "High Armor Threshold", 12, 1, 20, 1)
            .addDouble("optimizer-spear-min-distance", "Spear Selection Distance", 3.2, 2.0, 6.0, 0.1)
            .addInt("optimizer-cooldown-ms", "Weapon Switch Cooldown (ms)", 250, 0, 2000, 25)
            .addDouble("scan-range", "Target Scan Range", 8.0, 2.0, 16.0, 0.5)
            .addChoice("targeting", "Target Priority", "FOV",
                "Type", "HP", "Distance", "FOV", "Hurt Time", "Age")
            .addInt("fov", "FOV", 180, 10, 360, 10)
            .addBool("ignore-walls", "Ignore Walls", false)
            .addBool("teams-check", "Ignore Teammates", true)
            .onTick(this::tickCombat)
            .onDisabled(this::disableCombat);
    }

    private void tickCombat(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
            clearTransientState(null);
            return;
        }

        tickCounterAssist(mc, player);

        boolean needsBlockingTracking = module.getBool("track-blocking")
            || module.getBool("shield-breaker")
            || module.getBool("weapon-optimizer");
        if (needsBlockingTracking) {
            updateBlockingTracks(mc, player, module);
        } else {
            blockingTracks.clear();
        }

        Entity target = findTarget(mc, player, module);

        if (module.getBool("spear-counter")) {
            updateSpearThreats(mc, player, module);
        } else {
            spearThreatTracks.clear();
        }

        if (module.getBool("shield-breaker")
            && tryShieldBreak(mc, player, target, module)) {
            return;
        }

        if (module.getBool("smart-smash")
            && trySmartSmash(mc, player, target, module)) {
            return;
        }

        if (module.getBool("weapon-optimizer") && target != null) {
            optimizeWeapon(mc, player, target, module);
        }

        if (player.onGround() || !isFalling(player)) {
            smashUsedThisFall = false;
            lastSmashFallTick = Integer.MIN_VALUE;
        }
    }

    private void updateBlockingTracks(Minecraft mc, LocalPlayer player, SimpleModule module) {
        long nowTick = mc.level.getGameTime();
        double scanRange = module.getDouble("scan-range");

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)
                || !isTrackable(player, entity, module)
                || player.distanceTo(entity) > scanRange) {
                continue;
            }

            int id = entity.getId();
            UUID uuid = entity.getUUID();
            BlockingTrack state = blockingTracks.get(id);
            if (state == null || !state.uuid.equals(uuid)) {
                state = new BlockingTrack(uuid);
                blockingTracks.put(id, state);
            }

            boolean blocking = living.isUsingItem() && living.isBlocking();
            if (!blocking) {
                state.attackedThisBlock = false;
            }
            state.blocking = blocking;
            state.lastSeenTick = nowTick;
        }

        blockingTracks.entrySet().removeIf(entry ->
            nowTick - entry.getValue().lastSeenTick > 40L);
    }

    private boolean tryShieldBreak(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (target == null || !isBlocking(target)
            || player.distanceTo(target) > module.getDouble("shield-reach")
            || (!module.getBool("ignore-walls") && !player.hasLineOfSight(target))
            || player.getAttackStrengthScale(0.5F) < 0.9F) {
            return false;
        }

        BlockingTrack state = blockingTracks.get(target.getId());
        if (state == null || !state.uuid.equals(target.getUUID())
            || !state.blocking || state.attackedThisBlock
            || mc.level.getGameTime() < state.disabledUntilTick) {
            return false;
        }

        long now = System.nanoTime();
        long cooldownNanos = module.getInt("shield-cooldown-ms") * 1_000_000L;
        if (now - lastShieldBreakNanos < cooldownNanos) {
            return false;
        }

        int axeSlot = findAxeSlot(player);
        if (axeSlot < 0 || !QysAutismAddon.claimAttackTick(player.tickCount)) {
            return false;
        }

        int previousSlot = player.getInventory().getSelectedSlot();
        selectSlot(player, axeSlot);
        mc.gameMode.attack(player, target);

        int returnSlot = resolveShieldReturnSlot(mc, player, target, previousSlot, module);
        if (returnSlot >= 0 && returnSlot != axeSlot) {
            restoreSlot(mc, player, returnSlot);
        }

        state.attackedThisBlock = true;
        state.disabledUntilTick = mc.level.getGameTime() + module.getInt("shield-disabled-ticks");
        lastShieldBreakNanos = now;
        return true;
    }

    private int resolveShieldReturnSlot(
        Minecraft mc, LocalPlayer player, Entity target, int previousSlot, SimpleModule module
    ) {
        return switch (module.getChoice("shield-return")) {
            case "Sword" -> firstOrFallback(findSwordSlot(player), previousSlot);
            case "Mace" -> firstOrFallback(findMaceSlot(player), previousSlot);
            case "Spear" -> firstOrFallback(findSpearSlot(player), previousSlot);
            case "Best Weapon" -> firstOrFallback(choosePostShieldWeaponSlot(mc, player, target, module), previousSlot);
            default -> previousSlot;
        };
    }

    private int firstOrFallback(int preferred, int fallback) {
        return preferred >= 0 ? preferred : fallback;
    }

    private int choosePostShieldWeaponSlot(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (target instanceof Player armored
            && armored.getArmorValue() >= module.getInt("armor-threshold")) {
            int breach = findBreachMaceSlot(mc, player);
            if (breach >= 0) return breach;
        }
        int sword = findSwordSlot(player);
        if (sword >= 0) return sword;
        int mace = findMaceSlot(player);
        if (mace >= 0) return mace;
        int spear = findSpearSlot(player);
        if (spear >= 0) return spear;
        return findAxeSlot(player);
    }

    private boolean trySmartSmash(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (target == null || smashUsedThisFall || !isFalling(player)
            || player.fallDistance < module.getDouble("smash-min-fall")
            || player.distanceTo(target) > module.getDouble("smash-range")
            || (!module.getBool("ignore-walls") && !player.hasLineOfSight(target))
            || player.getAttackStrengthScale(0.5F) < 0.9F) {
            return false;
        }

        int maceSlot = -1;
        if (target instanceof Player armored
            && armored.getArmorValue() >= module.getInt("armor-threshold")) {
            maceSlot = findBreachMaceSlot(mc, player);
        }
        if (maceSlot < 0) {
            maceSlot = findMaceSlot(player);
        }
        if (maceSlot < 0 || !QysAutismAddon.claimAttackTick(player.tickCount)) {
            return false;
        }

        int previousSlot = player.getInventory().getSelectedSlot();
        selectSlot(player, maceSlot);
        mc.gameMode.attack(player, target);
        if (module.getBool("smash-restore-slot")) {
            restoreSlot(mc, player, previousSlot);
        }

        smashUsedThisFall = true;
        lastSmashFallTick = player.tickCount;
        return true;
    }

    private void updateSpearThreats(Minecraft mc, LocalPlayer player, SimpleModule module) {
        long nowTick = mc.level.getGameTime();
        double range = module.getDouble("counter-range");
        Set<Integer> seen = new LinkedHashSet<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player attacker) || attacker == player
                || attacker.isSpectator() || !attacker.isAlive()
                || player.distanceTo(attacker) > range) {
                continue;
            }

            int id = attacker.getId();
            seen.add(id);
            UUID uuid = attacker.getUUID();
            SpearThreatTrack state = spearThreatTracks.get(id);
            if (state == null || !state.uuid.equals(uuid)) {
                state = new SpearThreatTrack(uuid);
                spearThreatTracks.put(id, state);
            }

            boolean threatening = isApproachingSpearAttacker(player, attacker, module);
            boolean newlyThreatening = threatening && !state.threatening;
            state.threatening = threatening;
            state.lastSeenTick = nowTick;

            if (newlyThreatening) {
                triggerSpearCounter(mc, player, attacker, module);
            }
        }

        spearThreatTracks.entrySet().removeIf(entry ->
            !seen.contains(entry.getKey()) && nowTick - entry.getValue().lastSeenTick > 10L);
    }

    private boolean isApproachingSpearAttacker(
        LocalPlayer player, Player attacker, SimpleModule module
    ) {
        if (!attacker.isUsingItem()
            || (!isSpear(attacker.getUseItem()) && !isSpear(attacker.getMainHandItem()))) {
            return false;
        }

        Vec3 toPlayer = player.getBoundingBox().getCenter().subtract(attacker.getEyePosition());
        if (toPlayer.lengthSqr() <= 1.0E-8D) return true;
        Vec3 direction = toPlayer.normalize();
        double dot = attacker.getLookAngle().normalize().dot(direction);
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        double facingAngle = Math.toDegrees(Math.acos(dot));
        if (facingAngle > module.getDouble("counter-facing-angle")) return false;

        Vec3 movement = attacker.getDeltaMovement();
        double towardSpeed = movement.dot(direction);
        // A close, aimed charge is still treated as a threat if the remote
        // player's velocity has not arrived yet due to network interpolation.
        return towardSpeed > 0.015D || player.distanceTo(attacker) <= 4.0D;
    }

    private void triggerSpearCounter(
        Minecraft mc, LocalPlayer player, Player attacker, SimpleModule module
    ) {
        long now = System.nanoTime();
        long cooldownNanos = module.getInt("counter-cooldown-ms") * 1_000_000L;
        if (now - lastSpearCounterNanos < cooldownNanos) return;

        String action = module.getChoice("counter-action");
        int previousSlot = player.getInventory().getSelectedSlot();
        int switchSlot = -1;
        if (action.equals("Switch Sword") || action.equals("Switch + Backstep")) {
            switchSlot = findSwordSlot(player);
        } else if (action.equals("Switch Axe")) {
            switchSlot = findAxeSlot(player);
        }

        if (switchSlot >= 0 && switchSlot != previousSlot) {
            counterReturnSlot = previousSlot;
            selectSlot(player, switchSlot);
            counterSelectedSlot = switchSlot;
            counterAssistTicks = module.getInt("counter-duration");
        }

        if (action.equals("Backstep") || action.equals("Switch + Backstep")) {
            beginCounterMovement(mc.options.keyDown, module);
        } else if (action.equals("Strafe Left")) {
            beginCounterMovement(mc.options.keyLeft, module);
        } else if (action.equals("Strafe Right")) {
            beginCounterMovement(mc.options.keyRight, module);
        }

        if (counterAssistTicks == 0 && counterMoveKey != null) {
            counterAssistTicks = module.getInt("counter-duration");
        }

        lastSpearCounterNanos = now;
    }

    private void beginCounterMovement(KeyMapping key, SimpleModule module) {
        if (counterMoveKey != null && counterMoveKey != key) {
            counterMoveKey.setDown(counterMoveWasDown);
        }
        if (counterMoveKey != key) {
            counterMoveWasDown = key.isDown();
        }
        counterMoveKey = key;
        counterMoveKey.setDown(true);
        counterAssistTicks = Math.max(counterAssistTicks, module.getInt("counter-duration"));
    }

    private void tickCounterAssist(Minecraft mc, LocalPlayer player) {
        if (counterAssistTicks <= 0) return;
        if (counterMoveKey != null) {
            counterMoveKey.setDown(true);
        }

        counterAssistTicks--;
        if (counterAssistTicks > 0) return;

        if (counterMoveKey != null) {
            counterMoveKey.setDown(counterMoveWasDown);
            counterMoveKey = null;
            counterMoveWasDown = false;
        }
        if (counterReturnSlot >= 0 && player.getInventory().getSelectedSlot() == counterSelectedSlot) {
            restoreSlot(mc, player, counterReturnSlot);
        }
        counterReturnSlot = -1;
        counterSelectedSlot = -1;
    }

    private void optimizeWeapon(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        int recommendedSlot = chooseWeaponSlot(mc, player, target, module);
        if (recommendedSlot < 0 || recommendedSlot == player.getInventory().getSelectedSlot()) {
            return;
        }

        long now = System.nanoTime();
        long cooldownNanos = module.getInt("optimizer-cooldown-ms") * 1_000_000L;
        if (now - lastOptimizerSwitchNanos < cooldownNanos) return;

        selectSlot(player, recommendedSlot);
        syncSelectedSlot(mc);
        lastOptimizerSwitchNanos = now;
    }

    private int chooseWeaponSlot(
        Minecraft mc, LocalPlayer player, Entity target, SimpleModule module
    ) {
        if (isBlocking(target)) {
            int axe = findAxeSlot(player);
            if (axe >= 0) return axe;
        }

        double distance = player.distanceTo(target);
        boolean smashWindow = isFalling(player)
            && player.fallDistance >= module.getDouble("smash-min-fall")
            && distance <= module.getDouble("smash-range");
        if (smashWindow) {
            if (target instanceof Player armored
                && armored.getArmorValue() >= module.getInt("armor-threshold")) {
                int breach = findBreachMaceSlot(mc, player);
                if (breach >= 0) return breach;
            }
            int mace = findMaceSlot(player);
            if (mace >= 0) return mace;
        }

        if (distance > module.getDouble("optimizer-spear-min-distance")) {
            int spear = findSpearInRange(player, target);
            if (spear >= 0) return spear;
        }

        if (target instanceof Player armored
            && armored.getArmorValue() >= module.getInt("armor-threshold")) {
            int axe = findAxeSlot(player);
            if (axe >= 0) return axe;
        }

        int sword = findSwordSlot(player);
        if (sword >= 0) return sword;

        int axe = findAxeSlot(player);
        if (axe >= 0) return axe;

        int mace = findMaceSlot(player);
        if (mace >= 0) return mace;

        return findSpearSlot(player);
    }

    private Entity findTarget(Minecraft mc, LocalPlayer player, SimpleModule module) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)
                || !isValidTarget(player, entity, module)
                || player.distanceTo(entity) > module.getDouble("scan-range")) {
                continue;
            }
            candidates.add(living);
        }

        candidates.sort(targetComparator(player, module));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean isValidTarget(LocalPlayer player, Entity entity, SimpleModule module) {
        if (!isTrackable(player, entity, module)) return false;
        if (crosshairAngleToEntity(player, entity) > module.getInt("fov") * 0.5D) return false;
        return module.getBool("ignore-walls") || player.hasLineOfSight(entity);
    }

    private boolean isTrackable(LocalPlayer player, Entity entity, SimpleModule module) {
        if (entity == player || entity.isSpectator() || entity.isRemoved()
            || !(entity instanceof LivingEntity living) || !living.isAlive()
            || !EntitySelector.CAN_BE_PICKED.test(entity) || entity.hasPassenger(player)
            || !matchesEntity(entity)) {
            return false;
        }

        if (entity instanceof Player other) {
            if (other.isSleeping()) return false;
            if (module.getBool("teams-check") && player.isAlliedTo(other)) return false;
        }
        return true;
    }

    private boolean matchesEntity(Entity entity) {
        Set<String> ids = entityIds();
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase(Locale.ROOT);
        int separator = id.indexOf(':');
        return ids.contains(id) || separator >= 0 && ids.contains(id.substring(separator + 1));
    }

    private Set<String> entityIds() {
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

    private double crosshairAngleToEntity(LocalPlayer player, Entity entity) {
        Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(player.getEyePosition());
        if (toTarget.lengthSqr() <= 1.0E-12D) return 0.0D;
        double dot = player.getLookAngle().normalize().dot(toTarget.normalize());
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private boolean isBlocking(Entity target) {
        return target instanceof LivingEntity living && living.isUsingItem() && living.isBlocking();
    }

    private boolean isFalling(LocalPlayer player) {
        return !player.onGround() && player.getDeltaMovement().y < 0.0D;
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
            if (stack.getItem() instanceof MaceItem
                && enchantmentLevel(mc, stack, Enchantments.BREACH) > 0) {
                return i;
            }
        }
        return -1;
    }

    private int enchantmentLevel(
        Minecraft mc, ItemStack stack,
        net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key
    ) {
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

    private int findSwordSlot(LocalPlayer player) {
        if (isSword(player.getMainHandItem())) return player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (isSword(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int findSpearSlot(LocalPlayer player) {
        if (isSpear(player.getMainHandItem())) return player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (isSpear(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private int findSpearInRange(LocalPlayer player, Entity target) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!isSpear(stack)) continue;
            AttackRange range = stack.get(DataComponents.ATTACK_RANGE);
            if (range != null && range.isInRange(player, target.getBoundingBox(), 0.0D)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSpear(ItemStack stack) {
        return !stack.isEmpty()
            && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
    }

    private boolean isSword(ItemStack stack) {
        return !stack.isEmpty()
            && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_sword");
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

    private void clearTransientState(LocalPlayer player) {
        if (counterMoveKey != null) {
            counterMoveKey.setDown(counterMoveWasDown);
        }
        if (player != null && counterReturnSlot >= 0
            && player.getInventory().getSelectedSlot() == counterSelectedSlot) {
            player.getInventory().setSelectedSlot(counterReturnSlot);
            player.getInventory().setChanged();
        }

        counterMoveKey = null;
        counterMoveWasDown = false;
        counterAssistTicks = 0;
        counterReturnSlot = -1;
        counterSelectedSlot = -1;
        blockingTracks.clear();
        spearThreatTracks.clear();
        smashUsedThisFall = false;
        lastSmashFallTick = Integer.MIN_VALUE;
    }

    private void disableCombat(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        clearTransientState(mc.player);
    }

    private static final class BlockingTrack {
        private final UUID uuid;
        private boolean blocking;
        private boolean attackedThisBlock;
        private long disabledUntilTick;
        private long lastSeenTick;

        private BlockingTrack(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private static final class SpearThreatTrack {
        private final UUID uuid;
        private boolean threatening;
        private long lastSeenTick;

        private SpearThreatTrack(UUID uuid) {
            this.uuid = uuid;
        }
    }
}
