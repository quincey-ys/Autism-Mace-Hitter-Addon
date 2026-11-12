package com.autismdev.macehitter;

import autismclient.api.module.KeybindSetting;
import autismclient.api.module.SimpleModule;
import autismclient.api.module.StringSetting;
import autismclient.util.AutismBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-game music player. Audio is decoded client-side and mixed by Java Sound
 * while Minecraft is running. Supports local audio files, direct audio URLs,
 * and M3U playlists; it does not scrape protected music services.
 */
public final class MusicPlayerModule extends SimpleModule {
    private enum State {
        STOPPED, LOADING, PLAYING, PAUSED, ERROR
    }

    private record Track(String source, String title) {
    }

    private final AtomicLong playbackGeneration = new AtomicLong();

    private volatile State state = State.STOPPED;
    private volatile SourceDataLine activeLine;
    private volatile boolean paused;
    private volatile boolean trackFinished;
    private volatile String playbackError;

    private Thread playbackThread;
    private List<Track> playlist = List.of();
    private int currentIndex = -1;
    private int volume = 80;

    private boolean playWasDown;
    private boolean pauseWasDown;
    private boolean nextWasDown;
    private boolean previousWasDown;
    private boolean stopWasDown;
    private boolean volumeUpWasDown;
    private boolean volumeDownWasDown;
    private boolean shuffleWasDown;
    private boolean pvpWasActive;
    private boolean startedByPvp;

    public MusicPlayerModule() {
        super(
            "music-player",
            "Music Player",
            "Plays local audio and direct audio streams in-game with M3U playlists, hotkeys, shuffle, looping, and PvP auto-start."
        );

        add(new StringSetting("playlist-source", "Playlist Source", "")
            .group("Playlist")
            .description("Local audio path, direct MP3/OGG/WAV URL, or an M3U playlist file/URL. Relative paths use the Minecraft game folder.")
            .build());

        addBool("auto-start-pvp", "Auto Start In PvP", true)
            .addDouble("pvp-range", "PvP Detection Range", 12.0, 3.0, 32.0, 1.0)
            .addBool("pvp-line-of-sight", "Require Line Of Sight", true)
            .addBool("pvp-ignore-teammates", "Ignore Teammates", true)
            .addBool("pause-when-pvp-ends", "Pause When PvP Ends", false)
            .addBool("shuffle", "Shuffle", false)
            .addChoice("loop-mode", "Loop Mode", "Playlist", "Off", "Track", "Playlist")
            .addInt("volume", "Player Volume (%)", 80, 0, 100, 5);

        add(new KeybindSetting("play-key", "Play Playlist Key", GLFW.GLFW_KEY_M)
            .group("Keybinds")
            .description("Loads the configured playlist and starts playback.")
            .build());
        add(new KeybindSetting("pause-key", "Play / Pause Key", GLFW.GLFW_KEY_P)
            .group("Keybinds")
            .description("Pauses or resumes the in-game player.")
            .build());
        add(new KeybindSetting("next-key", "Next Track Key", GLFW.GLFW_KEY_RIGHT_BRACKET)
            .group("Keybinds").build());
        add(new KeybindSetting("previous-key", "Previous Track Key", GLFW.GLFW_KEY_LEFT_BRACKET)
            .group("Keybinds").build());
        add(new KeybindSetting("stop-key", "Stop Key", GLFW.GLFW_KEY_BACKSPACE)
            .group("Keybinds").build());
        add(new KeybindSetting("volume-up-key", "Volume Up Key", GLFW.GLFW_KEY_PAGE_UP)
            .group("Keybinds").build());
        add(new KeybindSetting("volume-down-key", "Volume Down Key", GLFW.GLFW_KEY_PAGE_DOWN)
            .group("Keybinds").build());
        add(new KeybindSetting("shuffle-key", "Toggle Shuffle Key", GLFW.GLFW_KEY_R)
            .group("Keybinds").build());

        onTick(this::tickMusicPlayer)
            .onDisabled(module -> stopPlayback());
    }

    private void tickMusicPlayer(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !player.isAlive()) {
            pvpWasActive = false;
            if (module.getBool("pause-when-pvp-ends") && startedByPvp) {
                pausePlayback();
                startedByPvp = false;
            }
            return;
        }

        volume = module.getInt("volume");
        applyVolume();

        boolean acceptKeys = mc.screen == null;
        boolean playDown = acceptKeys && pressed(mc, module, "play-key");
        boolean pauseDown = acceptKeys && pressed(mc, module, "pause-key");
        boolean nextDown = acceptKeys && pressed(mc, module, "next-key");
        boolean previousDown = acceptKeys && pressed(mc, module, "previous-key");
        boolean stopDown = acceptKeys && pressed(mc, module, "stop-key");
        boolean volumeUpDown = acceptKeys && pressed(mc, module, "volume-up-key");
        boolean volumeDownDown = acceptKeys && pressed(mc, module, "volume-down-key");
        boolean shuffleDown = acceptKeys && pressed(mc, module, "shuffle-key");

        if (playDown && !playWasDown) {
            startedByPvp = false;
            loadAndPlay(mc, module);
        }
        if (pauseDown && !pauseWasDown) {
            startedByPvp = false;
            togglePause(mc);
        }
        if (nextDown && !nextWasDown) {
            startedByPvp = false;
            nextTrack(mc, module);
        }
        if (previousDown && !previousWasDown) {
            startedByPvp = false;
            previousTrack(mc, module);
        }
        if (stopDown && !stopWasDown) {
            startedByPvp = false;
            stopPlayback();
            notifyPlayer(mc, "Playback stopped.");
        }
        if (volumeUpDown && !volumeUpWasDown) {
            module.setValue("volume", Integer.toString(Math.min(100, module.getInt("volume") + 5)));
            volume = module.getInt("volume");
            applyVolume();
            notifyVolume(mc);
        }
        if (volumeDownDown && !volumeDownWasDown) {
            module.setValue("volume", Integer.toString(Math.max(0, module.getInt("volume") - 5)));
            volume = module.getInt("volume");
            applyVolume();
            notifyVolume(mc);
        }
        if (shuffleDown && !shuffleWasDown) {
            module.setValue("shuffle", Boolean.toString(!module.getBool("shuffle")));
            notifyPlayer(mc, "Shuffle " + (module.getBool("shuffle") ? "enabled." : "disabled."));
        }

        playWasDown = playDown;
        pauseWasDown = pauseDown;
        nextWasDown = nextDown;
        previousWasDown = previousDown;
        stopWasDown = stopDown;
        volumeUpWasDown = volumeUpDown;
        volumeDownWasDown = volumeDownDown;
        shuffleWasDown = shuffleDown;

        boolean pvpActive = hasNearbyEnemyPlayer(mc, player, module);
        if (module.getBool("auto-start-pvp") && pvpActive && !pvpWasActive
            && state != State.PLAYING && state != State.LOADING) {
            startedByPvp = true;
            loadAndPlay(mc, module);
        } else if (!pvpActive && pvpWasActive
            && module.getBool("pause-when-pvp-ends") && startedByPvp) {
            pausePlayback();
            startedByPvp = false;
            notifyPlayer(mc, "Paused because PvP ended.");
        }
        pvpWasActive = pvpActive;

        String error = playbackError;
        if (error != null) {
            playbackError = null;
            state = State.ERROR;
            notifyPlayer(mc, "Playback error: " + error);
        }

        if (trackFinished) {
            trackFinished = false;
            advanceAfterTrack(mc, module);
        }
    }

    private boolean pressed(Minecraft mc, SimpleModule module, String setting) {
        int key = module.getInt(setting);
        return key != -1 && AutismBindUtil.isBindPressed(mc, key);
    }

    private boolean hasNearbyEnemyPlayer(
        Minecraft mc, LocalPlayer player, SimpleModule module
    ) {
        double rangeSquared = module.getDouble("pvp-range") * module.getDouble("pvp-range");
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player other)
                || other == player
                || !other.isAlive()
                || other.isSpectator()
                || other.isRemoved()
                || player.distanceToSqr(other) > rangeSquared) {
                continue;
            }
            if (module.getBool("pvp-ignore-teammates") && player.isAlliedTo(other)) continue;
            if (module.getBool("pvp-line-of-sight") && !player.hasLineOfSight(other)) continue;
            return true;
        }
        return false;
    }

    private void loadAndPlay(Minecraft mc, SimpleModule module) {
        String source = module.getText("playlist-source");
        if (source == null || source.isBlank()) {
            notifyPlayer(mc, "Set Playlist Source first: use a local audio file, direct audio URL, or .m3u playlist.");
            return;
        }

        try {
            List<Track> loaded = parsePlaylist(source);
            if (loaded.isEmpty()) {
                notifyPlayer(mc, "The playlist is empty or contains no playable entries.");
                return;
            }
            playlist = List.copyOf(loaded);
            currentIndex = module.getBool("shuffle")
                ? ThreadLocalRandom.current().nextInt(playlist.size()) : 0;
            playIndex(mc);
            notifyPlayer(mc, "Loaded " + playlist.size() + " track(s).");
        } catch (IOException exception) {
            notifyPlayer(mc, "Could not load playlist: " + safeMessage(exception));
        }
    }

    private List<Track> parsePlaylist(String source) throws IOException {
        String trimmed = source.trim();
        if (isM3u(trimmed)) {
            return readM3u(trimmed);
        }

        String[] entries = trimmed.split("[\\r\\n;]+");
        List<Track> result = new ArrayList<>();
        for (String entry : entries) {
            String value = entry.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (isM3u(value)) {
                result.addAll(readM3u(value));
            } else {
                result.add(new Track(value, titleFromSource(value)));
            }
        }
        return result;
    }

    private boolean isM3u(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) lower = lower.substring(0, query);
        return lower.endsWith(".m3u");
    }

    private List<Track> readM3u(String source) throws IOException {
        List<Track> result = new ArrayList<>();
        String pendingTitle = null;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(openSource(source), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                if (value.regionMatches(true, 0, "#EXTINF:", 0, 8)) {
                    int comma = value.indexOf(',');
                    pendingTitle = comma >= 0 && comma + 1 < value.length()
                        ? value.substring(comma + 1).trim() : null;
                    continue;
                }
                if (value.startsWith("#")) continue;
                String resolved = resolvePlaylistEntry(source, value);
                result.add(new Track(resolved,
                    pendingTitle == null || pendingTitle.isBlank()
                        ? titleFromSource(resolved) : pendingTitle));
                pendingTitle = null;
            }
        }
        return result;
    }

    private String resolvePlaylistEntry(String playlistSource, String entry) {
        if (isHttp(entry) || entry.startsWith("file:")) return entry;
        if (isHttp(playlistSource)) {
            return URI.create(playlistSource).resolve(entry).toString();
        }
        try {
            Path playlistPath = toLocalPath(playlistSource);
            Path parent = playlistPath.toAbsolutePath().getParent();
            return (parent == null ? Path.of(entry) : parent.resolve(entry)).normalize().toString();
        } catch (Exception ignored) {
            return entry;
        }
    }

    private InputStream openSource(String source) throws IOException {
        if (isHttp(source)) {
            URL url = URI.create(source).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "QYS-MusicPlayer/1.0");
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("HTTP " + status);
            }
            return new BufferedInputStream(connection.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            };
        }
        if (source.startsWith("file:")) {
            return new BufferedInputStream(Files.newInputStream(Path.of(URI.create(source))));
        }
        return new BufferedInputStream(Files.newInputStream(toLocalPath(source)));
    }

    private Path toLocalPath(String source) {
        Path path = Path.of(source);
        if (path.isAbsolute()) return path.normalize();
        return Minecraft.getInstance().gameDirectory.toPath().resolve(path).normalize();
    }

    private boolean isHttp(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String titleFromSource(String source) {
        try {
            String candidate = isHttp(source)
                ? Path.of(URI.create(source).getPath()).getFileName().toString()
                : toLocalPath(source).getFileName().toString();
            return candidate.isBlank() ? source : candidate;
        } catch (Exception ignored) {
            return source;
        }
    }

    private void playIndex(Minecraft mc) {
        if (playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) return;

        stopPlayback();
        long generation = playbackGeneration.incrementAndGet();
        trackFinished = false;
        playbackError = null;
        paused = false;
        Track track = playlist.get(currentIndex);
        state = State.LOADING;

        playbackThread = new Thread(() -> decodeAndPlay(track, generation),
            "QYS-MusicPlayer-Audio");
        playbackThread.setDaemon(true);
        playbackThread.start();
        notifyPlayer(mc, "Loading: " + track.title());
    }

    private void decodeAndPlay(Track track, long generation) {
        SourceDataLine line = null;
        try (InputStream raw = openSource(track.source());
             BufferedInputStream buffered = new BufferedInputStream(raw);
             AudioInputStream encoded = AudioSystem.getAudioInputStream(buffered)) {

            AudioFormat sourceFormat = encoded.getFormat();
            float sampleRate = sourceFormat.getSampleRate();
            int channels = sourceFormat.getChannels();
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("Audio stream has an unsupported format.");
            }

            AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
            );

            AudioInputStream decoded;
            if (sourceFormat.matches(pcmFormat)) {
                decoded = encoded;
            } else {
                if (!AudioSystem.isConversionSupported(pcmFormat, sourceFormat)) {
                    throw new IOException("Unsupported audio codec or sample format.");
                }
                decoded = AudioSystem.getAudioInputStream(pcmFormat, encoded);
            }

            try (AudioInputStream audio = decoded) {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmFormat);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(pcmFormat, 32768);
                if (generation != playbackGeneration.get()) {
                    line.close();
                    return;
                }
                activeLine = line;
                boolean hardwareVolume = configureLineVolume(line, volume);
                line.start();
                state = paused ? State.PAUSED : State.PLAYING;
                if (paused) line.stop();

                byte[] buffer = new byte[8192];
                int read;
                while (generation == playbackGeneration.get()
                    && !Thread.currentThread().isInterrupted()
                    && (read = audio.read(buffer, 0, buffer.length)) != -1) {
                    while (paused && generation == playbackGeneration.get()) {
                        Thread.sleep(50L);
                    }
                    if (generation != playbackGeneration.get()) break;
                    if (!hardwareVolume) applySoftwareVolume(buffer, read, volume);
                    line.write(buffer, 0, read);
                }

                if (generation == playbackGeneration.get() && !paused) {
                    line.drain();
                    trackFinished = true;
                }
            }
        } catch (UnsupportedAudioFileException exception) {
            setPlaybackError(generation,
                "Unsupported format. Use MP3, OGG, WAV, AIFF, or a direct audio stream.");
        } catch (LineUnavailableException exception) {
            setPlaybackError(generation, "No available audio output device.");
        } catch (Exception exception) {
            if (generation == playbackGeneration.get()) {
                setPlaybackError(generation, safeMessage(exception));
            }
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.flush();
                    line.close();
                } catch (Exception ignored) {
                }
                if (activeLine == line) activeLine = null;
            }
        }
    }

    private void setPlaybackError(long generation, String message) {
        if (generation == playbackGeneration.get()) {
            playbackError = message;
            state = State.ERROR;
        }
    }

    private void applySoftwareVolume(byte[] buffer, int length, int currentVolume) {
        if (currentVolume >= 100) return;
        double scale = Math.max(0, currentVolume) / 100.0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xff));
            int scaled = (int) Math.round(sample * scale);
            buffer[i] = (byte) (scaled & 0xff);
            buffer[i + 1] = (byte) ((scaled >>> 8) & 0xff);
        }
    }

    private boolean configureLineVolume(SourceDataLine line, int value) {
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            setGain(gain, value);
            return true;
        }
        if (line.isControlSupported(BooleanControl.Type.MUTE)) {
            BooleanControl mute = (BooleanControl) line.getControl(BooleanControl.Type.MUTE);
            mute.setValue(value <= 0);
        }
        return false;
    }

    private void applyVolume() {
        SourceDataLine line = activeLine;
        if (line == null || !line.isOpen()) return;
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                setGain((FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN), volume);
            } else if (line.isControlSupported(BooleanControl.Type.MUTE)) {
                ((BooleanControl) line.getControl(BooleanControl.Type.MUTE)).setValue(volume <= 0);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void setGain(FloatControl gain, int value) {
        float minimum = gain.getMinimum();
        float maximum = gain.getMaximum();
        float decibels = value <= 0 ? minimum : (float) (20.0 * Math.log10(value / 100.0));
        gain.setValue(Math.max(minimum, Math.min(maximum, decibels)));
    }

    private void togglePause(Minecraft mc) {
        if (state == State.PLAYING || state == State.LOADING) {
            pausePlayback();
            notifyPlayer(mc, "Paused.");
        } else if (state == State.PAUSED) {
            resumePlayback();
            notifyPlayer(mc, "Resumed.");
        } else if (state == State.STOPPED || state == State.ERROR) {
            if (playlist.isEmpty()) {
                notifyPlayer(mc, "Load a playlist first with the Play Playlist key.");
            } else {
                if (currentIndex < 0) currentIndex = 0;
                playIndex(mc);
            }
        }
    }

    private void pausePlayback() {
        paused = true;
        SourceDataLine line = activeLine;
        if (line != null && line.isOpen()) line.stop();
        if (state == State.PLAYING || state == State.LOADING) state = State.PAUSED;
    }

    private void resumePlayback() {
        paused = false;
        SourceDataLine line = activeLine;
        if (line != null && line.isOpen()) line.start();
        if (state == State.PAUSED) state = State.PLAYING;
    }

    private void nextTrack(Minecraft mc, SimpleModule module) {
        if (playlist.isEmpty()) {
            loadAndPlay(mc, module);
            return;
        }
        currentIndex = chooseNextIndex(module, false);
        playIndex(mc);
    }

    private void previousTrack(Minecraft mc, SimpleModule module) {
        if (playlist.isEmpty()) {
            loadAndPlay(mc, module);
            return;
        }
        if (module.getBool("shuffle") && playlist.size() > 1) {
            currentIndex = chooseRandomIndex(currentIndex);
        } else {
            currentIndex = Math.floorMod(currentIndex - 1, playlist.size());
        }
        playIndex(mc);
    }

    private void advanceAfterTrack(Minecraft mc, SimpleModule module) {
        if (playlist.isEmpty()) {
            state = State.STOPPED;
            return;
        }

        String loopMode = module.getChoice("loop-mode");
        if ("Track".equals(loopMode)) {
            playIndex(mc);
            return;
        }

        if (currentIndex >= playlist.size() - 1 && !"Playlist".equals(loopMode)) {
            state = State.STOPPED;
            notifyPlayer(mc, "Playlist finished.");
            return;
        }

        currentIndex = chooseNextIndex(module, true);
        playIndex(mc);
    }

    private int chooseNextIndex(SimpleModule module, boolean naturalEnd) {
        if (playlist.size() <= 1) return 0;
        if (module.getBool("shuffle")) return chooseRandomIndex(currentIndex);
        if (naturalEnd && currentIndex >= playlist.size() - 1
            && !"Playlist".equals(module.getChoice("loop-mode"))) {
            return currentIndex;
        }
        return (currentIndex + 1) % playlist.size();
    }

    private int chooseRandomIndex(int previous) {
        if (playlist.size() <= 1) return 0;
        int next = ThreadLocalRandom.current().nextInt(playlist.size() - 1);
        if (next >= previous) next++;
        return next;
    }

    private void stopPlayback() {
        playbackGeneration.incrementAndGet();
        paused = false;
        trackFinished = false;
        playbackError = null;
        SourceDataLine line = activeLine;
        activeLine = null;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception ignored) {
            }
        }
        Thread thread = playbackThread;
        playbackThread = null;
        if (thread != null) thread.interrupt();
        state = State.STOPPED;
    }

    private void notifyVolume(Minecraft mc) {
        notifyPlayer(mc, "Volume: " + volume + "%");
    }

    private void notifyPlayer(Minecraft mc, String message) {
        if (mc.gui != null) {
            mc.gui.getChat().addMessage(Component.literal("[Music Player] " + message));
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName() : message;
    }
}
