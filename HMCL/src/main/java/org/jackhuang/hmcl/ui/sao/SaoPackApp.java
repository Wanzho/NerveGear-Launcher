/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoInstallProgress;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// 整合包 / 启动器更新: the window that takes the files from Quark. Everything is the backend's
/// (`acceptDroppedFiles`, the prompt's versions, its fixed sentences); this only shows it.
/// A Downloads find is only offered, never installed without a press.
@NotNullByDefault
final class SaoPackApp {
    /// Which file the window asks for.
    enum Mode {
        /// The pack: first install, a full pack or quick updates.
        PACK,
        /// The launcher: below the minimum, an optional newer one, or one waiting for a restart.
        LAUNCHER
    }

    /// The backend's own first-run sentence, shown until its prompt is published.
    static final String FIRST_RUN = "请把在夸克里下载的模组文件拖进来";

    /// Drops being checked now (from the window or anywhere on the page).
    private static final ReadOnlyIntegerWrapper BUSY = new ReadOnlyIntegerWrapper();
    /// The last drop's result: null while none, "" when it went through, else the reason.
    private static final ReadOnlyObjectWrapper<@Nullable String> RESULT = new ReadOnlyObjectWrapper<>();
    /// The first-open checks, once they are in (null until then).
    private static final ReadOnlyObjectWrapper<@Nullable List<SaoData.SystemCheck>> CHECKS = new ReadOnlyObjectWrapper<>();

    /// The engine the checks ran for (one run per engine, normally one per start).
    private static @Nullable SaoLauncherService checkedFor;

    private SaoPackApp() {
    }

    static ReadOnlyIntegerProperty busy() {
        return BUSY.getReadOnlyProperty();
    }

    static ReadOnlyObjectProperty<@Nullable String> result() {
        return RESULT.getReadOnlyProperty();
    }

    static ReadOnlyObjectProperty<@Nullable List<SaoData.SystemCheck>> checks() {
        return CHECKS.getReadOnlyProperty();
    }

    /// Runs the system check once (first open); BLOCK stops every import.
    static void runChecks(SaoLauncherService s) {
        if (checkedFor == s) return;
        checkedFor = s;
        CHECKS.set(null);
        s.systemCheck().whenComplete((list, err) -> Platform.runLater(() ->
                CHECKS.set(err == null && list != null ? List.copyOf(list) : List.of())));
    }

    /// Whether a BLOCK check stops installing.
    static boolean blocked() {
        @Nullable List<SaoData.SystemCheck> c = CHECKS.get();
        if (c == null) return false;
        for (SaoData.SystemCheck k : c) if (k.level() == SaoData.CheckLevel.BLOCK) return true;
        return false;
    }

    /// Hands files to the backend (full packs, quick updates and launcher files alike, in any order).
    static void accept(SaoLauncherService s, List<Path> files) {
        if (files.isEmpty() || blocked()) return;
        BUSY.set(BUSY.get() + 1);
        RESULT.set(null);
        s.acceptDroppedFiles(List.copyOf(files)).whenComplete((ok, err) -> Platform.runLater(() -> {
            BUSY.set(Math.max(0, BUSY.get() - 1));
            if (err == null) RESULT.set("");
            else {
                @Nullable String backend = s.lastError().get();
                Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                RESULT.set(backend != null && !backend.isBlank() ? backend
                        : t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : "校验失败");
            }
        }));
    }

    /// The files of a drag, any kind (the backend tells them apart by content).
    static List<Path> files(DragEvent e) {
        List<Path> out = new ArrayList<>();
        if (e.getDragboard().hasFiles()) for (File f : e.getDragboard().getFiles()) if (f.isFile()) out.add(f.toPath());
        return out;
    }

    /// The not-installed state, as the backend reports it: nothing installed.
    static boolean notInstalled(SaoLauncherService s) {
        return s.installedVersion().get() == null && s.state().get() != SaoPackState.WORKING
                && s.state().get() != SaoPackState.CHECKING && s.state().get() != SaoPackState.RUNNING;
    }

    /// Builds the window.
    static SaoFloatWindow make(SaoLauncherService s, Runnable close, Mode mode) {
        boolean pack = mode == Mode.PACK;
        SaoFloatWindow win = new SaoFloatWindow(pack ? (notInstalled(s) ? "安装整合包" : "整合包更新") : "启动器更新",
                SaoApps.W, SaoApps.H, close);

        Text message = SaoMotion.report(ink("", 17));
        message.setWrappingWidth(SaoApps.W - 40);
        Text sub = dim("", 12.5);
        HBox chips = new HBox(8);
        chips.setAlignment(Pos.CENTER_LEFT);

        // The drop target; the whole page takes drops too.
        Text dropText = ink("拖入文件", 15);
        Text dropSub = dim(pack ? ".zip" : ".jar · .zip", 12);
        VBox dropWords = new VBox(4, icon(SVG.FILE_OPEN, 28), dropText, dropSub);
        dropWords.setAlignment(Pos.CENTER);
        StackPane drop = new StackPane(dropWords);
        drop.getStyleClass().add("vr-drop");
        drop.setMinHeight(104);
        drop.setPrefHeight(104);
        VBox.setVgrow(drop, Priority.SOMETIMES);

        // A Downloads find, offered with its own button.
        Text candName = ink("", 13.5);
        Button candInstall = accent("安装");
        HBox candidate = SaoMotion.revealOne(tile(icon(SVG.DOWNLOAD, 22), new VBox(3, key("下载中找到"), candName), grow(), candInstall));
        candidate.setAlignment(Pos.CENTER_LEFT);

        // Checking, failure, done.
        Text status = SaoMotion.report(ink("", 13.5));
        // Done (已安装, 已就绪) lands with a glow, once per change (James, 14 Nov: "成功等").
        boolean[] wasOk = {false};
        status.setWrappingWidth(SaoApps.W - 70);
        StackPane statusBar = bar(SaoApps.W - 40, 0);
        VBox statusBox = SaoMotion.revealOne(new VBox(6, status, statusBar));

        VBox checks = SaoMotion.reveal(new VBox(6));

        Button quark = button("打开夸克");
        quark.setGraphic(icon(SVG.DOWNLOAD, 15));
        quark.setOnAction(e -> {
            if (pack) s.openPackDownload();
            else s.openLauncherDownload();
        });
        Button pick = button("选择文件");
        pick.setGraphic(icon(SVG.FOLDER_OPEN, 15));
        pick.setOnAction(e -> {
            FileChooser c = new FileChooser();
            c.setTitle("选择文件");
            c.getExtensionFilters().add(pack ? new FileChooser.ExtensionFilter("整合包", "*.zip")
                    : new FileChooser.ExtensionFilter("启动器", "*.jar", "*.zip"));
            @Nullable List<File> chosen = c.showOpenMultipleDialog(win.getScene() != null ? win.getScene().getWindow() : null);
            if (chosen != null) accept(s, chosen.stream().map(File::toPath).toList());
        });
        Button later = button("稍后");
        later.setOnAction(e -> {
            s.dismissLauncherUpdate();
            close.run();
        });
        HBox buttons = new HBox(8, grow(), later, quark, pick);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Runnable refresh = () -> {
            SaoData.DownloadPrompt p = s.downloadPrompt().get();
            SaoData.UpdateStatus u = s.updateStatus().get();
            SaoData.LauncherUpdate lu = s.launcherUpdate().get();
            @Nullable String installed = s.installedVersion().get();
            boolean busy = BUSY.get() > 0 || s.state().get() == SaoPackState.WORKING;
            boolean block = blocked();

            // The backend's sentence, verbatim.
            String text;
            if (pack) text = p.message().isBlank() ? (installed == null ? FIRST_RUN : "") : p.message();
            else if (lu.restartRequired()) text = lu.message();
            else text = u.message();
            message.setText(text);
            message.setVisible(!text.isBlank());
            message.setManaged(message.isVisible());

            SaoData.@Nullable VersionMetadata meta = u.metadata();
            List<String> facts = new ArrayList<>();
            if (pack) {
                facts.add(installed == null ? "未安装" : "已安装 v" + installed);
                if (meta != null) facts.add("最新 v" + meta.pack().version());
            } else {
                facts.add("当前 v" + org.jackhuang.hmcl.Metadata.VERSION);
                if (meta != null) facts.add("最新 v" + meta.launcher().latest());
                if (meta != null && u.launcherBlocked()) facts.add("最低 v" + meta.launcher().min());
            }
            sub.setText(String.join(" · ", facts));

            // Quick updates: each version that is in, and each still missing.
            chips.getChildren().clear();
            if (pack && !p.fullPackRequired()) for (String v : p.required()) {
                boolean in = p.present().contains(v);
                HBox chip = new HBox(6, icon(in ? SVG.CHECK_CIRCLE : SVG.DOWNLOAD, 15), ink("[" + v + "]", 13.5),
                        in ? good("已就绪", 12.5) : dim("缺少", 12.5));
                chip.setAlignment(Pos.CENTER_LEFT);
                chip.getStyleClass().add("vr-tile");
                chip.setPadding(new Insets(5, 10, 5, 10));
                chips.getChildren().add(chip);
            }
            chips.setVisible(!chips.getChildren().isEmpty());
            chips.setManaged(chips.isVisible());

            @Nullable Path cand = pack ? p.candidate() : null;
            candidate.setVisible(cand != null && !busy && !block);
            candidate.setManaged(candidate.isVisible());
            if (cand != null) {
                candName.setText(cand.getFileName().toString());
                candInstall.setOnAction(e -> accept(s, List.of(cand)));
            }

            // Status: checking, the backend's failure, done.
            SaoInstallProgress ip = s.installProgress().get();
            String st;
            boolean bad = false, ok = false;
            double amount = -1;
            if (busy) {
                st = ip.stage() != SaoInstallProgress.Stage.IDLE && !ip.label().isBlank() ? ip.label() : "校验中";
                if (ip.totalBytes() > 0) amount = (double) ip.bytesDone() / ip.totalBytes();
                else if (ip.totalFiles() > 0) amount = (double) ip.completedFiles() / ip.totalFiles();
                else if (s.progress().get() >= 0 && s.state().get() == SaoPackState.WORKING) amount = s.progress().get();
            } else if (RESULT.get() != null && !RESULT.get().isEmpty()) {
                st = "! " + RESULT.get();
                bad = true;
            } else if (s.lastError().get() != null && s.state().get() == SaoPackState.FAILED) {
                st = "! " + s.lastError().get();
                bad = true;
            } else if (!pack && lu.restartRequired()) {
                st = lu.version().isBlank() ? "已就绪" : "v" + lu.version() + " 已就绪";
                ok = true;
            } else if (pack && installed != null && "".equals(RESULT.get()) && p.missing().isEmpty()) {
                st = "已安装 v" + installed;
                ok = true;
            } else st = "";
            status.setText(st);
            status.getStyleClass().removeAll("vr-warn", "vr-good", "vr-ink");
            status.getStyleClass().add(bad ? "vr-warn" : ok ? "vr-good" : "vr-ink");
            if (ok && !wasOk[0]) SaoMotion.success(status, 0);
            wasOk[0] = ok;
            statusBar.setVisible(busy);
            statusBar.setManaged(busy);
            if (busy) setBar(statusBar, amount);
            statusBox.setVisible(!st.isEmpty());
            statusBox.setManaged(statusBox.isVisible());

            // First-open checks: warnings and blocks only.
            checks.getChildren().clear();
            @Nullable List<SaoData.SystemCheck> cs = CHECKS.get();
            if (cs != null && pack) for (SaoData.SystemCheck c : cs) {
                if (c.level() == SaoData.CheckLevel.OK) continue;
                Text t = ink(c.text(), 13);
                t.setWrappingWidth(SaoApps.W - 110);
                HBox h = tile(icon(c.level() == SaoData.CheckLevel.BLOCK ? SVG.ERROR : SVG.WARNING, 20), t);
                h.setAlignment(Pos.CENTER_LEFT);
                h.getStyleClass().add("vr-tile-warn");
                checks.getChildren().add(h);
            }
            checks.setVisible(!checks.getChildren().isEmpty());
            checks.setManaged(checks.isVisible());

            // A launcher JAR only stands in for an optional update the release names one for; below the minimum, or
            // when the runtime changed, only the complete launcher from Quark will do (the backend's rule).
            boolean jarOk = meta != null && !u.launcherBlocked() && meta.launcher().artifact() != null
                    && !meta.launcher().nativeRequired() && !lu.restartRequired();
            boolean takesFiles = pack || jarOk;
            drop.setDisable(busy || block);
            pick.setDisable(busy || block);
            drop.setVisible(takesFiles);
            drop.setManaged(takesFiles);
            pick.setVisible(takesFiles);
            pick.setManaged(takesFiles);
            quark.setVisible(pack || !lu.restartRequired());
            quark.setManaged(quark.isVisible());
            later.setVisible(!pack && !u.launcherBlocked() && u.launcherUpdateAvailable() && !lu.restartRequired());
            later.setManaged(later.isVisible());
        };

        drop.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && !drop.isDisabled()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        drop.setOnDragEntered(e -> {
            drop.getStyleClass().add("vr-drop-on");
            SaoMotion.lift(drop, true);
        });
        drop.setOnDragExited(e -> {
            drop.getStyleClass().remove("vr-drop-on");
            SaoMotion.lift(drop, false);
        });
        drop.setOnDragDropped(e -> {
            List<Path> f = files(e);
            accept(s, f);
            e.setDropCompleted(!f.isEmpty());
            e.consume();
        });

        Observable[] watched = {s.downloadPrompt(), s.updateStatus(), s.launcherUpdate(), s.lastError(), s.installedVersion(),
                s.state(), s.progress(), s.installProgress(), BUSY, RESULT, CHECKS};
        InvalidationListener on = o -> refresh.run();
        for (Observable o : watched) o.addListener(on);
        // While the window is up the backend watches Downloads; closing it stops that and drops the half-done quick
        // updates (the backend's contract: they gather only while the prompt is open).
        if (pack) s.watchPackDownload();
        win.parentProperty().addListener((o, a, b) -> {
            if (b != null) return;
            for (Observable x : watched) x.removeListener(on);
            if (pack) s.cancelPackDownload();
        });
        RESULT.set(null);
        refresh.run();

        VBox top = new VBox(6, message, sub);
        VBox content = new VBox(10, top, chips, checks, candidate, drop, statusBox);
        VBox.setVgrow(content, Priority.ALWAYS);
        return win.add(content, buttons);
    }

    /// A state word in the "good" colour.
    private static Text good(String s, double size) {
        Text t = ink(s, size);
        t.getStyleClass().setAll("vr-good");
        return t;
    }

    /// Sets a [SaoUiKit#bar] to an amount (unknown: a short sliver).
    private static void setBar(StackPane bar, double amount) {
        if (bar.getChildren().size() < 2) return;
        Node fill = bar.getChildren().get(1);
        if (fill instanceof javafx.scene.layout.Region r) {
            SaoMotion.width(r, bar.getMaxWidth() * (amount < 0 ? 0.08 : Math.max(0.02, Math.min(1, amount))));
        }
    }
}
