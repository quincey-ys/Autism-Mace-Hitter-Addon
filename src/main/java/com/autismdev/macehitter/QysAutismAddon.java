package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.SimpleModule;
import com.autismdev.macehitter.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
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

    // Spear Double Pop: watch a native spear charge, then follow a confirmed hit
    // with a mace smash only when the mace's own range/fall/cooldown checks pass.
    private Entity spearChargeTarget;
    private int spearChargeSlot = -1;
    private int spearChargeTicks;
    private int spearLastHurtTime;
    private float spearLastHealth;
    private float spearLastAbsorption;

    // Delayed slot restoration keeps the post-attack swap visible to the server
    // for a full client tick instead of immediately undoing the swap packet.
    private int pendingRestoreSlot = -1;
    private int pendingRestoreTicks;

    private Entity spearFollowupTarget;
    private int spearFollowupMaceSlot = -1;
    private int spearFollowupReturnSlot = -1;
    private int spearFollowupTicks;

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
            .addDouble("spear-range", "Spear Charge Tracking Range", 4.5, 3.2, 4.5, 0.1)
            .addDouble("min-fall-distance", "Minimum Smash Fall", 1.5, 0.5, 6.0, 0.1)
            .addInt("spear-followup-window", "Spear Follow-up Window (ticks)", 40, 10, 100, 1)
            .addBool("wait-hurt-reset", "Wait for Target Hurt Window", true)
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
        cancelSpearSequence(mc, mc.player, true);
        currentTarget = null;
        delayCounter = 0;
    }

    private void onMaceTick(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
            releaseJump(mc);
            clearSpearTracking();
            clearSpearFollowup();
            stunTarget = null;
            stunMaceSlot = -1;
            stunReturnSlot = -1;
            stunTicks = 0;
            currentTarget = null;
            return;
        }

        if (pendingRestoreTicks > 0 && --pendingRestoreTicks == 0) {
            restoreSlot(mc, player, pendingRestoreSlot);
            pendingRestoreSlot = -1;
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

        if (spearFollowupTarget != null) {
            tickSpearFollowup(mc, player, module);
            return;
        }

        Entity target = findTarget(mc, module);
        currentTarget = target;

        // Do not interrupt a real spear charge. Track the target's actual hit
        // confirmation; switching before the spear connects would cancel it.
        if (module.getBool("spear-double-pop")
            && (spearChargeTarget != null || (player.isUsingItem() && isSpear(player.getUseItem())))) {
            if (updateSpearCharge(mc, player, target, module)) {
                return;
            }
            if (spearChargeTarget != null) {
                return;
            }
        } else {
            clearSpearTracking();
        }

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
            && player.getMainHandItem().getItem() instanceof SwordItem
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
     * Tracks a spear while the player is genuinely using it. The spear hit is
     * detected from the target's hurt-time/health update. Only after that hit
     * is confirmed do we swap to the mace and wait for a legal follow-up smash.
     */
    private boolean updateSpearCharge(
        Minecraft mc, LocalPlayer player, Entity selectedTarget, SimpleModule module
    ) {
        if (spearChargeTarget == null) {
            if (!player.isUsingItem() || !isSpear(player.getUseItem()) || selectedTarget == null) {
                return false;
            }
            if (player.distanceTo(selectedTarget) > module.getDouble("spear-range")
                || !hasLineOfSight(player, selectedTarget)) {
                return false;
            }

            spearChargeTarget = selectedTarget;
            spearChargeSlot = player.getInventory().getSelectedSlot();
            spearChargeTicks = 0;
            spearLastHurtTime = selectedTarget instanceof LivingEntity living ? living.hurtTime : 0;
            spearLastHealth = selectedTarget instanceof LivingEntity living ? living.getHealth() : 0.0F;
            spearLastAbsorption = selectedTarget instanceof LivingEntity living ? living.getAbsorptionAmount() : 0.0F;
            return true;
        }

        spearChargeTicks++;
        if (!spearChargeTarget.isAlive()
            || spearChargeTicks > 120
            || player.getInventory().getSelectedSlot() != spearChargeSlot
            || player.distanceTo(spearChargeTarget) > module.getDouble("spear-range") + 1.0) {
            clearSpearTracking();
            return false;
        }

        if (!(spearChargeTarget instanceof LivingEntity living)) {
            clearSpearTracking();
            return false;
        }

        float health = living.getHealth();
        float absorption = living.getAbsorptionAmount();
        boolean tookDamage = living.hurtTime > spearLastHurtTime
            || health + absorption < spearLastHealth + spearLastAbsorption - 0.01F;

        spearLastHurtTime = living.hurtTime;
        spearLastHealth = health;
        spearLastAbsorption = absorption;

        if (!tookDamage) {
            return true;
        }

        int maceSlot = findMaceSlot(player);
        if (maceSlot < 0) {
            clearSpearTracking();
            return false;
        }

        spearFollowupTarget = spearChargeTarget;
        spearFollowupMaceSlot = maceSlot;
        spearFollowupReturnSlot = spearChargeSlot;
        spearFollowupTicks = 0;
        clearSpearTracking();

        // Sync immediately so the spear charge ends on the server after its
        // confirmed collision. The mace follow-up itself still waits for range,
        // hurt-window, falling-smash, and cooldown conditions.
        selectSlot(player, maceSlot);
        syncSelectedSlot(mc);
        return true;
    }

    private void tickSpearFollowup(Minecraft mc, LocalPlayer player, SimpleModule module) {
        spearFollowupTicks++;
        if (spearFollowupTarget == null || !spearFollowupTarget.isAlive()
            || spearFollowupTicks > module.getInt("spear-followup-window")
            || spearFollowupMaceSlot < 0 || spearFollowupMaceSlot > 8
            || !(player.getInventory().getItem(spearFollowupMaceSlot).getItem() instanceof MaceItem)) {
            cancelSpearSequence(mc, player, true);
            return;
        }

        // Respect a manual slot change instead of fighting the player's input.
        if (player.getInventory().getSelectedSlot() != spearFollowupMaceSlot) {
            cancelSpearSequence(mc, player, false);
            return;
        }

        if (module.getBool("rotate")) {
            rotateTo(player, spearFollowupTarget);
        }

        double legalMaceRange = Math.min(module.getDouble("range"), 3.2);
        if (player.distanceTo(spearFollowupTarget) > legalMaceRange
            || !hasLineOfSight(player, spearFollowupTarget)) {
            return;
        }

        // Wait out the spear charge's hurt window so the mace can be a distinct
        // second hit rather than being swallowed by the target's damage immunity.
        if (module.getBool("wait-hurt-reset")
            && spearFollowupTarget instanceof LivingEntity living
            && living.hurtTime > 0) {
            return;
        }

        if (!isSmashReady(player, module) || player.getAttackStrengthScale(0.5F) < 0.9F) {
            return;
        }

        mc.gameMode.attack(player, spearFollowupTarget);
        if (module.getBool("restore-slot")) {
            restoreSlot(mc, player, spearFollowupReturnSlot);
        }

        clearSpearFollowup();
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
        if (module.getBool("spear-double-pop") && isSpear(player.getMainHandItem())) {
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
        // Attribute/Breach swapping is a post-hit sequence: attack with the
        // source weapon first, then switch to the mace immediately afterward.
        mc.gameMode.attack(player, target);
        selectSlot(player, swapSlot);
        syncSelectedSlot(mc);
        if (restore) {
            pendingRestoreSlot = originalSlot;
            pendingRestoreTicks = 1;
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

    private void cancelSpearSequence(Minecraft mc, LocalPlayer player, boolean restore) {
        if (restore && player != null && spearFollowupMaceSlot >= 0
            && player.getInventory().getSelectedSlot() == spearFollowupMaceSlot) {
            restoreSlot(mc, player, spearFollowupReturnSlot);
        }
        clearSpearTracking();
        clearSpearFollowup();
    }

    private void clearSpearTracking() {
        spearChargeTarget = null;
        spearChargeSlot = -1;
        spearChargeTicks = 0;
        spearLastHurtTime = 0;
        spearLastHealth = 0.0F;
        spearLastAbsorption = 0.0F;
    }

    private void clearSpearFollowup() {
        spearFollowupTarget = null;
        spearFollowupMaceSlot = -1;
        spearFollowupReturnSlot = -1;
        spearFollowupTicks = 0;
    }

    private void releaseJump(Minecraft mc) {
        mc.options.keyJump.setDown(false);
    }
}
