package com.autismdev.macehitter;

import autismclient.api.module.KeybindSetting;
import autismclient.api.module.SimpleModule;
import autismclient.api.module.StringSetting;
import autismclient.util.AutismBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.Locale;

/**
 * YouTube Music playlist launcher and global media-key controller.
 *
 * The playlist URL is user-configurable. Media keys are sent to the operating
 * system's active media session; the browser may still require a user gesture
 * before YouTube Music starts playback.
 */
public final class MusicPlayerModule extends SimpleModule {
    private Robot mediaRobot;

    private boolean openWasDown;
    private boolean playPauseWasDown;
    private boolean nextWasDown;
    private boolean previousWasDown;
    private boolean volumeUpWasDown;
    private boolean volumeDownWasDown;
    private boolean pvpWasActive;

    public MusicPlayerModule() {
        super(
            "music-player",
            "Music Player",
            "Launch any configured YouTube Music playlist, auto-open it near PvP, and control the active system media session."
        );

        add(new StringSetting("playlist-url", "YouTube Music Playlist URL", "")
            .group("Playlist")
            .description("Paste a YouTube Music playlist URL, such as https://music.youtube.com/playlist?list=...")
            .build());

        addBool("auto-open-pvp", "Auto Open In PvP", true)
            .addDouble("pvp-range", "PvP Detection Range", 12.0, 3.0, 32.0, 1.0)
            .addBool("pvp-line-of-sight", "Require Line Of Sight", true)
            .addBool("pvp-ignore-teammates", "Ignore Teammates", true)
            .addBool("media-controls", "Enable Media Controls", true);

        add(new KeybindSetting("open-key", "Open Playlist Key", GLFW.GLFW_KEY_M)
            .group("Keybinds")
            .description("Opens the configured playlist in your default browser.")
            .build());
        add(new KeybindSetting("play-pause-key", "Play / Pause Key", GLFW.GLFW_KEY_P)
            .group("Keybinds")
            .description("Sends the system Play/Pause media key.")
            .build());
        add(new KeybindSetting("next-key", "Next Track Key", GLFW.GLFW_KEY_RIGHT_BRACKET)
            .group("Keybinds")
            .description("Sends the system Next Track media key.")
            .build());
        add(new KeybindSetting("previous-key", "Previous Track Key", GLFW.GLFW_KEY_LEFT_BRACKET)
            .group("Keybinds")
            .description("Sends the system Previous Track media key.")
            .build());
        add(new KeybindSetting("volume-up-key", "Volume Up Key", GLFW.GLFW_KEY_EQUAL)
            .group("Keybinds")
            .description("Raises system volume through the operating system.")
            .build());
        add(new KeybindSetting("volume-down-key", "Volume Down Key", GLFW.GLFW_KEY_MINUS)
            .group("Keybinds")
            .description("Lowers system volume through the operating system.")
            .build());

        onTick(this::tickMusicPlayer)
            .onDisabled(module -> resetState());
    }

    private void tickMusicPlayer(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null || !player.isAlive()) {
            resetState();
            return;
        }

        boolean canUseKeys = mc.screen == null;
        boolean openDown = canUseKeys && isPressed(mc, module.getInt("open-key"));
        boolean playPauseDown = canUseKeys && module.getBool("media-controls")
            && isPressed(mc, module.getInt("play-pause-key"));
        boolean nextDown = canUseKeys && module.getBool("media-controls")
            && isPressed(mc, module.getInt("next-key"));
        boolean previousDown = canUseKeys && module.getBool("media-controls")
            && isPressed(mc, module.getInt("previous-key"));
        boolean volumeUpDown = canUseKeys && module.getBool("media-controls")
            && isPressed(mc, module.getInt("volume-up-key"));
        boolean volumeDownDown = canUseKeys && module.getBool("media-controls")
            && isPressed(mc, module.getInt("volume-down-key"));

        if (openDown && !openWasDown) {
            openPlaylist(mc, module);
        }
        if (playPauseDown && !playPauseWasDown) {
            sendMediaKey(mc, KeyEvent.VK_MEDIA_PLAY_PAUSE);
        }
        if (nextDown && !nextWasDown) {
            sendMediaKey(mc, KeyEvent.VK_MEDIA_NEXT_TRACK);
        }
        if (previousDown && !previousWasDown) {
            sendMediaKey(mc, KeyEvent.VK_MEDIA_PREVIOUS_TRACK);
        }
        if (volumeUpDown && !volumeUpWasDown) {
            sendMediaKey(mc, KeyEvent.VK_VOLUME_UP);
        }
        if (volumeDownDown && !volumeDownWasDown) {
            sendMediaKey(mc, KeyEvent.VK_VOLUME_DOWN);
        }

        openWasDown = openDown;
        playPauseWasDown = playPauseDown;
        nextWasDown = nextDown;
        previousWasDown = previousDown;
        volumeUpWasDown = volumeUpDown;
        volumeDownWasDown = volumeDownDown;

        boolean pvpActive = hasNearbyEnemyPlayer(mc, player, module);
        if (module.getBool("auto-open-pvp") && pvpActive && !pvpWasActive) {
            openPlaylist(mc, module);
        }
        pvpWasActive = pvpActive;
    }

    private boolean hasNearbyEnemyPlayer(
        Minecraft mc, LocalPlayer player, SimpleModule module
    ) {
        double range = module.getDouble("pvp-range");
        double rangeSquared = range * range;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player other)
                || other == player
                || !other.isAlive()
                || other.isSpectator()
                || other.isRemoved()
                || player.distanceToSqr(other) > rangeSquared) {
                continue;
            }

            if (module.getBool("pvp-ignore-teammates") && player.isAlliedTo(other)) {
                continue;
            }
            if (module.getBool("pvp-line-of-sight") && !player.hasLineOfSight(other)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private void openPlaylist(Minecraft mc, SimpleModule module) {
        String rawUrl = module.getText("playlist-url");
        URI playlistUri = parsePlaylistUri(rawUrl);
        if (playlistUri == null) {
            notifyPlayer(mc, "Set a valid YouTube Music playlist URL in Music Player settings first.");
            return;
        }

        try {
            Util.getPlatform().openUri(playlistUri);
        } catch (RuntimeException exception) {
            notifyPlayer(mc, "Could not open the playlist URL. Check your default browser.");
        }
    }

    private URI parsePlaylistUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;

        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean youtubeHost = normalizedHost.equals("youtube.com")
                || normalizedHost.endsWith(".youtube.com");
            boolean musicHost = normalizedHost.equals("music.youtube.com");
            if (!youtubeHost && !musicHost) return null;

            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
            boolean hasPlaylistId = false;
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("list=") && parameter.length() > 5) {
                    hasPlaylistId = true;
                    break;
                }
            }

            return hasPlaylistId || path.equals("/playlist") ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isPressed(Minecraft mc, int keyCode) {
        return keyCode != -1 && AutismBindUtil.isBindPressed(mc, keyCode);
    }

    private void sendMediaKey(Minecraft mc, int keyCode) {
        try {
            if (mediaRobot == null) {
                mediaRobot = new Robot();
            }
            mediaRobot.keyPress(keyCode);
            mediaRobot.keyRelease(keyCode);
        } catch (Exception exception) {
            notifyPlayer(mc, "System media controls are unavailable on this device.");
        }
    }

    private void notifyPlayer(Minecraft mc, String message) {
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(Component.literal("[Music Player] " + message));
        }
    }

    private void resetState() {
        openWasDown = false;
        playPauseWasDown = false;
        nextWasDown = false;
        previousWasDown = false;
        volumeUpWasDown = false;
        volumeDownWasDown = false;
        pvpWasActive = false;
    }
}
