/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.sao.SaoServices;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/// The launcher as the view from inside the NerveGear (James 6 Nov: "do it as if you are in the NerveGear",
/// "inside the visor", "just do the helmet view"): the measured S01E01 visor (glass, top recess, ear-pad arc,
/// clock, battery) with the choices projected on the glass as the pack's floating holo plates. The HUD sways a
/// little with the mouse like a head turning; Link Start powers the display down and starts the game at once,
/// whose own Link Start covers the loading.
@NotNullByDefault
public final class SaoHelmetPage extends StackPane {
    /// Episode picture size the visor and the HUD are laid out in.
    private static final double EW = 1280, EH = 720;
    /// Minecraft's rule for player names.
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /// The engine.
    private final SaoLauncherService service;
    /// The visor picture.
    private final ImageView visor = new ImageView();
    /// The visor without its display (clock and battery off), for power-on and power-down.
    private @Nullable Image visorDark;
    /// The visor with its display on.
    private @Nullable Image visorLit;
    /// Size the visor was last rendered at.
    private int renderedW, renderedH;
    /// Minute the clock was last rendered for.
    private long renderedMinute = -1;
    /// The HUD in episode coordinates.
    private final Pane hud = new Pane();
    /// The HUD's cover-fit scale.
    private final Scale fit = new Scale(1, 1, 0, 0);
    /// The HUD's cover-fit offset plus the head sway.
    private final Translate shift = new Translate();
    /// The soft glint drifting over the glass.
    private final Region glint = new Region();
    /// Black over everything for power-down.
    private final Region black = new Region();
    /// The helmet hum.
    private final SaoMusic hum = new SaoMusic("helmet_intro.ogg", "helmet_loop.ogg", 0.55);
    /// Whether the player turned the sound off.
    private boolean muted;
    /// Mouse position in parts of the window, -0.5 to 0.5.
    private double headX, headY;

    /// Offline or Microsoft.
    private final ToggleGroup kind = new ToggleGroup();
    /// The offline chip.
    private final ToggleButton offline = chip("离线 Offline");
    /// The Microsoft chip.
    private final ToggleButton microsoft = chip("正版 Verified");
    /// The player name.
    private final TextField name = new TextField();
    /// The signed-in Microsoft name.
    private final TextField msName = new TextField();
    /// Opens the Microsoft sign-in.
    private final Button msSignIn = plate("登录 Microsoft");
    /// The sao_auth password.
    private final PasswordField password = new PasswordField();
    /// The character face.
    private final SaoCharacterCard face = new SaoCharacterCard();
    /// The character's name under the face.
    private final Text faceName = new Text();
    /// Classic arms.
    private final ToggleButton classic = chip("Classic");
    /// Slim arms.
    private final ToggleButton slim = chip("Slim");
    /// The arm model choice.
    private final ToggleGroup arms = new ToggleGroup();
    /// Link Start.
    private final Button start = new Button("Link Start");
    /// The status line.
    private final Text status = new Text();
    /// The problem line.
    private final Text problem = new Text();
    /// The progress fill.
    private final Region fill = new Region();
    /// The sound plate.
    private final Button sound = plate("♪");

    /// Builds the page with the installed engine, or a stand-in when none is installed yet.
    public SaoHelmetPage() {
        this(serviceOrFake());
    }

    /// Builds the page for an engine.
    ///
    /// @param service the engine
    public SaoHelmetPage(SaoLauncherService service) {
        this.service = service;
        getStylesheets().add(Objects.requireNonNull(SaoHelmetPage.class.getResource("/assets/css/sao.css")).toExternalForm());
        setStyle("-fx-background-color: #252525;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        visor.setManaged(false);
        visor.setSmooth(false);
        glint.setManaged(false);
        glint.setMouseTransparent(true);
        glint.setBlendMode(BlendMode.ADD);
        black.setStyle("-fx-background-color: black;");
        black.setMouseTransparent(true);
        black.setOpacity(1);

        hud.setManaged(false);
        hud.getTransforms().addAll(shift, fit);
        hud.setPrefSize(EW, EH);
        hud.resize(EW, EH);
        hud.setPickOnBounds(false);
        buildHud();

        getChildren().setAll(visor, glint, hud, black);

        setOnMouseMoved(e -> {
            headX = e.getX() / Math.max(1, getWidth()) - 0.5;
            headY = e.getY() / Math.max(1, getHeight()) - 0.5;
        });
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                frame(now / 1e9);
            }
        }.start();

        service.state().addListener((o, a, b) -> onState(a, b));
        service.progress().addListener((o, a, b) -> refreshState());
        service.stepLabel().addListener((o, a, b) -> refreshState());
        service.lastError().addListener((o, a, b) -> refreshState());
        refreshState();

        sceneProperty().addListener((o, a, b) -> {
            if (b == null) return;
            hookWindow();
            powerOn();
        });
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

    // ------------------------------------------------------------------ layout

    /// Builds every HUD element at its place in the episode picture: the logo under the recess, the panel in the
    /// middle of the glass, Link Start above the ear pad, the status at the bottom left and the sound plate at the
    /// bottom right, on the pad, where the pack's helmet screen keeps its Skip and gear.
    private void buildHud() {
        @Nullable Image logoImg = SaoAssets.image("logo.png");
        ImageView logo = new ImageView(logoImg);
        logo.setPreserveRatio(true);
        logo.setFitWidth(300);
        logo.relocate(640 - 150, 70);
        logo.setOpacity(0.92);

        // Left column: log in.
        offline.setToggleGroup(kind);
        microsoft.setToggleGroup(kind);
        for (TextField f : new TextField[]{name, msName, password}) {
            f.getStyleClass().add("holo-field");
            f.setFont(SaoAssets.sao(16, false));
            f.setPrefWidth(268);
        }
        name.setPromptText("Player name");
        msName.setEditable(false);
        msName.setFocusTraversable(false);
        msSignIn.setOnAction(e -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT)));
        password.setPromptText("sao_auth");
        StackPane accountField = new StackPane(name, msName, msSignIn);
        accountField.setAlignment(Pos.CENTER_LEFT);
        problem.getStyleClass().add("holo-warn");
        problem.setFont(Font.font(null, FontWeight.BOLD, 13));
        VBox login = new VBox(5,
                heading("LOGIN", "登录"),
                new HBox(8, offline, microsoft),
                gap(4), key(":account"), accountField,
                gap(4), key(":password"), password,
                gap(2), problem);
        login.setPrefWidth(290);

        // Right column: the character.
        classic.setToggleGroup(arms);
        slim.setToggleGroup(arms);
        faceName.getStyleClass().add("holo-ink");
        faceName.setFont(SaoAssets.sao(20, false));
        Button change = plate("更换皮肤 Skin");
        change.setOnAction(e -> pickSkin());
        VBox character = new VBox(8, heading("CHARACTER", "角色"), face, faceName, new HBox(8, classic, slim), change);
        character.setAlignment(Pos.TOP_CENTER);
        character.setPrefWidth(220);

        Region divider = new Region();
        divider.setStyle("-fx-background-color: rgba(157,187,200,0.45);");
        divider.setPrefWidth(1);
        divider.setMinWidth(1);
        HBox panel = new HBox(24, login, divider, character);
        panel.getStyleClass().add("holo-plate");
        panel.setPadding(new Insets(16, 22, 16, 22));
        panel.relocate(640 - 300, 222);
        panel.setPrefWidth(600);
        panel.widthProperty().addListener((o, a, b) -> panel.relocate(640 - b.doubleValue() / 2, 222));

        start.getStyleClass().add("holo-start");
        start.setFont(SaoAssets.welcome(28));
        start.setOnAction(e -> linkStart());
        start.setDefaultButton(true);
        start.widthProperty().addListener((o, a, b) -> start.relocate(640 - b.doubleValue() / 2, 476));
        start.relocate(640 - 110, 476);

        // Bottom left, on the pad: what the pack is doing, with a thin bar.
        status.getStyleClass().add("holo-ink");
        status.setFont(SaoAssets.sao(13, false));
        Region track = new Region();
        track.getStyleClass().add("holo-track");
        track.setPrefSize(260, 2);
        track.setMaxSize(260, 2);
        fill.getStyleClass().add("holo-fill");
        fill.setPrefHeight(2);
        fill.setMaxHeight(2);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane bar = new StackPane(track, fill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(260);
        Text version = new Text("刀剑神域·誓约 启动器 " + Metadata.VERSION + " · 基于 HMCL");
        version.getStyleClass().add("holo-dim");
        version.setFont(SaoAssets.sao(10, false));
        VBox corner = new VBox(5, status, bar, version);
        corner.relocate(64, 640);

        sound.setOnAction(e -> toggleSound());
        Button minimize = plate("—");
        minimize.setOnAction(e -> Controllers.getDecorator().minimizeWindow());
        Button close = plate("✕");
        close.setOnAction(e -> Controllers.getDecorator().closeWindow());
        HBox windowRow = new HBox(6, sound, minimize, close);
        windowRow.widthProperty().addListener((o, a, b) -> windowRow.relocate(EW - 64 - b.doubleValue(), 662));
        windowRow.relocate(1100, 662);

        fill.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            SaoPackState s = service.state().get();
            if (s == SaoPackState.WORKING) return 260 * Math.max(0.03, service.progress().get());
            return s == SaoPackState.READY || s == SaoPackState.RUNNING ? 260.0 : 0.0;
        }, service.state(), service.progress()));

        hud.getChildren().setAll(logo, panel, start, corner, windowRow);

        kind.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) kind.selectToggle(a);
            else refreshAccount();
        });
        arms.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                arms.selectToggle(a);
                return;
            }
            if (service.skin() != null) service.setSkin(service.skin(), b == slim);
        });
        name.textProperty().addListener((o, a, b) -> refreshFace());
        Accounts.getAccounts().addListener((javafx.collections.ListChangeListener<Account>) c -> refreshAccount());
        preselect();
    }

    /// A column heading: the SAO-face English word with the Chinese beside it.
    private static HBox heading(String en, String cn) {
        Text a = new Text(en);
        a.getStyleClass().add("holo-title");
        a.setFont(SaoAssets.sao(20, true));
        Text b = new Text(cn);
        b.getStyleClass().add("holo-label");
        b.setFont(Font.font(13));
        HBox h = new HBox(8, a, b);
        h.setAlignment(Pos.BASELINE_LEFT);
        return h;
    }

    /// A field key in the SAO face, as the episode's panels write them.
    private static Text key(String k) {
        Text t = new Text(k);
        t.getStyleClass().add("holo-label");
        t.setFont(SaoAssets.sao(15, false));
        return t;
    }

    /// A fixed vertical gap.
    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setPrefHeight(h);
        return r;
    }

    /// A holo chip.
    private static ToggleButton chip(String text) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("holo-button");
        b.setFont(SaoAssets.sao(13, false));
        b.setFocusTraversable(false);
        return b;
    }

    /// A holo plate button.
    private static Button plate(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("holo-button");
        b.setFont(SaoAssets.sao(13, false));
        b.setFocusTraversable(false);
        return b;
    }

    // ------------------------------------------------------------------ frame

    /// Keeps the visor rendered at the window's size and minute, fits the HUD, sways it with the head and moves
    /// the glint.
    ///
    /// @param t seconds on the clock
    private void frame(double t) {
        int w = (int) Math.ceil(getWidth()), h = (int) Math.ceil(getHeight());
        if (w <= 1 || h <= 1) return;
        long minute = System.currentTimeMillis() / 60000;
        if (w != renderedW || h != renderedH || minute != renderedMinute) {
            renderedW = w;
            renderedH = h;
            renderedMinute = minute;
            boolean lit = visor.getImage() == visorLit && visorLit != null;
            visorDark = SaoVisor.render(w, h, false).image();
            visorLit = SaoVisor.render(w, h, true).image();
            visor.setImage(lit || visor.getImage() == null && poweredOn ? visorLit : visorDark);
            visor.relocate(0, 0);
        }
        double s = Math.min(w / EW, h / EH);
        fit.setX(s);
        fit.setY(s);
        // The head: the display lags a touch behind the mouse and breathes very slightly.
        double sx = -headX * 14 + Math.sin(t * 0.6) * 1.2, sy = -headY * 9 + Math.sin(t * 0.43 + 1) * 0.9;
        swayX += (sx - swayX) * 0.08;
        swayY += (sy - swayY) * 0.08;
        shift.setX(w / 2.0 - EW / 2 * s + swayX * s);
        shift.setY(h / 2.0 - EH / 2 * s + swayY * s);
        black.resizeRelocate(0, 0, w, h);
        // A soft light crossing the glass every few seconds.
        double r = 420 * s, gx = (((t * 0.045) % 1.6) - 0.3) * w, gy = h * 0.42;
        glint.resizeRelocate(gx - r, gy - r, 2 * r, 2 * r);
        glint.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0.45, 0.75, 1, 0.07)), new Stop(1, Color.color(0.45, 0.75, 1, 0))), null, null)));
    }

    /// Smoothed head sway in episode pixels.
    private double swayX, swayY;
    /// Whether the display is on.
    private boolean poweredOn;

    // ------------------------------------------------------------------ power

    /// Turns the display on: black lifts off the glass, then the visor's clock and battery and the HUD flicker on.
    private void powerOn() {
        poweredOn = true;
        if (!muted && service.state().get() != SaoPackState.RUNNING) hum.start();
        hud.setOpacity(0);
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(black.opacityProperty(), black.getOpacity())),
                new KeyFrame(Duration.millis(520), new KeyValue(black.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(560), e -> {
                    visor.setImage(visorLit);
                    hud.setOpacity(0.85);
                }),
                new KeyFrame(Duration.millis(620), e -> {
                    visor.setImage(visorDark);
                    hud.setOpacity(0.15);
                }),
                new KeyFrame(Duration.millis(680), e -> {
                    visor.setImage(visorLit);
                    hud.setOpacity(1);
                }));
        t.play();
    }

    /// Powers the display down like the pack's helmet screen: a flicker, the HUD fades, the view sinks to black.
    ///
    /// @param then what to do once it is dark
    private void powerDown(Runnable then) {
        poweredOn = false;
        hum.stop();
        if (!muted) SaoMusic.once("helmet_outro.ogg", 0.55);
        Timeline t = new Timeline(
                new KeyFrame(Duration.millis(60), e -> visor.setImage(visorDark)),
                new KeyFrame(Duration.millis(120), e -> visor.setImage(visorLit)),
                new KeyFrame(Duration.millis(180), e -> visor.setImage(visorDark)),
                new KeyFrame(Duration.ZERO, new KeyValue(hud.opacityProperty(), hud.getOpacity()), new KeyValue(black.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(260), new KeyValue(hud.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(480), new KeyValue(black.opacityProperty(), 1)));
        t.setOnFinished(e -> then.run());
        t.play();
    }

    /// Follows the engine: dark while the game runs, the display back on when it ends.
    private void onState(@Nullable SaoPackState a, SaoPackState b) {
        refreshState();
        if (a == SaoPackState.RUNNING && b != SaoPackState.RUNNING) {
            PauseTransition p = new PauseTransition(Duration.millis(200));
            p.setOnFinished(e -> powerOn());
            p.play();
        }
    }

    // ------------------------------------------------------------------ choices

    /// Starts on the account the launcher used last.
    private void preselect() {
        @Nullable Account sel = Accounts.getSelectedAccount();
        if (sel instanceof MicrosoftAccount) {
            kind.selectToggle(microsoft);
        } else {
            kind.selectToggle(offline);
            if (sel instanceof OfflineAccount) name.setText(sel.getProfileName());
        }
        arms.selectToggle(service.skinSlim() ? slim : classic);
        face.show(service.skin());
        refreshAccount();
    }

    /// Shows the account field that fits the kind.
    private void refreshAccount() {
        boolean ms = kind.getSelectedToggle() == microsoft;
        @Nullable MicrosoftAccount m = microsoftAccount();
        name.setVisible(!ms);
        msName.setVisible(ms && m != null);
        msSignIn.setVisible(ms && m == null);
        if (m != null) msName.setText(m.getProfileName());
        problem.setText("");
        refreshFace();
    }

    /// Puts the name under the face.
    private void refreshFace() {
        boolean ms = kind.getSelectedToggle() == microsoft;
        @Nullable MicrosoftAccount m = microsoftAccount();
        String n = ms ? (m == null ? "" : m.getProfileName()) : name.getText().strip();
        faceName.setText(n.isEmpty() ? "———" : n);
        boolean hasSkin = service.skin() != null;
        classic.setDisable(!hasSkin);
        slim.setDisable(!hasSkin);
    }

    /// The Microsoft account to use.
    private static @Nullable MicrosoftAccount microsoftAccount() {
        if (Accounts.getSelectedAccount() instanceof MicrosoftAccount m) return m;
        for (Account a : Accounts.getAccounts()) if (a instanceof MicrosoftAccount m) return m;
        return null;
    }

    /// The status line and Link Start from the engine.
    private void refreshState() {
        SaoPackState s = service.state().get();
        String v = service.embeddedVersion();
        switch (s) {
            case CHECKING -> status.setText("检查中 · Checking");
            case NOT_INSTALLED -> status.setText("首次连接自动安装 · v" + v);
            case UPDATE_READY -> status.setText("自动更新至 v" + v);
            case WORKING -> {
                double p = service.progress().get();
                String l = service.stepLabel().get();
                status.setText((l.isEmpty() ? "准备中" : l) + (p >= 0 ? "  " + Math.round(p * 100) + "%" : ""));
            }
            case READY -> status.setText("就绪 · Ready · v" + v);
            case RUNNING -> status.setText("连接中 · Linked");
            case FAILED -> status.setText("失败 · Failed");
        }
        @Nullable String err = service.lastError().get();
        if (s == SaoPackState.FAILED && err != null) problem.setText(err);
        else if (!problem.getText().startsWith("!")) problem.setText("");
        start.setDisable(s == SaoPackState.CHECKING || s == SaoPackState.WORKING || s == SaoPackState.RUNNING);
    }

    /// Link Start: the account, then the display powers down and the game starts at once.
    private void linkStart() {
        @Nullable Account account;
        if (kind.getSelectedToggle() == microsoft) {
            account = microsoftAccount();
            if (account == null) {
                problem.setText("! 请先登录 Microsoft");
                return;
            }
        } else {
            String n = name.getText().strip();
            if (!NAME.matcher(n).matches()) {
                problem.setText("! 名称 3-16 位：字母、数字、下划线");
                return;
            }
            account = offlineAccount(n);
        }
        problem.setText("");
        Accounts.setSelectedAccount(account);
        char @Nullable [] pw = password.getText().isEmpty() ? null : password.getText().toCharArray();
        password.clear();
        Account chosen = account;
        if (service.state().get() == SaoPackState.READY) {
            // Normal start: the game starts while the display powers down; its own Link Start covers the loading.
            service.linkStart(chosen, pw);
            powerDown(() -> {
            });
        } else {
            // First start or an update: the launcher itself has to wait, so the display stays on with the progress.
            service.linkStart(chosen, pw);
        }
        if (pw != null) Arrays.fill(pw, '\0');
    }

    /// The offline account with this name, created when new.
    private static Account offlineAccount(String n) {
        for (Account a : Accounts.getAccounts())
            if (a instanceof OfflineAccount o && o.getProfileName().equals(n)) return o;
        OfflineAccount a = Accounts.FACTORY_OFFLINE.create(n, OfflineAccountFactory.getUUIDFromUserName(n));
        Accounts.getAccounts().add(a);
        return a;
    }

    /// Picks a skin file.
    private void pickSkin() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择皮肤 (64×64 PNG)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        @Nullable File f = chooser.showOpenDialog(Controllers.getStage());
        if (f == null) return;
        try {
            service.setSkin(f.toPath(), arms.getSelectedToggle() == slim);
            face.show(service.skin());
            refreshFace();
        } catch (IllegalArgumentException ex) {
            problem.setText("! 皮肤需为 64×64 PNG");
        }
    }

    /// Turns the helmet's sound off or on.
    private void toggleSound() {
        muted = !muted;
        if (muted) hum.stop();
        else if (poweredOn) hum.start();
        sound.setOpacity(muted ? 0.5 : 1);
    }

    /// Lets the window be dragged by the empty glass; the window's buttons live on the pad at the bottom right.
    private void hookWindow() {
        try {
            Controllers.getDecorator().capableDraggingWindow(this);
            for (Node n : hud.getChildren()) Controllers.getDecorator().forbidDraggingWindow(n);
        } catch (NullPointerException noWindow) {
            // Screenshot tools: no launcher window.
        }
    }

    /// Powers the display down at once (screenshot tools: the dark state).
    void forceDark() {
        poweredOn = false;
        visor.setImage(visorDark);
        hud.setOpacity(0);
        black.setOpacity(1);
    }

    /// Skips the power-on (screenshot tools).
    void forceLit() {
        poweredOn = true;
        visor.setImage(visorLit);
        hud.setOpacity(1);
        black.setOpacity(0);
    }
}
