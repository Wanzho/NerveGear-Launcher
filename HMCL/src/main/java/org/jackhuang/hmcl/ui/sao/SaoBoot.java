/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import org.jackhuang.hmcl.sao.SaoHelmetWait;
import org.jackhuang.hmcl.sao.SaoCoverHandoff;

/// The helmet's power-on and its hand-over to the game, as James approved them in the prototypes of 13 Nov
/// (launcher-boot-anim-v2..v5: "其他的没问题", "别的东西定稿"). Times are the prototype's, in milliseconds; places are the
/// page's design space (1280 x 720), the prototype's 1920 x 1080 times two thirds.
///
/// Power-on, 2.9 s, played in full every time ("2.9秒跳啥"): a point of light, a hairline, the eyes open on an
/// out-of-focus visor; the NERVEGEAR sync ring draws itself, 神经同步 counts to 100 % while a brain wave settles; the
/// ring falls to a point, flashes, and one pulse pulls the visor into focus; the windows come in, each an outline first
/// and then its glass (dock, 公告, player, 服务器); one faint glint crosses the glass.
///
/// Link Start: the windows put themselves away in the reverse order, the glass going and the outline left a moment;
/// the visor stays (the game opens on the same visor); a row of segments where the game will write its prompt fills
/// with the game's real start-up, read from its log; when the game's helmet is up the launcher fades off it.
@NotNullByDefault
final class SaoBoot {
    private static final double CX = 640, CY = 360;
    /// The power-on's length.
    static final double OPEN_MS = 2900;

    /// The page's parts the power-on works on.
    @NotNullByDefault
    interface Page {
        /// The windows in the order they are built (dock, 公告, player, 服务器).
        SaoFloatWindow[] windows();

        /// The visor's layers that are out of focus before the sync lands (glass, world, frame, pad, reflection).
        Node[] visor();

        /// The launcher's own controls on the visor (window buttons, version), which leave with the windows at Link
        /// Start: the game's visor has none.
        Node chrome();
    }

    private final Page page;
    /// Over the visor, in screen pixels; the design group inside is fitted each frame.
    final Pane layer = new Pane();
    private final Group design = new Group();
    private final Translate shift = new Translate();
    private final Scale fit = new Scale(1, 1, 0, 0);
    /// Darkens the visor while it is out of focus (a multiply the effect chain lacks).
    private final Rectangle dim = new Rectangle();
    private final GaussianBlur blur = new GaussianBlur(0);
    private final ColorAdjust tone = new ColorAdjust();

    // Power-on.
    private final Circle dot = new Circle(CX, CY, 0);
    private final Rectangle hair = new Rectangle(CX, CY - 0.7, 0, 1.4);
    private final Group lidHolder = new Group();
    private final Group ring = new Group();
    private final Arc ringDraw = new Arc(0, 0, 78.7, 78.7, 90, 0);
    private final Group ticks = new Group();
    private final Rotate ticksTurn = new Rotate(0);
    private final Text brand = new Text("N E R V E G E A R");
    private final Text pct = new Text("0%");
    private final Text pctLabel = new Text("神经同步");
    private final Polyline wave = new Polyline();
    private final Circle flash = new Circle(CX, CY, 20);
    private final Circle pulse = new Circle(CX, CY, 0);
    private final Rectangle glintBar = new Rectangle(-80, -140, 173, 1000);
    private final Rotate glintTurn = new Rotate(18, 0, 0);

    // Link Start.
    private static final int SEGS = 30;
    private static final double SEG_STEP = 9.33, SEG_X0 = CX - SEGS * 9.33 / 2, SEG_Y = 603.3;
    private final Group loading = new Group();
    private final Rectangle[] segs = new Rectangle[SEGS];
    private final Rectangle head = new Rectangle(0, SEG_Y - 1.3, 8, 5.4);
    private final Rectangle halo = new Rectangle(0, SEG_Y - 3.3, 10.7, 9.3);
    private final Text loadPct = new Text("0%");

    /// What runs now, and when it started (ns).
    private enum Phase { IDLE, OPENING, PUTTING_AWAY }

    private Phase phase = Phase.IDLE;
    private long started;
    /// Called when the power-on ends.
    private @Nullable Runnable openDone;
    /// The game's start-up, 0 to 1, from its log, and what is shown.
    private double progressTarget, progressShown;
    /// When the game's helmet showed (ms into the put-away), or < 0 while it has not.
    private double helmetAt = -1;
    /// Pending/running completion ownership, shared with the page's drag and release APIs.
    final SaoCoverHandoff handoff = new SaoCoverHandoff();
    /// Pure readiness clock, independent of animation phase and ring snapshots.
    private final SaoHelmetWait wait = new SaoHelmetWait();
    /// Link Start timestamp, retained through delayed put-away.
    private long waitStarted;
    /// The hand-over's fade of the launcher window, 0 to 1 (applied by the page to its stage).
    private final javafx.beans.property.DoubleProperty windowFade = new javafx.beans.property.SimpleDoubleProperty(0);


    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            double t = (now - started) / 1e6;
            if (phase == Phase.OPENING) renderOpening(t);
            else if (phase == Phase.PUTTING_AWAY) renderPutAway(t);
        }
    };

    SaoBoot(Page page) {
        this.page = page;
        layer.setMouseTransparent(true);
        layer.setManaged(false);
        design.getTransforms().addAll(shift, fit);
        dim.setFill(Color.BLACK);
        dim.setOpacity(0);
        dim.setMouseTransparent(true);
        dim.setManaged(false);
        blur.setInput(tone);

        RadialGradient glow = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(233, 246, 255, 1)), new Stop(1, Color.rgb(124, 200, 255, 0)));
        dot.setFill(glow);
        hair.setFill(Color.rgb(234, 246, 255));
        flash.setFill(glow);
        flash.setOpacity(0);
        pulse.setFill(Color.TRANSPARENT);
        pulse.setStroke(Color.rgb(223, 241, 255));
        pulse.setOpacity(0);

        Circle base = new Circle(0, 0, 78.7);
        base.setFill(Color.TRANSPARENT);
        base.setStroke(Color.rgb(159, 182, 204, 0.18));
        base.setStrokeWidth(1.4);
        ringDraw.setType(ArcType.OPEN);
        ringDraw.setFill(Color.TRANSPARENT);
        ringDraw.setStroke(Color.rgb(228, 243, 255));
        ringDraw.setStrokeWidth(2);
        ringDraw.setStrokeLineCap(StrokeLineCap.ROUND);
        for (int i = 0; i < 60; i++) {
            double a = i / 60.0 * Math.PI * 2, r1 = 92, r2 = i % 5 != 0 ? 96 : 101.3;
            Line l = new Line(Math.cos(a) * r1, Math.sin(a) * r1, Math.cos(a) * r2, Math.sin(a) * r2);
            l.setStroke(Color.rgb(159, 182, 204));
            l.setStrokeWidth(i % 5 != 0 ? 0.7 : 1.4);
            ticks.getChildren().add(l);
        }
        ticks.getTransforms().add(ticksTurn);
        centre(brand, 12, true, Color.rgb(217, 230, 242), -107);
        centre(pct, 29, false, Color.rgb(242, 248, 255), 9);
        centre(pctLabel, 10, false, Color.rgb(142, 162, 184), 29);
        wave.setStroke(Color.rgb(191, 230, 255));
        wave.setStrokeWidth(1.4);
        wave.setFill(null);
        ring.getChildren().addAll(base, ringDraw, ticks, brand, pct, pctLabel, wave);
        ring.setOpacity(0);

        glintBar.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0)), new Stop(0.5, Color.rgb(255, 255, 255, 0.5)), new Stop(1, Color.rgb(255, 255, 255, 0))));
        glintBar.getTransforms().add(glintTurn);
        glintBar.setOpacity(0);

        for (int i = 0; i < SEGS; i++) {
            Rectangle r = new Rectangle(SEG_X0 + i * SEG_STEP, SEG_Y, SEG_STEP - 2.67, 4);
            r.setArcWidth(2);
            r.setArcHeight(2);
            segs[i] = r;
            loading.getChildren().add(r);
        }
        halo.setArcWidth(5);
        halo.setArcHeight(5);
        halo.setFill(Color.rgb(191, 230, 255));
        halo.setEffect(new GaussianBlur(5));
        head.setArcWidth(3);
        head.setArcHeight(3);
        head.setFill(Color.WHITE);
        SaoUiKit.font(loadPct, 11.3, false);
        loadPct.setFill(Color.rgb(201, 219, 234));
        loadPct.setX(SEG_X0 + SEGS * SEG_STEP + 8);
        loadPct.setY(SEG_Y + 5.5);
        loading.getChildren().addAll(halo, head, loadPct);
        loading.setOpacity(0);

        design.getChildren().addAll(lidHolder, glintBar, ring, pulse, flash, hair, dot, loading);
        layer.getChildren().addAll(design);
        layer.setVisible(false);
        dim.setVisible(false);
    }

    private static void centre(Text t, double size, boolean bold, Color c, double y) {
        SaoUiKit.font(t, size, bold);
        t.setFill(c);
        t.setTextAlignment(TextAlignment.CENTER);
        t.setY(y);
        t.layoutBoundsProperty().addListener((o, a, b) -> t.setX(-b.getWidth() / 2));
        t.setX(-t.getLayoutBounds().getWidth() / 2);
    }

    /// The dimming layer, put by the page just over the visor and under the windows' character layer.
    Rectangle dim() {
        return dim;
    }

    /// The fade of the launcher window at the hand-over, 0 to 1.
    javafx.beans.property.ReadOnlyDoubleProperty windowFade() {
        return windowFade;
    }

    /// Fits the overlay to the 16:9 screen (called by the page every frame).
    void fit(double w, double h, double s) {
        layer.resizeRelocate(0, 0, w, h);
        dim.setWidth(w);
        dim.setHeight(h);
        shift.setX(w / 2.0 - 640 * s);
        shift.setY(h / 2.0 - 360 * s);
        fit.setX(s);
        fit.setY(s);
        scale = s;
    }

    private double scale = 1;

    /// Whether the power-on or the hand-over is running (the page keeps its own animations off meanwhile).
    boolean busy() {
        return phase != Phase.IDLE;
    }

    // ------------------------------------------------------------------------------------------------ power-on

    /// Plays the power-on from black.
    ///
    /// @param done runs when it ends
    void open(Runnable done) {
        stopAll();
        openDone = done;
        for (SaoFloatWindow w : page.windows()) {
            w.glass.set(0);
            w.recede.set(60);
            w.edge.setOpacity(0);
            w.edgeDrawn.set(0);
        }
        for (Node n : page.visor()) n.setEffect(blur);
        page.chrome().setOpacity(1);
        layer.setVisible(true);
        dim.setVisible(true);
        phase = Phase.OPENING;
        started = System.nanoTime();
        renderOpening(0);
        timer.start();
    }

    /// Ends whatever runs and leaves the visor clear.
    private void stopAll() {
        timer.stop();
        phase = Phase.IDLE;
        for (Node n : page.visor()) n.setEffect(null);
        dim.setOpacity(0);
        dim.setVisible(false);
        layer.setVisible(false);
        lidHolder.getChildren().clear();
    }

    /// Draws the approved power-on choreography at its existing timings.
    private void renderOpening(double t) {
        // 1. a point of light, drawn out into a hairline
        dot.setRadius(lerp(0, 17.3, eo(seg(t, 0, 140))) * (1 - seg(t, 300, 520)));
        double hw = lerp(0, 1000, eo(seg(t, 90, 300)));
        hair.setX(CX - hw / 2);
        hair.setWidth(hw);
        hair.setOpacity(1 - seg(t, 330, 620));
        // 2. the eyes open; with the focus they open the last of the way, past the corners
        double ry = t < 260 ? 0 : lerp(0.7, 547, eo(seg(t, 260, 820)));
        double wide = lerp(1, 1.35, eio(seg(t, 1440, 1800)));
        lids(787 * wide, ry * wide);
        // 3. the visor out of focus and dark until the sync lands
        double focus = eio(seg(t, 1440, 1760));
        blur.setRadius(lerp(13.3, 0, focus) * scale);
        tone.setSaturation(lerp(-0.4, 0, seg(t, 1440, 1800)));
        double br = lerp(0.28, 1, eio(seg(t, 1440, 1800))) * lerp(0.6, 1, seg(t, 300, 800));
        dim.setOpacity(1 - br);
        // 4. the sync ring
        double ra = seg(t, 440, 640) * (1 - seg(t, 1340, 1470)), rs = lerp(1, 0.05, ei(seg(t, 1330, 1470)));
        ring.setTranslateX(CX);
        ring.setTranslateY(CY);
        ring.setScaleX(rs);
        ring.setScaleY(rs);
        ring.setOpacity(ra);
        double prog = eio(seg(t, 520, 1320));
        ringDraw.setLength(-360 * prog);
        ticksTurn.setAngle(lerp(-40, 0, eo(seg(t, 440, 1320))));
        ticks.setOpacity(0.25 + 0.75 * prog);
        pct.setText(Math.round(prog * 100) + "%");
        brand.setOpacity(seg(t, 560, 820));
        // the brain wave: noise settles into one clean beat
        double calm = eio(seg(t, 600, 1300));
        Double[] pts = new Double[130];
        for (int i = 0; i <= 64; i++) {
            double x = -73.3 + i * 146.7 / 64, env = Math.sin(i / 64.0 * Math.PI);
            double noise = (NOISE[Math.floorMod(i * 7 + (int) Math.floor(t / 45), 64)] - 0.5) * 20 * (1 - calm);
            double clean = Math.sin(i / 64.0 * Math.PI * 4 + t / 120) * 6 * calm;
            pts[i * 2] = x;
            pts[i * 2 + 1] = 52 + (noise + clean) * env;
        }
        wave.getPoints().setAll(pts);
        wave.setOpacity(seg(t, 560, 760));
        // 5. the lock: the ring falls to a point, a flash, one pulse rolls out and pulls the visor into focus
        flash.setOpacity(seg(t, 1400, 1460) * (1 - seg(t, 1460, 1700)));
        flash.setRadius(lerp(20, 147, eo(seg(t, 1400, 1700))));
        double pu = seg(t, 1440, 1840);
        pulse.setRadius(lerp(0, 833, eo(pu)));
        pulse.setOpacity(pu > 0 ? (1 - pu) * 0.9 : 0);
        pulse.setStrokeWidth(lerp(4, 0.7, pu));
        // 6. the windows: the outline first, then the glass
        SaoFloatWindow[] ws = page.windows();
        for (int i = 0; i < ws.length; i++) {
            double a = 1560 + 80 * i;
            double ol = eo(seg(t, a, a + 240));
            ws[i].edgeDrawn.set(ol);
            ws[i].edge.setOpacity(ol > 0 ? 1 - seg(t, a + 320, a + 620) : 0);
            double g = eo(seg(t, a + 110, a + 460));
            ws[i].glass.set(g);
            ws[i].recede.set(lerp(60, 0, g));
        }
        // 7. one glint across the glass
        double gl = seg(t, 2250, 2780);
        glintBar.setTranslateX(lerp(-470, 1530, eio(gl)));
        glintBar.setOpacity(gl > 0 && gl < 1 ? 0.16 : 0);
        if (t >= OPEN_MS) {
            finishOpening();
        }
    }

    /// Establishes exactly the normal opening end state before an interrupted opening puts windows away.
    private void finishOpening() {
        for (SaoFloatWindow w : page.windows()) {
            w.glass.set(1);
            w.recede.set(0);
            w.edgeDrawn.set(1);
            w.edge.setOpacity(0);
        }
        stopAll();
        @Nullable Runnable done = openDone;
        openDone = null;
        if (done != null) done.run();
    }

    /// The black round the open eyes: the screen less an ellipse.
    private void lids(double rx, double ry) {
        if (rx > 1100 && ry > 700) {
            lidHolder.getChildren().clear();
            return;
        }
        Shape s = ry <= 0.01 ? new Rectangle(-20, -20, 1320, 760)
                : Shape.subtract(new Rectangle(-20, -20, 1320, 760), new Ellipse(CX, CY, rx, ry));
        s.setFill(Color.BLACK);
        lidHolder.getChildren().setAll(s);
    }

    // ------------------------------------------------------------------------------------------------ Link Start

    /// Puts the windows away and waits for the game's helmet; the engine's hide then runs (see [SaoHandoff]).
    void putAway() {
        if (!handoff.active()) pending();
        if (phase == Phase.OPENING) finishOpening();
        stopAll();
        progressShown = 0;
        helmetAt = -1;

        windowFade.set(0);
        layer.setVisible(true);
        loading.setOpacity(0);
        phase = Phase.PUTTING_AWAY;
        started = System.nanoTime();
        lastU = 0;
        renderPutAway(0);
        timer.start();
    }

    /// Marks put-away pending before launch, including an app-close delay or power-on interruption.
    void pending() {
        if (handoff.active()) return;
        handoff.begin();
        waitStarted = System.nanoTime();
        wait.begin(0);
        progressTarget = 0;
    }

    /// Holds lifecycle completion even while the app-close transition has not started put-away.
    void holdHide(Runnable hide) { handoff.hold(hide); }

    /// Cancels without invoking the held visibility action.
    void cancel() {
        handoff.cancel();
        if (phase == Phase.PUTTING_AWAY) {
            stopAll();
            windowFade.set(0);
        }
        page.chrome().setOpacity(1);
    }

    /// The game's start-up, as its log tells it. Each stage moves the row to its share; in between, it creeps on.
    private static final String @Unmodifiable [] @Unmodifiable [] STAGES = {
            {"ModLauncher running", "0.04"},
            {"Loading ImmediateWindowProvider", "0.09"},
            {"Launching target", "0.24"},
            {"Backend library: LWJGL", "0.51"},
            {"Reloading ResourceManager", "0.84"},
    };

    /// Streams every current-run sanitized line, even before put-away starts; empty marks log.clear().
    void readLog(java.util.Optional<String> event, boolean legacy) {
        wait.allowLegacy(legacy);
        if (!handoff.active()) return;
        double now = (System.nanoTime() - waitStarted) / 1e6;
        if (event.isEmpty()) {
            wait.begin(now);
            progressTarget = 0;
            helmetAt = -1;
            return;
        }
        String line = event.get();
        wait.line(line, now);
        for (String[] st : STAGES)
            if (line.contains(st[0])) progressTarget = Math.max(progressTarget, Double.parseDouble(st[1]));
    }

    /// The share the next stage of the log will bring.
    private double nextStage() {
        for (String[] st : STAGES) {
            double v = Double.parseDouble(st[1]);
            if (v > progressTarget) return v;
        }
        return 0.97;
    }

    /// The launcher's fade off the game.
    private static final double FADE_MS = 450;

    private double lastU;

    /// Draws the unchanged put-away/loading/fade choreography; readiness is supplied by the backend clock.
    private void renderPutAway(double u) {
        double dt = Math.max(0, u - lastU);
        lastU = u;
        // 1. the windows put themselves away, the build in reverse: the glass goes, the outline is left, then the
        //    outline runs back to nothing. The visor stays: the game opens on this same visor.
        SaoFloatWindow[] ws = page.windows();
        for (int i = 0; i < ws.length; i++) {
            double a = 180 + 80 * (ws.length - 1 - i);
            double g = 1 - eio(seg(u, a, a + 300));
            ws[i].glass.set(g);
            ws[i].recede.set(lerp(0, 30, 1 - g));
            double off = eio(seg(u, a + 220, a + 520));
            ws[i].edgeDrawn.set(1 - off);
            ws[i].edge.setOpacity(seg(u, a, a + 40) * (1 - off));
        }
        page.chrome().setOpacity(1 - eio(seg(u, 180, 480)));
        // 2. the loading row: segments where the game will write its prompt, filled by the game's real start-up
        if (helmetAt < 0) {
            double next = nextStage();
            progressShown = Math.max(progressShown, progressTarget);
            progressShown += (next * 0.92 - progressShown) * (1 - Math.exp(-dt / 4000));
            progressShown = Math.min(progressShown, 0.97);
        }
        double h = helmetAt < 0 ? -1 : Math.max(helmetAt, 1050);
        double pr = h < 0 ? progressShown : lerp(progressShown, 1, eo(seg(u, h, h + 120)));
        loading.setOpacity(seg(u, 820, 1050) * (h < 0 ? 1 : 1 - seg(u, h + 120, h + 380)));
        double lit = pr * SEGS;
        double done = h < 0 ? 0 : 1 - seg(u, h + 40, h + 200);
        for (int i = 0; i < SEGS; i++) {
            double k = clamp(lit - i);
            segs[i].setFill(k >= 1 ? Color.rgb(207, 232, 255) : Color.rgb(138, 157, 177));
            segs[i].setOpacity(Math.min(1, 0.16 + 0.66 * k + (k >= 1 ? done * 0.5 : 0)));
        }
        double hx = SEG_X0 + Math.min(Math.floor(lit), SEGS - 1) * SEG_STEP - 0.7;
        head.setX(hx);
        halo.setX(hx - 1.3);
        double hb = u >= 900 && pr < 1 ? 0.6 + 0.4 * Math.sin(u / 160) : 0;
        head.setOpacity(hb);
        halo.setOpacity(hb * 0.9);
        loadPct.setText(Math.round(pr * 100) + "%");
        // 3. the hand-over: once the game's helmet is up, the launcher fades off it, then the engine hides it
        if (h < 0 && wait.ready((System.nanoTime() - waitStarted) / 1e6, handoff.armed())) {
            helmetAt = u;
            h = u;
        }
        if (h >= 0) {
            windowFade.set(eio(seg(u, h + 120, h + 120 + FADE_MS)));
            if (u >= h + 120 + FADE_MS) {
                stopAll();
                handoff.complete();
                windowFade.set(0);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ tools

    /// Screenshot tool: the power-on at a time, the clock stopped.
    void debugOpening(double t) {
        if (phase != Phase.OPENING) open(() -> {
        });
        timer.stop();
        renderOpening(t);
    }

    /// Screenshot tool: the put-away at a time, with the game's start-up and its helmet time given.
    void debugPutAway(double u, double progress, double helmet) {
        if (phase != Phase.PUTTING_AWAY) putAway();
        timer.stop();
        progressShown = progress;
        progressTarget = progress;
        helmetAt = helmet;
        lastU = u;
        renderPutAway(u);
    }

    // ------------------------------------------------------------------------------------------------ easing

    private static final double[] NOISE = new double[64];

    static {
        for (int i = 0; i < 64; i++) {
            double v = Math.sin(i * 12.9898) * 43758.5453;
            NOISE[i] = v - Math.floor(v);
        }
    }

    static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    static double seg(double t, double a, double b) {
        return clamp((t - a) / (b - a));
    }

    static double lerp(double a, double b, double x) {
        return a + (b - a) * x;
    }

    static double eo(double x) {
        return 1 - Math.pow(1 - x, 3);
    }

    static double ei(double x) {
        return x * x * x;
    }

    static double eio(double x) {
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }
}
