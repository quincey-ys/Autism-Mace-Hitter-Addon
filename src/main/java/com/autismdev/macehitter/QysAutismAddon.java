package com.autismdev.macehitter;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.module.SimpleModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;

/**
 * QYS Addon entrypoint.
 *
 * The addon keeps its legacy Fabric ID and module setting IDs so existing
 * AUTISM Client configuration remains associated with the same addon/module.
 */
public final class QysAutismAddon extends SimpleAddon {
    public static final String ID = "autism-mace-hitter";

    private int delayCounter;
    private Entity currentTarget;

    public QysAutismAddon() {
        super(ApiVersion.CURRENT, "com.autismdev.macehitter");
    }

    @Override
    protected void initialize() {
        SimpleModule mace = new SimpleModule(
            "mace-hitter",
            "Mace",
            "Situational mace combat: axe stun slam, mace attribute swap, and spear-reach conversion."
        )
            .addDouble("range", "Normal Range", 3.2, 1.0, 6.0, 0.1)
            .addInt("delay", "Attack Delay (ticks)", 0, 0, 20, 1)
            .addBool("rotate", "Rotate to Target", true)
            .addBool("auto-swap", "Auto Equip Mace", true)
            .addBool("auto-jump", "Auto Jump", true)
            .addBool("falling-only", "Only Attack While Falling", false)
            // Keep the old setting ID for existing user configs.
            .addBool("shield-stun", "Stun Slam (Axe → Mace)", true)
            .addBool("mace-attribute-swap", "Mace Attribute Swap", false)
            .addBool("spear-mace-swap", "Spear Reach → Mace Swap", false)
            .addDouble("spear-range", "Spear Reach", 4.5, 3.2, 6.0, 0.1)
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
        releaseJump(Minecraft.getInstance());
        currentTarget = null;
        delayCounter = 0;
    }

    private void onTick(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
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

        if (module.getBool("auto-jump") && player.onGround()) {
            mc.options.keyJump.setDown(true);
        } else if (player.onGround()) {
            mc.options.keyJump.setDown(false);
        }

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        boolean blocking = isBlocking(target);
        int originalSlot = player.getInventory().selected;
        int maceSlot = findMaceSlot(player);
        int axeSlot = findAxeSlot(player);
        double distance = player.distanceTo(target);

        // Shield users get the actual axe -> mace sequence. The first attack
        // disables the shield; the second immediately follows with the mace.
        if (blocking && module.getBool("shield-stun") && axeSlot >= 0 && maceSlot >= 0) {
            performStunSlam(mc, player, target, axeSlot, maceSlot, originalSlot, module.getBool("restore-slot"));
            delayCounter = module.getInt("delay");
            return;
        }

        // When the spear is already in the main hand, its reach attributes
        // become the source attributes for a same-tick mace swap. This path is
        // only considered outside normal range, where the extra reach matters.
        boolean holdingSpear = isSpear(player.getMainHandItem());
        if (module.getBool("spear-mace-swap")
            && holdingSpear
            && distance > module.getDouble("range")
            && distance <= module.getDouble("spear-range")
            && maceSlot >= 0) {
            performAttributeSwap(mc, player, target, maceSlot, originalSlot, module.getBool("restore-slot"));
            delayCounter = module.getInt("delay");
            return;
        }

        // A falling player can transfer the held weapon's attack damage and
        // speed to the mace while keeping the mace's smash/enchantment behavior.
        if (module.getBool("mace-attribute-swap")
            && isFalling(player)
            && maceSlot >= 0
            && originalSlot != maceSlot) {
            performAttributeSwap(mc, player, target, maceSlot, originalSlot, module.getBool("restore-slot"));
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

    private void performStunSlam(
        Minecraft mc,
        LocalPlayer player,
        Entity target,
        int axeSlot,
        int maceSlot,
        int originalSlot,
        boolean restoreSlot
    ) {
        selectSlot(player, axeSlot);
        mc.gameMode.attack(player, target);

        selectSlot(player, maceSlot);
        mc.gameMode.attack(player, target);

        if (restoreSlot) {
            restoreSlot(mc, player, originalSlot);
        }
    }

    /**
     * Selects the swap item, performs the vanilla attack (which sends the
     * selected-slot packet before the attack packet), then sends the original
     * slot back immediately. This is the packet ordering used by same-tick
     * attribute swapping; server support is required.
     */
    private void performAttributeSwap(
        Minecraft mc,
        LocalPlayer player,
        Entity target,
        int swapSlot,
        int originalSlot,
        boolean restoreSlot
    ) {
        selectSlot(player, swapSlot);
        mc.gameMode.attack(player, target);

        if (restoreSlot) {
            restoreSlot(mc, player, originalSlot);
        }
    }

    private void restoreSlot(Minecraft mc, LocalPlayer player, int slot) {
        selectSlot(player, slot);
        if (mc.gameMode instanceof MultiPlayerGameMode gameMode) {
            ((MultiPlayerGameModeInvoker) gameMode).qys$ensureHasSentCarriedItem();
        }
    }

    private void selectSlot(LocalPlayer player, int slot) {
        if (slot < 0 || slot > 8 || player.getInventory().selected == slot) {
            return;
        }
        player.getInventory().selected = slot;
        player.getInventory().setChanged();
    }

    private int findMaceSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) {
            return player.getInventory().selected;
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof MaceItem) {
                return i;
            }
        }
        return -1;
    }

    private int findAxeSlot(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof AxeItem) {
            return player.getInventory().selected;
        }
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private boolean ensureMace(LocalPlayer player, SimpleModule module) {
        if (player.getMainHandItem().getItem() instanceof MaceItem) {
            return true;
        }
        if (!module.getBool("auto-swap")) {
            return false;
        }

        int slot = findMaceSlot(player);
        if (slot < 0) {
            return false;
        }
        selectSlot(player, slot);
        return true;
    }

    private boolean isSpear(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.endsWith("_spear");
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
        if (player == null || mc.level == null || mc.gameMode == null) {
            return;
        }

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
