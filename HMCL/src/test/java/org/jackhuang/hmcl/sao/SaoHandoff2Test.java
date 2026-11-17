package org.jackhuang.hmcl.sao;

import javafx.geometry.Rectangle2D;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Pure hand-over regressions: no process, toolkit, stage, network or focus changes.
@NotNullByDefault
class SaoHandoff2Test {
    /// Isolated log fixture root.
    @TempDir Path temp;
    /// Default windowed game.
    private final SaoData.GameWindow game = new SaoData.GameWindow(1280, 720, false);
    /// Cocoa points are independent of Retina output scale and agree with the hidden measurement within one point.
    @Test void macPointsAndMeasurement() {
        var r = SaoGameWindowGeometry.content(new Rectangle2D(0,0,1728,1117),2,2,true,game).orElseThrow();
        assertEquals(1280,r.getWidth()); assertEquals(720,r.getHeight());
        assertEquals(224,r.getMinX(),1); assertEquals(166+32,r.getMinY(),1);
    }
    /// Windows GLFW physical pixels convert at both requested fractional DPI scales.
    @Test void windowsDpi() {
        for (double scale : new double[]{1.25,1.5}) {
            var r = SaoGameWindowGeometry.content(new Rectangle2D(0,0,1920/scale,1080/scale),scale,scale,false,game).orElseThrow();
            assertEquals(1280/scale,r.getWidth(),0.001); assertEquals(720/scale,r.getHeight(),0.001);
            assertEquals(320/scale,r.getMinX(),0.001); assertEquals(180/scale,r.getMinY(),0.001);
        }
    }
    /// Negative screen origins remain logical coordinates; a secondary launcher never changes primary game placement.
    @Test void negativeOriginAndFullscreen() {
        var r = SaoGameWindowGeometry.content(new Rectangle2D(-1920,-200,1920,1080),1,1,false,game).orElseThrow();
        assertEquals(-1600,r.getMinX()); assertEquals(-20,r.getMinY());
        assertTrue(SaoGameWindowGeometry.content(new Rectangle2D(0,0,1920,1080),1,1,false,
                new SaoData.GameWindow(1280,720,true)).isEmpty());
    }
    /// Fullscreen and negative-origin secondary launchers fall back without stage alignment.
    @Test void coverEligibility() {
        var primary=new Rectangle2D(0,0,1728,1117);
        assertTrue(SaoGameWindowGeometry.alignable(primary,primary,false,game));
        assertFalse(SaoGameWindowGeometry.alignable(primary,new Rectangle2D(-1600,0,1280,720),false,game));
        assertFalse(SaoGameWindowGeometry.alignable(primary,primary,true,game));
        assertFalse(SaoGameWindowGeometry.alignable(primary,primary,false,new SaoData.GameWindow(1280,720,true)));
        assertFalse(SaoGameWindowGeometry.alignable(primary,primary,false,new SaoData.GameWindow(1024,768,false)));
    }

    /// Maximized/non-16:9 pages keep their letterboxing and fixed decorator insets when the picture matches content.
    @Test void maximizedAndLetterboxedStage() {
        var stage = new Rectangle2D(0,0,1728,1117);
        var page = new Rectangle2D(0,0,1728,1117);
        var picture = new Rectangle2D(0,72.5,1728,972);
        var content = new Rectangle2D(224,198.5,1280,720);
        var target = SaoGameWindowGeometry.target(stage,page,picture,content);
        assertEquals(1280,target.getWidth());
        assertEquals(198.5,target.getMinY()+72.5*1280/1728,0.001);
        assertEquals(stage,SaoGameWindowGeometry.interpolate(stage,target,0));
        assertEquals(target,SaoGameWindowGeometry.interpolate(stage,target,1));
        var mid = SaoGameWindowGeometry.interpolate(stage,target,.5);
        assertEquals((stage.getHeight()+target.getHeight())/2,mid.getHeight());
        var insetTarget = SaoGameWindowGeometry.target(new Rectangle2D(10,20,1020,820),
                new Rectangle2D(20,30,1000,800),new Rectangle2D(20,148.75,1000,562.5),content);
        assertEquals(1300,insetTarget.getWidth());
        assertEquals(198.5,insetTarget.getMinY()+10+118.75*1.28,0.001);
    }
    /// Alive output extends waiting beyond 120 seconds, but never beyond ten minutes.
    @Test void waitWithActivityAndHardLimit() {
        var wait = new SaoHelmetWait(); wait.begin(0);
        for (int t=60_000;t<600_000;t+=60_000) { wait.line("working",t); assertFalse(wait.ready(t,true)); }
        assertTrue(wait.ready(600_000,true)); assertFalse(wait.ready(600_000,false));
        wait.begin(0); assertFalse(wait.ready(89_999,true)); assertTrue(wait.ready(90_000,true));
        wait.line("working",90_000); assertFalse(wait.ready(179_999,true)); assertTrue(wait.ready(180_000,true));
    }
    /// Only the exact first-frame suffix or FancyMenu registration counts, with 800 ms legacy grace.
    @Test void helmetRulesAndClear() {
        var wait = new SaoHelmetWait(); wait.begin(0);
        for (String line : List.of("entrance.HelmetScreen", "[sao-entrance] helmet", "[sao-entrance] helmet visible later")) {
            wait.line(line,1); assertFalse(wait.ready(1000,true));
        }
        wait.line("ScreenCustomizationLayer registered: package.entrance.HelmetScreen",1000);
        assertFalse(wait.ready(1800,true));
        wait.allowLegacy(true);
        wait.line("ScreenCustomizationLayer registered: package.entrance.HelmetScreen",2000);
        assertFalse(wait.ready(2799,true)); assertTrue(wait.ready(2800,true));
        wait.begin(3000); assertFalse(wait.ready(4000,true));
        wait.line("[Render thread/INFO] [sao-entrance/]: [sao-entrance] helmet visible",4100);
        assertTrue(wait.ready(4100,true)); assertFalse(wait.ready(4100,false));
    }
    /// Current pack geometry suffixes must not defer readiness until the inactivity timeout.
    @Test void acceptsCurrentPackMarkerWithGeometry() {
        var wait = new SaoHelmetWait(); wait.begin(0);
        wait.line("[Render thread/INFO] [sao-entrance] helmet visible window=-10,20,1280,720 fb=2560,1440 full=0", 10);
        assertTrue(wait.ready(10, true));
        wait.begin(0);
        wait.line("[sao-entrance] helmet visible window=broken", 10);
        assertFalse(wait.ready(1000, true));
    }
    /// With no animation holder, visibility completes immediately and CLOSE still waits for authentication.
    @Test void disabledAnimationHasNoWaitOrCoverLock() {
        var events = new ArrayList<String>();
        var life = new SaoLauncherLifecycle(Runnable::run, new SaoLauncherLifecycle.Window() {
            public void hide() { events.add("hide"); }
            public void show() { events.add("show"); }
            public void close() { events.add("close"); }
        });
        life.begin(SaoData.LauncherBehavior.HIDE); life.started();
        assertEquals(List.of("hide"), events);
        life.exited(); assertEquals(List.of("hide", "show"), events);
        events.clear(); life.begin(SaoData.LauncherBehavior.CLOSE); life.started();
        assertTrue(events.isEmpty()); life.handedOff(); assertEquals(List.of("close"), events);
    }
    /// Installed old/new/unknown capability, independent of whether registration precedes the first draw.
    @Test void packSignalCapability() throws Exception {
        assertFalse(SaoHelmetSignal.legacy(temp));
        writeMod("old code"); assertTrue(SaoHelmetSignal.legacy(temp));
        writeMod("[sao-entrance] helmet visible"); assertFalse(SaoHelmetSignal.legacy(temp));
        java.nio.file.Files.writeString(temp.resolve("broken.jar"),"invalid");
        assertFalse(SaoHelmetSignal.legacy(temp));
    }
    /// Writes only synthetic class constants; no executable game is used.
    private void writeMod(String constants) throws Exception {
        try (var out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(temp.resolve("entrance.jar")))) {
            out.putNextEntry(new java.util.zip.ZipEntry("test/entrance/HelmetScreen.class"));
            out.write(constants.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
        }
    }

    /// Stream survives overflow and clear; historical lines never enter it.
    @Test void logRingOverflowClearAndHistory() throws Exception {
        java.nio.file.Files.createDirectories(temp.resolve("logs"));
        java.nio.file.Files.writeString(temp.resolve("logs/latest.log"),"[sao-entrance] helmet visible");
        var logs = new SaoGameLogs(temp); List<Optional<String>> events = new ArrayList<>(); logs.observe(events::add);
        logs.loadPrevious(); assertTrue(events.isEmpty());
        logs.clear();
        for (int i=0;i<6000;i++) logs.append("line " + i);
        logs.append("[sao-entrance] helmet visible");
        assertEquals(5000,logs.snapshot().size()); assertEquals(6002,events.size());
        assertEquals(Optional.empty(),events.get(0));
        assertEquals(Optional.of("[sao-entrance] helmet visible"),events.get(events.size()-1));
        logs.clear(); logs.append("new run"); assertEquals(List.of("new run"),logs.snapshot());
        assertEquals(Optional.empty(),events.get(events.size()-2));
    }
    /// Early hide is retained across the pending phase; release unlocks cover ownership without completing it.
    @Test void earlyHideReleaseAndCancel() {
        var cover = new SaoCoverHandoff(); int[] calls={0};
        cover.begin(); cover.hold(() -> calls[0]++);
        assertTrue(cover.active()); assertTrue(cover.armed()); assertEquals(0,calls[0]);
        cover.release(); assertTrue(cover.released()); assertTrue(cover.active()); assertEquals(0,calls[0]);
        cover.complete(); cover.complete(); assertEquals(1,calls[0]); assertFalse(cover.active());
        cover.begin(); cover.hold(() -> calls[0]++); cover.cancel(); cover.complete(); assertEquals(1,calls[0]);
        assertFalse(cover.released()); assertFalse(cover.active());
    }
    /// Window adapter captures the screen callback without executing it until the simulated fade ends.
    @NotNullByDefault
    private static final class Window implements SaoLauncherLifecycle.Window {
        /// Recorded policy effects.
        final List<String> actions = new ArrayList<>();
        /// Held completion.
        @Nullable Runnable held;
        /// Holds through the simulated screen fade.
        public void handover(Runnable action) { held=action; }
        /// Records screen cancellation.
        public void cancel() { held=null; }
        /// Records hide.
        public void hide() { actions.add("hide"); }
        /// Records show.
        public void show() { actions.add("show"); }
        /// Records close.
        public void close() { actions.add("close"); }
        /// Completes the simulated fade.
        void finish() { Objects.requireNonNull(held).run(); }
    }
    /// Every policy waits for screen completion; CLOSE additionally waits for authentication.
    @Test void visibilityBranchesAndAuthOrder() {
        for (SaoData.LauncherBehavior behavior : SaoData.LauncherBehavior.values()) {
            var window=new Window(); var life=new SaoLauncherLifecycle(Runnable::run,window);
            life.begin(behavior); life.started(); life.handedOff(); assertTrue(window.actions.isEmpty());
            window.finish();
            assertEquals(behavior == SaoData.LauncherBehavior.KEEP ? List.of() :
                    List.of(behavior == SaoData.LauncherBehavior.CLOSE ? "close" : "hide"),window.actions);
        }
        var window=new Window(); var life=new SaoLauncherLifecycle(Runnable::run,window);
        life.begin(SaoData.LauncherBehavior.CLOSE); life.started(); window.finish(); assertTrue(window.actions.isEmpty());
        life.handedOff(); life.handedOff(); assertEquals(List.of("close"),window.actions);
    }
    /// Process exit cancels stale fades immediately but restores hidden UI only after diagnostics are ready.
    @Test void restorationWaitsForExitDiagnostics() {
        var window = new Window(); var life = new SaoLauncherLifecycle(Runnable::run, window);
        life.begin(SaoData.LauncherBehavior.HIDE); life.started(); window.finish();
        assertEquals(List.of("hide"), window.actions);
        life.processEnded(); assertNull(window.held);
        assertEquals(List.of("hide"), window.actions);
        life.exited(); life.exited();
        assertEquals(List.of("hide", "show"), window.actions);
    }
    /// Early exit and new generations fence both a held screen callback and queued authentication completion.
    @Test void earlyExitAndGenerationFence() {
        var window=new Window(); var life=new SaoLauncherLifecycle(Runnable::run,window);
        life.begin(SaoData.LauncherBehavior.CLOSE); life.started(); Runnable stale=Objects.requireNonNull(window.held);
        life.handedOff(); life.exited(); stale.run(); assertTrue(window.actions.isEmpty());
        life.begin(SaoData.LauncherBehavior.CLOSE); life.started(); stale.run(); life.handedOff(); assertTrue(window.actions.isEmpty());
        window.finish(); assertEquals(List.of("close"),window.actions);
        List<Runnable> queue=new ArrayList<>(); var queued=new SaoLauncherLifecycle(queue::add,new Window());
        queued.begin(SaoData.LauncherBehavior.CLOSE); queued.started(); queued.handedOff(); queued.exited(); queue.forEach(Runnable::run);
    }
}
