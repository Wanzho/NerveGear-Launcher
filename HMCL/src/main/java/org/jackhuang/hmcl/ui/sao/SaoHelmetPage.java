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
import org.jackhuang.hmcl.sao.SaoHandoff;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.application.Platform;
import org.jackhuang.hmcl.ui.FXUtils;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.sao.SaoReadiness;
import org.jackhuang.hmcl.sao.SaoServices;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
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
    /// Builds the helmet home windows and handles their account and friend controls.
    private final SaoHelmetDashboard dashboard = new SaoHelmetDashboard(this);

    /// The design space the windows are laid out in (the episode picture at depth 0).
    private static final double EW = 1280, EH = 720;
    /// The dock's turn: its right edge away, so it leans toward facing the eye (JavaFX's Rotate, see DOCK_WIDEN).
    private static final double DOCK_TURN = -18;
    /// The dock's centre x and depth in the design space.
    private static final double DOCK_X = 60, DOCK_Z = -40;
    /// Half the camera's vertical field of view, radians.
    private static final double HALF_FOV = Math.toRadians(15);
    /// How much the dock's icons are scaled across so they look round on the turned dock (James, 8 Nov: "wide
    /// putin"). JavaFX's Rotate of a about Y sends the window's x axis to (cos a, 0, -sin a): DOCK_TURN = -18 turns
    /// the dock's right edge away, toward facing the eye. The eye sits at x 640, z -360 / tan 15 = -1343.6, so the
    /// dock's centre is X = -580 across and Z = 1303.6 deep from it. A small width there is seen
    /// (cos a + X sin a / Z) = cos 18 + 580 sin 18 / 1303.6 = 1.089 times its height: wider, not narrower (the old
    /// comment had the sign of the second term reversed and widened them by 1 / 0.814 on top). Measured on the
    /// 1920 x 1080 shot before the fix: gear 1.29, info 1.33 with that 1.23 widening, so 1.08 from the turn alone;
    /// after it (0.919): gear 0.96 / 0.97, info 1.04 / 0.97, download 1.00 / 0.96 at 1920 / 2560 wide, within a pixel.
    static final double DOCK_WIDEN = 1 / (Math.cos(Math.toRadians(DOCK_TURN))
            + (DOCK_X - EW / 2) * Math.sin(Math.toRadians(DOCK_TURN)) / (EH / 2 / Math.tan(HALF_FOV) + DOCK_Z));
    /// The engine.
    final SaoLauncherService service;
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
    /// How far the view sinks at the top of a breath, in design pixels.
    private static final double BREATH_PX = 2.6;
    /// One breath, in seconds (about 13 a minute at rest).
    private static final double BREATH_S = 4.6;
    /// Switches for measuring what each moving part costs (-Dsao.glint=over|add|off, -Dsao.breathe=false,
    /// -Dsao.sway=false); the defaults are the look James approved.
    private static final String GLINT_MODE = System.getProperty("sao.glint", "over");
    /// Off since 14 Nov (James: "呼吸很奇怪，还是最好跟着鼠标来吧"); -Dsao.breathe=true brings it back.
    private static final boolean BREATHE = "true".equals(System.getProperty("sao.breathe"));
    /// The helmet view follows the mouse only when asked for (James, 15 Nov: 画面不动).
    static final boolean FOLLOW = "true".equals(System.getProperty("sao.follow"));
    /// Turn at the window's edge (mouse 0.5 from the centre), in degrees.
    private static final double TURN_Y_DEG = 1.1, TURN_X_DEG = 0.8;
    static final boolean SWAY = !"false".equals(System.getProperty("sao.sway"));
    /// The reflection texture under `/assets/sao-launcher/glint/` (-Dsao.glintTex=double|corner|arc): soft sheens on
    /// the glass and a faint rim along its edge, outshone over the lit plates so their text reads as before.
    private static final String GLINT_TEXTURE = System.getProperty("sao.glintTex", "double").replaceAll("[^a-z]", "");
    /// The windows, back to front.
    private final Group windows = new Group();
    /// The flat layer on the ear pad (version and window buttons), in design coordinates.
    private final Pane pad = new Pane();
    private final Scale padFit = new Scale(1, 1, 0, 0);
    /// The 16:9 picture everything is drawn in, and its clip.
    private final Pane screen = new Pane();
    private final Rectangle screenClip = new Rectangle();
    /// High-resolution still used only while the window size is changing; it never receives input.
    private final ImageView resizePreview = new ImageView();
    /// Applies the preview's resize to the hidden live nodes too, keeping their hit targets aligned.
    private final Scale resizeFit = new Scale(1, 1, 0, 0);
    /// Last requested preview size and the monotonic time it changed.
    private int resizeWidth, resizeHeight;
    /// Nanoseconds of the most recent size change during a preview.
    private long resizeChangedAt;
    /// Quiet interval before restoring the exact live rendering at the final size.
    private static final long RESIZE_QUIET_NS = 150_000_000L;
    /// Shows the k-times world at the window's size (see [#frame(double)]).
    private final Scale worldShrink = new Scale(1, 1, 0, 0);
    /// The pixel scale the visor and world were last made for.
    private double renderedK;
    private final Translate padShift = new Translate();
    /// The still reflection on the glass, sized again only when the window is resized.
    private final ImageView glint = new ImageView();
    /// Black over everything for power-down.
    private final Region black = new Region();

    /// The home windows.
    private final SaoFloatWindow news, player, server, dock;
    /// The power-on and the hand-over to the game.
    private final SaoBoot boot;
    /// App windows asked for during the power-on, opened when it ends.
    /// The Link Start hand-over to the game's visor (SaoBoot.putAway). Off until its risks are worked through (James,
    /// 13 Nov: "你风险考虑的还不够多，在此之前只把启动器开启的这个东西先给我"); off, Link Start powers the display down
    /// and the engine hides the launcher at game start, as before.
    static final boolean HANDOFF = false;
    private final List<Runnable> afterBoot = new java.util.ArrayList<>();
    /// The app window open in front, or null.
    private @Nullable SaoFloatWindow app;
    /// The 整合包 / 启动器更新 window while it is the app in front.
    private @Nullable SaoFloatWindow packWin;
    /// Whether the first-run 安装整合包 window has been shown (once per start).
    private boolean firstRunShown;
    /// The launch waiting for RUNNING, so the display powers down only when the game is really up.
    private javafx.beans.value.@Nullable ChangeListener<SaoPackState> pendingLaunch;
    /// The status line text last set from a state, cleared when that state ends.
    private @Nullable String autoProblem;
    /// The dock button of the open app.
    private @Nullable Button appButton;
    /// The dock's buttons.
    final Button[] dockButtons = new Button[SaoApps.APPS.length];

    /// Mouse position in parts of the window, -0.5 to 0.5, answered on the same frame.
    private double headX, headY, lookX, lookY;
    /// Global pointer reader so the windows keep following the mouse outside the launcher window.
    private javafx.scene.robot.@Nullable Robot pointerRobot;
    /// Optional global-pointer override (screen coordinates) for deterministic off-screen tests.
    java.util.function.@Nullable Supplier<Point2D> pointerOverride;
    /// Whether the display is on.
    private boolean poweredOn;

    /// The ARGUS ID side of the player window, drawn by James (7 Nov, two sketches): signed out, "ARGUS ID 未登录",
    /// the name and password, 记住 with 注册 and 忘记密码, 登录, a line, 使用 Microsoft; signed in, "ARGUS ID [退出登录]",
    /// the name large, the mailbox and Microsoft each with 验证, and Link Start at the bottom right.
    // The grey hint like 密码's (James, 14 Nov: "用户名这个里面没有灰掉的用户名输入的地方").
    final TextField name = field("用户名／邮箱");
    final PasswordField pw = SaoAccountApp.password("");
    final ToggleButton remember = new ToggleButton();
    final Button login = SaoUiKit.accent("登录");
    final Button useMicrosoft = SaoUiKit.button("使用 Microsoft 登录");
    final VBox signedOut = new VBox(10);
    final VBox signedIn = new VBox(10);
    /// Logging in with the remembered password on opening: the player sees the account, not a password form
    /// (James, 7 Nov: "登录进去就直接显示已登录，别再显示一整个密码界面").
    boolean autoLogging;
    /// The 游戏昵称 being edited in place (the pencil beside the name).
    boolean editingName;
    /// A name to start the editor with instead of the current one (the screenshot tool).
    @Nullable String typedName;
    /// 确定改成…吗？ over the player's body, before a new 游戏昵称 is saved.
    final VBox confirmName = new VBox(10);
    /// What 确定 on [#confirmName] does.
    Runnable confirmNameAction = () -> {
    };
    /// 确定退出吗？ over the player's body.
    final VBox confirmOut = new VBox(10);
    /// 离线游玩: the name and the warning, in place of the body.
    final VBox confirmOffline = new VBox(11);
    /// The name for 离线游玩.
    final javafx.scene.control.TextField offlineName = SaoUiKit.field("游戏里的名字");
    /// The character.
    final SaoHelmetCharacter skin = new SaoHelmetCharacter();
    /// Link Start and its lines.
    final Button start = new Button("Link Start");
    final Text problem = new Text();
    /// Direct recovery action beside missing-drive and disk-space errors.
    final Button locationRecovery = button("重新选择游戏位置");
    final Region fill = new Region();

    /// Builds the page with the installed engine, or a stand-in when none is installed yet.
    public SaoHelmetPage() {
        this(serviceOrFake());
    }

    /// Builds the page for an engine.
    ///
    /// @param service the engine
    public SaoHelmetPage(SaoLauncherService service) {
        this.service = service;
        // 微软正版登录 (James, 13 Nov: "为什么不直接做个新界面"): every Microsoft sign-in opens our own window, never
        // HMCL's dialog. The first flow is the one already running; 重试 starts a fresh one from the engine.
        // A click anywhere but the focused text field takes the focus off it (James, 14 Nov: "选中一个textbox后点击旁边
        // 取消不了textbox的选中"). Another field or a button then takes the focus itself, as before.
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            @Nullable javafx.scene.Scene sc = getScene();
            @Nullable Node owner = sc == null ? null : sc.getFocusOwner();
            if (!(owner instanceof javafx.scene.control.TextInputControl)) return;
            for (@Nullable Node n = e.getPickResult().getIntersectedNode(); n != null; n = n.getParent())
                if (n == owner) return;
            requestFocus();
        });
        // Microsoft first, ARGUS behind it (James, 14 Nov: "可以先直连微软吗"): while ARGUS links, the status line says so.
        service.microsoftIdentity().addListener((o, was, now) -> Platform.runLater(this.dashboard::refreshAccount));
        service.oathLinking().addListener((o, was, now) -> Platform.runLater(() -> {
            if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.FAILED) startupPending = false;
            dashboard.refreshAccount();
            String linking = now.reason().isEmpty() ? "正在连接 ARGUS…" : now.reason();
            if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKING) problem.setText(linking);
            else if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKED
                    && (problem.getText().startsWith("正在连接") || problem.getText().startsWith("正在用 Microsoft"))) problem.setText("");
        }));
        org.jackhuang.hmcl.sao.SaoMicrosoftFlow.setPresenter((start, cancel) ->
                openApp(new SaoApps.App(SVG.PERSON, "微软正版登录",
                        (s, close) -> SaoMicrosoftApp.make(start, close, cancel)), null));
        // The head follows the mouse (James, 14 Nov), measured from the page centre, -0.5..0.5 on each axis; drags
        // (window, character) count too, so the view never jumps when a drag ends.
        javafx.event.EventHandler<MouseEvent> look = e -> {
            headX = clampHead(e.getX() / Math.max(1, getWidth()) - 0.5);
            headY = clampHead(e.getY() / Math.max(1, getHeight()) - 0.5);
        };
        addEventFilter(MouseEvent.MOUSE_MOVED, look);
        addEventFilter(MouseEvent.MOUSE_DRAGGED, look);
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
        // James, 8 Nov: "请优化". Added light (BlendMode.ADD) made JavaFX read the screen back from the graphics card
        // under the glint every frame, so it is drawn over as one cached picture.
        // James, 8 Nov: "光泽留着但不动，变成一块固定的反光。这个吧", then "调亮到看得见吧", then "你直接一个贴图糊上去
        // 不行吗". The glint is a still reflection texture laid over the glass (see [#GLINT_TEXTURE]), sized only on
        // resize, so no frame touches it.
        glint.setSmooth(true);
        if (!"off".equals(GLINT_MODE)) glint.setImage(SaoAssets.image("glint/" + GLINT_TEXTURE + ".png"));
        if ("add".equals(GLINT_MODE)) glint.setBlendMode(BlendMode.ADD);
        glint.setCache(true);
        glint.setVisible(!"off".equals(GLINT_MODE));
        black.setStyle("-fx-background-color: black;");
        black.setMouseTransparent(true);

        world.setManaged(false);
        world.setFill(Color.TRANSPARENT);
        world.getTransforms().add(worldShrink);
        world.setCamera(new PerspectiveCamera(false));
        // The breath moves the finished picture of the space (world's translate, see [#frame(double)]), not the space.
        // The floating windows turn with the head; the dock stands directly in space like a visor HUD so it stays put.
        space.getTransforms().addAll(centre, fit);
        windows.getTransforms().addAll(turnY, turnX);

        news = dashboard.buildNews();
        server = dashboard.buildServer();
        player = dashboard.buildPlayer();
        dock = buildDock();
        windows.getChildren().addAll(news, server, player);
        space.getChildren().addAll(windows, dock);
        boot = new SaoBoot(new SaoBoot.Page() {
            @Override
            public SaoFloatWindow[] windows() {
                return new SaoFloatWindow[]{dock, news, player, server};
            }

            @Override
            public Node chrome() {
                return pad;
            }

            @Override
            public Node[] visor() {
                return new Node[]{glass, world, frame, pad, glint};
            }
        });
        // The engine's hide at game start waits for the hand-over (James, 13 Nov).
        if (HANDOFF) SaoHandoff.install(hide -> boot.holdHide(() -> {
            handedOver();
            hide.run();
        }), this::cancelHandoff);
        boot.windowFade().addListener((o, a, f) -> {
            if (getScene() != null && getScene().getWindow() != null) getScene().getWindow().setOpacity(1 - f.doubleValue());
        });
        if (HANDOFF) service.observeGameLog(event -> boot.readLog(event, service.legacyHelmetSignal()));

        buildPad();
        // The window style James picked (6 Nov, from four): the anime's light menu panels with orange highlights.
        getStyleClass().add("theme-sao");
        space.getStyleClass().add("theme-sao");
        // James, 7 Nov: "make sure only 16:9 is available": the helmet is one 16:9 picture, centred, black around it
        // when the window or the screen is another shape.
        screen.setManaged(false);
        screen.getTransforms().add(resizeFit);
        resizePreview.setManaged(false);
        resizePreview.setMouseTransparent(true);
        resizePreview.setSmooth(true);
        resizePreview.setVisible(false);
        screen.setClip(screenClip);
        current = this;
        cover.setManaged(false);
        cover.setVisible(false);
        cover.setStyle("-fx-background-color: rgba(0,0,0,0.55);");
        cover.getStyleClass().add("theme-sao");
        cover.getStylesheets().add(css);
        // 外观 (James, 9 Nov: "你设计个深色的版本？", then "按跟随系统/时间来定"): decided here, before the first
        // frame, then kept up live: the system's own change, the window regaining focus, and a check every minute
        // (the 19:00 / 07:00 boundary and systems JavaFX cannot watch).
        sceneProperty().addListener((o, a, b) -> {
            if (a != null) a.getRoot().getStyleClass().remove(DARK);
            applyDark();
            // HMCL's own dialogs in this window take the SAO face too.
            if (b != null && !b.getStylesheets().contains(SaoDialogs.fontSheet())) b.getStylesheets().add(SaoDialogs.fontSheet());
            // ... and are drawn at the SAO windows' scale.
            if (b != null) {
                try {
                    SaoDialogs.scaleDialogs(Controllers.getDecorator().getDialogContainer());
                } catch (NullPointerException noMainWindow) {
                    // Off-screen tools: no main window, they scale their own container.
                }
            }
            if (b != null) b.windowProperty().addListener((o2, w0, w) -> {
                if (w != null) w.focusedProperty().addListener((o3, was, now) -> {
                    if (now) refreshTheme(true);
                });
            });
        });
        themeMode = SaoTheme.Mode.of(service.preference(THEME_KEY, SaoTheme.Mode.SYSTEM.key));
        refreshTheme(false);
        if (FXUtils.DARK_MODE != null) FXUtils.DARK_MODE.addListener((o, a, b) -> refreshTheme(false));
        javafx.animation.Timeline themeTick = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.minutes(1), e -> refreshTheme(true)));
        themeTick.setCycleCount(javafx.animation.Animation.INDEFINITE);
        themeTick.play();
        coverDesign.getTransforms().addAll(coverShift, coverFit);
        coverDesign.setPickOnBounds(false);
        cover.getChildren().add(new Group(coverDesign));
        StackPane.setAlignment(cover.getChildren().get(0), Pos.TOP_LEFT);
        characterLayer.setManaged(false);
        characterLayer.setPickOnBounds(false);
        characterBox.getTransforms().addAll(characterAt, characterFit);
        screen.getChildren().setAll(glass, world, characterLayer, frame, pad, boot.dim(), cover, glint, boot.layer, black);
        // Friend messages, requests and invites that arrive while the launcher is in the background.
        toast.setManaged(false);
        toast.setVisible(false);
        toast.setOnMouseClicked(e -> { toast.setVisible(false); openFriendsApp(); });
        screen.getChildren().add(toast);
        service.notification().addListener((o, a, n) -> { if (n != null) showNotification(n); });
        getChildren().setAll(screen, resizePreview);

        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && app != null) closeApp();
        });
        ticker.start();

        service.state().addListener((o, a, b) -> onState(a, b));
        service.progress().addListener((o, a, b) -> refreshState());
        service.stepLabel().addListener((o, a, b) -> refreshState());
        service.lastError().addListener((var o, @Nullable var a, @Nullable var b) -> {
            refreshState();
            if (b != null && !b.isBlank()) SaoDialogs.failure(service, b);
        });
        javafx.application.Platform.runLater(() -> SaoDialogs.previousCrash(service));
        service.updateStatus().addListener((o, a, b) -> refreshState());
        service.launcherUpdate().addListener((o, a, b) -> refreshState());
        service.installProgress().addListener((o, a, b) -> refreshState());
        service.installedVersion().addListener((o, a, b) -> refreshState());
        SaoPackApp.checks().addListener((o, a, b) -> refreshState());
        SaoPackApp.runChecks(service);
        // Files dropped anywhere on the page go to the backend (packs, quick updates, launcher files; any order, any
        // batch); the 整合包 window comes up to show what happened. Windows with their own drops (光影) keep theirs.
        addEventHandler(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            if (e.getDragboard().hasFiles() && !SaoPackApp.blocked()) e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
        });
        addEventHandler(javafx.scene.input.DragEvent.DRAG_DROPPED, e -> {
            List<Path> files = SaoPackApp.files(e);
            if (files.isEmpty() || SaoPackApp.blocked()) return;
            boolean jars = files.stream().allMatch(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
            if (app == null || app != packWin) openPack(jars ? SaoPackApp.Mode.LAUNCHER : SaoPackApp.Mode.PACK);
            SaoPackApp.accept(service, files);
            e.setDropCompleted(true);
            e.consume();
        });
        refreshState();
        Timeline ping = new Timeline(new KeyFrame(Duration.seconds(60), e -> service.refreshOnline()));
        ping.setCycleCount(Timeline.INDEFINITE);
        ping.play();
        service.refreshOnline();

        SaoBattery.start();
        sceneProperty().addListener((o, a, b) -> {
            checkAwake();
            if (b == null) return;
            b.windowProperty().addListener((oo, was, win) -> watchWindow(win));
            watchWindow(b.getWindow());
            hookWindow();
            powerOn();
            measuringHooks();
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

    /// The player, in the middle: the logo over the character on the left, the ARGUS ID on the right as James drew it
    /// (7 Nov); Link Start lives here too, at the bottom right once signed in. The skin has its own page (James, 7 Nov:
    /// "把classic slim等等塞到一个新的页面").
    /// James, 13 Nov: "中间图标先去掉吧，我不喜欢在那里": the logo under the friends is hidden for now; its room stays
    /// empty so Link Start keeps its place.
    static final boolean SHOW_PLAYER_LOGO = false;

    /// The player window's size and its parts (James, 8 Nov: "中间这个界面，里面东西搞稍微大点，留白有点多").
    static final double PLAYER_W = 468, PLAYER_H = 420, STAGE_W = 168, STAGE_H = 300, GAP = 16,
            RIGHT_W = PLAYER_W - 32 - STAGE_W - GAP, FIELD_H = 40, BUTTON_H = 40;

    /// The player card's friends line, when friends are online (screenshot checks).
    @Nullable FriendsLine friendsLine;

    /// One line of online friends under the player's name: the label, as many name chips as fit, then a +N chip for
    /// the rest. Laid out by hand so it never wraps or pushes the column wider.
    static final class FriendsLine extends Region {
        private static final double GAP_X = 6;
        private final Node label;
        private final List<Node> chips;
        final Node more;
        private final Text moreText;
        /// How many chips the last layout showed (screenshot checks).
        int shown;

        FriendsLine(Node label, List<Node> chips, Node more, Text moreText) {
            this.label = label;
            this.chips = chips;
            this.more = more;
            this.moreText = moreText;
            getChildren().add(label);
            getChildren().addAll(chips);
            getChildren().add(more);
            setMinWidth(0);
        }

        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefHeight(double width) {
            double h = label.prefHeight(-1);
            for (Node c : getChildren()) h = Math.max(h, c.prefHeight(-1));
            return h + snappedTopInset() + snappedBottomInset();
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth(), h = getHeight(), x = 0;
            x = place(label, x, h) + GAP_X;
            int n = chips.size(), k = 0;
            for (; k < n; k++) {
                double cw = chips.get(k).prefWidth(-1);
                int rest = n - k - 1;
                double need = cw + (rest > 0 ? GAP_X + moreWidth(rest) : 0);
                if (x + need > w) break;
                chips.get(k).setVisible(true);
                x = place(chips.get(k), x, h) + GAP_X;
            }
            for (int i = k; i < n; i++) chips.get(i).setVisible(false);
            shown = k;
            more.setVisible(k < n);
            if (k < n) {
                moreWidth(n - k);
                place(more, x, h);
            }
        }

        private double moreWidth(int rest) {
            String t = "+" + rest;
            if (!t.equals(moreText.getText())) moreText.setText(t);
            more.applyCss();
            return more.prefWidth(-1);
        }

        private static double place(Node n, double x, double h) {
            double pw = n.prefWidth(-1), ph = n.prefHeight(-1);
            n.resizeRelocate(x, Math.round((h - ph) / 2), pw, ph);
            return x + pw;
        }
    }

    /// Shows 确定把昵称改成… (the screenshot tool).
    void forceNameConfirm(String name) {
        dashboard.askName(name, () -> {
        });
    }

    /// Opens the name editor with a name typed (the screenshot tool).
    void forceNameEdit(String typed) {
        typedName = typed;
        editingName = true;
        dashboard.refreshAccount();
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
            // The dock stays turned, so its icons are scaled across by what the turn adds (see DOCK_WIDEN).
            cell.setScaleX(DOCK_WIDEN);
            tip.resizeRelocate(48, 5, 52, 26);
            SaoMotion.revealOne(tip);
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
        // 怎么直接给修平了": the dock keeps its turn and the icons are scaled across to look round again.
        return w.home(DOCK_X, 184, DOCK_Z, DOCK_TURN, 0);
    }

    /// The flat layer on the ear pad: the version at the bottom left, minimise and close at the bottom
    /// right, where the pack's helmet screen keeps its Skip and gear.
    private void buildPad() {
        pad.setManaged(false);
        pad.setPickOnBounds(false);
        pad.getTransforms().addAll(padShift, padFit);
        Text version = dim(SaoUi.LAUNCHER_NAME + " " + Metadata.VERSION + (Metadata.SAO_BETA ? " Beta" : ""), 10);
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
    void openApp(SaoApps.App a, @Nullable Button b) {
        // An app opens once the power-on is over (the first start's 安装整合包 window, a file dropped meanwhile).
        if (boot.busy()) {
            afterBoot.add(() -> openApp(a, b));
            return;
        }
        if (app != null) {
            SaoFloatWindow old = app;
            old.close(() -> windows.getChildren().remove(old));
        }
        if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
        SaoFloatWindow w = a.make().make(service, this::closeApp);
        packWin = a.label().equals(PACK_LABEL) ? w : null;
        // Centred in the view (James, 8 Nov: "为什么所有东西又这么靠下不居中了").
        w.home(660, Math.max(96, (EH - w.height()) / 2 - 10), 0, 0, 0);
        windows.getChildren().add(w);
        // Opens and closes like a window in the game (James, 14 Nov: "窗口打开我们不是做了吗": the interface's MenuMotion).
        w.open();
        w.contentIn(SaoFloatWindow.contentMs());
        app = w;
        appButton = b;
        if (b != null) b.getStyleClass().add("vr-dock-on");
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) home.moveBack(300, 0.22, 320);
        requestFocus();
    }

    /// The 整合包 window's dock label (it has no dock button).
    private static final String PACK_LABEL = "整合包";

    /// Opens the 整合包 / 启动器更新 window (it replaces another app; Esc closes it).
    void openPack(SaoPackApp.Mode mode) {
        if (boot.busy()) {
            afterBoot.add(() -> openPack(mode));
            return;
        }
        // Rebuilding an open pack window would stop its Downloads watch and drop the quick updates gathered so far.
        if (app != null && app == packWin && mode == packMode) { requestFocus(); return; }
        packMode = mode;
        openApp(new SaoApps.App(SVG.DOWNLOAD, PACK_LABEL, (s, c) -> SaoPackApp.make(s, c, mode)), null);
    }

    /// Mode of the open 整合包 window, compared before reopening it.
    private SaoPackApp.@Nullable Mode packMode;

    /// One announcement in its own window; another one replaces it, Esc closes it.
    ///
    /// @param n the announcement
    void openNews(SaoData.News n) {
        openApp(new SaoApps.App(SVG.CHAT, "公告", (s, c) -> SaoNewsApp.make(n, c)), null);
    }

    /// Opens the 誓约账号 window on a page.
    void openAccount(SaoAccountApp.Page page) {
        openApp(new SaoApps.App(SVG.PERSON, "ARGUS ID", (s, c) -> SaoAccountApp.make(s, c, page)), null);
    }

    /// Opens the skin page, as its dock button does.
    void openSkin() {
        for (int i = 0; i < SaoApps.APPS.length; i++)
            if (SaoApps.APPS[i].label().equals("皮肤")) openApp(SaoApps.APPS[i], dockButtons[i]);
    }

    /// Closes the app in front; the home windows come forward again.
    private void closeApp() {
        @Nullable SaoFloatWindow w = app;
        if (w == null) return;
        app = null;
        packWin = null;
        if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
        appButton = null;
        w.close(() -> windows.getChildren().remove(w));
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) home.moveBack(0, 1, 320);
        // A skin put on or an account changed in the app shows on the player at once.
        dashboard.refreshAccount();
    }

    // ------------------------------------------------------------------ frame

    /// Keeps the visor rendered at the window's size and minute, fits the space, turns it with the head and, on resize, places the glint.
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
        if (frozenPicture) {
            double pictureScale = Math.min(pw / frozenWidth, ph / frozenHeight);
            screen.setScaleX(pictureScale); screen.setScaleY(pictureScale);
            w = (int) frozenWidth; h = (int) frozenHeight;
        }
        if (w <= 1 || h <= 1) return;
        if (previewResize(w, h, k)) return;
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
            if (resized) {
                // The still reflection covers the glass, which is the 16:9 screen.
                glint.setFitWidth(w);
                glint.setFitHeight(h);
                glint.relocate(0, 0);
            }
        }
        double s = Math.min(w / EW, h / EH);
        SaoFloatWindow.fitScale = s;
        SaoDialogs.setScale(s);
        SaoUiKit.setWindowDensity(s * k);
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
        // James, 14 Nov: "我想要游戏内的效果" and "在窗口外动的话，里面这东西也不会动": poll the global cursor relative to
        // the window (clamped to [-0.5, 0.5] outside) and answer on this exact frame with no trailing ease.
        // James, 15 Nov: "试一版干脆去掉任何抖动的" (asked: 画面不动): by default the helmet view stays still; no
        // mouse follow, no breathing. -Dsao.follow=true brings the same-frame follow back.
        if (FOLLOW) {
            pollPointer(pw, ph);
            lookX = headX;
            lookY = headY;
        } else {
            lookX = 0;
            lookY = 0;
        }
        // Only the finished picture moves (James, 8 Nov: "请优化"): the space stays still, so JavaFX keeps its 3D
        // render and only shifts it, in whole screen pixels so the text stays sharp.
        world.setTranslateY(BREATHE ? Math.round(s * BREATH_PX * breath(t) * k) / k : 0);
        turnY.setPivotX(EW / 2);
        turnY.setPivotY(EH / 2);
        turnY.setPivotZ(eye);
        turnX.setPivotX(EW / 2);
        turnX.setPivotY(EH / 2);
        turnX.setPivotZ(eye);
        // James, 14 Nov: "为什么要invert" / "为什么要动的幅度这么大", then (asked) "画面跟着鼠标走": the picture moves the
        // same way as the mouse on BOTH axes (mouse right → windows right, mouse up → windows up), small.
        // SaoHelmetTurnTest checks both directions through the same Rotate setup.
        turnY.setAngle(turnYDeg(lookX));
        turnX.setAngle(turnXDeg(lookY));

        black.resizeRelocate(0, 0, w, h);
        boot.fit(w, h, s);
        glass.relocate(0, 0);
        frame.relocate(0, 0);
        placeCharacter();
    }

    /// Reuses one high-resolution picture through a resize gesture, then returns to the original live renderer.
    /// JavaFX resizes a SubScene's render texture even when its design coordinates stay fixed; that repeatedly
    /// rasterizes text on different pixel grids. The still avoids those changes and the visor's expensive bitmap
    /// rebuilds. Hidden live nodes retain picking, with the same scale as the still. Boot frames remain live.
    ///
    /// @return true when the preview supplies this frame
    private boolean previewResize(int w, int h, double k) {
        boolean active = resizePreview.isVisible();
        boolean changed = w != renderedW || h != renderedH;
        if (!boot.busy() && k == renderedK && renderedW > 1 && renderedH > 1 && (active || changed)) {
            if (!active) {
                javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
                params.setFill(Color.TRANSPARENT);
                // Headroom for enlargement during a gesture, in addition to the display's pixel density.
                double density = Math.max(2, k * 1.5);
                params.setTransform(new Scale(density, density));
                params.setViewport(new javafx.geometry.Rectangle2D(0, 0, renderedW * density, renderedH * density));
                double oldX = screen.getLayoutX(), oldY = screen.getLayoutY();
                screen.setLayoutX(0);
                screen.setLayoutY(0);
                try {
                    resizePreview.setImage(screen.snapshot(params, null));
                } finally {
                    screen.setLayoutX(oldX);
                    screen.setLayoutY(oldY);
                }
                resizePreview.setVisible(true);
                screen.setOpacity(0);
                resizeWidth = w;
                resizeHeight = h;
                resizeChangedAt = System.nanoTime();
            }
            if (w != resizeWidth || h != resizeHeight) {
                resizeWidth = w;
                resizeHeight = h;
                resizeChangedAt = System.nanoTime();
            }
            if (System.nanoTime() - resizeChangedAt < RESIZE_QUIET_NS) {
                double x = Math.floor((getWidth() - w) / 2), y = Math.floor((getHeight() - h) / 2);
                resizePreview.setFitWidth(w);
                resizePreview.setFitHeight(h);
                resizePreview.relocate(x, y);
                screen.setLayoutX(x);
                screen.setLayoutY(y);
                resizeFit.setX(w / (double) renderedW);
                resizeFit.setY(h / (double) renderedH);
                return true;
            }
        }
        if (resizePreview.isVisible()) {
            resizePreview.setVisible(false);
            resizePreview.setImage(null);
            resizeFit.setX(1);
            resizeFit.setY(1);
            screen.setOpacity(1);
        }
        return false;
    }

    /// The character's stage inside the player window, and the character itself with its pencil.
    final StackPane characterSlot = new StackPane(), characterBox = new StackPane();
    /// The flat layer over the 3D space that holds the character while the player window rests.
    private final Pane characterLayer = new Pane();
    private final Translate characterAt = new Translate();
    private final Scale characterFit = new Scale(1, 1, 0, 0);

    /// Puts the character where its stage is seen. James, 8 Nov: "这个启动器很消耗吗", "请优化": the character's slight arm
    /// sway inside the 3D space made JavaFX draw the whole space again every frame. While the player window rests
    /// (lit, at home, nothing in front) the character is drawn on a flat layer just over the space, at the stage's
    /// place, size and light, so only the character is drawn again; the player window never turns, so its stage is
    /// seen as a plain rectangle. Otherwise it goes back into the window and moves with it.
    private void placeCharacter() {
        boolean flat = app == null && !cover.isVisible() && player.resting() && world.getOpacity() >= 0.999;
        double light = 1;
        for (Node n = characterSlot; n != null && n != space; n = n.getParent()) {
            if (!n.isVisible()) flat = false;
            light *= n.getOpacity();
        }
        if (light < 0.999) flat = false;
        Point2D a = null, b = null;
        if (flat) {
            // Laid out first, so the place is this frame's, not the last one's (a clean window returns at once).
            player.layout();
            a = characterSlot.localToScene(0, 0, true);
            b = characterSlot.localToScene(STAGE_W, STAGE_H, true);
            if (a == null || b == null) flat = false;
        }
        if (!flat) {
            if (characterBox.getParent() != characterSlot) {
                characterAt.setX(0);
                characterAt.setY(0);
                characterFit.setX(1);
                characterFit.setY(1);
                characterSlot.getChildren().setAll(characterBox);
                player.held.set(false);
            }
            return;
        }
        if (characterBox.getParent() != characterLayer) characterLayer.getChildren().setAll(characterBox);
        a = screen.sceneToLocal(a);
        b = screen.sceneToLocal(b);
        // Set only when it moved: an unchanged layer is not drawn again.
        double k = (b.getX() - a.getX()) / STAGE_W;
        if (Math.abs(characterAt.getX() - a.getX()) > 0.01) characterAt.setX(a.getX());
        if (Math.abs(characterAt.getY() - a.getY()) > 0.01) characterAt.setY(a.getY());
        if (Math.abs(characterFit.getX() - k) > 1e-4) {
            characterFit.setX(k);
            characterFit.setY(k);
        }
        player.held.set(characterBox.isHover());
    }

    /// Runs [#frame(double)] every frame while the launcher can be seen.
    private final AnimationTimer ticker = new AnimationTimer() {
        @Override
        public void handle(long now) {
            frame(now / 1e9);
        }
    };
    /// Whether the frame and the character's sway run.
    private boolean awake = true;
    /// The window listened to.
    private javafx.stage.@Nullable Window watched;

    /// Follows the window: minimised or hidden, nothing moves (James, 8 Nov: "请优化").
    private void watchWindow(javafx.stage.@Nullable Window win) {
        if (win == null || win == watched) {
            checkAwake();
            return;
        }
        watched = win;
        win.showingProperty().addListener((o, a, b) -> { checkAwake(); reportAttention(win); });
        win.focusedProperty().addListener((o, a, b) -> reportAttention(win));
        if (win instanceof javafx.stage.Stage st) st.iconifiedProperty().addListener((o, a, b) -> { checkAwake(); reportAttention(win); });
        checkAwake();
        reportAttention(win);
    }

    /// Tells the service whether the player can see the launcher, so social notifications are raised only when not.
    private void reportAttention(javafx.stage.Window win) {
        boolean visible = win.isShowing() && !(win instanceof javafx.stage.Stage st && st.isIconified());
        service.setLauncherAttention(visible, visible && win.isFocused());
    }

    /// Current diagnostic backend, absent before the main page is constructed.
    static @Nullable SaoLauncherService diagnosticService() {
        @Nullable SaoHelmetPage page = current();
        return page == null ? null : page.service;
    }

    /// Brief in-window confirmation without requesting OS focus.
    static void exportToast(String message) {
        @Nullable SaoHelmetPage page = current();
        if (page == null) return;
        page.toast.setText(message);
        page.toast.applyCss();
        page.toast.relocate((EW - page.toast.getLayoutBounds().getWidth()) / 2, 46);
        page.toast.setVisible(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(8));
        hide.setOnFinished(e -> page.toast.setVisible(false));
        hide.play();
    }

    /// One line at the top of the helmet for a background social event (click opens 好友), plus a taskbar/Dock
    /// attention request where the system supports it; the window is never brought to the front.
    private void showNotification(SaoData.Notification n) {
        String what = switch (n.kind()) {
            case MESSAGE -> n.title() + "：" + n.preview();
            case FRIEND_REQUEST -> n.title() + " 想加你为好友";
            case INVITE -> n.title() + " 邀请你一起玩";
        };
        toast.setText(what.length() > 60 ? what.substring(0, 60) + "…" : what);
        toast.applyCss();
        toast.relocate((EW - toast.getLayoutBounds().getWidth()) / 2, 46);
        toast.setVisible(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(8));
        hide.setOnFinished(e -> { if (toast.getText().equals(what.length() > 60 ? what.substring(0, 60) + "…" : what)) toast.setVisible(false); });
        hide.play();
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Taskbar.isTaskbarSupported()
                    && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.USER_ATTENTION))
                java.awt.Taskbar.getTaskbar().requestUserAttention(true, false);
        } catch (RuntimeException | LinkageError ignored) { /* The in-window line is enough. */ }
    }

    /// Opens the 好友 app from the dock.
    private void openFriendsApp() {
        for (int i = 0; i < SaoApps.APPS.length; i++)
            if (SaoApps.APPS[i].label().equals("好友")) openApp(SaoApps.APPS[i], dockButtons[i]);
    }

    /// The background social event line.
    private final Text toast = dim("", 13);

    /// Wakes or stills the page. With no window at all (the screen-shot tools) it stays awake.
    private void checkAwake() {
        javafx.stage.@Nullable Window win = getScene() == null ? null : getScene().getWindow();
        setAwake(getScene() != null && (win == null
                || win.isShowing() && !(win instanceof javafx.stage.Stage st && st.isIconified())));
    }

    /// Starts or stops the frame and the character's sway.
    private void setAwake(boolean on) {
        if (on == awake) return;
        awake = on;
        if (on) {
            ticker.start();
            skin.resume();
        } else {
            ticker.stop();
            skin.pause();
        }
    }

    // ------------------------------------------------------------------ power

    /// Turns the display on: black lifts off the glass, the helmet's clock flickers on and the windows float in one
    /// after another.
    private void powerOn() {
        poweredOn = true;
        world.setOpacity(1);
        frame.setImage(frameLit);
        black.setOpacity(0);
        windowNormal();
        // James, 13 Nov: the boot he approved (see SaoBoot), played in full every time ("2.9秒跳啥").
        boot.open(() -> {
            List<Runnable> queued = new java.util.ArrayList<>(afterBoot);
            afterBoot.clear();
            queued.forEach(Runnable::run);
        });
    }

    /// The power-on and the hand-over, for the screenshot tool.
    SaoBoot boot() {
        return boot;
    }

    /// The launcher window as it is outside the hand-over: opaque, not kept over other windows.
    private void windowNormal() {
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) {
            st.setOpacity(1);
            st.setAlwaysOnTop(false);
        }
    }

    /// Stage bounds and page geometry captured before Link Start (including maximized letterboxing).
    private @Nullable javafx.geometry.Rectangle2D originalBounds, alignmentTarget;
    /// Original maximize flag, restored after hand-over, cancellation, or releasing the cover.
    private boolean originalMaximized;
    /// Minimum sizes temporarily relaxed for DPI-scaled game content, restored with stage bounds.
    private double originalMinWidth, originalMinHeight;
    /// Captured picture dimensions; while alignment runs only its outer scale changes.
    private double frozenWidth, frozenHeight;
    /// Whether frame() is preserving the captured picture layout.
    private boolean frozenPicture;
    /// True only after an interpolation call actually changed stage geometry.
    private boolean boundsApplied;

    /// True from Link Start through completion/cancel; decorator and page drags share this gate.
    public static boolean handoverLocked() { return current != null && current.boot.handoff.active(); }

    /// Captures a target without moving the stage. Claude owns calling applyStageBounds(t) from the timeline.
    /// Fullscreen games, native-fullscreen launchers and other monitors cannot cover the primary game:
    /// retain existing put-away/loading, never move or stay on top, and hide at readiness instead.
    public java.util.Optional<javafx.geometry.Rectangle2D> targetStageBounds() {
        if (originalBounds != null) return java.util.Optional.ofNullable(alignmentTarget);
        if (getScene() == null || !(getScene().getWindow() instanceof javafx.stage.Stage st))
            return java.util.Optional.empty();
        originalBounds = new javafx.geometry.Rectangle2D(st.getX(), st.getY(), st.getWidth(), st.getHeight());
        originalMaximized = st.isMaximized();
        originalMinWidth = st.getMinWidth(); originalMinHeight = st.getMinHeight();
        javafx.stage.Screen primary = javafx.stage.Screen.getPrimary();
        javafx.geometry.Rectangle2D monitor = primary.getBounds();
        var game = org.jackhuang.hmcl.sao.SaoGameWindowGeometry.content(primary, service.gameWindow());
        if (game.isEmpty() || !org.jackhuang.hmcl.sao.SaoGameWindowGeometry.alignable(
                monitor, originalBounds, st.isFullScreen(), service.gameWindow()))
            return java.util.Optional.empty();
        @Nullable javafx.geometry.Bounds pageBounds = localToScreen(getLayoutBounds());
        @Nullable javafx.geometry.Bounds pictureBounds = screen.localToScreen(screen.getLayoutBounds());
        if (pageBounds == null || pictureBounds == null || pictureBounds.getWidth() <= 0) return java.util.Optional.empty();
        alignmentTarget = org.jackhuang.hmcl.sao.SaoGameWindowGeometry.target(originalBounds,
                rectangle(pageBounds), rectangle(pictureBounds), game.get());
        frozenWidth = screen.getWidth(); frozenHeight = screen.getHeight();
        return java.util.Optional.of(alignmentTarget);
    }

    /// Converts measured scene bounds into the pure geometry API.
    private static javafx.geometry.Rectangle2D rectangle(javafx.geometry.Bounds b) {
        return new javafx.geometry.Rectangle2D(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }

    /// Straight stage interpolation only; deliberately owns no visual timing or animation.
    public void applyStageBounds(double t) {
        if (!boot.handoff.active() || boot.handoff.released() || alignmentTarget == null || originalBounds == null
                || getScene() == null || !(getScene().getWindow() instanceof javafx.stage.Stage st)) return;
        frozenPicture = true;
        boundsApplied = true;
        st.setMaximized(false);
        st.setMinWidth(0); st.setMinHeight(0);
        setStageBounds(st, org.jackhuang.hmcl.sao.SaoGameWindowGeometry.interpolate(originalBounds, alignmentTarget, t));
    }

    /// Applies all four logical bounds; no outer game frame/title-bar inset is guessed.
    private static void setStageBounds(javafx.stage.Stage st, javafx.geometry.Rectangle2D r) {
        st.setWidth(r.getWidth()); st.setHeight(r.getHeight()); st.setX(r.getMinX()); st.setY(r.getMinY());
    }

    /// Restores the exact captured stage bounds and maximized flag, and unlocks picture layout.
    public void restoreStageBounds() {
        if (boundsApplied && originalBounds != null && getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) {
            if (!st.isFullScreen()) {
                st.setMaximized(false);
                setStageBounds(st, originalBounds);
                st.setMinWidth(originalMinWidth); st.setMinHeight(originalMinHeight);
                st.setMaximized(originalMaximized);
            }
        }
        originalBounds = alignmentTarget = null;
        frozenPicture = false;
        boundsApplied = false;
        screen.setScaleX(1); screen.setScaleY(1);
    }

    /// Lets the user remove the cover without cancelling readiness or changing visibility policy.
    public void releaseCover() {
        if (!boot.handoff.active()) return;
        boot.handoff.release();
        restoreStageBounds();
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) {
            st.setAlwaysOnTop(false);
            st.setIconified(true);
        }
    }

    /// Restores the stage on early exit or failed preparation, including pending put-away.
    private void cancelHandoff() {
        boot.cancel();
        restoreStageBounds();
        windowNormal();
    }

    /// Link Start: the windows put themselves away and the launcher stays over the game's window until the game shows
    /// its own visor (see SaoBoot); kept on top meanwhile so the game's first windows open behind it.
    private void linkStartOut() {
        if (!HANDOFF) {
            powerDown();
            return;
        }
        boot.pending();
        boolean align = targetStageBounds().isPresent() && !boot.handoff.released();
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) st.setAlwaysOnTop(align);
        if (app == null) {
            boot.putAway();
            return;
        }
        // An app in front closes first and the home windows come back; then they all go together.
        closeApp();
        PauseTransition p = new PauseTransition(Duration.millis(340));
        p.setOnFinished(e -> {
            if (service.state().get() == SaoPackState.RUNNING && boot.handoff.active()) boot.putAway();
        });
        p.play();
    }

    /// The screenshot tool: closes the app in front.
    /// For the screenshot tool: the account panel again, as after a service event.
    void debugRefreshAccount() {
        dashboard.refreshAccount();
    }

    void debugCloseApp() {
        closeApp();
    }

    /// The launcher has left the game's screen: dark until it comes back.
    private void handedOver() {
        restoreStageBounds();
        poweredOn = false;
        black.setOpacity(1);
        frame.setImage(frameDark);
        windowNormal();
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
        if (a == SaoPackState.WORKING && b == SaoPackState.READY) SaoPackApp.refreshChecks(service);
        if (a == SaoPackState.RUNNING && b != SaoPackState.RUNNING) {
            cancelHandoff();
            PauseTransition p = new PauseTransition(Duration.millis(200));
            p.setOnFinished(e -> { if (service.state().get() != SaoPackState.RUNNING && !boot.handoff.active()) powerOn(); });
            p.play();
        }
    }

    // ------------------------------------------------------------------ choices

    /// Starts on the account the launcher used last.
    /// Logs in with the remembered name and password, if any; the account shows as signed in at once and the
    /// password form comes back only when that fails.
    void autoLogin() {
        @Nullable String r = service.rememberedOathName();
        if (r == null || service.oathAccount().get() != null) return;
        autoLogging = true;
        dashboard.refreshAccount();
        service.oathLogin(r, null, true).whenComplete((ok, err) -> Platform.runLater(() -> {
            autoLogging = false;
            if (err != null) startupPending = false;
            dashboard.refreshAccount();
            if (err != null) problem.setText("! " + SaoSkinApp.message(err) + "，请重新登录");
        }));
    }

    /// Stars in the password box only while the name typed is the one whose password is remembered (signing out
    /// forgets it).
    void refreshPrompt() {
        @Nullable String r = service.rememberedOathName();
        // James, 8 Nov: "密码的那个点可以小一点": the prompt says it in words, no row of big dots.
        pw.setPromptText(r != null && name.getText().strip().equals(r) ? "已记住密码" : "密码");
    }

    /// The signed-in side last shown: null signed out, "" logging in, else the name (and whether it was being renamed).
    private @Nullable String shownAccount;
    /// The big name drawn last, for its success glow.
    @Nullable Text shownNameText;
    /// The "正在登录…" line breathing now.
    private @Nullable Node busyLine;

    /// Moves the signed-in side only when it changes, never on a plain refresh (James, 14 Nov: "login时，成功等"):
    /// logging in comes in and breathes; signed in (from out, logging in, or another name) comes in row after row and
    /// the name lands with a glow; a rename shows its editor coming in.
    void accountMotion(boolean waiting, SaoData.@Nullable OathAccount a) {
        @Nullable String now = a != null ? a.name() + (editingName ? "\u0000" : "") : waiting ? "" : null;
        @Nullable String was = shownAccount;
        shownAccount = now;
        if (now == null || now.equals(was)) return;
        if (busyLine != null) {
            SaoMotion.idle(busyLine);
            busyLine = null;
        }
        if (now.isEmpty()) {
            SaoMotion.stagger(signedIn, 0);
            busyLine = signedIn.getChildren().get(signedIn.getChildren().size() - 1);
            SaoMotion.busy(busyLine);
            return;
        }
        boolean renaming = was != null && !was.isEmpty() && a != null
                && was.replace("\u0000", "").equals(a.name());
        if (renaming) {
            if (signedIn.getChildren().size() > 1) SaoMotion.enter(signedIn.getChildren().get(1));
            return;
        }
        SaoMotion.stagger(signedIn, 0);
        if (!editingName && shownNameText != null) SaoMotion.success(shownNameText, 90);
    }

    /// A Microsoft identity is known and ARGUS is still linking it, with no account yet.
    boolean microsoftSigningIn() {
        return service.oathAccount().get() == null && service.microsoftIdentity().get() != null
                && service.oathLinking().get().kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKING;
    }

    /// Whether the last session's snapshot is shown until the startup sign-in answers.
    boolean startupPending;
    /// Preference key of the signed-in panel snapshot.
    static final String SNAPSHOT_KEY = "account.snapshot";

    /// The signed-in panel as last shown: name, card and skin file. No token, password or email address.
    record Snapshot(String name, String profileId, @Nullable String premiumName, int level, long col,
                            long lastPlayed, long playSeconds, @Nullable String skin, boolean slim) {
        SaoData.OathAccount account() { return new SaoData.OathAccount(name, profileId, "", true, false, 0, premiumName); }

        SaoData.PlayerCard card() { return new SaoData.PlayerCard(level, 0, col, lastPlayed, playSeconds, List.of()); }
    }

    /// The status line and Link Start from the engine.
    private void refreshState() {
        SaoPackState s = service.state().get();
        @Nullable String err = service.lastError().get();
        SaoData.UpdateStatus u = service.updateStatus().get();
        org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
        @Nullable String block = blockText();
        // Messages that follow a state go when the state goes (a launcher update installed, a BLOCK cleared).
        @Nullable String auto = u.launcherBlocked() ? "! " + u.message() : block != null ? "! " + block
                : s == SaoPackState.FAILED && ip.stage() == org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.FAILED && !ip.failures().isEmpty()
                ? "! 下载失败 · " + ip.failures().size() + " 个文件" + (err != null ? " · " + err : "")
                : s == SaoPackState.FAILED && err != null ? "! " + err : null;
        if (auto != null) {
            problem.setText(auto);
            autoProblem = auto;
        } else if (!problem.getText().startsWith("!") || problem.getText().equals(autoProblem)
                || problem.getText().equals("! 未安装整合包") && !SaoPackApp.notInstalled(service)) {
            problem.setText("");
            autoProblem = null;
        }
        boolean missingLocation = service.readiness().get().action() == org.jackhuang.hmcl.sao.SaoReadiness.Action.NONE
                && service.readiness().get().reason().contains("找不到");
        boolean locationProblem = missingLocation || err != null && err.contains("空间不够");
        locationRecovery.setText(missingLocation ? "重新选择游戏位置" : "换个盘");
        locationRecovery.setVisible(locationProblem); locationRecovery.setManaged(locationProblem);
        start.setDisable(missingLocation);
        // First run with no pack: the 安装整合包 window comes up by itself, once.
        if (!missingLocation && !firstRunShown && s != SaoPackState.CHECKING && SaoPackApp.notInstalled(service)) {
            firstRunShown = true;
            Platform.runLater(() -> {
                if (app == null && SaoPackApp.notInstalled(service)) openPack(SaoPackApp.Mode.PACK);
            });
        }
    }

    /// The first BLOCK of the system check, if any.
    private static @Nullable String blockText() {
        @Nullable List<SaoData.SystemCheck> c = SaoPackApp.checks().get();
        if (c != null) for (SaoData.SystemCheck k : c) if (k.level() == SaoData.CheckLevel.BLOCK) return k.text();
        return null;
    }

    /// The progress across the Link row: measured file or byte counts from the backend when it has them.
    double fraction() {
        org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
        if (ip.stage() != org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE) {
            if (ip.totalBytes() > 0) return Math.min(1, (double) ip.bytesDone() / ip.totalBytes());
            if (ip.totalFiles() > 0) return Math.min(1, (double) ip.completedFiles() / ip.totalFiles());
        }
        return service.progress().get();
    }

    /// Whether a launch has to wait: no pack, a launcher below the minimum, or a BLOCK check. Says why and opens
    /// the window that fixes it.
    private boolean gated(boolean offline) {
        SaoData.UpdateStatus u = service.updateStatus().get();
        if (!offline && u.launcherBlocked()) {
            problem.setText("! " + u.message());
            openPack(SaoPackApp.Mode.LAUNCHER);
            return true;
        }
        @Nullable String block = blockText();
        if (block != null) {
            problem.setText("! " + block);
            return true;
        }
        if (SaoPackApp.notInstalled(service)) {
            problem.setText("! 未安装整合包");
            openPack(SaoPackApp.Mode.PACK);
            return true;
        }
        return false;
    }

    /// 登录: the name and the password (or the remembered one).
    void login() {
        if (autoLogging) {
            problem.setText("! 正在登录，稍等");
            return;
        }
        String n = name.getText().strip();
        // James, 8 Nov: "登录的时候可以Username/邮箱 登录".
        boolean mail = n.indexOf('@') > 0 && n.length() <= 254;
        if (!mail && !SaoAccountApp.NAME.matcher(n).matches()) {
            problem.setText("! 填用户名或邮箱");
            SaoMotion.shake(name);
            return;
        }
        @Nullable String remembered = service.rememberedOathName();
        char @Nullable [] p = pw.getText().isEmpty() && n.equals(remembered) ? null : pw.getText().toCharArray();
        if (p != null && p.length == 0) {
            problem.setText("! 请输入密码");
            SaoMotion.shake(pw);
            return;
        }
        pw.clear();
        login.setDisable(true);
        login.setText("登录中…");
        SaoMotion.busy(login);
        problem.setText("");
        service.oathLogin(n, p, remember.isSelected()).whenComplete((ok, err) -> Platform.runLater(() -> {
            if (p != null) java.util.Arrays.fill(p, '\0');
            SaoMotion.idle(login);
            login.setText("登录");
            login.setDisable(false);
            if (err != null) {
                problem.setText("! " + SaoSkinApp.message(err));
                SaoMotion.shake(signedOut);
            }
        }));
    }

    /// Shows 离线游玩 in place of the sign-in.
    void askOffline() {
        if (offlineName.getText().isBlank() && !name.getText().isBlank()) offlineName.setText(name.getText().strip());
        confirmOffline.setVisible(true);
        offlineName.requestFocus();
    }

    /// The main button: Link Start when ready; otherwise 下载 / 更新, which only prepares and needs no account.
    void pressMain() {
        SaoReadiness r = service.readiness().get();
        // Still checking: Link Start, which checks again before launching.
        if (r.ready() || r.kind() == SaoReadiness.Kind.CHECKING) {
            linkStart();
            return;
        }
        SaoData.UpdateStatus u = service.updateStatus().get();
        if (u.launcherBlocked()) {
            problem.setText("! " + u.message());
            openPack(SaoPackApp.Mode.LAUNCHER);
            return;
        }
        @Nullable String block = blockText();
        if (block != null) {
            problem.setText("! " + block);
            return;
        }
        problem.setText("");
        if (r.action() == SaoReadiness.Action.PACK_WINDOW) openPack(SaoPackApp.Mode.PACK);
        else service.download();
    }

    /// Link Start, as the signed-in ARGUS ID.
    private void linkStart() {
        @Nullable String clockProblem = org.jackhuang.hmcl.sao.SaoClockCheck.problem();
        if (clockProblem != null) { problem.setText("! " + clockProblem); return; }
        SaoData.@Nullable OathAccount a = service.oathAccount().get();
        if (a == null) {
            problem.setText(autoLogging || microsoftSigningIn() ? "! 正在登录，稍等" : "! 先登录 ARGUS ID");
            return;
        }
        joinAs(a);
    }

    /// Starts with the signed-in 誓约 account, unless its mailbox check ran out.
    private void joinAs(SaoData.OathAccount a) {
        if (a.mustRename()) {
            problem.setText("! 昵称已被正版玩家使用，请更换昵称");
            editingName = true;
            dashboard.refreshAccount();
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
    private void begin(Runnable launch) { begin(launch, false); }

    /// Offline entry retains local safety checks without requiring an online launcher upgrade.
    void begin(Runnable launch, boolean offline) {
        if (gated(offline)) return;
        // James, 14 Nov: "按linkstart没有反应……我根本不知道". A click while something runs says what is running.
        SaoPackState now = service.state().get();
        if (now == SaoPackState.RUNNING) { launch.run(); return; }
        if (now == SaoPackState.WORKING || now == SaoPackState.CHECKING) {
            org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
            String doing = now == SaoPackState.CHECKING ? "检查文件" : !ip.label().isBlank() ? ip.label() : service.stepLabel().get();
            notice("! 正在" + (doing.isBlank() ? "准备" : doing) + "，完成后再按 Link Start");
            return;
        }
        if (HANDOFF) {
            boot.pending();
            targetStageBounds();
            moving = false;
        }
        // The display powers down only once the engine reports the game RUNNING: a gate the engine applies (launcher
        // minimum, account ticket) leaves the helmet on with its message.
        if (pendingLaunch != null) service.state().removeListener(pendingLaunch);
        javafx.beans.value.ChangeListener<SaoPackState> l = new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends SaoPackState> o, SaoPackState a, SaoPackState b) {
                if (b == SaoPackState.RUNNING) {
                    o.removeListener(this);
                    pendingLaunch = null;
                    linkStartOut();
                } else if (b == SaoPackState.FAILED || b == SaoPackState.READY && a != SaoPackState.RUNNING) {
                    o.removeListener(this);
                    pendingLaunch = null;
                    cancelHandoff();
                    // Refused for a newer pack with no Quark link known: the 整合包 window instead.
                    SaoReadiness r = service.readiness().get();
                    if (b == SaoPackState.FAILED && r.kind() == SaoReadiness.Kind.UPDATE && r.action() == SaoReadiness.Action.PACK_WINDOW)
                        openPack(SaoPackApp.Mode.PACK);
                }
            }
        };
        pendingLaunch = l;
        service.state().addListener(l);
        launch.run();
        if (pendingLaunch == l && service.state().get() != SaoPackState.RUNNING
                && service.state().get() != SaoPackState.WORKING) {
            service.state().removeListener(l);
            pendingLaunch = null;
            cancelHandoff();
        }
        if (service.state().get() == SaoPackState.RUNNING && pendingLaunch == l) {
            service.state().removeListener(l);
            pendingLaunch = null;
            linkStartOut();
        }
    }

    /// A short-lived status line for a click that could not start anything; it clears itself after 4 s.
    private void notice(String text) {
        problem.setText(text);
        PauseTransition p = new PauseTransition(Duration.seconds(4));
        p.setOnFinished(e -> { if (problem.getText().equals(text)) problem.setText(""); });
        p.play();
    }

    /// Lets the window be dragged by the empty glass.
    private void hookWindow() {
        // James, 8 Nov: "为什么没有任何一个可以拖动整个窗口的地方？": a press on the empty space around the windows moves the
        // launcher; a press on a window, the character or a button never does ("现在steve焊上面了": the decorator's
        // hover test let a drag on the character move the whole window). The page does this itself, by where the
        // press went, instead of through the decorator's hover flag.
        windows.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        dock.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        pad.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        cover.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        characterLayer.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = false);
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            moving = !boot.handoff.active() && !pressOnWindow && e.isPrimaryButtonDown() && getCursor() == null && getScene() != null
                    && getScene().getWindow() instanceof javafx.stage.Stage st && !st.isFullScreen()
                    && !org.jackhuang.hmcl.ui.decorator.WindowState.isMaximized(st);
            if (!moving) return;
            javafx.stage.Window win = getScene().getWindow();
            moveFromX = win.getX() - e.getScreenX();
            moveFromY = win.getY() - e.getScreenY();
        });
        addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (boot.handoff.active() || !moving || getScene() == null) return;
            javafx.stage.Window win = getScene().getWindow();
            win.setX(e.getScreenX() + moveFromX);
            win.setY(e.getScreenY() + moveFromY);
            e.consume();
        });
        addEventHandler(MouseEvent.MOUSE_RELEASED, e -> moving = false);
    }

    /// The 外观 preference key and the dark theme's style class.
    static final String THEME_KEY = "theme", DARK = "theme-sao-dark";
    /// Whether the screen is in 深色.
    private boolean dark;
    /// The 外观 choice.
    private SaoTheme.Mode themeMode = SaoTheme.Mode.SYSTEM;

    /// Sets the 外观 choice (设置) and applies it now.
    ///
    /// @param mode the choice
    void setThemeMode(SaoTheme.Mode mode) {
        themeMode = mode;
        refreshTheme(false);
    }

    /// Applies the 外观 choice. When the system has to be asked by a command, a background check asks it off the
    /// JavaFX thread; the first decision (before the first frame) asks it directly.
    ///
    /// @param background whether a slow system check may run in the background
    private void refreshTheme(boolean background) {
        SaoTheme.Mode m = themeMode;
        if (m != SaoTheme.Mode.SYSTEM || FXUtils.DARK_MODE != null || !background) {
            setDark(SaoTheme.decide(m, m == SaoTheme.Mode.SYSTEM ? SaoTheme.systemDark() : null, java.time.LocalTime.now()));
            return;
        }
        java.util.concurrent.CompletableFuture.supplyAsync(SaoTheme::systemDark).thenAccept(sys -> Platform.runLater(() -> {
            if (themeMode == SaoTheme.Mode.SYSTEM) setDark(SaoTheme.decide(SaoTheme.Mode.SYSTEM, sys, java.time.LocalTime.now()));
        }));
    }

    /// Switches 外观 live: every window, the space, the cover and HMCL's own dialogs (on the scene root).
    ///
    /// @param on true for 深色
    void setDark(boolean on) {
        dark = on;
        applyDark();
    }

    /// Whether the screen is in 深色.
    boolean dark() {
        return dark;
    }

    private void applyDark() {
        SaoAssets.setLogoDark(dark);
        SaoDialogs.setDark(dark);
        SaoUiKit.setDark(dark);
        for (javafx.scene.Node n : new javafx.scene.Node[]{this, space, cover, getScene() == null ? null : getScene().getRoot()}) {
            if (n == null) continue;
            n.getStyleClass().remove(DARK);
            if (dark) n.getStyleClass().add(DARK);
        }
    }

    /// The page on screen, for 设置's 外观 and the skin editor's cover.
    static @Nullable SaoHelmetPage current() {
        return current;
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

    /// For measuring the real window's CPU without touching it (James, 8 Nov: "请优化"): -Dsao.debugApp=N opens dock
    /// app N and -Dsao.debugIconify=S minimises the window S seconds after start. Off unless set; runs once.
    private void measuringHooks() {
        if (hooked) return;
        hooked = true;
        int n = Integer.getInteger("sao.debugApp", -1);
        if (n >= 0 && n < SaoApps.APPS.length) {
            PauseTransition p = new PauseTransition(Duration.seconds(4));
            p.setOnFinished(e -> forceApp(n));
            p.play();
        }
        @Nullable String after = System.getProperty("sao.debugIconify");
        if (after != null) {
            PauseTransition p = new PauseTransition(Duration.seconds(Double.parseDouble(after)));
            p.setOnFinished(e -> {
                if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) st.setIconified(true);
            });
            p.play();
        }
    }

    /// Whether [#measuringHooks()] ran.
    private boolean hooked;

    /// Stills or wakes the page as if minimised (idle probe).
    void forceAwake(boolean on) {
        setAwake(on);
    }

    /// Shows 确定退出吗？ (screenshot tools).
    void forceConfirmOut() {
        confirmOut.setVisible(true);
    }

    /// Fills the sign-in form, the name and a typed password (screenshot tools).
    void forceSignIn(String user, String password) {
        name.setText(user);
        pw.setText(password);
    }

    /// Shows or hides 离线游玩 in the right column (screenshot tools).
    void forceOffline(boolean shown) {
        if (shown) askOffline();
        else confirmOffline.setVisible(false);
    }

    /// Opens the 誓约账号 window on a page (screenshot tools).
    void forceAccount(SaoAccountApp.Page page) {
        openAccount(page);
    }

    /// Opens the 微软正版登录 window on a sign-in (the Microsoft presenter and the screenshot tools).
    void openMicrosoft(java.util.function.Supplier<org.jackhuang.hmcl.sao.SaoMicrosoftFlow> start) {
        openApp(new SaoApps.App(SVG.PERSON, "微软正版登录", (s, c) -> SaoMicrosoftApp.make(start, c)), null);
    }

    /// Opens the server's other nodes (the screenshot tool).
    void forceNodes() {
        openNodes.run();
    }

    /// What 其他节点 does, set by the server window.
    Runnable openNodes = () -> {
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

    /// Presses the main button, Link Start or 下载 / 更新 (screenshot tools).
    void forceLinkStart() {
        pressMain();
    }

    /// Whether the display is on (screenshot tools).
    boolean isAwakeForTools() {
        return poweredOn;
    }

    /// Opens an app by its dock index at once (screenshot tools).
    ///
    /// @param i the index in [SaoApps#APPS], or -1 to close
    void forceApp(int i) {
        if (i < 0) {
            if (app != null) windows.getChildren().remove(app);
            app = null;
            packWin = null;
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

    /// Clamps a normalised head offset to the window's maximum range `[-0.5, 0.5]`.
    static double clampHead(double v) {
        return v < -0.5 ? -0.5 : v > 0.5 ? 0.5 : v;
    }

    /// Reads the global cursor position relative to this page when the launcher window is showing on screen (or when a
    /// test supplies [#pointerOverride]), clamping outside-window positions to `[-0.5, 0.5]`.
    private void pollPointer(double pw, double ph) {
        java.util.function.@Nullable Supplier<Point2D> over = pointerOverride;
        javafx.stage.@Nullable Window win = getScene() == null ? null : getScene().getWindow();
        boolean liveWindow = win != null && win.isShowing()
                && !(win instanceof javafx.stage.Stage st && st.isIconified());
        if (over == null && !liveWindow) return;
        try {
            double sx, sy;
            if (over != null) {
                @Nullable Point2D p = over.get();
                if (p == null) return;
                sx = p.getX();
                sy = p.getY();
            } else {
                if (pointerRobot == null) pointerRobot = new javafx.scene.robot.Robot();
                sx = pointerRobot.getMouseX();
                sy = pointerRobot.getMouseY();
            }
            if (!Double.isFinite(sx) || !Double.isFinite(sy)) return;
            @Nullable Point2D local = screenToLocal(sx, sy);
            if (local == null || !Double.isFinite(local.getX()) || !Double.isFinite(local.getY())) return;
            headX = clampHead(local.getX() / Math.max(1, pw) - 0.5);
            headY = clampHead(local.getY() / Math.max(1, ph) - 0.5);
        } catch (RuntimeException | LinkageError ignored) {
            // Fall back to the in-window MouseEvent filter if the platform pointer query is unavailable.
        }
    }

    /// Y-axis turn for a mouse offset (-0.5 left .. 0.5 right). Positive turns the space so it moves right on screen.
    static double turnYDeg(double lookX) { return clampHead(lookX) * TURN_Y_DEG; }

    /// X-axis turn for a mouse offset (-0.5 top .. 0.5 bottom). Negative turns the space so it moves down on screen.
    static double turnXDeg(double lookY) { return -clampHead(lookY) * TURN_X_DEG; }

    /// Turns the head (screenshot tools).
    void forceHead(double x, double y) {
        headX = lookX = clampHead(x);
        headY = lookY = clampHead(y);
    }

    /// The player's character, turning slowly in HMCL's 3D skin view and turned by dragging.
    /// Which way a drag turns and tips the character (checked by dragging the real window).
    static double TURN_SIGN = 1, TILT_SIGN = -1;
}
