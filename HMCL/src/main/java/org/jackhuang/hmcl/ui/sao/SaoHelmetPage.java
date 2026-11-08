/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
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
import javafx.scene.input.MouseEvent;
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
    /// The dock's turn, its right edge toward the eye.
    private static final double DOCK_TURN = -18;
    /// How much wider the dock's icons are drawn so they look round on the turned dock. Seen from the eye (camera
    /// 30 degree field, eye at 360 / tan 15), a small width at the dock's centre (x 60, depth -40) shrinks by
    /// cos 18 - 580 sin 18 / (1343.6 - 40) = 0.814 against its height.
    static final double DOCK_WIDEN = 1 / 0.814;
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
    /// The battery reading the visor was last rendered with.
    private int renderedBattery = -1;

    /// The 3D space.
    private final Group space = new Group();
    /// Its view.
    private final SubScene world = new SubScene(space, 10, 10, false, SceneAntialiasing.BALANCED);
    /// Design space to the view: centre, fit, then the head.
    private final Translate centre = new Translate();
    private final Scale fit = new Scale(1, 1, 1);
    private final Rotate turnY = new Rotate(0, Rotate.Y_AXIS);
    private final Rotate turnX = new Rotate(0, Rotate.X_AXIS);
    /// The breath: the whole space rises and falls a few design pixels.
    private final Translate breath = new Translate();
    /// How far the view sinks at the top of a breath, in design pixels.
    private static final double BREATH_PX = 2.6;
    /// One breath, in seconds (about 13 a minute at rest).
    private static final double BREATH_S = 4.6;
    /// The glint's light, made once.
    private static final javafx.scene.layout.Background GLINT = new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
            new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(0.45, 0.75, 1, 0.06)), new Stop(1, Color.color(0.45, 0.75, 1, 0))), null, null));
    /// The windows, back to front.
    private final Group windows = new Group();
    /// The flat layer on the ear pad (version and window buttons), in design coordinates.
    private final Pane pad = new Pane();
    private final Scale padFit = new Scale(1, 1, 0, 0);
    /// The 16:9 picture everything is drawn in, and its clip.
    private final Pane screen = new Pane();
    private final Rectangle screenClip = new Rectangle();
    /// Shows the k-times world at the window's size (see [#frame(double)]).
    private final Scale worldShrink = new Scale(1, 1, 0, 0);
    /// The pixel scale the visor and world were last made for.
    private double renderedK;
    private final Translate padShift = new Translate();
    /// The soft glint drifting over the glass.
    private final Region glint = new Region();
    /// Black over everything for power-down.
    private final Region black = new Region();

    /// The home windows.
    private final SaoFloatWindow news, player, server, dock;
    /// The app window open in front, or null.
    private @Nullable SaoFloatWindow app;
    /// The dock button of the open app.
    private @Nullable Button appButton;
    /// The dock's buttons.
    private final Button[] dockButtons = new Button[SaoApps.APPS.length];

    /// Mouse position in parts of the window, -0.5 to 0.5, and its smoothed follow.
    private double headX, headY, lookX, lookY;
    /// Whether the display is on.
    private boolean poweredOn;

    /// The ARGUS ID side of the player window, drawn by James (7 Nov, two sketches): signed out, "ARGUS ID 未登录",
    /// the name and password, 记住 with 注册 and 忘记密码, 登录, a line, 使用 Microsoft; signed in, "ARGUS ID [退出登录]",
    /// the name large, the mailbox and Microsoft each with 验证, and Link Start at the bottom right.
    private final TextField name = field("");
    private final PasswordField pw = SaoAccountApp.password("");
    private final ToggleButton remember = new ToggleButton();
    private final Button login = SaoUiKit.accent("登录");
    private final Button useMicrosoft = SaoUiKit.button("使用 Microsoft 登录");
    private final VBox signedOut = new VBox(6);
    private final VBox signedIn = new VBox(8);
    /// Logging in with the remembered password on opening: the player sees the account, not a password form
    /// (James, 7 Nov: "登录进去就直接显示已登录，别再显示一整个密码界面").
    private boolean autoLogging;
    /// The 游戏昵称 being edited in place (the pencil beside the name).
    private boolean editingName;
    /// A name to start the editor with instead of the current one (the screenshot tool).
    private @Nullable String typedName;
    /// 确定改成…吗？ over the player's body, before a new 游戏昵称 is saved.
    private final VBox confirmName = new VBox(10);
    /// What 确定 on [#confirmName] does.
    private Runnable confirmNameAction = () -> {
    };
    /// 确定退出吗？ over the player's body.
    private final VBox confirmOut = new VBox(10);
    /// 离线游玩: the name and the warning, in place of the body.
    private final VBox confirmOffline = new VBox(10);
    /// The name for 离线游玩.
    private final javafx.scene.control.TextField offlineName = SaoUiKit.field("游戏里的名字");
    /// The character.
    private final Character skin = new Character();
    /// Link Start and its lines.
    private final Button start = new Button("Link Start");
    private final Text problem = new Text();
    private final Region fill = new Region();

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
        setStyle("-fx-background-color: black;");
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(30);
        clip.setArcHeight(30);
        setClip(clip);
        screenClip.setArcWidth(30);
        screenClip.setArcHeight(30);

        for (ImageView v : new ImageView[]{glass, frame}) {
            v.setManaged(false);
            v.setSmooth(true);
            v.setMouseTransparent(true);
        }
        glint.setManaged(false);
        glint.setMouseTransparent(true);
        glint.setBlendMode(BlendMode.ADD);
        black.setStyle("-fx-background-color: black;");
        black.setMouseTransparent(true);

        world.setManaged(false);
        world.setFill(Color.TRANSPARENT);
        world.getTransforms().add(worldShrink);
        world.setCamera(new PerspectiveCamera(false));
        space.getTransforms().addAll(centre, fit, breath, turnY, turnX);
        space.getChildren().add(windows);

        news = buildNews();
        server = buildServer();
        player = buildPlayer();
        dock = buildDock();
        windows.getChildren().addAll(news, server, player, dock);

        buildPad();
        // The window style James picked (6 Nov, from four): the anime's light menu panels with orange highlights.
        getStyleClass().add("theme-sao");
        space.getStyleClass().add("theme-sao");
        // James, 7 Nov: "make sure only 16:9 is available": the helmet is one 16:9 picture, centred, black around it
        // when the window or the screen is another shape.
        screen.setManaged(false);
        screen.setClip(screenClip);
        current = this;
        cover.setManaged(false);
        cover.setVisible(false);
        cover.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
        cover.getStyleClass().add("theme-sao");
        cover.getStylesheets().add(css);
        coverDesign.getTransforms().addAll(coverShift, coverFit);
        coverDesign.setPickOnBounds(false);
        cover.getChildren().add(new Group(coverDesign));
        StackPane.setAlignment(cover.getChildren().get(0), Pos.TOP_LEFT);
        screen.getChildren().setAll(glass, world, frame, pad, cover, glint, black);
        getChildren().setAll(screen);

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

        SaoBattery.start();
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
        return new SaoFloatWindow("公告", 290, 430, null)
                .add(scroll(list))
                .home(246, 175, 40, -24, 0);
    }

    /// The server, on the right, turned toward the player.
    private SaoFloatWindow buildServer() {
        VBox box = new VBox(10);
        VBox.setVgrow(box, Priority.ALWAYS);
        // The address under the title, and 其他节点 opening the other nodes (James, 7 Nov: "更多改成其他节点").
        Text address = sao(service.serverAddress(), 19, true);
        VBox nodes = new VBox(4);
        Button[] moreRef = new Button[1];
        Button more = SaoAccountApp.link("其他节点", () -> {
            boolean open = !nodes.isVisible();
            nodes.setVisible(open);
            nodes.setManaged(open);
            moreRef[0].setText(open ? "收起" : "其他节点");
        });
        moreRef[0] = more;
        openNodes = () -> {
            if (!nodes.isVisible()) more.fire();
        };
        // The manifest loads in the background (launcherback5): read the address and nodes again once it has.
        Runnable fillNodes = () -> {
            address.setText(service.serverAddress());
            nodes.getChildren().clear();
            for (String n : service.serverNodes()) {
                // No light: the launcher does not ping the nodes.
                Text t = sao(n, 12, false);
                t.getStyleClass().setAll("vr-dim");
                nodes.getChildren().add(t);
            }
        };
        fillNodes.run();
        service.state().addListener((o, a, b) -> fillNodes.run());
        nodes.setVisible(false);
        nodes.setManaged(false);
        HBox addressRow = new HBox(8, address, grow(), more);
        addressRow.setAlignment(Pos.CENTER_LEFT);
        VBox where = new VBox(6, addressRow, nodes);
        Runnable show = () -> {
            @Nullable SaoData.ServerStatus st = service.serverStatus().get();
            box.getChildren().clear();
            if (st == null) {
                box.getChildren().addAll(where, empty(SVG.PUBLIC, "正在连接服务器"));
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
            box.getChildren().addAll(where, state, rule(), players, motd, names);
        };
        service.serverStatus().addListener((o, a, b) -> show.run());
        show.run();
        Button refresh = round(SVG.REFRESH, 26, 14);
        refresh.setOnAction(e -> service.refreshOnline());
        SaoFloatWindow w = new SaoFloatWindow("服务器", 290, 430, null).add(box);
        w.head.getChildren().add(refresh);
        return w.home(1034, 175, 40, 24, 0);
    }

    /// The player, in the middle: the logo over the character on the left, the ARGUS ID on the right as James drew it
    /// (7 Nov); Link Start lives here too, at the bottom right once signed in. The skin has its own page (James, 7 Nov:
    /// "把classic slim等等塞到一个新的页面").
    private SaoFloatWindow buildPlayer() {
        ImageView logo = new ImageView(SaoAssets.image("logo.png"));
        logo.setPreserveRatio(true);
        logo.setFitHeight(46);
        logo.getStyleClass().add("vr-logo");

        Button editSkin = round(SVG.EDIT, 28, 14);
        editSkin.setOnAction(e -> openSkin());
        StackPane.setAlignment(editSkin, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(editSkin, new Insets(6));
        StackPane stage = new StackPane(skin, editSkin);
        stage.getStyleClass().add("vr-stage");
        stage.setMinSize(150, 214);
        stage.setPrefSize(150, 214);
        stage.setMaxSize(150, 214);
        // The left as it was: the logo over the character ("左边不用动").
        VBox left = new VBox(12, logo, stage);

        // ---- signed out
        @Nullable String remembered = service.rememberedOathName();
        if (remembered != null) name.setText(remembered);
        name.textProperty().addListener((o, a, b) -> refreshPrompt());
        remember.getStyleClass().add("vr-check");
        remember.setFocusTraversable(false);
        remember.setSelected(true);
        remember.setGraphic(icon(SVG.CHECK, 12));
        remember.getGraphic().visibleProperty().bind(remember.selectedProperty());
        Text rememberLabel = dim("记住我", 12);
        rememberLabel.setOnMouseClicked(e -> remember.setSelected(!remember.isSelected()));
        HBox rememberRow = new HBox(6, remember, rememberLabel, grow(),
                SaoAccountApp.link("注册", () -> openAccount(SaoAccountApp.Page.REGISTER)),
                SaoAccountApp.link("忘记密码", () -> openAccount(SaoAccountApp.Page.FORGOT)));
        rememberRow.setAlignment(Pos.CENTER_LEFT);
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> login());
        login.defaultButtonProperty().bind(signedOut.visibleProperty());
        useMicrosoft.setGraphic(microsoftLogo(14));
        useMicrosoft.setMaxWidth(Double.MAX_VALUE);
        useMicrosoft.setOnAction(e -> premiumLogin());
        HBox outHead = new HBox(8, sao("ARGUS ID", 15, true), grow(), dim("未登录", 12));
        outHead.setAlignment(Pos.CENTER_LEFT);
        // James, 8 Nov: "可以选择不登录任何东西，顶着个名字，但是提醒窗口写这样就登录不了咱服务器".
        HBox offlineRow = new HBox(SaoAccountApp.link("不登录，离线游玩", this::askOffline));
        offlineRow.setAlignment(Pos.CENTER);
        signedOut.getChildren().setAll(outHead, key("用户名/邮箱"), name, key("密码"), pw, rememberRow, login, rule(), useMicrosoft, offlineRow);

        // ---- signed in: filled by refreshAccount; Link Start at its bottom right
        HBox linkRow = buildLinkRow();

        problem.getStyleClass().add("vr-warn");
        problem.setFont(SaoUiKit.ui(12, false));
        problem.setWrappingWidth(240);
        StackPane sides = new StackPane(signedOut, signedIn, confirmOffline);
        for (VBox v : new VBox[]{signedOut, signedIn, confirmOffline}) StackPane.setAlignment(v, Pos.TOP_LEFT);
        VBox right = new VBox(6, sides, grow(), problem, linkRow);
        right.setPrefWidth(246);
        right.setMinWidth(246);
        right.setMaxWidth(246);
        linkRow.visibleProperty().bind(signedIn.visibleProperty());
        linkRow.managedProperty().bind(signedIn.visibleProperty());
        HBox bodyRow = new HBox(16, left, right);

        // 确定退出吗？ in place of the body until answered.
        Text sure = ink("确定退出吗？", 18);
        sure.setFont(SaoUiKit.ui(18, true));
        Text after = dim("下次打开要重新输入密码", 12);
        Button no = SaoUiKit.button("取消"), yes = SaoUiKit.button("退出登录");
        yes.getStyleClass().add("vr-danger");
        HBox answers = new HBox(10, no, yes);
        answers.setAlignment(Pos.CENTER);
        confirmOut.getChildren().setAll(sure, after, answers);
        confirmOut.setAlignment(Pos.CENTER);
        confirmOut.setVisible(false);
        confirmOut.visibleProperty().addListener((o, a, shown) -> bodyRow.setVisible(!shown));
        no.setOnAction(e -> confirmOut.setVisible(false));
        yes.setOnAction(e -> {
            confirmOut.setVisible(false);
            service.oathLogout();
        });
        // 确定改成…吗？, the same way.
        Button nameNo = SaoUiKit.button("取消"), nameYes = SaoUiKit.accent("确定");
        HBox nameAnswers = new HBox(10, nameNo, nameYes);
        nameAnswers.setAlignment(Pos.CENTER);
        confirmName.setAlignment(Pos.CENTER);
        confirmName.setVisible(false);
        confirmName.getProperties().put("answers", nameAnswers);
        confirmName.visibleProperty().addListener((o, a, shown) -> bodyRow.setVisible(!shown && !confirmOut.isVisible()));
        nameNo.setOnAction(e -> confirmName.setVisible(false));
        nameYes.setOnAction(e -> {
            confirmName.setVisible(false);
            confirmNameAction.run();
        });
        // 离线游玩: a name, and the warning that it cannot reach the 誓约 server.
        Text offTitle = ink("离线游玩", 15);
        offTitle.setFont(SaoUiKit.ui(15, true));
        offlineName.setMaxWidth(240);
        Text offWarn = dim("离线进不了誓约服务器，只能玩单人游戏", 12);
        offWarn.getStyleClass().setAll("vr-warn");
        Text offProblem = dim("", 12);
        offProblem.getStyleClass().setAll("vr-warn");
        Button offNo = SaoUiKit.button("取消"), offYes = SaoUiKit.accent("离线进入");
        HBox offAnswers = new HBox(10, offNo, offYes);
        offAnswers.setAlignment(Pos.CENTER);
        // James, 8 Nov: "离线游玩不要开个新的页面，只把右边的页面改了就行": it takes the sign-in's place on the right.
        HBox offHead = new HBox(8, offTitle);
        offHead.setAlignment(Pos.CENTER_LEFT);
        offlineName.setMaxWidth(Double.MAX_VALUE);
        offWarn.setWrappingWidth(240);
        offAnswers.setAlignment(Pos.CENTER_LEFT);
        confirmOffline.getChildren().setAll(offHead, key("名字"), offlineName, offWarn, offProblem, offAnswers);
        confirmOffline.setAlignment(Pos.TOP_LEFT);
        confirmOffline.setVisible(false);
        confirmOffline.visibleProperty().addListener((o, a, shown) -> {
            if (service.oathAccount().get() == null) signedOut.setVisible(!shown);
        });
        offNo.setOnAction(e -> confirmOffline.setVisible(false));
        offYes.setOnAction(e -> {
            String n = offlineName.getText().strip();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) {
                offProblem.setText("名字要 3 到 16 位，只能用英文字母、数字和下划线");
                return;
            }
            offProblem.setText("");
            confirmOffline.setVisible(false);
            begin(() -> service.linkStartOffline(n));
        });
        offlineName.setOnAction(e -> offYes.fire());
        StackPane body = new StackPane(bodyRow, confirmOut, confirmName, confirmOffline);
        VBox.setVgrow(body, Priority.ALWAYS);

        service.oathAccount().addListener((o, a, b) -> refreshAccount());
        service.playerCard().addListener((o, a, b) -> refreshAccount());
        Accounts.getAccounts().addListener((ListChangeListener<Account>) c -> refreshAccount());
        refreshAccount();
        autoLogin();
        SaoFloatWindow w = new SaoFloatWindow("", 448, 410, null).add(body);
        w.body.setPadding(new Insets(16, 18, 16, 18));
        return w.home(640, 186, 0, 0, 0);
    }

    /// The Microsoft logo, its four squares in their colours, as Microsoft's own sign-in buttons show it.
    private static Node microsoftLogo(double size) {
        double gap = size / 12, cell = (size - gap) / 2;
        Group g = new Group();
        String[] colours = {"#f25022", "#7fba00", "#00a4ef", "#ffb900"};
        for (int i = 0; i < 4; i++) {
            Rectangle r = new Rectangle((i % 2) * (cell + gap), (i / 2) * (cell + gap), cell, cell);
            r.setFill(Color.web(colours[i]));
            g.getChildren().add(r);
        }
        return g;
    }

    /// 正版 login: Microsoft first when not signed in yet, then ARGUS ID with it.
    private void premiumLogin() {
        SaoAccountApp.withMicrosoft(() -> {
            problem.setText("正在用 Microsoft 登录…");
            service.oathPremiumLogin().whenComplete((ok, err) -> Platform.runLater(() -> {
                problem.setText(err == null ? "" : "! " + SaoSkinApp.message(err));
            }));
        });
    }

    /// Link Start inside the player window, bottom right. While the pack downloads or installs, only a progress bar
    /// across the row (James, 7 Nov: "那个就绪可以删了，下载的时候linkstart别显示然后只有进度条就行").
    private HBox buildLinkRow() {
        double bw = 246;
        Region track = new Region();
        track.getStyleClass().add("vr-track");
        track.setPrefSize(bw, 4);
        track.setMaxSize(bw, 4);
        fill.getStyleClass().add("vr-fill");
        fill.setPrefHeight(4);
        fill.setMaxHeight(4);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> {
            SaoPackState s = service.state().get();
            return s == SaoPackState.WORKING ? bw * Math.max(0.03, service.progress().get()) : 0.0;
        }, service.state(), service.progress()));
        StackPane track2 = new StackPane(track, fill);
        track2.setAlignment(Pos.CENTER_LEFT);
        track2.setMaxWidth(bw);
        // James, 7 Nov: "write more stuff on the progress bar while downloading": the step and how far over it,
        // the speed and how many files are left under it.
        Text step = SaoUiKit.ink("", 12), percent = dim("", 12), speed = dim("", 11), left = dim("", 11);
        percent.setFont(SaoAssets.sao(13, false));
        speed.setFont(SaoAssets.sao(12, false));
        HBox above = new HBox(6, step, grow(), percent);
        above.setAlignment(Pos.BASELINE_LEFT);
        HBox below = new HBox(6, speed, grow(), left);
        below.setAlignment(Pos.BASELINE_LEFT);
        VBox bar = new VBox(5, above, track2, below);
        bar.setMaxWidth(bw);
        bar.setAlignment(Pos.CENTER_LEFT);
        Runnable words = () -> {
            SaoPackState s = service.state().get();
            double p = service.progress().get();
            String label = service.stepLabel().get();
            step.setText(s == SaoPackState.CHECKING ? "检查文件" : label.isEmpty() ? "准备中" : label);
            percent.setText(p >= 0 ? Math.round(p * 100) + "%" : "");
            long bps = 0;
            int open = 0;
            for (SaoData.Task t : service.tasks()) {
                if (t.done()) continue;
                open++;
                bps += t.bytesPerSecond();
            }
            speed.setText(bps > 0 ? speedText(bps) : "");
            left.setText(open > 0 ? "剩 " + open + " 项" : "");
        };
        service.state().addListener((o, a, b) -> words.run());
        service.progress().addListener((o, a, b) -> words.run());
        service.stepLabel().addListener((o, a, b) -> words.run());
        service.tasks().addListener((ListChangeListener<SaoData.Task>) c -> words.run());
        words.run();
        start.getStyleClass().add("vr-start");
        start.setFont(ui(20, true));
        start.setMinWidth(Region.USE_PREF_SIZE);
        start.setOnAction(e -> linkStart());
        start.defaultButtonProperty().bind(signedIn.visibleProperty());
        // Installed: only the version beside Link Start ("安装完了那个进度条也删了，只剩v0.x").
        Text version = dim("", 12);
        version.setFont(SaoAssets.sao(13, false));
        StackPane.setAlignment(version, Pos.CENTER_LEFT);
        StackPane slot = new StackPane(bar, version, start);
        StackPane.setAlignment(start, Pos.CENTER_RIGHT);
        slot.setMinHeight(44);
        HBox.setHgrow(slot, Priority.ALWAYS);
        Runnable show = () -> {
            SaoPackState s = service.state().get();
            boolean busy = s == SaoPackState.WORKING || s == SaoPackState.CHECKING;
            bar.setVisible(busy);
            start.setVisible(!busy);
            @Nullable String v = service.installedVersion().get();
            version.setText("v" + (v != null ? v : service.embeddedVersion()));
            version.setVisible(!busy);
        };
        service.state().addListener((o, a, b) -> show.run());
        service.installedVersion().addListener((o, a, b) -> show.run());
        show.run();
        HBox row = new HBox(slot);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// The 游戏昵称 edited in place: the field with the name, save and cancel, and whether the name typed is free
    /// (names never collide: "名字不能撞，永远不能").
    private Node nameEditor(SaoData.OathAccount a) {
        TextField f = field("游戏昵称");
        f.setText(typedName != null ? typedName : a.name());
        typedName = null;
        f.setFont(SaoAssets.sao(20, true));
        HBox.setHgrow(f, Priority.ALWAYS);
        Button ok = round(SVG.CHECK, 28, 14), cancel = round(SVG.CLOSE, 28, 14);
        Text note = dim("", 11);
        Runnable close = () -> {
            editingName = false;
            refreshAccount();
        };
        Runnable save = () -> {
            String n = f.getText().strip();
            if (n.equals(a.name())) {
                close.run();
                return;
            }
            if (!SaoAccountApp.NAME.matcher(n).matches()) {
                problem.setText("! 昵称 3-16 位：字母、数字、下划线");
                return;
            }
            // James, 7 Nov: "确定的时候弹个确认框出来".
            askName(n, () -> {
                ok.setDisable(true);
                service.oathRename(n).whenComplete((v, err) -> Platform.runLater(() -> {
                    ok.setDisable(false);
                    if (err != null) {
                        problem.setText("! " + SaoSkinApp.message(err));
                        return;
                    }
                    problem.setText("");
                    close.run();
                }));
            });
        };
        ok.setOnAction(e -> save.run());
        cancel.setOnAction(e -> close.run());
        f.setOnAction(e -> save.run());
        f.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) close.run();
        });
        // Whether it is free, a moment after typing stops.
        PauseTransition wait = new PauseTransition(Duration.millis(350));
        wait.setOnFinished(e -> {
            String n = f.getText().strip();
            note.getStyleClass().setAll("vr-dim");
            if (n.equals(a.name())) {
                note.setText("");
            } else if (!SaoAccountApp.NAME.matcher(n).matches()) {
                note.setText("3-16 位：字母、数字、下划线");
            } else {
                service.oathNameAvailable(n).whenComplete((free, err) -> Platform.runLater(() -> {
                    if (!f.getText().strip().equals(n) || err != null) return;
                    note.getStyleClass().setAll(free ? "vr-dim" : "vr-warn");
                    note.setText(free ? "昵称可用" : "昵称被占用了，请换昵称再试");
                    ok.setDisable(!free);
                }));
                return;
            }
            ok.setDisable(false);
        });
        f.textProperty().addListener((o, x, y) -> {
            ok.setDisable(true);
            wait.playFromStart();
        });
        if (!f.getText().equals(a.name())) {
            ok.setDisable(true);
            wait.playFromStart();
        }
        Platform.runLater(() -> {
            f.requestFocus();
            f.selectAll();
        });
        HBox row = new HBox(6, f, ok, cancel);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(3, row, note);
    }

    /// Opens 好友 on the conversation with a friend (their name under the player's).
    private void openFriendChat(String name) {
        service.directChat(name).whenComplete((id, err) -> Platform.runLater(() -> {
            if (id != null) SaoFriendsApp.openFirst = id;
            for (int i = 0; i < SaoApps.APPS.length; i++)
                if (SaoApps.APPS[i].label().equals("好友")) openApp(SaoApps.APPS[i], dockButtons[i]);
        }));
    }

    /// Asks before saving a new 游戏昵称.
    private void askName(String name, Runnable yes) {
        Text sure = ink("确定把昵称改成", 16);
        sure.setFont(SaoUiKit.ui(16, true));
        Text n = sao(name, 30, true);
        Text after = dim("一天最多改 3 次", 12);
        confirmName.getChildren().setAll(sure, n, after, (Node) confirmName.getProperties().get("answers"));
        confirmNameAction = yes;
        confirmName.setVisible(true);
    }

    /// Shows 确定把昵称改成… (the screenshot tool).
    void forceNameConfirm(String name) {
        askName(name, () -> {
        });
    }

    /// Opens the name editor with a name typed (the screenshot tool).
    void forceNameEdit(String typed) {
        typedName = typed;
        editingName = true;
        refreshAccount();
    }

    /// One number on the player card: what it is small, the value in the SAO face, no box (James, 7 Nov: the three
    /// big tiles were too heavy, "给这三个雷霆大框换个表现形式").
    private static Node stat(String before, String value, String after) {
        HBox h = new HBox(4);
        h.setAlignment(Pos.BASELINE_LEFT);
        if (!before.isEmpty()) h.getChildren().add(dim(before, 11));
        h.getChildren().add(sao(value, 19, true));
        if (!after.isEmpty()) h.getChildren().add(dim(after, 11));
        return h;
    }

    /// A short upright line between stats.
    private static Node statRule() {
        Region r = new Region();
        r.getStyleClass().add("vr-rule");
        r.setMinSize(1, 14);
        r.setPrefSize(1, 14);
        r.setMaxSize(1, 14);
        return r;
    }

    /// When the game last ran, in a few words: 今天21:40, 昨天21:40, 3天前.
    private static String whenText(long epochMs) {
        java.time.ZoneId z = java.time.ZoneId.systemDefault();
        java.time.LocalDateTime t = java.time.Instant.ofEpochMilli(epochMs).atZone(z).toLocalDateTime();
        long days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), java.time.LocalDate.now(z));
        String hm = String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
        if (days <= 0) return "今天" + hm;
        if (days == 1) return "昨天" + hm;
        return days + "天前";
    }

    /// Play time: 12.7小时, under an hour 40分钟.
    private static String playText(long seconds) {
        if (seconds < 3600) return Math.max(seconds > 0 ? 1 : 0, seconds / 60) + "分钟";
        return String.format(java.util.Locale.ROOT, "%.1f小时", seconds / 3600.0);
    }

    /// A download speed in a few characters: 820 KB/s, 3.2 MB/s.
    private static String speedText(long bps) {
        if (bps < 1024 * 1024) return Math.max(1, bps / 1024) + " KB/s";
        return String.format(java.util.Locale.ROOT, "%.1f MB/s", bps / (1024.0 * 1024));
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
            // The dock stays turned, so its icons are drawn wider by what the turn takes off (see DOCK_WIDEN).
            cell.setScaleX(DOCK_WIDEN);
            tip.resizeRelocate(48, 5, 52, 26);
            b.hoverProperty().addListener((o, x, on) -> tip.setVisible(on));
            b.setOnAction(e -> {
                if (app != null && appButton == b) closeApp();
                else openApp(a, b);
            });
            dockButtons[i] = b;
            col.getChildren().add(cell);
        }
        SaoFloatWindow w = new SaoFloatWindow("", 60, SaoApps.APPS.length * 36 + (SaoApps.APPS.length - 1) * 6 + 16, null).add(col);
        w.body.setPadding(new Insets(8, 12, 8, 12));
        w.body.getStyleClass().add("vr-dock");
        // James, 8 Nov: first "左边那一系列icons都感觉拉扁了", then, when it was turned to face the eye, "我叫你左边icon不要很宽，你
        // 怎么直接给修平了": the dock keeps its turn and the icons are widened to look round again.
        return w.home(60, 184, -40, DOCK_TURN, 0);
    }

    /// The flat layer on the ear pad: the version at the bottom left, minimise and close at the bottom
    /// right, where the pack's helmet screen keeps its Skip and gear.
    private void buildPad() {
        pad.setManaged(false);
        pad.setPickOnBounds(false);
        pad.getTransforms().addAll(padShift, padFit);
        Text version = dim("刀剑神域·誓约 启动器 " + Metadata.VERSION + " · 基于 HMCL", 10);
        version.relocate(64, 676);
        Button minimize = round(SVG.MINIMIZE_CENTER, 30, 15);
        minimize.setOnAction(e -> Controllers.getDecorator().minimizeWindow());
        Button close = round(SVG.CLOSE, 30, 15);
        close.setOnAction(e -> Controllers.getDecorator().closeWindow());
        // At the top right, and on a Mac at the top left with X first, as Mac windows have them (James, 7 Nov:
        // "这三个上面按钮放右边（mac放左边然后X在左边").
        @Nullable String side = System.getProperty("sao.windowButtons");
        boolean mac = side != null ? side.equals("left")
                : org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS == org.jackhuang.hmcl.util.platform.OperatingSystem.MACOS;
        // No sound or music in the launcher (James, 8 Nov: "启动器里不要有任何音效", "启动器不要留音乐").
        HBox row = mac ? new HBox(6, close, minimize) : new HBox(6, minimize, close);
        row.relocate(mac ? 16 : EW - 16 - 66, 8);
        pad.getChildren().setAll(version, row);
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
        // Centred in the view (James, 8 Nov: "为什么所有东西又这么靠下不居中了").
        w.home(660, Math.max(96, (EH - w.height()) / 2 - 10), 0, 0, 0);
        windows.getChildren().add(w);
        w.flyIn(320);
        app = w;
        appButton = b;
        if (b != null) b.getStyleClass().add("vr-dock-on");
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) home.moveBack(300, 0.22, 320);
        requestFocus();
    }

    /// Opens the 誓约账号 window on a page.
    private void openAccount(SaoAccountApp.Page page) {
        openApp(new SaoApps.App(SVG.PERSON, "ARGUS ID", (s, c) -> SaoAccountApp.make(s, c, page)), null);
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
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) home.moveBack(0, 1, 320);
        // A skin put on or an account changed in the app shows on the player at once.
        refreshAccount();
    }

    // ------------------------------------------------------------------ frame

    /// Keeps the visor rendered at the window's size and minute, fits the space, turns it with the head and moves the glint.
    ///
    /// @param t seconds on the clock
    private void frame(double t) {
        double pw = getWidth(), ph = getHeight();
        // James, 8 Nov: "字体还是糊，不止字体，修全局的antialiasing". Inside the 3D world JavaFX rasterises glyphs and
        // edges at the model's scale, and a Retina screen's 2x only comes in with the camera, so everything was drawn
        // at half resolution and stretched. The world is built k times larger and shown at 1/k, and the visor
        // bitmaps are drawn at k, k being the screen's pixel scale.
        double k = getScene() != null && getScene().getWindow() != null
                ? Math.max(1, Math.max(getScene().getWindow().getOutputScaleX(), getScene().getWindow().getOutputScaleY())) : 1;
        int w = (int) Math.floor(Math.min(pw, ph * 16 / 9)), h = (int) Math.round(w * 9 / 16.0);
        if (w <= 1 || h <= 1) return;
        screen.resizeRelocate(Math.floor((pw - w) / 2), Math.floor((ph - h) / 2), w, h);
        screenClip.setWidth(w);
        screenClip.setHeight(h);
        long minute = System.currentTimeMillis() / 60000;
        int battery = SaoBattery.version;
        if (w != renderedW || h != renderedH || k != renderedK || minute != renderedMinute || battery != renderedBattery) {
            renderedBattery = battery;
            boolean resized = w != renderedW || h != renderedH || k != renderedK;
            renderedW = w;
            renderedH = h;
            renderedK = k;
            renderedMinute = minute;
            int iw = (int) Math.round(w * k), ih = (int) Math.round(h * k);
            if (resized) glass.setImage(SaoVisor.renderGlass(iw, ih).image());
            frameDark = SaoVisor.renderFrame(iw, ih, false).image();
            frameLit = SaoVisor.renderFrame(iw, ih, true).image();
            frame.setImage(poweredOn ? frameLit : frameDark);
            for (ImageView v : new ImageView[]{glass, frame}) {
                v.setFitWidth(w);
                v.setFitHeight(h);
            }
            world.setWidth(w * k);
            world.setHeight(h * k);
            worldShrink.setX(1 / k);
            worldShrink.setY(1 / k);
        }
        double s = Math.min(w / EW, h / EH);
        SaoFloatWindow.fitScale = s;
        fit.setX(s * k);
        fit.setY(s * k);
        fit.setZ(s * k);
        centre.setX(w * k / 2.0 - EW / 2 * s * k);
        centre.setY(h * k / 2.0 - EH / 2 * s * k);
        padFit.setX(s);
        padFit.setY(s);
        padShift.setX(w / 2.0 - EW / 2 * s);
        padShift.setY(h / 2.0 - EH / 2 * s);
        cover.resizeRelocate(0, 0, w, h);
        coverFit.setX(s);
        coverFit.setY(s);
        coverShift.setX(w / 2.0 - EW / 2 * s);
        coverShift.setY(h / 2.0 - EH / 2 * s);

        // The head turns about the eye: the space moves the other way, near windows more than far ones.
        double eye = -(h / 2.0) / Math.tan(HALF_FOV) / s;
        // James, 8 Nov: "为什么要乱晃" / "模拟呼吸不是这么模拟的". The view no longer follows the mouse or drifts
        // on two axes. It only breathes: a slow vertical rise and fall, inhale quicker than exhale, then a short rest.
        breath.setY(BREATH_PX * breath(t));
        turnY.setPivotX(EW / 2);
        turnY.setPivotY(EH / 2);
        turnY.setPivotZ(eye);
        turnX.setPivotX(EW / 2);
        turnX.setPivotY(EH / 2);
        turnX.setPivotZ(eye);
        turnY.setAngle(lookX * 3.2);
        turnX.setAngle(lookY * 2.4);
        if (glint.getBackground() == null) glint.setBackground(GLINT);

        black.resizeRelocate(0, 0, w, h);
        glass.relocate(0, 0);
        frame.relocate(0, 0);
        double r = 420 * s, gx = (((t * 0.045) % 1.6) - 0.3) * w, gy = h * 0.42;
        glint.resizeRelocate(gx - r, gy - r, 2 * r, 2 * r);
    }

    // ------------------------------------------------------------------ power

    /// Turns the display on: black lifts off the glass, the helmet's clock flickers on and the windows float in one
    /// after another.
    private void powerOn() {
        poweredOn = true;
        world.setOpacity(1);
        SaoFloatWindow[] order = {player, news, server, dock};
        for (SaoFloatWindow w : order) {
            w.glass.set(0);
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
        if (app != null) closeApp();
        for (SaoFloatWindow w : new SaoFloatWindow[]{news, server, player, dock}) w.moveBack(600, 0, 300);
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

    // ------------------------------------------------------------------ choices

    /// Starts on the account the launcher used last.
    /// Logs in with the remembered name and password, if any; the account shows as signed in at once and the
    /// password form comes back only when that fails.
    private void autoLogin() {
        @Nullable String r = service.rememberedOathName();
        if (r == null || service.oathAccount().get() != null) return;
        autoLogging = true;
        refreshAccount();
        service.oathLogin(r, null, true).whenComplete((ok, err) -> Platform.runLater(() -> {
            autoLogging = false;
            refreshAccount();
            if (err != null) problem.setText("! " + SaoSkinApp.message(err) + "，请重新登录");
        }));
    }

    /// Stars in the password box only while the name typed is the one whose password is remembered (signing out
    /// forgets it).
    private void refreshPrompt() {
        @Nullable String r = service.rememberedOathName();
        pw.setPromptText(r != null && name.getText().strip().equals(r) ? "●●●●●●  已记住" : "密码");
    }

    /// Shows the side that fits, signed out or signed in (or logging in), and the skin worn now.
    private void refreshAccount() {
        SaoData.@Nullable OathAccount a = service.oathAccount().get();
        boolean waiting = a == null && autoLogging;
        boolean in = a != null || waiting;
        signedOut.setVisible(!in);
        signedIn.setVisible(in);
        if (!in) {
            confirmOut.setVisible(false);
            refreshPrompt();
        }
        if (waiting) {
            @Nullable String r = service.rememberedOathName();
            signedIn.getChildren().setAll(sao("ARGUS ID", 15, true), sao(r == null ? "" : r, 30, true), dim("正在登录…", 12));
        } else if (a != null) {
            Button out = SaoUiKit.button("退出登录");
            out.setOnAction(e -> confirmOut.setVisible(true));
            // James, 7 Nov: "Sign out 右边加个Settings": the account's own settings.
            Button settings = SaoUiKit.button("设置");
            settings.setOnAction(e -> openAccount(SaoAccountApp.Page.MANAGE));
            HBox head = new HBox(8, sao("ARGUS ID", 15, true), out, settings);
            head.setAlignment(Pos.CENTER_LEFT);
            // James, 7 Nov: "让名字大一点", then "a bit too big now"; a long name shrinks to stay in the column.
            Text n = sao(a.name(), 38, true);
            double nw = n.getLayoutBounds().getWidth();
            if (nw > 216) n.setFont(ui(38 * 216 / nw, true));
            n.getStyleClass().add("vr-link-text");
            n.setOnMouseClicked(e -> openAccount(SaoAccountApp.Page.MANAGE));
            // The mailbox and Microsoft live in 设置 only (James, 7 Nov).
            // A pencil beside the name: it can be changed (James, 7 Nov), in 设置.
            // James, 7 Nov: "点击铅笔可以直接在那个界面换昵称".
            Button rename = round(SVG.EDIT, 26, 13);
            rename.setOnAction(e -> {
                editingName = true;
                refreshAccount();
            });
            HBox nameRow = new HBox(8, n, rename);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            signedIn.getChildren().setAll(head, editingName ? nameEditor(a) : nameRow);
            if (a.mustRename()) {
                Text taken = new Text("昵称已被正版玩家使用，请更换昵称");
                taken.getStyleClass().add("vr-warn");
                taken.setFont(SaoUiKit.ui(12, false));
                Button change = SaoAccountApp.link("更换", () -> {
                    editingName = true;
                    refreshAccount();
                });
                HBox takenRow = new HBox(6, taken, grow(), change);
                takenRow.setAlignment(Pos.CENTER_LEFT);
                signedIn.getChildren().add(takenRow);
            }
            // Under the name (James, 7 Nov, picked 1 3 4 5): LV, floor and Col; the last time in the game and the
            // play time; friends in the game now; and a red line while the mailbox waits for its code.
            SaoData.@Nullable PlayerCard card = service.playerCard().get();
            if (card != null) {
                // No floor (James, 7 Nov: "那就不显示floor").
                HBox tiles = new HBox(12,
                        stat("LV", card.level() < 0 ? "-" : Integer.toString(card.level()), ""), statRule(),
                        stat("", card.col() < 0 ? "-" : String.format(java.util.Locale.ROOT, "%,d", card.col()), "Col"));
                tiles.setAlignment(Pos.CENTER_LEFT);
                // James, 7 Nov: "上次进入：昨天22:18", "累计游玩时长：_._小时 （不到一小时，_分钟）".
                Text last = dim(card.lastPlayed() <= 0 ? "上次进入：还没进过游戏" : "上次进入：" + whenText(card.lastPlayed()), 12);
                Text total = dim("累计游玩时长：" + playText(card.playSeconds()), 12);
                HBox friends = new HBox(6);
                friends.setAlignment(Pos.CENTER_LEFT);
                if (card.friendsOnline().isEmpty()) {
                    friends.getChildren().add(dim("没有好友在线", 12));
                } else {
                    friends.getChildren().add(dim("好友在线", 12));
                    for (String f : card.friendsOnline().subList(0, Math.min(3, card.friendsOnline().size()))) {
                        HBox chip = new HBox(5, light(true), sao(f, 12, false));
                        chip.setAlignment(Pos.CENTER_LEFT);
                        chip.getStyleClass().addAll("vr-name", "vr-row");
                        chip.setOnMouseClicked(e -> openFriendChat(f));
                        friends.getChildren().add(chip);
                    }
                    if (card.friendsOnline().size() > 3) friends.getChildren().add(dim("+" + (card.friendsOnline().size() - 3), 12));
                }
                signedIn.getChildren().addAll(tiles, new VBox(2, last, total), friends);
            }
            if (!a.emailVerified()) {
                Text w = new Text(a.maskedEmail().isEmpty() ? "还没有邮箱，绑定后才能找回密码"
                        : a.locked() ? "邮箱没验证，验证后才能进游戏"
                        : "邮箱待验证 · 还剩 " + SaoAccountApp.hours(a.graceSeconds()));
                w.getStyleClass().add("vr-warn");
                w.setFont(SaoUiKit.ui(12, false));
                Button go = SaoAccountApp.link(a.maskedEmail().isEmpty() ? "去设置" : "去验证",
                        () -> openAccount(a.maskedEmail().isEmpty() ? SaoAccountApp.Page.MANAGE : SaoAccountApp.Page.VERIFY));
                HBox warnRow = new HBox(6, w, grow(), go);
                warnRow.setAlignment(Pos.CENTER_LEFT);
                signedIn.getChildren().add(warnRow);
            }
        }
        problem.setText("");
        @Nullable Path worn = service.oathSkin() != null ? service.oathSkin() : service.skin();
        skin.show(worn, service.skinSlim());
    }

    /// The status line and Link Start from the engine.
    private void refreshState() {
        SaoPackState s = service.state().get();
        @Nullable String err = service.lastError().get();
        if (s == SaoPackState.FAILED && err != null) problem.setText("! " + err);
        else if (!problem.getText().startsWith("!")) problem.setText("");
        start.setDisable(s == SaoPackState.RUNNING);
    }

    /// 登录: the name and the password (or the remembered one).
    private void login() {
        if (autoLogging) {
            problem.setText("! 正在登录，稍等");
            return;
        }
        String n = name.getText().strip();
        // James, 8 Nov: "登录的时候可以Username/邮箱 登录".
        boolean mail = n.indexOf('@') > 0 && n.length() <= 254;
        if (!mail && !SaoAccountApp.NAME.matcher(n).matches()) {
            problem.setText("! 填用户名或邮箱");
            return;
        }
        @Nullable String remembered = service.rememberedOathName();
        char @Nullable [] p = pw.getText().isEmpty() && n.equals(remembered) ? null : pw.getText().toCharArray();
        if (p != null && p.length == 0) {
            problem.setText("! 请输入密码");
            return;
        }
        pw.clear();
        login.setDisable(true);
        problem.setText("");
        service.oathLogin(n, p, remember.isSelected()).whenComplete((ok, err) -> Platform.runLater(() -> {
            if (p != null) java.util.Arrays.fill(p, '\0');
            login.setDisable(false);
            if (err != null) problem.setText("! " + SaoSkinApp.message(err));
        }));
    }

    /// Shows 离线游玩 in place of the sign-in.
    private void askOffline() {
        if (offlineName.getText().isBlank() && !name.getText().isBlank()) offlineName.setText(name.getText().strip());
        confirmOffline.setVisible(true);
        offlineName.requestFocus();
    }

    /// Link Start, as the signed-in ARGUS ID.
    private void linkStart() {
        SaoData.@Nullable OathAccount a = service.oathAccount().get();
        if (a == null) {
            problem.setText(autoLogging ? "! 正在登录，稍等" : "! 先登录 ARGUS ID");
            return;
        }
        joinAs(a);
    }

    /// Starts with the signed-in 誓约 account, unless its mailbox check ran out.
    private void joinAs(SaoData.OathAccount a) {
        if (a.mustRename()) {
            problem.setText("! 昵称已被正版玩家使用，请更换昵称");
            editingName = true;
            refreshAccount();
            return;
        }
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
        // James, 8 Nov: "为什么没有任何一个可以拖动整个窗口的地方？": a press on the empty space around the windows moves the
        // launcher; a press on a window, the character or a button never does ("现在steve焊上面了": the decorator's
        // hover test let a drag on the character move the whole window). The page does this itself, by where the
        // press went, instead of through the decorator's hover flag.
        windows.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        pad.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        cover.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = false);
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            moving = !pressOnWindow && e.isPrimaryButtonDown() && getCursor() == null && getScene() != null
                    && getScene().getWindow() instanceof javafx.stage.Stage st && !st.isFullScreen() && !st.isMaximized();
            if (!moving) return;
            javafx.stage.Window win = getScene().getWindow();
            moveFromX = win.getX() - e.getScreenX();
            moveFromY = win.getY() - e.getScreenY();
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!moving || getScene() == null) return;
            javafx.stage.Window win = getScene().getWindow();
            win.setX(e.getScreenX() + moveFromX);
            win.setY(e.getScreenY() + moveFromY);
            e.consume();
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> moving = false);
    }

    /// The page on screen, for the skin editor's cover.
    private static @Nullable SaoHelmetPage current;
    /// A large flat panel over the whole helmet (the skin editor), with the helmet dimmed behind it.
    private final StackPane cover = new StackPane();
    /// The cover's panel in design pixels (1280 × 720), fitted like the pad.
    private final javafx.scene.layout.Pane coverDesign = new javafx.scene.layout.Pane();
    private final Translate coverShift = new Translate();
    private final Scale coverFit = new Scale(1, 1);

    /// Shows a panel over the whole helmet (James, 8 Nov: "编辑页面大点（可以覆盖头盔）").
    static void cover(Region panel) {
        SaoHelmetPage p = current;
        if (p == null) return;
        panel.setManaged(false);
        panel.resizeRelocate(36, 34, EW - 72, EH - 68);
        p.coverDesign.getChildren().setAll(panel);
        p.cover.setVisible(true);
        p.cover.setOpacity(0);
        new Timeline(new KeyFrame(Duration.millis(160), new KeyValue(p.cover.opacityProperty(), 1, Interpolator.EASE_OUT))).play();
    }

    /// Takes the cover away.
    static void uncover() {
        SaoHelmetPage p = current;
        if (p == null || !p.cover.isVisible()) return;
        Timeline t = new Timeline(new KeyFrame(Duration.millis(140), new KeyValue(p.cover.opacityProperty(), 0, Interpolator.EASE_IN)));
        t.setOnFinished(e -> {
            p.cover.setVisible(false);
            p.coverDesign.getChildren().clear();
        });
        t.play();
    }

    /// Whether the last press landed on a window or the pad, and whether it is moving the launcher window.
    private boolean pressOnWindow, moving;
    /// The window's corner relative to the pointer when a move began.
    private double moveFromX, moveFromY;

    // ------------------------------------------------------------------ tools

    /// Shows 确定退出吗？ (screenshot tools).
    void forceConfirmOut() {
        confirmOut.setVisible(true);
    }

    /// Opens the 誓约账号 window on a page (screenshot tools).
    void forceAccount(SaoAccountApp.Page page) {
        openAccount(page);
    }

    /// Opens the server's other nodes (the screenshot tool).
    void forceNodes() {
        openNodes.run();
    }

    /// What 其他节点 does, set by the server window.
    private Runnable openNodes = () -> {
    };

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
        for (SaoFloatWindow w : new SaoFloatWindow[]{news, server, player, dock}) {
            w.recede.set(0);
            w.glass.set(1);
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
            for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) {
                home.recede.set(0);
                home.glass.set(1);
            }
            return;
        }
        openApp(SaoApps.APPS[i], dockButtons[i]);
    }

    /// The breath at time `t`, 0 at rest to 1 at the top: inhale over 40 % of the breath, exhale over 50 %, rest 10 %.
    private static double breath(double t) {
        double p = (t % BREATH_S) / BREATH_S;
        if (p < 0.4) return 0.5 - 0.5 * Math.cos(Math.PI * p / 0.4);
        if (p < 0.9) return 0.5 + 0.5 * Math.cos(Math.PI * (p - 0.4) / 0.5);
        return 0;
    }

    /// Turns the head (screenshot tools).
    void forceHead(double x, double y) {
        headX = lookX = x;
        headY = lookY = y;
    }

    /// The player's character, turning slowly in HMCL's 3D skin view and turned by dragging.
    /// Which way a drag turns and tips the character (checked by dragging the real window).
    static double TURN_SIGN = 1, TILT_SIGN = -1;

    private static final class Character extends StackPane {
        /// HMCL's skin model.
        private final Turntable canvas = new Turntable();
        /// Where a drag started, and the turn then.
        private double pressX, pressY, pressYaw, pressTilt;

        /// HMCL's model, turned only about its own upright axis and tipped a little, never rolled
        /// (James, 8 Nov: "你的这个3d皮肤拖动鼠标的这个很搞笑，根本没做好反人类").
        private static final class Turntable extends SkinCanvas {
            /// The camera's tip, after its turn: up and down tip the view about the screen's horizontal whichever side
            /// faces you (James, 8 Nov: "上下划怎么是反的", "像个pendulum一样").
            private final javafx.scene.transform.Rotate pitch = new javafx.scene.transform.Rotate(0, javafx.scene.transform.Rotate.X_AXIS);

            Turntable() {
                super(TexturesLoader.getDefaultSkinImage(), 150, 214, true);
                camera.getTransforms().add(camera.getTransforms().indexOf(yRotate) + 1, pitch);
            }

            double yaw() {
                return yRotate.getAngle();
            }

            double tilt() {
                return pitch.getAngle();
            }

            void turn(double yaw, double tilt) {
                yRotate.setAngle(yaw);
                // The camera faces the front from yaw 180, where its own horizontal is the model's reversed, so a
                // camera tip of +a looks like the model's old tilt of +a.
                pitch.setAngle(Math.max(-20, Math.min(20, tilt)));
                xRotate.setAngle(0);
                zRotate.setAngle(0);
            }
        }

        /// Builds it with the default skin.
        Character() {
            // James, 8 Nov: "史蒂夫神秘跑步": HMCL's preview broke into a run at random; only the slight arm sway is left.
            canvas.getAnimationPlayer().addSkinAnimation(new SkinAniWavingArms(100, 2000, 7.5, canvas));
            // The front follows the hand: dragging right turns the side under the pointer to the right.
            canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                pressX = e.getScreenX();
                pressY = e.getScreenY();
                pressYaw = canvas.yaw();
                pressTilt = canvas.tilt();
                e.consume();
            });
            canvas.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
                canvas.turn(pressYaw + TURN_SIGN * (e.getScreenX() - pressX) * 0.7, pressTilt + TILT_SIGN * (e.getScreenY() - pressY) * 0.25);
                e.consume();
            });
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
