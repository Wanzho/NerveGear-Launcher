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

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Objects;

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

    /// Asks before ending an instance process; dismisses itself if the process exits naturally.
    public static java.util.concurrent.CompletableFuture<Boolean> endGame(boolean held,
            java.util.function.BooleanSupplier alive) {
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        if (Boolean.getBoolean("java.awt.headless")) { result.complete(false); return result; }
        javafx.application.Platform.runLater(() -> {
            if (!alive.getAsBoolean()) { result.complete(true); return; }
            var end = new javafx.scene.control.ButtonType(held ? "结束游戏" : "结束并继续",
                    javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            var cancel = new javafx.scene.control.ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert alert = style(new Alert(Alert.AlertType.CONFIRMATION, held
                    ? "游戏还在运行（可能卡在后台），请先关掉游戏"
                    : "上次的游戏还在后台运行（可能卡住了），要结束它吗？", end, cancel));
            var timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), e -> {
                if (!alive.getAsBoolean()) { result.complete(true); alert.close(); }
            }));
            timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
            alert.setOnHidden(e -> { timer.stop(); result.complete(alert.getResult() == end); });
            alert.show(); timer.play();
        });
        return result;
    }

    /// Leaves an actionable path when the operating-system handler cannot open a local object.
    public static void folderFailure(Path path) {
        if (Boolean.getBoolean("java.awt.headless")) return;
        javafx.application.Platform.runLater(() -> {
            var copy = new javafx.scene.control.ButtonType("复制路径", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            Alert alert = style(new Alert(Alert.AlertType.INFORMATION,
                    "无法打开文件夹，请将路径粘贴到文件管理器：\n" + path, copy, javafx.scene.control.ButtonType.CLOSE));
            alert.setOnHidden(e -> {
                if (alert.getResult() == copy) {
                    var content = new javafx.scene.input.ClipboardContent(); content.putString(path.toString());
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                }
            });
            alert.show();
        });
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
        if (alert.getAlertType() == Alert.AlertType.ERROR) {
            @Nullable SaoLauncherService service = SaoHelmetPage.diagnosticService();
            if (service != null) {
                alert.getDialogPane().setExpandableContent(exportButton(service));
                alert.getDialogPane().setExpanded(true);
            }
        }
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

    /// The SAO windows' scale (the helmet's 1280 x 720 design fitted to the window).
    private static final javafx.beans.property.DoubleProperty SCALE = new javafx.beans.property.SimpleDoubleProperty(1);

    /// Records the SAO windows' scale (the helmet page, on every resize).
    ///
    /// @param s the scale
    static void setScale(double s) {
        SCALE.set(s);
    }

    /// Draws HMCL's in-window dialogs at the SAO windows' scale (James, 9 Nov: the dialogs were tiny beside them):
    /// every dialog that opens in `container` has its panel scaled about its centre.
    ///
    /// @param container the window's dialog container
    public static void scaleDialogs(javafx.scene.layout.StackPane container) {
        if (container.getProperties().put("sao-dialog-scale", Boolean.TRUE) != null) return;
        javafx.collections.ListChangeListener<javafx.scene.Node> l = c -> {
            while (c.next()) for (javafx.scene.Node n : c.getAddedSubList()) scale(n);
        };
        container.getChildren().addListener(l);
        container.getChildren().forEach(SaoDialogs::scale);
    }

    private static void scale(javafx.scene.Node n) {
        if (n instanceof com.jfoenix.controls.JFXDialog d && d.getContent() != null) {
            d.getContent().scaleXProperty().bind(SCALE);
            d.getContent().scaleYProperty().bind(SCALE);
        }
    }

    /// A stylesheet giving HMCL's in-window dialogs the SAO face (Chinese falls back to the Simplified face).
    ///
    /// @return a data URI
    public static String fontSheet() {
        String css = ".jfx-dialog-layout, .task-executor-dialog-layout, .jfx-dialog-layout .label, "
                + ".task-executor-dialog-layout .label, .jfx-dialog-layout .button { -fx-font-family: \"" + SaoAssets.saoFamily() + "\"; }";
        return "data:text/css;charset=UTF-8;base64," + java.util.Base64.getEncoder().encodeToString(css.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /// 一键反馈 on the crash card: the engine's `buildFeedbackZip()`, then the zip shown in the file manager.
    ///
    /// @param s    the engine
    /// @param done gets null when the zip is there, else the reason it failed (on the FX thread)
    static void feedback(SaoLauncherService s, java.util.function.Consumer<@Nullable String> done) {
        s.buildFeedbackZip().whenComplete((zip, err) -> javafx.application.Platform.runLater(() -> {
            if (err == null) {
                show(s, zip);
                done.accept(null);
                return;
            }
            Throwable t = err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err;
            LOG.warning("SAO: feedback zip failed", t);
            done.accept(t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : t.getClass().getSimpleName());
        }));
    }

    /// Adds diagnostics to HMCL error panes while preserving their existing actions.
    public static void addDiagnosticAction(javafx.scene.layout.HBox actions) {
        @Nullable SaoLauncherService service = SaoHelmetPage.diagnosticService();
        if (service != null) actions.getChildren().add(exportButton(service));
    }

    /// Visible single-click diagnostic action shared by settings and failure windows.
    static javafx.scene.control.Button exportButton(SaoLauncherService service) {
        javafx.scene.control.Button button = SaoUiKit.button("一键导出日志");
        button.setOnAction(e -> {
            button.setDisable(true);
            button.setText("正在导出…");
            feedback(service, (@Nullable String error) -> {
                button.setDisable(false);
                button.setText("一键导出日志");
                SaoHelmetPage.exportToast(error == null ? "日志已保存到桌面，直接发到群里就行" : "导出失败：" + error);
            });
        });
        return button;
    }

    /// Failure details always expose the same no-dialog export action.
    static void failure(SaoLauncherService service, String message) {
        Alert alert = style(new Alert(Alert.AlertType.ERROR));
        alert.setContentText(message);
        alert.getDialogPane().setExpandableContent(exportButton(service));
        alert.getDialogPane().setExpanded(true);
        alert.show();
    }

    /// Consumes the previous abnormal-run notice once, without entering any account flow.
    static void previousCrash(SaoLauncherService service) {
        if (!service.consumePreviousCrash()) return;
        Alert alert = style(new Alert(Alert.AlertType.CONFIRMATION));
        alert.setContentText("上次启动器意外关闭了，要导出日志吗？");
        javafx.scene.control.ButtonType export = new javafx.scene.control.ButtonType("一键导出日志");
        alert.getButtonTypes().setAll(export, new javafx.scene.control.ButtonType("不用"));
        alert.setOnHidden(e -> {
            if (alert.getResult() == export) feedback(service, (@Nullable String error) ->
                    SaoHelmetPage.exportToast(error == null ? "日志已保存到桌面，直接发到群里就行" : "导出失败：" + error));
        });
        alert.show();
    }

    /// Reveals the backend's committed feedback archive while retaining the older path-only service seam.
    private static void show(SaoLauncherService s, @Nullable Object result) {
        if (result instanceof org.jackhuang.hmcl.sao.SaoData.FeedbackZip zip)
            org.jackhuang.hmcl.ui.FXUtils.showFileInExplorer(zip.path());
        else if (result instanceof Path p) s.openFile(p);
    }
}
