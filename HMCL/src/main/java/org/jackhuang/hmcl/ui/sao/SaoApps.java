/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// The windows the dock opens: settings, worlds, multiplayer, shaders and resource packs, screenshots, logs,
/// Java and download source, downloads, and about. Each reads and changes the pack only through the engine.
@NotNullByDefault
final class SaoApps {
    /// Not instantiable.
    private SaoApps() {
    }


    /// The size every app window has.
    static final double W = 700, H = 392;

    /// Builds an app's window.
    @FunctionalInterface
    interface Maker {
        /// Builds the window.
        ///
        /// @param service the engine
        /// @param close   closes it
        /// @return the window
        SaoFloatWindow make(SaoLauncherService service, Runnable close);
    }

    /// One app in the dock.
    ///
    /// @param icon  its icon
    /// @param label its label
    /// @param make  builds its window
    record App(SVG icon, String label, Maker make) {
    }

    /// The dock's apps, top to bottom.
    static final App[] APPS = {
            new App(SVG.SETTINGS, "设置", SaoApps::settings),
            new App(SVG.CHAT, "好友", SaoFriendsApp::make),
            new App(SVG.CHECKROOM, "皮肤", SaoSkinApp::make),
            new App(SVG.LANDSCAPE, "世界", SaoApps::worlds),
            new App(SVG.HOST, "联机", SaoApps::multiplayer),
            new App(SVG.WB_SUNNY, "光影", SaoApps::packs),
            new App(SVG.SCREENSHOT_MONITOR, "截图", SaoApps::screenshots),
            new App(SVG.SCRIPT, "日志", SaoApps::logs),
            new App(SVG.LOCAL_CAFE, "Java", SaoApps::java),
            new App(SVG.DOWNLOAD, "下载", SaoApps::downloads),
            new App(SVG.INFO, "关于", SaoApps::about),
    };

    // ------------------------------------------------------------------ helpers

    /// Keeps a column in step with a list: rebuilds its rows whenever the list changes.
    ///
    /// @param list  the list
    /// @param row   builds one row
    /// @param empty shown when the list is empty
    /// @param <T>   the item type
    /// @return the column
    static <T> VBox live(ObservableList<T> list, Function<T, Node> row, Node empty) {
        VBox col = new VBox(6);
        Runnable fill = () -> {
            List<Node> rows = new ArrayList<>();
            for (T t : list) {
                Node r = row.apply(t);
                // Keyed by its item: only rows that are new come in when the list changes (SaoMotion.list).
                if (r.getUserData() == null) r.setUserData(t);
                rows.add(r);
            }
            if (rows.isEmpty()) rows.add(empty);
            SaoMotion.list(col, rows);
        };
        list.addListener((ListChangeListener<T>) c -> fill.run());
        fill.run();
        return col;
    }

    /// Runs again whenever a value changes, and once now.
    private static <T> void follow(ObservableValue<T> v, Runnable r) {
        v.addListener((o, a, b) -> r.run());
        r.run();
    }

    /// A column of fixed width.
    private static VBox column(double width, Node... nodes) {
        VBox v = new VBox(8, nodes);
        v.setPrefWidth(width);
        v.setMinWidth(width);
        v.setMaxWidth(width);
        return v;
    }

    /// Two columns side by side with a line between them.
    private static HBox columns(VBox left, VBox right) {
        Region line = new Region();
        line.getStyleClass().add("vr-rule");
        line.setMinWidth(1);
        line.setMaxWidth(1);
        HBox h = new HBox(18, left, line, right);
        VBox.setVgrow(h, Priority.ALWAYS);
        return h;
    }

    /// A row of footer actions at the bottom of a window.
    private static HBox footer(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    /// A small square standing in for a picture.
    private static StackPane thumb(@Nullable Path png, SVG fallback, double size) {
        StackPane box = new StackPane();
        box.getStyleClass().add("vr-thumb");
        box.setMinSize(size, size);
        box.setMaxSize(size, size);
        if (png != null) {
            ImageView v = new ImageView(new Image(png.toUri().toString(), size, size, true, false, true));
            box.getChildren().add(v);
        } else {
            box.getChildren().add(icon(fallback, size * 0.55));
        }
        return box;
    }

    /// Shows an action's outcome in its window: a short word on success, the reason on failure (then `undo`).
    private static void report(java.util.concurrent.CompletableFuture<Void> action, Text status, String done, @Nullable Runnable undo) {
        status.setText("");
        action.whenComplete((ignored, failure) -> javafx.application.Platform.runLater(() -> {
            if (failure == null) { status.setText(done); return; }
            Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            status.setText("! " + java.util.Objects.toString(cause.getMessage(), "操作失败"));
            if (undo != null) undo.run();
        }));
    }

    // ------------------------------------------------------------------ settings

    /// Settings: memory, the game window, JVM arguments, repair and the game folder.
    static SaoFloatWindow settings(SaoLauncherService s, Runnable close) {
        int max = Math.max(4096, s.systemMemoryMiB());
        Slider memory = new Slider(2048, max, Math.min(max, s.memoryMiB()));
        memory.getStyleClass().add("vr-slider");
        memory.setBlockIncrement(512);
        memory.setMajorTickUnit(512);
        memory.setMinorTickCount(0);
        memory.setSnapToTicks(true);
        Text memValue = sao("", 14, true);
        ToggleButton auto = toggle(s.autoMemory());
        Runnable showMem = () -> {
            memory.setDisable(auto.isSelected());
            memValue.setText(auto.isSelected() ? "AUTO" : String.format("%.1f GB", memory.getValue() / 1024));
        };
        memory.valueProperty().addListener((o, a, b) -> {
            showMem.run();
            if (!memory.isValueChanging()) s.setMemoryMiB((int) Math.round(b.doubleValue()));
        });
        memory.valueChangingProperty().addListener((o, a, b) -> {
            if (!b) s.setMemoryMiB((int) Math.round(memory.getValue()));
        });
        auto.selectedProperty().addListener((o, a, b) -> {
            s.setAutoMemory(b);
            showMem.run();
        });
        showMem.run();
        HBox memHead = row(key("内存"), memValue);
        HBox memAuto = row(ink("自动", 13), auto);

        SaoData.GameWindow gw = s.gameWindow();
        TextField width = field("宽");
        TextField height = field("高");
        width.setText(Integer.toString(gw.width()));
        height.setText(Integer.toString(gw.height()));
        width.setPrefWidth(76);
        height.setPrefWidth(76);
        ToggleButton full = toggle(gw.fullscreen());
        Runnable saveWindow = () -> {
            try {
                s.setGameWindow(new SaoData.GameWindow(Integer.parseInt(width.getText().strip()), Integer.parseInt(height.getText().strip()), full.isSelected()));
            } catch (NumberFormatException ignored) {
                // Half typed.
            }
        };
        width.textProperty().addListener((o, a, b) -> saveWindow.run());
        height.textProperty().addListener((o, a, b) -> saveWindow.run());
        full.selectedProperty().addListener((o, a, b) -> {
            width.setDisable(b);
            height.setDisable(b);
            saveWindow.run();
        });
        HBox size = new HBox(6, width, dim("×", 13), height);
        size.setAlignment(Pos.CENTER_LEFT);
        HBox windowRow = row(size, dim("全屏", 12), full);

        // James, 8 Nov: "加几个字体大小控制吧，我眼睛要瞎了".
        ToggleGroup sizes = new ToggleGroup();
        var settings = SaoUiKit.settings();
        javafx.beans.property.DoubleProperty scale = settings != null ? settings.saoTextScaleProperty() : new javafx.beans.property.SimpleDoubleProperty(1);
        HBox sizeRow = new HBox(6);
        String[] names = {"小", "标准", "大", "特大"};
        double[] factors = {0.9, 1, 1.15, 1.3};
        for (int i = 0; i < names.length; i++) {
            ToggleButton c = chip(names[i], sizes);
            double k = factors[i];
            if (Math.abs(scale.get() - k) < 0.01) c.setSelected(true);
            c.setOnAction(e -> scale.set(k));
            sizeRow.getChildren().add(c);
        }
        if (sizes.getSelectedToggle() == null) sizes.selectToggle(sizes.getToggles().get(1));
        keepOne(sizes);

        // 外观 (James, 9 Nov: "你设计个深色的版本？", "按跟随系统/时间来定"): live, saved, 跟随系统 by default.
        ToggleGroup looks = new ToggleGroup();
        HBox lookRow = new HBox(6);
        SaoTheme.Mode chosen = SaoTheme.Mode.of(s.preference(SaoHelmetPage.THEME_KEY, SaoTheme.Mode.SYSTEM.key));
        for (SaoTheme.Mode m : SaoTheme.Mode.values()) {
            ToggleButton c = chip(m.label, looks);
            c.setSelected(m == chosen);
            c.setOnAction(e -> {
                s.setPreference(SaoHelmetPage.THEME_KEY, m.key);
                SaoHelmetPage p = SaoHelmetPage.current();
                if (p != null) p.setThemeMode(m);
            });
            lookRow.getChildren().add(c);
        }
        keepOne(looks);

        VBox left = column(318,
                memHead, memory, memAuto,
                rule(),
                key("游戏窗口"), windowRow,
                rule(),
                key("字体大小"), sizeRow,
                rule(),
                key("外观"), lookRow);

        TextField jvm = field("例如 -XX:+UseZGC");
        jvm.setText(s.jvmArgs());
        jvm.textProperty().addListener((o, a, b) -> s.setJvmArgs(b.strip()));
        Button repair = button("修复整合包");
        repair.setOnAction(e -> s.repair());
        Button folder = button("打开游戏文件夹");
        folder.setOnAction(e -> s.openFolder(SaoData.Folder.GAME));
        Text where = dim(s.instanceDir().toString(), 11);
        where.setWrappingWidth(318);
        Button uninstall = button("一键卸载");
        uninstall.getStyleClass().add("vr-danger");

        // 画质 (James approved: "除了可选模组外全做"): the backend writes the game's settings at the next safe launch.
        ToggleGroup presets = new ToggleGroup();
        HBox presetRow = new HBox(6);
        String[][] presetNames = {{"AUTO", "自动"}, {"FAST", "流畅"}, {"BALANCED", "均衡"}, {"QUALITY", "画质"}};
        for (String[] pn : presetNames) {
            SaoData.GraphicsPreset gp = SaoData.GraphicsPreset.valueOf(pn[0]);
            ToggleButton c = chip(pn[1], presets);
            c.getStyleClass().add("vr-chip-tight");
            c.setSelected(s.graphicsPreset() == gp);
            c.setOnAction(e -> s.setGraphicsPreset(gp));
            presetRow.getChildren().add(c);
        }
        keepOne(presets);
        Text suggested = dim("", 11.5);
        s.suggestedGraphicsPreset().whenComplete((gp, err) -> javafx.application.Platform.runLater(() -> {
            if (err == null && gp != null) for (String[] pn : presetNames)
                if (pn[0].equals(gp.name()) && gp != SaoData.GraphicsPreset.AUTO) suggested.setText("推荐 " + pn[1]);
        }));
        // 游戏启动后: what the launcher does while the game runs.
        ToggleGroup behaviours = new ToggleGroup();
        HBox behaviourRow = new HBox(6);
        String[][] behaviourNames = {{"HIDE", "隐藏到后台"}, {"KEEP", "保持打开"}, {"CLOSE", "直接关闭"}};
        for (String[] bn : behaviourNames) {
            SaoData.LauncherBehavior lb = SaoData.LauncherBehavior.valueOf(bn[0]);
            ToggleButton c = chip(bn[1], behaviours);
            c.getStyleClass().add("vr-chip-tight");
            c.setSelected(s.launcherBehavior() == lb);
            c.setOnAction(e -> s.setLauncherBehavior(lb));
            behaviourRow.getChildren().add(c);
        }
        keepOne(behaviours);
        @Nullable String installedPack = s.installedVersion().get();
        VBox right = column(318,
                row(key("画质"), suggested), presetRow,
                key("游戏启动后"), behaviourRow,
                rule(),
                key("启动参数（高级）"), jvm,
                rule(),
                key(installedPack != null ? "整合包 v" + installedPack : "整合包 · 未安装"),
                new HBox(8, repair, folder),
                rule(),
                uninstall,
                grow(),
                where);
        HBox main = columns(left, right);
        VBox confirm = SaoMotion.reveal(uninstallConfirm(s));
        SaoMotion.reveal(main);
        confirm.setVisible(false);
        StackPane body = new StackPane(main, confirm);
        VBox.setVgrow(body, Priority.ALWAYS);
        uninstall.setOnAction(e -> {
            main.setVisible(false);
            confirm.setVisible(true);
        });
        confirm.setUserData((Runnable) () -> {
            confirm.setVisible(false);
            main.setVisible(true);
        });
        return new SaoFloatWindow("设置", W, H, close).add(body);
    }

    /// The uninstall question, in place of the settings: what goes, keep the saves, cancel or uninstall.
    private static VBox uninstallConfirm(SaoLauncherService s) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Text title = ink("卸载刀剑神域·誓约", 20);
        SaoUiKit.font(title, 20, true);
        Text what = ink("", 14);
        what.setWrappingWidth(560);
        // The engine measures the size in the background (-1 until known), so read it again while the question shows.
        Runnable sizeText = () -> {
            long bytes = s.installedBytes();
            what.setText("游戏、模组、Java、账号和启动器本身" + (bytes > 0 ? "（约 " + size(bytes) + "）" : "")
                    + "都会移到" + (windows ? "回收站" : "废纸篓") + "，启动器随后退出。");
        };
        sizeText.run();
        javafx.animation.Timeline poll = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(500), e -> sizeText.run()));
        poll.setCycleCount(javafx.animation.Animation.INDEFINITE);
        ToggleButton keep = toggle(true);
        Text where = dim("复制到「" + s.keptSavesFolder() + "」", 12);
        where.visibleProperty().bind(keep.selectedProperty());
        HBox keepRow = row(ink("保留存档和截图", 14), keep);
        keepRow.setMaxWidth(560);
        Button cancel = button("取消");
        Button go = button("卸载");
        go.getStyleClass().add("vr-danger");
        Text running = dim("游戏运行中，先退出游戏", 12);
        Runnable busyCheck = () -> {
            boolean busy = s.state().get() == org.jackhuang.hmcl.sao.SaoPackState.RUNNING;
            go.setDisable(busy);
            running.setText("游戏运行中，先退出游戏");
            running.setVisible(busy);
        };
        busyCheck.run();
        VBox box = new VBox(14, title, what, keepRow, where, grow(), running, new HBox(10, cancel, go));
        box.setAlignment(Pos.TOP_LEFT);
        box.setPadding(new Insets(6, 4, 4, 4));
        boolean[] working = {false};
        box.visibleProperty().addListener((o, a, shown) -> {
            if (shown) {
                sizeText.run();
                busyCheck.run();
                poll.play();
            } else {
                poll.stop();
            }
        });
        cancel.setOnAction(e -> ((Runnable) box.getUserData()).run());
        go.setOnAction(e -> {
            working[0] = true;
            go.setDisable(true);
            cancel.setDisable(true);
            go.setText("正在卸载…");
            s.uninstall(keep.isSelected());
        });
        // A refusal or a failure comes back as the engine's error: show it and give the buttons back.
        s.lastError().addListener((o, a, err) -> {
            if (!working[0] || err == null) return;
            working[0] = false;
            go.setText("卸载");
            cancel.setDisable(false);
            busyCheck.run();
            running.setText(err);
            running.setVisible(true);
            SaoMotion.shake(running);
        });
        return box;
    }

    // ------------------------------------------------------------------ worlds

    /// Worlds: the saves with backup and export, and the backups with restore.
    static SaoFloatWindow worlds(SaoLauncherService s, Runnable close) {
        Text status = dim("", 12);
        VBox saves = live(s.worlds(), w -> {
            Button backup = button("备份");
            backup.setOnAction(e -> report(s.backupWorld(w), status, "备份完成", null));
            Button export = button("导出");
            export.setOnAction(e -> {
                FileChooser c = new FileChooser();
                c.setTitle("导出世界");
                c.setInitialFileName(w.name() + ".zip");
                c.getExtensionFilters().add(new FileChooser.ExtensionFilter("zip", "*.zip"));
                @Nullable File f = c.showSaveDialog(Controllers.getStage());
                if (f != null) report(s.exportWorld(w, f.toPath()), status, "导出完成", null);
            });
            HBox t = tile(thumb(w.icon(), SVG.LANDSCAPE, 40), lines(w.name(), w.mode() + " · " + ago(w.lastPlayed()) + " · " + size(w.sizeBytes())), grow(), backup, export);
            return t;
        }, empty(SVG.LANDSCAPE, "还没有单人世界"));
        VBox backups = live(s.backups(), b -> {
            Button restore = button("恢复");
            restore.setOnAction(e -> report(s.restoreBackup(b), status, "恢复完成", null));
            String world = b.world().getFileName() == null ? "" : b.world().getFileName().toString();
            for (SaoData.World w : s.worlds()) if (w.folder().equals(b.world())) world = w.name();
            return tile(lines(world, ago(b.time()) + " · " + size(b.sizeBytes())), grow(), restore);
        }, empty(SVG.RESTORE, "还没有备份"));
        Button openSaves = button("打开存档文件夹");
        openSaves.setOnAction(e -> s.openFolder(SaoData.Folder.SAVES));
        Button openBackups = button("打开备份文件夹");
        openBackups.setOnAction(e -> s.openFolder(SaoData.Folder.BACKUPS));
        VBox left = column(400, key("存档"), scroll(saves));
        VBox right = column(236, key("备份"), scroll(backups));
        return new SaoFloatWindow("世界", W, H, close).add(columns(left, right), footer(openSaves, openBackups, status));
    }

    // ------------------------------------------------------------------ multiplayer

    /// Multiplayer without a server: HMCL's Terracotta room, hosted or joined with a code.
    static SaoFloatWindow multiplayer(SaoLauncherService s, Runnable close) {
        VBox card = new VBox(12);
        card.setAlignment(Pos.CENTER);
        card.setFillWidth(false);
        VBox.setVgrow(card, Priority.ALWAYS);
        follow(s.room(), () -> {
            SaoData.Room r = s.room().get();
            card.getChildren().clear();
            switch (r.state()) {
                case OFF -> {
                    Button host = accent("创建房间");
                    host.setOnAction(e -> s.hostRoom());
                    VBox hostSide = new VBox(10, icon(SVG.HOST, 40), ink("我来开房间", 15), host);
                    hostSide.setAlignment(Pos.CENTER);
                    hostSide.setPrefWidth(280);
                    TextField code = field("房间码");
                    code.setPrefWidth(200);
                    Button join = button("加入");
                    join.setOnAction(e -> {
                        if (!code.getText().isBlank()) s.joinRoom(code.getText().strip());
                    });
                    VBox joinSide = new VBox(10, icon(SVG.PUBLIC, 40), ink("加入朋友的房间", 15), new HBox(8, code, join));
                    joinSide.setAlignment(Pos.CENTER);
                    ((HBox) joinSide.getChildren().get(2)).setAlignment(Pos.CENTER);
                    joinSide.setPrefWidth(280);
                    Region line = new Region();
                    line.getStyleClass().add("vr-rule");
                    line.setMinWidth(1);
                    line.setMaxWidth(1);
                    line.setPrefHeight(170);
                    HBox both = new HBox(30, hostSide, line, joinSide);
                    both.setAlignment(Pos.CENTER);
                    card.getChildren().add(both);
                }
                case PREPARING, JOINING -> card.getChildren().addAll(icon(SVG.REFRESH, 36), ink(r.message() == null ? "连接中" : r.message(), 15));
                case HOSTING -> {
                    Text code = sao(r.code() == null ? "" : r.code(), 30, true);
                    code.getStyleClass().add("vr-code");
                    Button copy = button("复制房间码");
                    copy.setOnAction(e -> {
                        ClipboardContent c = new ClipboardContent();
                        c.putString(r.code());
                        Clipboard.getSystemClipboard().setContent(c);
                    });
                    Button stop = button("关闭房间");
                    stop.setOnAction(e -> s.leaveRoom());
                    card.getChildren().addAll(key("房间码"), code, dim(r.message() == null ? "" : r.message(), 12),
                            ink("房间里 " + r.members() + " 人", 13), new HBox(8, copy, stop));
                    ((HBox) card.getChildren().get(4)).setAlignment(Pos.CENTER);
                }
                case JOINED -> {
                    Button leave = button("离开房间");
                    leave.setOnAction(e -> s.leaveRoom());
                    card.getChildren().addAll(icon(SVG.CHECK_CIRCLE, 36), ink("已加入房间 · " + r.members() + " 人", 15),
                            dim(r.message() == null ? "" : r.message(), 12), leave);
                }
                case FAILED -> {
                    Button retry = button("重试");
                    retry.setOnAction(e -> s.leaveRoom());
                    Text why = ink(r.message() == null ? "联机失败" : r.message(), 14);
                    why.getStyleClass().setAll("vr-warn");
                    card.getChildren().addAll(icon(SVG.WARNING, 36), why, retry);
                }
            }
        });
        return new SaoFloatWindow("联机", W, H, close)
                .add(card);
    }

    // ------------------------------------------------------------------ shaders and resource packs

    /// Shaders and resource packs, as two tabs; zips dropped on the window are added.
    static SaoFloatWindow packs(SaoLauncherService s, Runnable close) {
        Text status = dim("", 12);
        ToggleGroup tabs = new ToggleGroup();
        ToggleButton shaders = chip("光影", tabs);
        ToggleButton resources = chip("资源包", tabs);
        keepOne(tabs);

        ToggleGroup pick = new ToggleGroup();
        VBox shaderList = new VBox(6);
        Runnable[] refill = new Runnable[1];
        Runnable fillShaders = () -> {
            List<Node> rows = new ArrayList<>();
            ToggleButton none = chip("使用", pick);
            none.setOnAction(e -> report(s.setShader(null), status, "", refill[0]));
            boolean any = false;
            for (SaoData.Pack p : s.shaderPacks()) {
                ToggleButton use = chip(p.enabled() ? "使用中" : "使用", pick);
                use.setSelected(p.enabled());
                any |= p.enabled();
                use.setOnAction(e -> report(s.setShader(p), status, "", refill[0]));
                rows.add(tile(thumb(p.icon(), SVG.WB_SUNNY, 36), lines(p.name(), p.description()), grow(), use));
            }
            none.setSelected(!any);
            if (!any) none.setText("使用中");
            rows.add(0, tile(thumb(null, SVG.VISIBILITY_OFF, 36), lines("关闭光影", "帧数最高"), grow(), none));
            SaoMotion.list(shaderList, rows);
        };
        refill[0] = fillShaders;
        s.shaderPacks().addListener((ListChangeListener<SaoData.Pack>) c -> fillShaders.run());
        fillShaders.run();

        VBox resourceList = live(s.resourcePacks(), p -> {
            ToggleButton on = toggle(p.enabled());
            boolean[] reverting = new boolean[1];
            on.selectedProperty().addListener((o, a, b) -> {
                if (reverting[0]) return;
                // A refused change flips the switch back without asking the engine again.
                report(s.setResourcePackEnabled(p, b), status, "", () -> { reverting[0] = true; on.setSelected(a); reverting[0] = false; });
            });
            return tile(thumb(p.icon(), SVG.TEXTURE, 36), lines(p.name(), p.description()), grow(), on);
        }, empty(SVG.TEXTURE, "没有资源包"));

        StackPane body = new StackPane(scroll(shaderList), scroll(resourceList));
        VBox.setVgrow(body, Priority.ALWAYS);
        Button open = button("打开文件夹");
        follow(tabs.selectedToggleProperty(), () -> {
            boolean sh = tabs.getSelectedToggle() != resources;
            body.getChildren().get(0).setVisible(sh);
            body.getChildren().get(1).setVisible(!sh);
        });
        tabs.selectToggle(shaders);
        open.setOnAction(e -> s.openFolder(tabs.getSelectedToggle() == resources ? SaoData.Folder.RESOURCES : SaoData.Folder.SHADERS));
        HBox head = new HBox(6, shaders, resources);
        SaoFloatWindow win = new SaoFloatWindow("光影与资源包", W, H, close).add(head, body, footer(open, status));
        win.body.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        win.body.setOnDragDropped(e -> {
            List<Path> files = new ArrayList<>();
            for (File f : e.getDragboard().getFiles()) if (f.getName().toLowerCase().endsWith(".zip")) files.add(f.toPath());
            if (!files.isEmpty()) report(s.addPacks(files, tabs.getSelectedToggle() != resources), status, "已添加", null);
            e.setDropCompleted(!files.isEmpty());
            e.consume();
        });
        return win;
    }

    // ------------------------------------------------------------------ screenshots

    /// Screenshots: a grid of the game's pictures; one opens with a click.
    static SaoFloatWindow screenshots(SaoLauncherService s, Runnable close) {
        TilePane grid = new TilePane(8, 8);
        grid.setPrefColumns(4);
        Runnable fill = () -> {
            grid.getChildren().clear();
            for (Path p : s.screenshots()) {
                ImageView v = new ImageView(new Image(p.toUri().toString(), 156, 88, false, true, true));
                v.setFitWidth(156);
                v.setFitHeight(88);
                Rectangle round = new Rectangle(156, 88);
                round.setArcWidth(14);
                round.setArcHeight(14);
                v.setClip(round);
                StackPane cell = new StackPane(v);
                cell.getStyleClass().add("vr-shot");
                cell.setOnMouseClicked(e -> s.openFile(p));
                grid.getChildren().add(cell);
            }
        };
        s.screenshots().addListener((ListChangeListener<Path>) c -> fill.run());
        fill.run();
        Node content = s.screenshots().isEmpty() ? empty(SVG.SCREENSHOT_MONITOR, "游戏里按 F2 截图") : scroll(grid);
        Button open = button("打开截图文件夹");
        open.setOnAction(e -> s.openFolder(SaoData.Folder.SCREENSHOTS));
        return new SaoFloatWindow("截图 · " + s.screenshots().size(), W, H, close).add(content, footer(open));
    }

    // ------------------------------------------------------------------ logs

    /// The graphics-driver crash's own fixes: 关闭光影, 用独显 (Windows with a discrete card) and the maker's driver page.
    private static HBox graphicsFixes(SaoLauncherService s, Text said) {
        Button noShaders = SaoUiKit.accent("关闭光影");
        Button discrete = button("用独显");
        Button driver = button("下载显卡驱动");
        java.util.function.Consumer<String> ok = m -> { said.getStyleClass().setAll("vr-ink"); said.setText(m); };
        java.util.function.Consumer<Throwable> bad = err -> { said.getStyleClass().setAll("vr-warn"); said.setText("! " + rootMessage(err)); };
        noShaders.setOnAction(e -> {
            noShaders.setDisable(true);
            s.setShader(null).whenComplete((v, err) -> javafx.application.Platform.runLater(() -> {
                noShaders.setDisable(false);
                if (err == null) ok.accept("光影已关，按 Link Start 再进一次");
                else bad.accept(err);
            }));
        });
        discrete.setVisible(s.canPreferDiscreteGpu());
        discrete.setManaged(discrete.isVisible());
        discrete.setOnAction(e -> {
            discrete.setDisable(true);
            s.preferDiscreteGpu().whenComplete((v, err) -> javafx.application.Platform.runLater(() -> {
                discrete.setDisable(false);
                if (err == null) ok.accept("已让游戏用独立显卡，按 Link Start 再进一次");
                else bad.accept(err);
            }));
        });
        driver.setVisible(false);
        driver.setManaged(false);
        s.graphicsVendor().thenAccept(vendor -> javafx.application.Platform.runLater(() -> {
            @Nullable String url = vendor == null ? null : switch (vendor) {
                case "NVIDIA" -> "https://www.nvidia.cn/drivers/";
                case "AMD" -> "https://www.amd.com/zh-cn/support/download/drivers.html";
                case "Intel" -> "https://www.intel.cn/content/www/cn/zh/support/detect.html";
                default -> null;
            };
            if (url == null) return;
            driver.setText("下载 " + vendor + " 驱动");
            driver.setOnAction(e -> SaoUi.linkOpener.accept(url));
            driver.setVisible(true);
            driver.setManaged(true);
        }));
        HBox row = new HBox(6, noShaders, discrete, driver);
        row.setPadding(new Insets(4, 0, 2, 0));
        return row;
    }

    /// A failure's own message, without the completion wrappers.
    private static String rootMessage(Throwable err) {
        Throwable t = err;
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? "没成功" : t.getMessage();
    }

    /// Logs: HMCL's crash analysis on top when the last run crashed, then the game's log.
    static SaoFloatWindow logs(SaoLauncherService s, Runnable close) {
        VBox crashBox = new VBox();
        follow(s.lastCrash(), () -> {
            @Nullable SaoData.Crash c = s.lastCrash().get();
            crashBox.getChildren().clear();
            if (c == null) return;
            Text why = ink(c.reason(), 14);
            why.getStyleClass().setAll("vr-warn");
            why.setWrappingWidth(380);
            Text detail = dim(c.detail(), 11.5);
            detail.setWrappingWidth(380);
            Button report = button("查看报告");
            report.setOnAction(e -> s.openFile(c.report()));
            // 一键反馈: logs and the crash report in one package for the QQ group, through the engine's
            // buildFeedbackZip() once it is there (SaoDialogs.feedback is the hook); hidden until then.
            Button feedback = SaoUiKit.accent("一键反馈");
            Text packed = dim("", 11.5);
            packed.setWrappingWidth(380);
            feedback.setOnAction(e -> {
                feedback.setDisable(true);
                packed.getStyleClass().setAll("vr-dim");
                packed.setText("打包中");
                SaoDialogs.feedback(s, err -> {
                    feedback.setDisable(false);
                    if (err == null) packed.setText("已打包");
                    else {
                        packed.getStyleClass().setAll("vr-warn");
                        packed.setText("! 打包失败 · " + err);
                    }
                });
            });
            VBox text = new VBox(3, key("崩溃原因"), why);
            // James, 14 Nov: most players can't update a driver or pick a GPU themselves, so the card does it.
            if (c.graphics()) text.getChildren().add(graphicsFixes(s, packed));
            text.getChildren().addAll(detail, packed);
            HBox h = tile(icon(SVG.WARNING, 26), text, grow(), feedback, report);
            h.getStyleClass().add("vr-tile-warn");
            crashBox.getChildren().add(h);
        });
        VBox lines = new VBox(1);
        Runnable fill = () -> {
            lines.getChildren().clear();
            ObservableList<String> log = s.gameLog();
            for (int i = Math.max(0, log.size() - 400); i < log.size(); i++) {
                String l = log.get(i);
                Text t = new Text(l);
                t.setFont(Font.font("Menlo", 11));
                t.getStyleClass().add(l.contains("/FATAL]") || l.contains("/ERROR]") ? "vr-log-error" : l.contains("/WARN]") ? "vr-log-warn" : "vr-log");
                lines.getChildren().add(t);
            }
        };
        s.gameLog().addListener((ListChangeListener<String>) c -> fill.run());
        fill.run();
        Node logView = s.gameLog().isEmpty() ? empty(SVG.SCRIPT, "游戏运行后这里会显示日志") : scroll(lines);
        logView.getStyleClass().add("vr-console");
        Button export = button("导出日志");
        export.setOnAction(e -> {
            FileChooser c = new FileChooser();
            c.setTitle("导出日志");
            c.setInitialFileName("sao-logs.zip");
            @Nullable File f = c.showSaveDialog(Controllers.getStage());
            if (f != null) s.exportLogs(f.toPath());
        });
        Button open = button("打开日志文件夹");
        open.setOnAction(e -> s.openFolder(SaoData.Folder.LOGS));
        return new SaoFloatWindow("日志", W, H, close).add(crashBox, logView,
                footer(export, open));
    }

    // ------------------------------------------------------------------ Java and download source

    /// Java and where downloads come from.
    static SaoFloatWindow java(SaoLauncherService s, Runnable close) {
        Text status = dim("", 12);
        ToggleGroup pick = new ToggleGroup();
        VBox list = new VBox(6);
        Runnable[] refill = new Runnable[1];
        Runnable fill = () -> {
            List<Node> rows = new ArrayList<>();
            ToggleButton auto = chip("自动", pick);
            auto.setSelected(s.java() == null);
            auto.setOnAction(e -> report(s.setJava(null), status, "", refill[0]));
            rows.add(tile(icon(SVG.LOCAL_CAFE, 24), lines("自动选择", "使用适合整合包的 Java 17"), grow(), auto));
            for (SaoData.JavaRuntime j : s.javas()) {
                ToggleButton use = chip("使用", pick);
                use.setSelected(j.executable().equals(s.java()));
                use.setOnAction(e -> report(s.setJava(j.executable()), status, "", refill[0]));
                rows.add(tile(icon(SVG.LOCAL_CAFE, 24), lines("Java " + j.version() + (j.managed() ? " · 启动器下载" : ""),
                        (j.vendor().isEmpty() ? "" : j.vendor() + " · ") + j.executable()), grow(), use));
            }
            SaoMotion.list(list, rows);
        };
        refill[0] = fill;
        s.javas().addListener((ListChangeListener<SaoData.JavaRuntime>) c -> fill.run());
        fill.run();
        Button download = button("下载 Java 17");
        download.setOnAction(e -> report(s.downloadJava(), status, "下载完成", null));
        VBox left = column(390, key("Java"), scroll(list), footer(download, status));

        ToggleGroup src = new ToggleGroup();
        ToggleButton auto = chip("自动", src);
        ToggleButton bmcl = chip("BMCLAPI", src);
        ToggleButton official = chip("官方", src);
        auto.setUserData(SaoData.Source.AUTO);
        bmcl.setUserData(SaoData.Source.BMCLAPI);
        official.setUserData(SaoData.Source.OFFICIAL);
        src.selectToggle(switch (s.downloadSource()) {
            case AUTO -> auto;
            case BMCLAPI -> bmcl;
            case OFFICIAL -> official;
        });
        keepOne(src);
        src.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) s.setDownloadSource((SaoData.Source) b.getUserData());
        });
        Slider threads = new Slider(1, 256, s.downloadThreads());
        threads.getStyleClass().add("vr-slider");
        // 黄, 15 Nov ("为什么只有一个条可以拉，不能直接改数字"): the number is a field too; Enter or leaving it applies,
        // anything outside 1-256 snaps back to the slider's value.
        TextField tv = field("");
        tv.setText(Integer.toString(s.downloadThreads()));
        tv.setPrefColumnCount(3);
        tv.setMaxWidth(64);
        threads.valueProperty().addListener((o, a, b) -> {
            if (!tv.isFocused()) tv.setText(Integer.toString(b.intValue()));
            if (b.intValue() != s.downloadThreads()) s.setDownloadThreads(b.intValue());
        });
        Runnable typed = () -> {
            int n;
            try { n = Integer.parseInt(tv.getText().strip()); } catch (NumberFormatException bad) { n = -1; }
            if (n >= 1 && n <= 256) threads.setValue(n);
            tv.setText(Integer.toString((int) threads.getValue()));
        };
        tv.setOnAction(e -> typed.run());
        tv.focusedProperty().addListener((o, was, now) -> { if (!now) typed.run(); });
        VBox right = column(246, key("下载源"), new HBox(6, auto, bmcl, official),
                rule(), row(key("同时下载"), tv), threads);
        return new SaoFloatWindow("Java 与下载源", W, H, close).add(columns(left, right));
    }

    // ------------------------------------------------------------------ downloads

    /// Downloads: what the launcher is fetching now.
    static SaoFloatWindow downloads(SaoLauncherService s, Runnable close) {
        VBox list = live(s.tasks(), t -> {
            Text pct = sao(t.done() ? "OK" : t.progress() < 0 ? "..." : Math.round(t.progress() * 100) + "%", 13, true);
            String speed = t.done() ? "完成" : t.bytesPerSecond() > 0 ? size(t.bytesPerSecond()) + "/s" : "等待中";
            VBox mid = new VBox(5, row(ink(t.name(), 13.5), dim(speed, 11.5)), bar(560, t.done() ? 1 : t.progress()));
            HBox.setHgrow(mid, Priority.ALWAYS);
            return tile(icon(t.done() ? SVG.CHECK_CIRCLE : SVG.DOWNLOAD, 22), mid, pct);
        }, empty(SVG.DOWNLOAD, "现在没有下载任务"));
        // The backend's measured install snapshot on top: stage, verified counts, bytes, real speed, every failure.
        VBox summary = new VBox(6);
        Runnable sum = () -> {
            org.jackhuang.hmcl.sao.SaoInstallProgress ip = s.installProgress().get();
            summary.getChildren().clear();
            if (ip.stage() == org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE) return;
            boolean failed = ip.stage() == org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.FAILED;
            // 整合包已就绪 carries no counts; a finished install is a full bar, not the 30 % unknown placeholder.
            boolean complete = ip.stage() == org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.COMPLETE;
            double amount = complete ? 1 : ip.totalBytes() > 0 ? (double) ip.bytesDone() / ip.totalBytes()
                    : ip.totalFiles() > 0 ? (double) ip.completedFiles() / ip.totalFiles() : -1;
            List<String> facts = new ArrayList<>();
            if (ip.totalBytes() > 0) facts.add(size(ip.bytesDone()) + " / " + size(ip.totalBytes()));
            else if (ip.bytesDone() > 0) facts.add(size(ip.bytesDone()));
            if (ip.bytesPerSecond() > 0) facts.add(size((long) ip.bytesPerSecond()) + "/s");
            if (!ip.failures().isEmpty()) facts.add("失败 " + ip.failures().size());
            Text label = ink(ip.label().isBlank() ? (failed ? "失败" : "进行中") : ip.label(), 14);
            if (failed) label.getStyleClass().setAll("vr-warn");
            VBox mid = new VBox(5, row(label, dim(String.join(" · ", facts), 11.5)), bar(560, amount));
            HBox.setHgrow(mid, Priority.ALWAYS);
            HBox top = tile(icon(failed ? SVG.WARNING : complete ? SVG.CHECK_CIRCLE : SVG.DOWNLOAD, 22), mid,
                    sao(amount >= 0 ? Math.round(amount * 100) + "%" : "...", 13, true));
            if (failed) top.getStyleClass().add("vr-tile-warn");
            summary.getChildren().add(top);
            for (org.jackhuang.hmcl.sao.SaoInstallProgress.Failure f : ip.failures()) {
                List<String> hosts = new ArrayList<>();
                for (org.jackhuang.hmcl.sao.SaoInstallProgress.Attempt a : f.attempts())
                    hosts.add(a.host() + (a.status() > 0 ? " " + a.status() : " 无响应"));
                Text why = dim(f.reason() + (hosts.isEmpty() ? "" : " · " + String.join(" · ", hosts)), 11.5);
                why.setWrappingWidth(560);
                summary.getChildren().add(tile(icon(SVG.ERROR, 18), new VBox(3, ink(f.path(), 12.5), why)));
            }
        };
        s.installProgress().addListener((o, a, b) -> sum.run());
        sum.run();
        return new SaoFloatWindow("下载", W, H, close).add(scroll(new VBox(6, summary, list)));
    }

    // ------------------------------------------------------------------ about

    /// The two link buttons under About's title (James, 9 Nov: "内置一下 爱发电 还有那个夸克链接 ... 两个button").
    ///
    /// @return the row
    static javafx.scene.layout.FlowPane aboutLinks(SaoLauncherService s) {
        // Wraps inside the left column (three buttons do not fit on one line there).
        javafx.scene.layout.FlowPane row = new javafx.scene.layout.FlowPane(10, 8);
        row.setAlignment(Pos.CENTER);
        row.setPrefWrapLength(262);
        row.setMaxWidth(270);
        row.setPadding(new Insets(10, 0, 0, 0));
        // links(): the backend's last-good community actions (爱发电, Quark from the release metadata, QQ群). A link
        // opens the browser; QQ群 has no link, only its number to copy.
        Runnable fill = () -> {
            row.getChildren().clear();
            for (SaoData.CommunityLink l : s.links()) {
                Button b = button(l.copy() != null ? l.label() + " " + l.copy() : l.label());
                if (l.url() != null) {
                    if ("quark".equals(l.id())) b.setGraphic(icon(SVG.DOWNLOAD, 15));
                    String url = l.url();
                    b.setOnAction(e -> SaoUi.linkOpener.accept(url));
                } else {
                    String number = java.util.Objects.requireNonNull(l.copy());
                    b.setGraphic(icon(SVG.CONTENT_COPY, 15));
                    String label = b.getText();
                    b.setOnAction(e -> {
                        ClipboardContent c = new ClipboardContent();
                        c.putString(number);
                        Clipboard.getSystemClipboard().setContent(c);
                        b.setText("已复制");
                        javafx.animation.PauseTransition back = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                        back.setOnFinished(x -> b.setText(label));
                        back.play();
                    });
                }
                row.getChildren().add(b);
            }
            if (row.getChildren().isEmpty()) row.getChildren().setAll(aboutLinks().getChildren());
        };
        s.links().addListener((ListChangeListener<SaoData.CommunityLink>) c -> fill.run());
        fill.run();
        s.refreshLinks();
        return row;
    }

    /// The fixed buttons, for an engine that has no links().
    static HBox aboutLinks() {
        Button afdian = button("爱发电");
        afdian.setOnAction(e -> SaoUi.linkOpener.accept(SaoUi.AFDIAN_URL));
        Button quark = button("夸克网盘");
        quark.setGraphic(icon(SVG.DOWNLOAD, 15));
        quark.setOnAction(e -> SaoUi.linkOpener.accept(SaoUi.QUARK_URL));
        HBox row = new HBox(10, afdian, quark);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(10, 0, 0, 0));
        return row;
    }

    /// About: the launcher, HMCL and the licences.
    static SaoFloatWindow about(SaoLauncherService s, Runnable close) {
        // logo-49 (James, 9 Nov) is near 4:3, so a height bound keeps it a title over the text (old wide one: 250 wide).
        ImageView logo = SaoAssets.logo(240, 170);
        VBox left = column(270, logo,
                ink(SaoUi.LAUNCHER_NAME, 15),
                dim("版本 " + Metadata.VERSION + " · " + (s.installedVersion().get() != null
                        ? "整合包 v" + s.installedVersion().get() : "整合包未安装"), 12),
                dim("刀剑神域·誓约 是免费的非商业整合包。", 12),
                aboutLinks(s));
        left.setAlignment(Pos.TOP_CENTER);
        Text hmcl = dim("本启动器基于 Hello Minecraft! Launcher (HMCL) 3.17，"
                + "Copyright © huangyuhui 及贡献者，按 GNU GPL v3 开源，源代码公开。", 12);
        hmcl.setWrappingWidth(360);
        VBox right = column(370,
                key("基于"), hmcl,
                rule(),
                key("开源许可"),
                lines("HMCL", "GPL v3"),
                lines("JOrbis（音乐解码）", "LGPL 2.0"),
                lines("SAO UI / SAO Welcome 字体", "免费字体，条款见字体说明"),
                lines("Terracotta 联机", "随 HMCL 提供"));
        return new SaoFloatWindow("关于", W, H, close).add(columns(left, right));
    }
}
