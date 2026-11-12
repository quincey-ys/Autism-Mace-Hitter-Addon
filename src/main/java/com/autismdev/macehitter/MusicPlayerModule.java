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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
    private static final int VK_MEDIA_NEXT_TRACK = 0xB0;
    private static final int VK_MEDIA_PREVIOUS_TRACK = 0xB1;
    private static final int VK_MEDIA_STOP = 0xB2;
    private static final int VK_MEDIA_PLAY_PAUSE = 0xB3;
    private static final int VK_VOLUME_UP = 0xAF;
    private static final int VK_VOLUME_DOWN = 0xAE;

    private enum State {
        STOPPED, LOADING, PLAYING, PAUSED, EXTERNAL, ERROR
    }

    private MethodHandle mediaKeyHandle;
    private boolean mediaKeyLookupAttempted;
    private boolean externalYoutubeMode;
    private String externalYoutubeSource = "";

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
            "Plays local audio in-game or opens YouTube / YouTube Music in your browser with global media controls and PvP auto-open."
        );

        add(new StringSetting("playlist-source", "Playlist Source", "")
            .group("Playlist")
            .description("Local audio path, direct MP3/OGG/WAV URL, M3U playlist, or a YouTube / YouTube Music video or playlist URL. YouTube opens in your browser; audio stays in that browser session.")
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
            .description("Pauses or resumes local playback, or sends the Windows media Play/Pause key for YouTube Music.")
            .build());
        add(new KeybindSetting("next-key", "Next Track Key", GLFW.GLFW_KEY_RIGHT_BRACKET)
            .group("Keybinds")
            .description("Skips the local playlist or sends the Windows Next Track media key.")
            .build());
        add(new KeybindSetting("previous-key", "Previous Track Key", GLFW.GLFW_KEY_LEFT_BRACKET)
            .group("Keybinds")
            .description("Goes back locally or sends the Windows Previous Track media key.")
            .build());
        add(new KeybindSetting("stop-key", "Stop Key", GLFW.GLFW_KEY_BACKSPACE)
            .group("Keybinds").build());
        add(new KeybindSetting("volume-up-key", "Volume Up Key", GLFW.GLFW_KEY_PAGE_UP)
            .group("Keybinds")
            .description("Raises local player volume or Windows system volume for YouTube Music.")
            .build());
        add(new KeybindSetting("volume-down-key", "Volume Down Key", GLFW.GLFW_KEY_PAGE_DOWN)
            .group("Keybinds")
            .description("Lowers local player volume or Windows system volume for YouTube Music.")
            .build());
        add(new KeybindSetting("shuffle-key", "Toggle Shuffle Key", GLFW.GLFW_KEY_R)
            .group("Keybinds").build());

        onTick(this::tickMusicPlayer)
            .onDisabled(this::onDisabled);
    }

    private void onDisabled(SimpleModule module) {
        Minecraft mc = Minecraft.getInstance();
        if (externalYoutubeMode) sendMediaKey(mc, VK_MEDIA_STOP);
        stopPlayback();
        externalYoutubeMode = false;
        externalYoutubeSource = "";
        resetKeyState();
    }

    private void resetKeyState() {
        playWasDown = false;
        pauseWasDown = false;
        nextWasDown = false;
        previousWasDown = false;
        stopWasDown = false;
        volumeUpWasDown = false;
        volumeDownWasDown = false;
        shuffleWasDown = false;
        pvpWasActive = false;
        startedByPvp = false;
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

        boolean acceptKeys = mc.gui == null || mc.gui.screen() == null;
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
            if (externalYoutubeMode) sendMediaKey(mc, VK_MEDIA_PLAY_PAUSE);
            else togglePause(mc);
        }
        if (nextDown && !nextWasDown) {
            startedByPvp = false;
            if (externalYoutubeMode) sendMediaKey(mc, VK_MEDIA_NEXT_TRACK);
            else nextTrack(mc, module);
        }
        if (previousDown && !previousWasDown) {
            startedByPvp = false;
            if (externalYoutubeMode) sendMediaKey(mc, VK_MEDIA_PREVIOUS_TRACK);
            else previousTrack(mc, module);
        }
        if (stopDown && !stopWasDown) {
            startedByPvp = false;
            if (externalYoutubeMode) sendMediaKey(mc, VK_MEDIA_STOP);
            else stopPlayback();
            notifyPlayer(mc, "Playback stop requested.");
        }
        if (volumeUpDown && !volumeUpWasDown) {
            if (externalYoutubeMode) {
                sendMediaKey(mc, VK_VOLUME_UP);
            } else {
                module.setValue("volume", Integer.toString(Math.min(100, module.getInt("volume") + 5)));
                volume = module.getInt("volume");
                applyVolume();
                notifyVolume(mc);
            }
        }
        if (volumeDownDown && !volumeDownWasDown) {
            if (externalYoutubeMode) {
                sendMediaKey(mc, VK_VOLUME_DOWN);
            } else {
                module.setValue("volume", Integer.toString(Math.max(0, module.getInt("volume") - 5)));
                volume = module.getInt("volume");
                applyVolume();
                notifyVolume(mc);
            }
        }
        if (shuffleDown && !shuffleWasDown) {
            if (externalYoutubeMode) {
                notifyPlayer(mc, "Use YouTube Music's Shuffle control in the browser. The system media keys cannot toggle it reliably.");
            } else {
                module.setValue("shuffle", Boolean.toString(!module.getBool("shuffle")));
                notifyPlayer(mc, "Shuffle " + (module.getBool("shuffle") ? "enabled." : "disabled."));
            }
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
            && state != State.PLAYING && state != State.LOADING && state != State.EXTERNAL) {
            startedByPvp = true;
            loadAndPlay(mc, module);
        } else if (!pvpActive && pvpWasActive
            && module.getBool("pause-when-pvp-ends") && startedByPvp) {
            if (externalYoutubeMode) {
                notifyPlayer(mc, "PvP ended. Press the Play / Pause key to control YouTube Music; external playback state cannot be queried.");
            } else {
                pausePlayback();
                notifyPlayer(mc, "Paused because PvP ended.");
            }
            startedByPvp = false;
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
            notifyPlayer(mc, "Set Playlist Source first: use a local audio file, direct audio URL, .m3u playlist, or YouTube Music URL.");
            return;
        }

        URI youtubeUri = parseYoutubeUri(source);
        if (youtubeUri != null) {
            openYoutubeSource(mc, youtubeUri);
            return;
        }

        externalYoutubeMode = false;
        externalYoutubeSource = "";

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

    private void openYoutubeSource(Minecraft mc, URI youtubeUri) {
        String normalized = youtubeUri.toString();
        if (externalYoutubeMode && normalized.equals(externalYoutubeSource)) {
            notifyPlayer(mc, "YouTube Music playlist is already open. Use P, ], or [ for media controls.");
            return;
        }

        stopPlayback();
        try {
            Util.getPlatform().openUri(youtubeUri);
            externalYoutubeMode = true;
            externalYoutubeSource = normalized;
            playlist = List.of();
            currentIndex = -1;
            state = State.EXTERNAL;
            notifyPlayer(mc, "Opened YouTube Music in your browser. Click Play once if the browser blocks autoplay; then use P, ], and [ for media controls.");
        } catch (RuntimeException exception) {
            externalYoutubeMode = false;
            externalYoutubeSource = "";
            state = State.ERROR;
            notifyPlayer(mc, "Could not open YouTube Music. Check your default browser.");
        }
    }

    private URI parseYoutubeUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;

        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getUserInfo() != null) {
                return null;
            }

            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean youtubeHost = normalizedHost.equals("youtube.com")
                || normalizedHost.equals("www.youtube.com")
                || normalizedHost.equals("m.youtube.com")
                || normalizedHost.equals("music.youtube.com");
            boolean shortHost = normalizedHost.equals("youtu.be");
            if (!youtubeHost && !shortHost) return null;

            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery().toLowerCase(Locale.ROOT);
            boolean hasPlaylistId = false;
            boolean hasVideoId = shortHost && path.length() > 1;
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("list=") && parameter.length() > 5) {
                    hasPlaylistId = true;
                } else if (parameter.startsWith("v=") && parameter.length() > 2) {
                    hasVideoId = true;
                }
            }

            boolean playlistPath = path.equals("/playlist");
            boolean videoPath = path.equals("/watch") || path.startsWith("/shorts/");
            return (playlistPath && hasPlaylistId) || (videoPath && hasVideoId) || (shortHost && hasVideoId)
                ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
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

    private void sendMediaKey(Minecraft mc, int keyCode) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            notifyPlayer(mc, "YouTube Music media-key control is currently supported on Windows only.");
            return;
        }

        try {
            MethodHandle handle = getMediaKeyHandle();
            if (handle == null) {
                notifyPlayer(mc, "Windows media-key controls could not be initialized.");
                return;
            }
            handle.invokeExact((byte) keyCode, (byte) 0, 0, MemorySegment.NULL);
            handle.invokeExact((byte) keyCode, (byte) 0, 2, MemorySegment.NULL);
        } catch (Throwable exception) {
            notifyPlayer(mc, "Windows media-key control failed: " + exception.getClass().getSimpleName());
        }
    }

    private MethodHandle getMediaKeyHandle() {
        if (mediaKeyLookupAttempted) return mediaKeyHandle;
        mediaKeyLookupAttempted = true;

        try {
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
            MemorySegment keybdEvent = user32.find("keybd_event").orElse(null);
            if (keybdEvent == null) return null;

            mediaKeyHandle = Linker.nativeLinker().downcallHandle(
                keybdEvent,
                FunctionDescriptor.ofVoid(
                    ValueLayout.JAVA_BYTE,
                    ValueLayout.JAVA_BYTE,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
                )
            );
        } catch (Throwable ignored) {
            mediaKeyHandle = null;
        }
        return mediaKeyHandle;
    }

    private void notifyVolume(Minecraft mc) {
        notifyPlayer(mc, "Volume: " + volume + "%");
    }

    private void notifyPlayer(Minecraft mc, String message) {
        if (mc.gui != null) {
            if (mc.gui == null || mc.gui.hud.getChat() == null) return;
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Music Player] " + message));
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
            ? exception.getClass().getSimpleName() : message;
    }
}
