/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.sao.SaoServices;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Objects;

/// The launcher's one screen: the pack's wallpapers crossfading under a still dim layer, the OP bubbles,
/// the 刀剑神域·誓约 logo, the blue log-in panel with Link Start, the title music with its lyrics,
/// and small round buttons for music, skin and the window.
@NotNullByDefault
public final class SaoMainPage extends StackPane {
    /// The engine the screen drives.
    private final SaoLauncherService service;
    /// The title music.
    private final SaoMusic music = new SaoMusic();
    /// The log-in panel.
    private final SaoLoginPanel panel;
    /// The lyrics line.
    private final SaoLyricsView lyrics = new SaoLyricsView(SaoLyrics.parse(SaoAssets.lyrics()));
    /// The music button, dimmed while the music is off.
    private final Button musicButton;
    /// Whether the player turned the music off.
    private boolean musicOff;
    /// The design size the interface is laid out at, then scaled to the window.
    private static final double DESIGN_W = 1100, DESIGN_H = 660;
    /// The interface layer (everything above the wallpaper, dim and bubbles).
    private final BorderPane layout;
    /// The interface layer's scale.
    private final javafx.scene.transform.Scale zoom = new javafx.scene.transform.Scale(1, 1, 0, 0);
    /// A stand-in clock for the lyrics (screenshot tools), or null to follow the music.
    private java.util.function.@Nullable DoubleSupplier clock;
    /// Whether the top bar has been made the window's drag area.
    private boolean dragReady;

    /// Builds the screen with the installed engine, or a stand-in when none is installed yet.
    public SaoMainPage() {
        this(serviceOrFake());
    }

    /// Builds the screen for a given engine.
    ///
    /// @param service the engine
    public SaoMainPage(SaoLauncherService service) {
        this.service = service;
        getStyleClass().add("sao-root");
        getStylesheets().add(Objects.requireNonNull(SaoMainPage.class.getResource("/assets/css/sao.css")).toExternalForm());

        SaoWallpaper wallpaper = new SaoWallpaper((int) (System.currentTimeMillis() / 86_400_000L));
        // The dark dim layer is still, always (James 1 Nov: it never animates).
        Region dim = new Region();
        dim.getStyleClass().add("sao-dim");
        dim.setMouseTransparent(true);
        SaoBubbles bubbles = new SaoBubbles();

        @Nullable Image logoImage = SaoAssets.image("logo.png");
        ImageView logo = new ImageView(logoImage);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        // The logo shrinks first when the window is short, so the panel and Link Start always fit.
        logo.fitWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(260, Math.min(560, Math.min(getWidth() * 0.42, (getHeight() - 400) * 2.1))),
                widthProperty(), heightProperty()));
        logo.setMouseTransparent(true);

        panel = new SaoLoginPanel(service);
        VBox middle = new VBox(6, logo, panel);
        middle.setAlignment(Pos.TOP_CENTER);
        middle.setPadding(new Insets(4, 0, 0, 0));

        musicButton = icon(SaoIcons.music(), "音乐 · Music");
        musicButton.setOnAction(e -> toggleMusic());
        Button skinButton = icon(SaoIcons.skin(), "皮肤 · Skin");
        skinButton.setOnAction(e -> pickSkin());
        Button minimize = icon(SaoIcons.minimize(), "最小化");
        minimize.setOnAction(e -> Controllers.getDecorator().minimizeWindow());
        Button close = icon(SaoIcons.close(), "关闭");
        close.setOnAction(e -> Controllers.getDecorator().closeWindow());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region gap = new Region();
        gap.setMinWidth(14);
        HBox top = new HBox(10, spacer, musicButton, skinButton, gap, minimize, close);
        top.setPadding(new Insets(14, 16, 0, 16));
        top.setAlignment(Pos.CENTER_RIGHT);

        Text version = new Text("刀剑神域·誓约 启动器 " + Metadata.VERSION + "  ·  基于 HMCL");
        version.getStyleClass().add("sao-version");
        version.setFont(SaoAssets.sao(11, false));
        Region fill = new Region();
        HBox.setHgrow(fill, Priority.ALWAYS);
        HBox bottom = new HBox(lyrics, fill, version);
        bottom.setAlignment(Pos.BOTTOM_LEFT);
        bottom.setPadding(new Insets(0, 18, 16, 28));

        layout = new BorderPane(middle, top, null, bottom, null);
        layout.setPickOnBounds(false);
        layout.setManaged(false);
        layout.getTransforms().add(zoom);
        getChildren().setAll(wallpaper, dim, bubbles, layout);

        // Music: on while the launcher is shown, fading out while the game runs.
        service.state().addListener((o, a, b) -> {
            if (b == SaoPackState.RUNNING) music.stop();
            else if (a == SaoPackState.RUNNING && !musicOff) music.start();
        });
        sceneProperty().addListener((o, a, b) -> {
            if (b == null) return;
            // The decorator exists once the page is in its scene: the top bar drags the window.
            if (!dragReady) {
                try {
                    Controllers.getDecorator().capableDraggingWindow(top);
                    for (Node n : top.getChildren()) Controllers.getDecorator().forbidDraggingWindow(n);
                    dragReady = true;
                } catch (NullPointerException noWindow) {
                    // No launcher window (screenshot tools): nothing to drag.
                }
            }
            if (!musicOff && service.state().get() != SaoPackState.RUNNING) music.start();
        });
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                lyrics.update(clock != null ? clock.getAsDouble() : music.position());
            }
        }.start();
    }

    /// Lays the backgrounds out at full size and the interface at its design size scaled to fit,
    /// so a bigger window shows a bigger panel instead of a small one lost in the middle.
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth(), h = getHeight();
        double k = Math.max(0.9, Math.min(2.0, Math.min(w / DESIGN_W, h / DESIGN_H)));
        zoom.setX(k);
        zoom.setY(k);
        layout.resizeRelocate(0, 0, w / k, h / k);
    }

    /// The installed engine, or a stand-in installed now.
    ///
    /// @return the engine
    private static SaoLauncherService serviceOrFake() {
        try {
            return SaoLauncherService.get();
        } catch (IllegalStateException e) {
            SaoFakeService fake = new SaoFakeService();
            SaoServices.install(fake);
            return fake;
        }
    }

    /// The music, for tools that need its clock.
    ///
    /// @return the player
    SaoMusic music() {
        return music;
    }

    /// Drives the lyrics from a stand-in clock instead of the music (screenshot tools).
    ///
    /// @param c the clock in seconds, or null to follow the music again
    void lyricsClock(java.util.function.@Nullable DoubleSupplier c) {
        clock = c;
    }

    /// Turns the music off or back on.
    private void toggleMusic() {
        musicOff = !musicOff;
        if (musicOff) music.stop();
        else music.start();
        musicButton.getStyleClass().remove("sao-icon-off");
        if (musicOff) musicButton.getStyleClass().add("sao-icon-off");
    }

    /// Lets the player pick a skin file for the game to send on join.
    private void pickSkin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择皮肤 (64×64 PNG)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        @Nullable File f = chooser.showOpenDialog(Controllers.getStage());
        if (f == null) return;
        try {
            service.setSkin(f.toPath(), service.skinSlim());
        } catch (IllegalArgumentException ex) {
            panel.showError("皮肤需为 64×64 PNG");
        }
    }

    /// A round white icon button.
    ///
    /// @param graphic the icon
    /// @param tip     the tooltip text
    /// @return the button
    private static Button icon(Node graphic, String tip) {
        Button b = new Button();
        b.setGraphic(graphic);
        b.getStyleClass().add("sao-icon");
        b.setFocusTraversable(false);
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        return b;
    }
}
