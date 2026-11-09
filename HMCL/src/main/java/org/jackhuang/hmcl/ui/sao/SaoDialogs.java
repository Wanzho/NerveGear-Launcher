/*
 * NerveGear启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// HMCL's own pop-ups in the launcher's look (James, 9 Nov: "所有hmcl可能会跳的弹窗都restyle了吗").
///
/// JavaFX alerts that open their own window (start-up checks, the known-crash notice) get [#style(Alert)]: our
/// panel, no system title bar, 浅色 or 深色 as the screen is. Dialogs inside the main window (Controllers.dialog:
/// messages, the licence, Microsoft sign-in, task progress) follow `sao-hmcl.css` and `.theme-sao-dark` on the scene
/// root. The 一键反馈 hook for the crash card is [#feedback(SaoLauncherService)].
@NotNullByDefault
public final class SaoDialogs {
    /// Not instantiable.
    private SaoDialogs() {
    }

    /// The look the screen is in, kept by the helmet page; before it exists, the system's look (or the time).
    private static @Nullable Boolean dark;

    /// Records the screen's look (the helmet page calls it on every switch).
    ///
    /// @param on true for 深色
    static void setDark(boolean on) {
        dark = on;
    }

    /// Whether pop-ups should be dark now.
    ///
    /// @return true for 深色
    public static boolean dark() {
        Boolean d = dark;
        return d != null ? d : SaoTheme.decide(SaoTheme.Mode.SYSTEM, SaoTheme.systemDark(), LocalTime.now());
    }

    /// Gives an alert window the launcher's look: our panel, no system title bar, no header or icon, orange main
    /// button, 浅色 or 深色.
    ///
    /// @param alert the alert
    /// @param <A>   its type
    /// @return the same alert
    public static <A extends Alert> A style(A alert) {
        if (!SaoUi.enabled()) return alert;
        alert.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        alert.setHeaderText(null);
        alert.setGraphic(null);
        stylePane(alert.getDialogPane());
        alert.setOnShowing(e -> alert.getDialogPane().getScene().setFill(javafx.scene.paint.Color.TRANSPARENT));
        return alert;
    }

    /// The panel part of [#style(Alert)] (also used for headless shots).
    ///
    /// @param pane the alert's pane
    public static void stylePane(DialogPane pane) {
        pane.getStylesheets().add(Objects.requireNonNull(SaoDialogs.class.getResource("/assets/css/sao-alert.css")).toExternalForm());
        pane.getStyleClass().add("sao-alert");
        pane.setStyle("-fx-font-family: \"" + SaoAssets.saoFamily() + "\";");
        if (dark()) pane.getStyleClass().add("sao-dark");
    }

    /// A stylesheet giving HMCL's in-window dialogs the SAO face (Chinese falls back to the Simplified face).
    ///
    /// @return a data URI
    public static String fontSheet() {
        String css = ".jfx-dialog-layout, .task-executor-dialog-layout, .jfx-dialog-layout .label, "
                + ".task-executor-dialog-layout .label, .jfx-dialog-layout .button { -fx-font-family: \"" + SaoAssets.saoFamily() + "\"; }";
        return "data:text/css;charset=UTF-8;base64," + java.util.Base64.getEncoder().encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /// HOOK for 一键反馈 on the crash card: calls the engine's `buildFeedbackZip()` when the service has it (the
    /// backend adds it on sao/backend; nothing here needs changing when it lands), then shows the zip.
    ///
    /// @param s the engine
    /// @return true when the engine has the method
    static boolean feedbackAvailable(SaoLauncherService s) {
        return feedbackMethod(s) != null;
    }

    /// HOOK: builds the feedback zip (logs and crash report in one package) and shows it in the file manager.
    ///
    /// @param s the engine
    static void feedback(SaoLauncherService s) {
        @Nullable Method m = feedbackMethod(s);
        if (m == null) return;
        try {
            Object r = m.invoke(s);
            if (r instanceof CompletionStage<?> stage)
                stage.thenAccept(p -> javafx.application.Platform.runLater(() -> show(s, p)));
            else show(s, r);
        } catch (ReflectiveOperationException e) {
            LOG.warning("SAO: feedback zip failed", e);
        }
    }

    private static void show(SaoLauncherService s, @Nullable Object result) {
        if (result instanceof Path p) s.openFile(p);
    }

    private static @Nullable Method feedbackMethod(SaoLauncherService s) {
        try {
            return s.getClass().getMethod("buildFeedbackZip");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
