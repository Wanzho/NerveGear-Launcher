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
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.sao.SaoServices;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniRunning;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniWavingArms;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// The launcher as the view from inside the NerveGear with windows floating in the space in front of the player
/// (James 6 Nov: "inside the visor", then "更像vr里窗口的样式"): the measured S01E01 visor glass behind, the helmet's
/// ear pad, recess, clock and battery in front, and between them glass windows standing in an arc around the
/// player at their own depths. Turning the head (the mouse) moves them with real parallax; the dock opens the
/// other windows in front, pushing the rest back. Link Start powers the display down and starts the game at
/// once; the game's own Link Start covers the loading.
@NotNullByDefault
public final class SaoHelmetPage extends StackPane {
    /// The design space the windows are laid out in (the episode picture at depth 0).
    private static final double EW = 1280, EH = 720;
    /// Half the camera's vertical field of view, radians.
    private static final double HALF_FOV = Math.toRadians(15);

    /// The engine.
    private final SaoLauncherService service;
    /// The glass behind the windows.
    private final ImageView glass = new ImageView();
    /// The helmet in front of the windows, display off.
    private @Nullable Image frameDark;
    /// The helmet in front of the windows, display on.
    private @Nullable Image frameLit;
    /// The helmet in front.
    private final ImageView frame = new ImageView();
    /// Size and minute the visor was last rendered at.
    private int renderedW, renderedH;
    private long renderedMinute = -1;

    /// The 3D space.
    private final Group space = new Group();
    /// Its view.
    private final SubScene world = new SubScene(space, 10, 10, false, SceneAntialiasing.BALANCED);
    /// Design space to the view: centre, fit, then the head.
    private final Translate centre = new Translate();
    private final Scale fit = new Scale(1, 1, 1);
    private final Rotate turnY = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate turnX = new Rotate(0, Rotate.X_AXIS);
    /// The windows, back to front.
    private final Group windows = new Group();
    /// The flat layer on the ear pad (version and window buttons), in design coordinates.
    private final Pane pad = new Pane();
    private final Scale padFit = new Scale(1, 1, 0, 0);
    private final Translate padShift = new Translate();
    /// The soft glint drifting over the glass.
    private final Region glint = new Region();
    /// Black over everything for power-down.
    private final Region black = new Region();

    /// The home windows.
    private final SaoFloatWindow news, player, server, link, dock;
    /// The app window open in front, or null.
    private @Nullable SaoFloatWindow app;
    /// The dock button of the open app.
    private @Nullable Button appButton;
    /// The dock's buttons.
    private final Button[] dockButtons = new Button[SaoApps.APPS.length];

    /// The helmet hum.
    private final SaoMusic hum = new SaoMusic("helmet_intro.ogg", "helmet_loop.ogg", 0.55);
    /// crossing field, when the player picked it.
    private final SaoMusic song = new SaoMusic(null, "title.ogg", 0.5);
    /// Mouse position in parts of the window, -0.5 to 0.5, and its smoothed follow.
    private double headX, headY, lookX, lookY;
    /// Whether the display is on.
    private boolean poweredOn;

    /// 誓约账号 or Microsoft (正版). The 誓约 account replaced offline play (James, 7 Nov: "这样，你做个账号系统行不").
    private final ToggleGroup kind = new ToggleGroup();
    private final ToggleButton oath = chip("誓约账号", kind);
    private final ToggleButton microsoft = chip("正版", kind);
    /// Signed out: the name, the password and 记住我; Link Start logs in and starts in one press.
    private final TextField name = field("角色名");
    private final PasswordField pw = SaoAccountApp.password("密码");
    private final ToggleButton remember = toggle(true);
    private final VBox signedOut = new VBox(6);
    /// Signed in: the name, the mailbox and the account's buttons.
    private final VBox signedIn = new VBox(7);
    /// Microsoft.
    private final VBox msBox = new VBox(6);
    private final TextField msName = field("");
    private final Button msSignIn = SaoUiKit.button("登录 Microsoft");
    /// The character.
    private final Character skin = new Character();
    /// Link Start and its lines.
    private final Button start = new Button("Link Start");
    private final Text status = ink("", 13);
    private final Text problem = new Text();
    private final Region fill = new Region();
    /// The sound button on the pad.
    private final Button sound = round(SVG.WB_SUNNY, 30, 15);

    /// Builds the page with the installed engine, or a stand-in when none is installed yet.
    public SaoHelmetPage() {
        this(serviceOrFake());
    }

    /// Builds the page for an engine.
    ///
    /// @param service the engine
    public SaoHelmetPage(SaoLauncherService service) {
        this.service = service;
        String css = Objects.requireNonNull(SaoHelmetPage.class.getResource("/assets/css/sao.css")).toExternalForm();
        getStylesheets().add(css);
        // A SubScene does not inherit the page's stylesheets: the space carries its own.
        space.getStylesheets().add(css);
        // The SAO UI face for every control's text (buttons, fields, chips); Chinese falls back to the system face.
        String face = "-fx-font-family: \"" + SaoAssets.saoFamily() + "\";";
        setStyle(face);
        space.setStyle(face);
        setStyle("-fx-background-color: #252525;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        for (ImageView v : new ImageView[]{glass, frame}) {
            v.setManaged(false);
            v.setSmooth(false);
            v.setMouseTransparent(true);
        }
        glint.setManaged(false);
        glint.setMouseTransparent(true);
        glint.setBlendMode(BlendMode.ADD);
        black.setStyle("-fx-background-color: black;");
        black.setMouseTransparent(true);

        world.setManaged(false);
        world.setFill(Color.TRANSPARENT);
        world.setCamera(new PerspectiveCamera(false));
        space.getTransforms().addAll(centre, fit, turnY, turnX);
        space.getChildren().add(windows);

        news = buildNews();
        server = buildServer();
        player = buildPlayer();
        link = buildLink();
        dock = buildDock();
        windows.getChildren().addAll(news, server, player, link, dock);

        buildPad();
        // The window style James picked (6 Nov, from four): the anime's light menu panels with orange highlights.
        getStyleClass().add("theme-sao");
        space.getStyleClass().add("theme-sao");
        SaoApps.onSoundChanged = () -> {
            stopSound();
            if (poweredOn) startSound();
            refreshSoundIcon();
        };
        getChildren().setAll(glass, world, frame, pad, glint, black);

        setOnMouseMoved(e -> {
            headX = e.getX() / Math.max(1, getWidth()) - 0.5;
            headY = e.getY() / Math.max(1, getHeight()) - 0.5;
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && app != null) closeApp();
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
        Timeline ping = new Timeline(new KeyFrame(Duration.seconds(60), e -> service.refreshOnline()));
        ping.setCycleCount(Timeline.INDEFINITE);
        ping.play();
        service.refreshOnline();

        sceneProperty().addListener((o, a, b) -> {
            if (b == null) return;
            hookWindow();
            powerOn();
        });
    }

    /// The installed engine, or a stand-in installed now.
    private static SaoLauncherService serviceOrFake() {
        try {
            return SaoLauncherService.get();
        } catch (IllegalStateException e) {
            SaoFakeService fake = new SaoFakeService();
            SaoServices.install(fake);
            return fake;
        }
    }

    // ------------------------------------------------------------------ home windows

    /// News, on the left, turned toward the player.
    private SaoFloatWindow buildNews() {
        VBox list = SaoApps.live(service.news(), n -> {
            Text title = ink(n.title(), 14);
            Text body = dim(n.body(), 12);
            body.setWrappingWidth(222);
            HBox head = new HBox(8, title, grow(), dim(n.date(), 12));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox v = new VBox(6, head, body);
            v.getStyleClass().add("vr-tile");
            return v;
        }, empty(SVG.CHAT, "暂无公告"));
        return new SaoFloatWindow("公告", 290, 352, null)
                .add(scroll(list))
                .home(258, 156, 40, -24, 0);
    }

    /// The server, on the right, turned toward the player.
    private SaoFloatWindow buildServer() {
        VBox box = new VBox(10);
        VBox.setVgrow(box, Priority.ALWAYS);
        Runnable show = () -> {
            @Nullable SaoData.ServerStatus st = service.serverStatus().get();
            box.getChildren().clear();
            if (st == null) {
                box.getChildren().add(empty(SVG.PUBLIC, "正在连接服务器"));
                return;
            }
            HBox state = new HBox(8, light(st.online()), ink(st.online() ? "在线" : "离线", 13), grow(),
                    sao(st.online() && st.pingMs() >= 0 ? st.pingMs() + " ms" : "--", 13, false));
            state.setAlignment(Pos.CENTER_LEFT);
            Text count = sao(st.online() ? Integer.toString(st.players()) : "-", 48, true);
            count.getStyleClass().add("vr-big");
            HBox players = new HBox(8, count, dim("/ " + st.max() + "  在线玩家", 13));
            players.setAlignment(Pos.BASELINE_LEFT);
            Text motd = ink(st.motd(), 13);
            motd.setWrappingWidth(240);
            FlowPane names = new FlowPane(6, 6);
            for (String n : st.names()) {
                Text t = sao(n, 12, false);
                StackPane chip = new StackPane(t);
                chip.getStyleClass().add("vr-name");
                names.getChildren().add(chip);
            }
            box.getChildren().addAll(state, rule(), players, motd, names);
        };
        service.serverStatus().addListener((o, a, b) -> show.run());
        show.run();
        Button refresh = round(SVG.REFRESH, 26, 14);
        refresh.setOnAction(e -> service.refreshOnline());
        SaoFloatWindow w = new SaoFloatWindow("服务器", 290, 352, null).add(box);
        w.head.getChildren().add(refresh);
        return w.home(1022, 156, 40, 24, 0);
    }

    /// The player, in the middle: the 誓约 account and the character turning in 3D. Signed out it asks for the name and
    /// password, and Link Start logs in and starts; the skin has its own page (James, 7 Nov: "把classic slim等等塞到一个新的页面").
    private SaoFloatWindow buildPlayer() {
        ImageView logo = new ImageView(SaoAssets.image("logo.png"));
        logo.setPreserveRatio(true);
        logo.setFitHeight(46);
        logo.getStyleClass().add("vr-logo");
        HBox head = new HBox(8, logo, grow(), oath, microsoft);
        head.setAlignment(Pos.CENTER_LEFT);
        keepOne(kind);

        @Nullable String remembered = service.rememberedOathName();
        if (remembered != null) {
            name.setText(remembered);
            pw.setPromptText("●●●●●●  已记住");
        }
        // A remembered password belongs to its name only.
        name.textProperty().addListener((o, a, b) -> pw.setPromptText(
                remembered != null && b.strip().equals(remembered) ? "●●●●●●  已记住" : "密码"));
        Text rememberLabel = dim("记住我", 12);
        HBox rememberRow = new HBox(6, remember, rememberLabel, grow(),
                SaoAccountApp.link("注册", () -> openAccount(SaoAccountApp.Page.REGISTER)),
                SaoAccountApp.link("忘记密码", () -> openAccount(SaoAccountApp.Page.FORGOT)));
        rememberRow.setAlignment(Pos.CENTER_LEFT);
        signedOut.getChildren().setAll(key("誓约账号"), name, pw, rememberRow);

        msName.setEditable(false);
        msName.setFocusTraversable(false);
        msSignIn.setOnAction(e -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT)));
        msSignIn.setMaxWidth(Double.MAX_VALUE);
        Text msWhy = dim("第一次用正版：先登录誓约账号，在「账号」里做正版验证", 11);
        msWhy.setWrappingWidth(206);
        msBox.getChildren().setAll(key("Microsoft"), new StackPane(msName, msSignIn), msWhy);

        StackPane area = new StackPane(signedOut, signedIn, msBox);
        area.setAlignment(Pos.TOP_LEFT);
        for (VBox v : new VBox[]{signedOut, signedIn, msBox}) StackPane.setAlignment(v, Pos.TOP_LEFT);
        problem.getStyleClass().add("vr-warn");
        problem.setFont(SaoUiKit.ui(12, false));
        problem.setWrappingWidth(206);
        VBox fields = new VBox(6, area, grow(), problem);
        fields.setPrefWidth(212);
        HBox.setHgrow(fields, Priority.ALWAYS);

        Button editSkin = round(SVG.EDIT, 28, 14);
        editSkin.setOnAction(e -> openSkin());
        StackPane.setAlignment(editSkin, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(editSkin, new Insets(6));
        StackPane stage = new StackPane(skin, editSkin);
        stage.getStyleClass().add("vr-stage");
        stage.setMinSize(150, 214);
        stage.setPrefSize(150, 214);
        stage.setMaxSize(150, 214);
        HBox bodyRow = new HBox(16, stage, fields);

        kind.selectedToggleProperty().addListener((o, a, b) -> refreshAccount());
        service.oathAccount().addListener((o, a, b) -> refreshAccount());
        Accounts.getAccounts().addListener((ListChangeListener<Account>) c -> refreshAccount());
        preselect();
        return new SaoFloatWindow("", 430, 300, null).add(head, bodyRow).home(640, 160, 0, 0, 0);
    }

    /// Link Start under the player: what the pack is doing on the left, the button on the right.
    private SaoFloatWindow buildLink() {
        Region track = new Region();
        track.getStyleClass().add("vr-track");
        track.setPrefSize(200, 3);
        track.setMaxSize(200, 3);
        fill.getStyleClass().add("vr-fill");
        fill.setPrefHeight(3);
        fill.setMaxHeight(3);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            SaoPackState s = service.state().get();
            if (s == SaoPackState.WORKING) return 200 * Math.max(0.03, service.progress().get());
            return s == SaoPackState.READY || s == SaoPackState.RUNNING ? 200.0 : 0.0;
        }, service.state(), service.progress()));
        StackPane bar = new StackPane(track, fill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(200);
        VBox left = new VBox(6, status, bar);
        left.setAlignment(Pos.CENTER_LEFT);
        start.getStyleClass().add("vr-start");
        start.setFont(ui(21, true));
        start.setOnAction(e -> linkStart());
        start.setDefaultButton(true);
        HBox row = new HBox(10, left, grow(), start);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(row, Priority.ALWAYS);
        SaoFloatWindow w = new SaoFloatWindow("", 430, 66, null).add(row);
        w.body.setPadding(new Insets(8, 10, 8, 18));
        w.body.getStyleClass().add("vr-window-start");
        return w.home(640, 476, -24, 0, 0);
    }

    /// The dock: a column of round buttons left of the windows, a label sliding out on hover.
    private SaoFloatWindow buildDock() {
        VBox col = new VBox(6);
        col.setAlignment(Pos.TOP_CENTER);
        for (int i = 0; i < SaoApps.APPS.length; i++) {
            SaoApps.App a = SaoApps.APPS[i];
            Button b = round(a.icon(), 36, 19);
            b.getStyleClass().add("vr-dock-button");
            Text label = new Text(a.label());
            label.setFont(javafx.scene.text.Font.font(12));
            label.getStyleClass().add("vr-ink");
            StackPane tip = new StackPane(label);
            tip.getStyleClass().add("vr-tip");
            tip.setManaged(false);
            tip.setVisible(false);
            tip.setMouseTransparent(true);
            StackPane cell = new StackPane(b, tip);
            cell.setMinSize(36, 36);
            cell.setMaxSize(36, 36);
            tip.resizeRelocate(48, 5, 52, 26);
            b.hoverProperty().addListener((o, x, on) -> tip.setVisible(on));
            b.setOnAction(e -> {
                if (app != null && appButton == b) closeApp();
                else openApp(a, b);
            });
            dockButtons[i] = b;
            col.getChildren().add(cell);
        }
        SaoFloatWindow w = new SaoFloatWindow("", 52, SaoApps.APPS.length * 36 + (SaoApps.APPS.length - 1) * 6 + 16, null).add(col);
        w.body.setPadding(new Insets(8));
        w.body.getStyleClass().add("vr-dock");
        return w.home(60, 168, -40, -18, 0);
    }

    /// The flat layer on the ear pad: the version at the bottom left, sound, minimise and close at the bottom
    /// right, where the pack's helmet screen keeps its Skip and gear.
    private void buildPad() {
        pad.setManaged(false);
        pad.setPickOnBounds(false);
        pad.getTransforms().addAll(padShift, padFit);
        Text version = dim("刀剑神域·誓约 启动器 " + Metadata.VERSION + " · 基于 HMCL", 10);
        version.relocate(64, 676);
        sound.setOnAction(e -> cycleSound());
        Button minimize = round(SVG.MINIMIZE_CENTER, 30, 15);
        minimize.setOnAction(e -> Controllers.getDecorator().minimizeWindow());
        Button close = round(SVG.CLOSE, 30, 15);
        close.setOnAction(e -> Controllers.getDecorator().closeWindow());
        HBox row = new HBox(6, sound, minimize, close);
        row.relocate(EW - 64 - 102, 662);
        pad.getChildren().setAll(version, row);
        refreshSoundIcon();
    }

    // ------------------------------------------------------------------ apps

    /// Opens an app's window in front; the home windows move back and dim.
    private void openApp(SaoApps.App a, @Nullable Button b) {
        if (app != null) {
            SaoFloatWindow old = app;
            old.moveBack(380, 0, 200);
            PauseTransition p = new PauseTransition(Duration.millis(210));
            p.setOnFinished(e -> windows.getChildren().remove(old));
            p.play();
        }
        if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
        SaoFloatWindow w = a.make().make(service, this::closeApp);
        w.home(660, 104, 0, 0, 0);
        windows.getChildren().add(w);
        w.flyIn(320);
        app = w;
        appButton = b;
        if (b != null) b.getStyleClass().add("vr-dock-on");
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player, link}) home.moveBack(300, 0.22, 320);
        requestFocus();
    }

    /// Opens the 誓约账号 window on a page.
    private void openAccount(SaoAccountApp.Page page) {
        openApp(new SaoApps.App(SVG.PERSON, "誓约账号", (s, c) -> SaoAccountApp.make(s, c, page)), null);
    }

    /// Opens the skin page, as its dock button does.
    private void openSkin() {
        for (int i = 0; i < SaoApps.APPS.length; i++)
            if (SaoApps.APPS[i].label().equals("皮肤")) openApp(SaoApps.APPS[i], dockButtons[i]);
    }

    /// Closes the app in front; the home windows come forward again.
    private void closeApp() {
        @Nullable SaoFloatWindow w = app;
        if (w == null) return;
        app = null;
        if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
        appButton = null;
        w.moveBack(420, 0, 240);
        PauseTransition p = new PauseTransition(Duration.millis(250));
        p.setOnFinished(e -> windows.getChildren().remove(w));
        p.play();
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player, link}) home.moveBack(0, 1, 320);
        // A skin put on or an account changed in the app shows on the player at once.
        refreshAccount();
    }

    // ------------------------------------------------------------------ frame

    /// Keeps the visor rendered at the window's size and minute, fits the space, turns it with the head and moves the glint.
    ///
    /// @param t seconds on the clock
    private void frame(double t) {
        int w = (int) Math.ceil(getWidth()), h = (int) Math.ceil(getHeight());
        if (w <= 1 || h <= 1) return;
        long minute = System.currentTimeMillis() / 60000;
        if (w != renderedW || h != renderedH || minute != renderedMinute) {
            boolean resized = w != renderedW || h != renderedH;
            renderedW = w;
            renderedH = h;
            renderedMinute = minute;
            if (resized) glass.setImage(SaoVisor.renderGlass(w, h).image());
            frameDark = SaoVisor.renderFrame(w, h, false).image();
            frameLit = SaoVisor.renderFrame(w, h, true).image();
            frame.setImage(poweredOn ? frameLit : frameDark);
            world.setWidth(w);
            world.setHeight(h);
        }
        double s = Math.min(w / EW, h / EH);
        SaoFloatWindow.fitScale = s;
        fit.setX(s);
        fit.setY(s);
        fit.setZ(s);
        centre.setX(w / 2.0 - EW / 2 * s);
        centre.setY(h / 2.0 - EH / 2 * s);
        padFit.setX(s);
        padFit.setY(s);
        padShift.setX(w / 2.0 - EW / 2 * s);
        padShift.setY(h / 2.0 - EH / 2 * s);

        // The head turns about the eye: the space moves the other way, near windows more than far ones.
        double eye = -(h / 2.0) / Math.tan(HALF_FOV) / s;
        lookX += (headX + Math.sin(t * 0.5) * 0.04 - lookX) * 0.06;
        lookY += (headY + Math.sin(t * 0.37 + 1) * 0.03 - lookY) * 0.06;
        turnY.setPivotX(EW / 2);
        turnY.setPivotY(EH / 2);
        turnY.setPivotZ(eye);
        turnX.setPivotX(EW / 2);
        turnX.setPivotY(EH / 2);
        turnX.setPivotZ(eye);
        turnY.setAngle(lookX * 3.2);
        turnX.setAngle(lookY * 2.4);

        black.resizeRelocate(0, 0, w, h);
        glass.relocate(0, 0);
        frame.relocate(0, 0);
        double r = 420 * s, gx = (((t * 0.045) % 1.6) - 0.3) * w, gy = h * 0.42;
        glint.resizeRelocate(gx - r, gy - r, 2 * r, 2 * r);
        glint.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0.45, 0.75, 1, 0.06)), new Stop(1, Color.color(0.45, 0.75, 1, 0))), null, null)));
    }

    // ------------------------------------------------------------------ power

    /// Turns the display on: black lifts off the glass, the helmet's clock flickers on and the windows float in one
    /// after another.
    private void powerOn() {
        poweredOn = true;
        startSound();
        world.setOpacity(1);
        SaoFloatWindow[] order = {player, link, news, server, dock};
        for (SaoFloatWindow w : order) {
            w.setOpacity(0);
            w.recede.set(420);
        }
        Timeline t = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(black.opacityProperty(), black.getOpacity())),
                new KeyFrame(Duration.millis(520), new KeyValue(black.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(560), e -> frame.setImage(frameLit)),
                new KeyFrame(Duration.millis(620), e -> frame.setImage(frameDark)),
                new KeyFrame(Duration.millis(680), e -> frame.setImage(frameLit)));
        for (int i = 0; i < order.length; i++) {
            SaoFloatWindow w = order[i];
            t.getKeyFrames().add(new KeyFrame(Duration.millis(640 + 70 * i), e -> w.moveBack(0, 1, 420)));
        }
        t.play();
    }

    /// Powers the display down like the pack's helmet screen: a flicker, the windows fall away, the view sinks to
    /// black.
    private void powerDown() {
        poweredOn = false;
        stopSound();
        if (!soundMode().equals("off")) SaoMusic.once("helmet_outro.ogg", 0.55);
        if (app != null) closeApp();
        for (SaoFloatWindow w : new SaoFloatWindow[]{news, server, player, link, dock}) w.moveBack(600, 0, 300);
        Timeline t = new Timeline(
                new KeyFrame(Duration.millis(60), e -> frame.setImage(frameDark)),
                new KeyFrame(Duration.millis(120), e -> frame.setImage(frameLit)),
                new KeyFrame(Duration.millis(180), e -> frame.setImage(frameDark)),
                new KeyFrame(Duration.ZERO, new KeyValue(black.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(480), new KeyValue(black.opacityProperty(), 1)));
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

    // ------------------------------------------------------------------ sound

    /// helmet, song or off.
    private String soundMode() {
        return service.preference("sound", "helmet");
    }

    /// Starts what the player picked.
    private void startSound() {
        if (service.state().get() == SaoPackState.RUNNING) return;
        switch (soundMode()) {
            case "song" -> song.start();
            case "off" -> {
            }
            default -> hum.start();
        }
    }

    /// Stops every sound.
    private void stopSound() {
        hum.stop();
        song.stop();
    }

    /// The pad's sound button steps helmet, song, off.
    private void cycleSound() {
        String next = switch (soundMode()) {
            case "helmet" -> "song";
            case "song" -> "off";
            default -> "helmet";
        };
        service.setPreference("sound", next);
        stopSound();
        if (poweredOn) startSound();
        refreshSoundIcon();
    }

    /// Shows the sound mode on its button.
    private void refreshSoundIcon() {
        sound.setGraphic(icon(switch (soundMode()) {
            case "song" -> NOTE;
            case "off" -> MUTED;
            default -> SPEAKER;
        }, 15));
        sound.setOpacity(soundMode().equals("off") ? 0.55 : 1);
    }

    // ------------------------------------------------------------------ choices

    /// Starts on the account the launcher used last.
    private void preselect() {
        kind.selectToggle(Accounts.getSelectedAccount() instanceof MicrosoftAccount ? microsoft : oath);
        refreshAccount();
    }

    /// Shows the account part that fits: signed out, signed in, or Microsoft; and the skin worn now.
    private void refreshAccount() {
        boolean ms = kind.getSelectedToggle() == microsoft;
        SaoData.@Nullable OathAccount a = service.oathAccount().get();
        signedOut.setVisible(!ms && a == null);
        signedIn.setVisible(!ms && a != null);
        msBox.setVisible(ms);
        @Nullable MicrosoftAccount m = microsoftAccount();
        msName.setVisible(m != null);
        msSignIn.setVisible(m == null);
        if (m != null) msName.setText(m.getProfileName());
        if (a != null) {
            Text n = sao(a.name(), 22, true);
            Button skinB = SaoUiKit.button("皮肤");
            skinB.setOnAction(e -> openSkin());
            Button manage = SaoUiKit.button("账号");
            manage.setOnAction(e -> openAccount(SaoAccountApp.Page.MANAGE));
            Button out = SaoUiKit.button("退出");
            out.setOnAction(e -> service.oathLogout());
            signedIn.getChildren().setAll(key("誓约账号"), n,
                    SaoAccountApp.mailRow(a, () -> openAccount(SaoAccountApp.Page.VERIFY)),
                    new HBox(6, skinB, manage, out));
        }
        problem.setText("");
        @Nullable Path worn = service.oathSkin() != null ? service.oathSkin() : service.skin();
        skin.show(worn, service.skinSlim());
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
            case CHECKING -> status.setText("检查中");
            case NOT_INSTALLED -> status.setText("首次连接自动安装 · v" + v);
            case UPDATE_READY -> status.setText("自动更新至 v" + v);
            case WORKING -> {
                double p = service.progress().get();
                String l = service.stepLabel().get();
                status.setText((l.isEmpty() ? "准备中" : l) + (p >= 0 ? "  " + Math.round(p * 100) + "%" : ""));
            }
            case READY -> status.setText("就绪 · v" + v);
            case RUNNING -> status.setText("已连接");
            case FAILED -> status.setText("失败");
        }
        @Nullable String err = service.lastError().get();
        if (s == SaoPackState.FAILED && err != null) problem.setText(err);
        else if (!problem.getText().startsWith("!")) problem.setText("");
        start.setDisable(s == SaoPackState.CHECKING || s == SaoPackState.WORKING || s == SaoPackState.RUNNING);
    }

    /// Link Start: the account, then the display powers down and the game starts at once. Signed out, it logs in to
    /// the 誓约 account first, so a returning player presses once.
    private void linkStart() {
        if (kind.getSelectedToggle() == microsoft) {
            @Nullable MicrosoftAccount m = microsoftAccount();
            if (m == null) {
                problem.setText("! 请先登录 Microsoft");
                return;
            }
            problem.setText("");
            Accounts.setSelectedAccount(m);
            begin(() -> service.linkStart(m, null));
            return;
        }
        SaoData.@Nullable OathAccount a = service.oathAccount().get();
        if (a != null) {
            joinAs(a);
            return;
        }
        String n = name.getText().strip();
        if (!SaoAccountApp.NAME.matcher(n).matches()) {
            problem.setText("! 角色名 3-16 位：字母、数字、下划线");
            return;
        }
        @Nullable String remembered = service.rememberedOathName();
        char @Nullable [] p = pw.getText().isEmpty() && n.equals(remembered) ? null : pw.getText().toCharArray();
        if (p != null && p.length == 0) {
            problem.setText("! 请输入密码");
            return;
        }
        pw.clear();
        start.setDisable(true);
        problem.setText("");
        status.setText("正在登录");
        service.oathLogin(n, p, remember.isSelected()).whenComplete((ok, err) -> Platform.runLater(() -> {
            if (p != null) java.util.Arrays.fill(p, '\0');
            refreshState();
            if (err != null) {
                problem.setText("! " + SaoSkinApp.message(err));
                return;
            }
            SaoData.@Nullable OathAccount b = service.oathAccount().get();
            if (b != null) joinAs(b);
        }));
    }

    /// Starts with the signed-in 誓约 account, unless its mailbox check ran out.
    private void joinAs(SaoData.OathAccount a) {
        if (a.locked()) {
            problem.setText("! 邮箱验证已过期，验证后才能进游戏");
            openAccount(SaoAccountApp.Page.VERIFY);
            return;
        }
        problem.setText("");
        begin(service::linkStartOath);
    }

    /// Starts the game: at once when ready (the display powers down), or after the first install or an update, which
    /// keeps the display on with the progress until the engine reports the game running.
    private void begin(Runnable launch) {
        boolean ready = service.state().get() == SaoPackState.READY;
        launch.run();
        if (ready) powerDown();
        else service.state().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends SaoPackState> o, SaoPackState a, SaoPackState b) {
                if (b == SaoPackState.RUNNING) {
                    o.removeListener(this);
                    powerDown();
                } else if (b == SaoPackState.FAILED || b == SaoPackState.READY && a != SaoPackState.RUNNING) {
                    o.removeListener(this);
                }
            }
        });
    }

    /// Lets the window be dragged by the empty glass.
    private void hookWindow() {
        try {
            Controllers.getDecorator().capableDraggingWindow(this);
            Controllers.getDecorator().forbidDraggingWindow(world);
            Controllers.getDecorator().forbidDraggingWindow(pad);
        } catch (NullPointerException | IllegalStateException noWindow) {
            // Screenshot tools: no launcher window.
        }
    }

    // ------------------------------------------------------------------ tools

    /// Opens the 誓约账号 window on a page (screenshot tools).
    void forceAccount(SaoAccountApp.Page page) {
        openAccount(page);
    }

    /// Presses the first danger button in view, for example settings' 一键卸载 (screenshot tools).
    void forceDanger() {
        for (javafx.scene.Node n : space.lookupAll(".vr-danger")) {
            if (n instanceof javafx.scene.control.Button b && b.isVisible() && n.getParent() != null && n.getParent().isVisible()) {
                b.fire();
                return;
            }
        }
    }

    /// Skips the power-on (screenshot tools).
    void forceLit() {
        poweredOn = true;
        frame.setImage(frameLit);
        black.setOpacity(0);
        for (SaoFloatWindow w : new SaoFloatWindow[]{news, server, player, link, dock}) {
            w.recede.set(0);
            w.setOpacity(1);
        }
    }

    /// Opens an app by its dock index at once (screenshot tools).
    ///
    /// @param i the index in [SaoApps#APPS], or -1 to close
    void forceApp(int i) {
        if (i < 0) {
            if (app != null) windows.getChildren().remove(app);
            app = null;
            if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
            appButton = null;
            for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player, link}) {
                home.recede.set(0);
                home.setOpacity(1);
            }
            return;
        }
        openApp(SaoApps.APPS[i], dockButtons[i]);
    }

    /// Turns the head (screenshot tools).
    void forceHead(double x, double y) {
        headX = lookX = x;
        headY = lookY = y;
    }

    /// The player's character, turning slowly in HMCL's 3D skin view and turned by dragging.
    private static final class Character extends StackPane {
        /// HMCL's skin model.
        private final SkinCanvas canvas = new SkinCanvas(TexturesLoader.getDefaultSkinImage(), 150, 214, true);

        /// Builds it with the default skin.
        Character() {
            canvas.enableRotation(0.5);
            canvas.getAnimationPlayer().addSkinAnimation(new SkinAniWavingArms(100, 2000, 7.5, canvas), new SkinAniRunning(100, 100, 30, canvas));
            getChildren().add(canvas);
        }

        /// Shows a skin, or the default one.
        ///
        /// @param png    the skin file, or null
        /// @param isSlim Slim arms
        void show(@Nullable Path png, boolean isSlim) {
            Image img = png == null ? TexturesLoader.getDefaultSkinImage() : new Image(png.toUri().toString());
            if (img.isError()) img = TexturesLoader.getDefaultSkinImage();
            canvas.updateSkin(img, isSlim, null);
        }
    }
}
