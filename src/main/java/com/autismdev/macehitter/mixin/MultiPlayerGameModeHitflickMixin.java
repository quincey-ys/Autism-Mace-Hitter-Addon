package com.autismdev.macehitter.mixin;

import com.autismdev.macehitter.HitflickController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends a short-lived server-side yaw flick around the local player's attack. */
@Mixin(targets = "net.minecraft.client.multiplayer.MultiPlayerGameMode")
public abstract class MultiPlayerGameModeHitflickMixin {
    @Unique
    private static final ThreadLocal<Float> qys$restoreYaw = new ThreadLocal<>();

    @Inject(method = "attack", at = @At("HEAD"))
    private void qys$beforeAttack(Player player, Entity target, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player || mc.getConnection() == null) return;

        Float offset = HitflickController.getAttackYawOffset(player.isSprinting(), player.getYRot());
        if (offset == null) return;

        float originalYaw = player.getYRot();
        float flickYaw = net.minecraft.util.Mth.wrapDegrees(originalYaw + offset);
        qys$restoreYaw.set(originalYaw);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
            flickYaw, player.getXRot(), player.onGround(), player.horizontalCollision
        ));
    }

    @Inject(method = "attack", at = @At("RETURN"))
    private void qys$afterAttack(Player player, Entity target, CallbackInfo ci) {
        Float originalYaw = qys$restoreYaw.get();
        if (originalYaw == null) return;
        qys$restoreYaw.remove();

        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (player != mc.player || connection == null) return;

        connection.send(new ServerboundMovePlayerPacket.Rot(
            originalYaw, player.getXRot(), player.onGround(), player.horizontalCollision
        ));
    }
}
