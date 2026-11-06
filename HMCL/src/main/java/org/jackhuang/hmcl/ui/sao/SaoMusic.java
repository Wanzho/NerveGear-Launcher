/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;

/// Plays a sound on its own thread, decoding Ogg Vorbis with JOrbis so no media module is needed: an optional
/// intro once, then a loop (the helmet's hum, or crossing field), and reports the loop's position for the lyrics.
@NotNullByDefault
public final class SaoMusic {
    /// Played once before the loop, or null.
    private final @Nullable String intro;
    /// Played again and again.
    private final String loop;
    /// The volume to fade to.
    private final double level;

    /// Builds a player.
    ///
    /// @param intro played once first, or null
    /// @param loop  played after it until stopped
    /// @param level the volume, 0 to 1
    public SaoMusic(@Nullable String intro, String loop, double level) {
        this.intro = intro;
        this.loop = loop;
        this.level = level;
    }

    /// Plays a sound once on its own thread (the helmet's power-down).
    ///
    /// @param name  the file under `music/`
    /// @param level the volume
    public static void once(String name, double level) {
        if (Boolean.getBoolean("sao.mute")) return;
        SaoMusic m = new SaoMusic(null, name, level);
        m.oneShot = true;
        m.start();
    }

    /// Whether the loop plays only once.
    private boolean oneShot;
    /// The volume the music fades to, 0 to 1.
    private volatile double target = 0.8;
    /// The volume being played now; follows [#target] smoothly.
    private volatile double gain = 0;
    /// Whether the player should be running.
    private volatile boolean running;
    /// Seconds into the track of the sample being heard now, or -1 when silent.
    private volatile double position = -1;
    /// The decoding thread, or null when stopped.
    private @Nullable Thread thread;

    /// Starts playing (fading in) if the music is built in; does nothing when already playing.
    public synchronized void start() {
        if (Boolean.getBoolean("sao.mute")) return; // screenshot tools stay silent
        target = level;
        running = true;
        @Nullable Thread old = thread;
        if (old != null && old.isAlive()) return; // still fading out: it picks up again
        if (oneShot) gain = level;
        Thread t = new Thread(this::loop, "SAO sound");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /// Fades out and stops.
    public synchronized void stop() {
        // The thread keeps playing until the fade has reached silence, then ends.
        target = 0;
        running = false;
    }

    /// Whether the music is on.
    ///
    /// @return true while playing or fading in
    public boolean isPlaying() {
        return running;
    }

    /// Seconds into the track being heard now.
    ///
    /// @return the position, or -1 when silent
    public double position() {
        return position;
    }

    /// Plays the track again and again until stopped and faded out.
    private void loop() {
        boolean first = true;
        while (running) {
            @Nullable String name = first && intro != null ? intro : loop;
            boolean isLoop = !(first && intro != null);
            first = false;
            try (@Nullable InputStream in = SaoAssets.music(name)) {
                if (in == null) return;
                playOnce(in, isLoop);
            } catch (Exception e) {
                return;
            }
            if (oneShot) break;
        }
        running = false;
        position = -1;
    }

    /// Decodes and plays one pass of the track (the classic JOrbis decode loop).
    ///
    /// @param in     the Ogg stream
    /// @param isLoop whether this is the loop (its position feeds the lyrics)
    /// @throws Exception when the stream or the audio device fails
    private void playOnce(InputStream in, boolean isLoop) throws Exception {
        SyncState oy = new SyncState();
        StreamState os = new StreamState();
        Page og = new Page();
        Packet op = new Packet();
        Info vi = new Info();
        Comment vc = new Comment();
        DspState vd = new DspState();
        Block vb = new Block(vd);
        oy.init();
        int headers = 0;
        @Nullable SourceDataLine line = null;
        float[][][] pcmHolder = new float[1][][];
        int[] index = null;
        byte[] out = new byte[8192 * 2];
        boolean eos = false;
        try {
            while (!eos && (running || gain > 0.005)) {
                int idx = oy.buffer(4096);
                int n = in.read(oy.data, idx, 4096);
                if (n <= 0) break;
                oy.wrote(n);
                while (oy.pageout(og) == 1) {
                    if (headers == 0) {
                        os.init(og.serialno());
                        vi.init();
                        vc.init();
                    }
                    os.pagein(og);
                    while (os.packetout(op) == 1) {
                        if (headers < 3) {
                            vi.synthesis_headerin(vc, op);
                            if (++headers == 3) {
                                vd.synthesis_init(vi);
                                vb.init(vd);
                                index = new int[vi.channels];
                                AudioFormat fmt = new AudioFormat(vi.rate, 16, vi.channels, true, false);
                                line = AudioSystem.getSourceDataLine(fmt);
                                line.open(fmt, vi.rate * vi.channels / 2);
                                line.start();
                            }
                            continue;
                        }
                        if (vb.synthesis(op) == 0) vd.synthesis_blockin(vb);
                        int samples;
                        while ((samples = vd.synthesis_pcmout(pcmHolder, index)) > 0) {
                            float[][] pcm = pcmHolder[0];
                            int frames = Math.min(samples, out.length / (2 * vi.channels));
                            for (int f = 0; f < frames; f++) {
                                // Ease the gain toward the target a little each frame (about 0.6 s fades).
                                gain += (target - gain) * 0.00008;
                                for (int c = 0; c < vi.channels; c++) {
                                    int v = (int) (pcm[c][index[c] + f] * 32767 * gain);
                                    v = Math.max(-32768, Math.min(32767, v));
                                    int o = 2 * (f * vi.channels + c);
                                    out[o] = (byte) v;
                                    out[o + 1] = (byte) (v >>> 8);
                                }
                            }
                            line.write(out, 0, 2 * vi.channels * frames);
                            vd.synthesis_read(frames);
                            position = isLoop ? line.getLongFramePosition() / (double) vi.rate : -1;
                        }
                        if (op.e_o_s != 0) eos = true;
                    }
                }
            }
            if (line != null && running) line.drain();
        } finally {
            if (line != null) line.close();
            os.clear();
            vb.clear();
            vd.clear();
            vi.clear();
            oy.clear();
        }
    }
}
