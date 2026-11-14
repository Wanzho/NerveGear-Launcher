/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.AnimationTimer;
import org.jackhuang.hmcl.sao.SaoHandoff;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
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
import javafx.scene.control.Label;
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
import org.jackhuang.hmcl.sao.SaoReadiness;
import org.jackhuang.hmcl.sao.SaoServices;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.skin.SkinCanvas;
import org.jackhuang.hmcl.ui.skin.animation.SkinAniWavingArms;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    /// How far the view sinks at the top of a breath, in design pixels.
    private static final double BREATH_PX = 2.6;
    /// One breath, in seconds (about 13 a minute at rest).
    private static final double BREATH_S = 4.6;
    /// Switches for measuring what each moving part costs (-Dsao.glint=over|add|off, -Dsao.breathe=false,
    /// -Dsao.sway=false); the defaults are the look James approved.
    private static final String GLINT_MODE = System.getProperty("sao.glint", "over");
    /// Off since 14 Nov (James: "呼吸很奇怪，还是最好跟着鼠标来吧"); -Dsao.breathe=true brings it back.
    private static final boolean BREATHE = "true".equals(System.getProperty("sao.breathe"));
    /// How fast the view follows the mouse: the gap closes by 1 - e^(-dt / FOLLOW_S), a spring with no overshoot.
    private static final double FOLLOW_S = 0.16;
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
    private final Button[] dockButtons = new Button[SaoApps.APPS.length];

    /// Mouse position in parts of the window, -0.5 to 0.5, and its smoothed follow.
    private double headX, headY, lookX, lookY;
    /// The time of the last frame, for the follow spring.
    private double lastFrameT = -1;
    /// Whether the display is on.
    private boolean poweredOn;

    /// The ARGUS ID side of the player window, drawn by James (7 Nov, two sketches): signed out, "ARGUS ID 未登录",
    /// the name and password, 记住 with 注册 and 忘记密码, 登录, a line, 使用 Microsoft; signed in, "ARGUS ID [退出登录]",
    /// the name large, the mailbox and Microsoft each with 验证, and Link Start at the bottom right.
    // The grey hint like 密码's (James, 14 Nov: "用户名这个里面没有灰掉的用户名输入的地方").
    private final TextField name = field("用户名／邮箱");
    private final PasswordField pw = SaoAccountApp.password("");
    private final ToggleButton remember = new ToggleButton();
    private final Button login = SaoUiKit.accent("登录");
    private final Button useMicrosoft = SaoUiKit.button("使用 Microsoft 登录");
    private final VBox signedOut = new VBox(10);
    private final VBox signedIn = new VBox(10);
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
    private final VBox confirmOffline = new VBox(11);
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
        service.microsoftIdentity().addListener((o, was, now) -> Platform.runLater(this::refreshAccount));
        service.oathLinking().addListener((o, was, now) -> Platform.runLater(() -> {
            if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.FAILED) startupPending = false;
            refreshAccount();
            String linking = now.reason().isEmpty() ? "正在连接 ARGUS…" : now.reason();
            if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKING) problem.setText(linking);
            else if (now.kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKED
                    && (problem.getText().startsWith("正在连接") || problem.getText().startsWith("正在用 Microsoft"))) problem.setText("");
        }));
        org.jackhuang.hmcl.sao.SaoMicrosoftFlow.setPresenter(flow -> {
            boolean[] first = {true};
            openMicrosoft(() -> {
                if (first[0]) { first[0] = false; return flow; }
                return service.microsoftSignIn();
            });
        });
        // The head follows the mouse (James, 14 Nov), measured from the page centre, -0.5..0.5 on each axis; drags
        // (window, character) count too, so the view never jumps when a drag ends.
        javafx.event.EventHandler<MouseEvent> look = e -> {
            headX = Math.max(-0.5, Math.min(0.5, e.getX() / Math.max(1, getWidth()) - 0.5));
            headY = Math.max(-0.5, Math.min(0.5, e.getY() / Math.max(1, getHeight()) - 0.5));
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
        space.getTransforms().addAll(centre, fit, turnY, turnX);
        space.getChildren().add(windows);

        news = buildNews();
        server = buildServer();
        player = buildPlayer();
        dock = buildDock();
        windows.getChildren().addAll(news, server, player, dock);
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
            hide.run();
            handedOver();
        }), boot::cancel);
        boot.windowFade().addListener((o, a, f) -> {
            if (getScene() != null && getScene().getWindow() != null) getScene().getWindow().setOpacity(1 - f.doubleValue());
        });
        service.gameLog().addListener((ListChangeListener<String>) c -> boot.readLog(service.gameLog()));

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
        service.lastError().addListener((o, a, b) -> refreshState());
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

    /// News, on the left, turned toward the player.
    private SaoFloatWindow buildNews() {
        VBox list = SaoApps.live(service.news(), n -> {
            Text title = ink(n.title(), 14);
            // The card keeps a short preview; the whole entry opens in its own window (James, 9 Nov:
            // "启动器点击某个公告能像别的一样单开一个窗口吗").
            Text body = dim(SaoNewsApp.preview(n.body(), 54), 12);
            body.setWrappingWidth(222);
            HBox head = new HBox(8, title, grow(), dim(n.date(), 12));
            head.setAlignment(Pos.CENTER_LEFT);
            VBox v = new VBox(6, head, body);
            v.getStyleClass().add("vr-tile");
            v.setCursor(javafx.scene.Cursor.HAND);
            v.setOnMouseClicked(e -> openNews(n));
            // Lifts a little under the mouse, like the buttons (SaoMotion).
            v.hoverProperty().addListener((o, x, on) -> SaoMotion.lift(v, on));
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
        // 维护中 (James, 10 Nov: "如果服务器在维护中，启动器要在右边面板上说明"): the signed notice under the address,
        // whatever the ping says. Its text is the backend's, shown as is; a long one scrolls inside the tile.
        Runnable show = () -> {
            @Nullable SaoData.ServerStatus st = service.serverStatus().get();
            SaoData.@Nullable Maintenance m = service.updateStatus().get().maintenance();
            box.getChildren().clear();
            box.getChildren().add(where);
            if (m != null) box.getChildren().add(maintenanceTile(m));
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
            box.getChildren().addAll(state, rule(), players);
            // During maintenance the notice takes the room of the MOTD and the name chips.
            if (m == null) box.getChildren().addAll(motd, names);
        };
        service.serverStatus().addListener((o, a, b) -> show.run());
        // The signed policy can turn maintenance on or off and move the address.
        service.updateStatus().addListener((o, a, b) -> {
            fillNodes.run();
            show.run();
        });
        show.run();
        Button refresh = round(SVG.REFRESH, 26, 14);
        // The button also asks for the signed policy again; the open call and the 60 s timer do not.
        refresh.setOnAction(e -> service.refreshOnline(true));
        SaoFloatWindow w = new SaoFloatWindow("服务器", 290, 430, null).add(box);
        w.head.getChildren().add(refresh);
        return w.home(1034, 175, 40, 24, 0);
    }

    /// The 维护中 tile: the state word, the backend's message and, when given, 预计结束 with its time.
    private static Node maintenanceTile(SaoData.Maintenance m) {
        Text title = ink("维护中", 15);
        title.getStyleClass().setAll("vr-warn");
        SaoUiKit.font(title, 15, true);
        Text message = ink(m.message(), 13);
        // Narrower than the tile so the scroll bar of a long notice never covers the line ends.
        message.setWrappingWidth(170);
        // A long notice scrolls inside the tile instead of pushing the window's rows out; the end time stays visible.
        javafx.scene.control.ScrollPane scroll = scroll(new VBox(message));
        // The text follows the visible width, which shrinks when the scroll bar shows.
        scroll.viewportBoundsProperty().addListener((o, a, b) -> {
            double w = Math.floor(b.getWidth()) - 6;
            if (w > 40 && Math.abs(w - message.getWrappingWidth()) > 0.5) message.setWrappingWidth(Math.min(184, w));
        });
        scroll.setMaxHeight(140);
        VBox.setVgrow(scroll, Priority.NEVER);
        VBox text = new VBox(4, title, scroll);
        @Nullable String until = m.until();
        if (until != null && !until.isBlank()) text.getChildren().add(ink("预计结束 " + until.strip(), 12.5));
        HBox tile = tile(icon(SVG.WARNING, 20), text);
        tile.setAlignment(Pos.TOP_LEFT);
        tile.getStyleClass().add("vr-tile-warn");
        return tile;
    }

    /// The player, in the middle: the logo over the character on the left, the ARGUS ID on the right as James drew it
    /// (7 Nov); Link Start lives here too, at the bottom right once signed in. The skin has its own page (James, 7 Nov:
    /// "把classic slim等等塞到一个新的页面").
    /// James, 13 Nov: "中间图标先去掉吧，我不喜欢在那里": the logo under the friends is hidden for now; its room stays
    /// empty so Link Start keeps its place.
    private static final boolean SHOW_PLAYER_LOGO = false;

    private SaoFloatWindow buildPlayer() {
        // James, 9 Nov: "左边只放人，logo放到好友在线这里": the logo-49 title fills the room under the friends, signed
        // in; it shrinks when warnings take that room and hides when too little is left.
        ImageView logo = SaoAssets.logo(RIGHT_W, 150);
        logo.getStyleClass().add("vr-logo");
        StackPane logoBox = new StackPane(logo);
        logoBox.setMinHeight(0);
        VBox.setVgrow(logoBox, Priority.ALWAYS);
        logoBox.heightProperty().addListener((o, a, h) -> {
            double fit = Math.min(170, h.doubleValue() - 4);
            logo.setFitHeight(Math.max(1, fit));
            logo.setVisible(SHOW_PLAYER_LOGO && fit >= 48);
        });

        Button editSkin = round(SVG.EDIT, 30, 15);
        editSkin.setOnAction(e -> openSkin());
        StackPane.setAlignment(editSkin, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(editSkin, new Insets(7));
        // The character and its pencil; drawn over the 3D space while the window rests (see [#placeCharacter()]).
        characterBox.getChildren().setAll(skin, editSkin);
        for (Region b : new Region[]{characterBox, characterSlot}) {
            b.setMinSize(STAGE_W, STAGE_H);
            b.setPrefSize(STAGE_W, STAGE_H);
            b.setMaxSize(STAGE_W, STAGE_H);
        }
        characterSlot.getChildren().setAll(characterBox);
        StackPane stage = characterSlot;
        stage.getStyleClass().add("vr-stage");
        // James, 8 Nov: "中间这个界面，里面东西搞稍微大点，留白有点多，密码的那个点可以小一点": the stage and its
        // turntable grow with the rest (150 x 214 before).
        stage.setMinSize(STAGE_W, STAGE_H);
        stage.setPrefSize(STAGE_W, STAGE_H);
        stage.setMaxSize(STAGE_W, STAGE_H);
        // The left: only the character and its pencil (James, 9 Nov: "左边只放人").
        VBox left = new VBox(stage);
        left.setAlignment(Pos.CENTER_LEFT);

        // ---- signed out
        @Nullable String remembered = service.rememberedOathName();
        if (remembered != null) name.setText(remembered);
        name.textProperty().addListener((o, a, b) -> refreshPrompt());
        remember.getStyleClass().addAll("vr-check", "vr-check-big");
        remember.setFocusTraversable(false);
        remember.setSelected(true);
        remember.setGraphic(icon(SVG.CHECK, 14));
        remember.getGraphic().visibleProperty().bind(remember.selectedProperty());
        SaoMotion.press(remember);
        remember.selectedProperty().addListener((o, a, on) -> { if (on) SaoMotion.pop(remember.getGraphic()); });
        Text rememberLabel = dim("记住我", 14);
        rememberLabel.setOnMouseClicked(e -> remember.setSelected(!remember.isSelected()));
        // James, 14 Nov: a new player's first step was a small grey 注册 link; it is a button beside 登录 now.
        HBox rememberRow = new HBox(7, remember, rememberLabel, grow(),
                bigLink("忘记密码", () -> openAccount(SaoAccountApp.Page.FORGOT)));
        rememberRow.setAlignment(Pos.CENTER_LEFT);
        for (javafx.scene.control.TextField f : new javafx.scene.control.TextField[]{name, pw, offlineName}) {
            SaoUiKit.font(f, 16, false);
            f.setMinHeight(FIELD_H);
            f.setPrefHeight(FIELD_H);
        }
        Button register = SaoUiKit.button("注册");
        register.setOnAction(e -> openAccount(SaoAccountApp.Page.REGISTER));
        register.setMinWidth(88);
        HBox loginRow = new HBox(8, login, register);
        HBox.setHgrow(login, javafx.scene.layout.Priority.ALWAYS);
        for (Button b : new Button[]{login, register, useMicrosoft}) {
            SaoUiKit.font(b, 15, false);
            b.setMinHeight(BUTTON_H);
            b.setPrefHeight(BUTTON_H);
        }
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> login());
        login.defaultButtonProperty().bind(signedOut.visibleProperty());
        useMicrosoft.setGraphic(microsoftLogo(16));
        useMicrosoft.setMaxWidth(Double.MAX_VALUE);
        useMicrosoft.setOnAction(e -> premiumLogin());
        HBox outHead = new HBox(8, sao("ARGUS ID", 17, true), grow(), dim("未登录", 14));
        outHead.setAlignment(Pos.CENTER_LEFT);
        // James, 8 Nov: "可以选择不登录任何东西，顶着个名字，但是提醒窗口写这样就登录不了咱服务器".
        HBox offlineRow = new HBox(bigLink("不登录，离线游玩", this::askOffline));
        offlineRow.setAlignment(Pos.CENTER);
        signedOut.getChildren().setAll(outHead, bigKey("用户名／邮箱"), name, bigKey("密码"), pw, rememberRow, loginRow, rule(), useMicrosoft, offlineRow);
        // Every side of the account panel comes in, row after row, when it is shown (James, 14 Nov: "login时，成功等").
        SaoMotion.reveal(signedOut);
        SaoMotion.reveal(confirmOut);
        SaoMotion.reveal(confirmOffline);
        SaoMotion.reveal(confirmName);

        // ---- signed in: filled by refreshAccount; Link Start at its bottom right
        HBox linkRow = buildLinkRow();

        problem.getStyleClass().add("vr-warn");
        SaoMotion.report(problem);
        SaoUiKit.font(problem, 13, false);
        problem.setWrappingWidth(RIGHT_W - 6);
        StackPane sides = new StackPane(signedOut, signedIn, confirmOffline);
        for (VBox v : new VBox[]{signedOut, signedIn, confirmOffline}) {
            StackPane.setAlignment(v, Pos.TOP_LEFT);
            // Only the side shown takes room: the taller sign-in form, hidden, pushed Link Start out of the window.
            v.managedProperty().bind(v.visibleProperty());
        }
        // Signed in, the logo takes the room between the friends and Link Start; elsewhere a plain spring.
        Region spring = grow();
        SaoMotion.revealOne(logoBox);
        logoBox.visibleProperty().bind(signedIn.visibleProperty());
        logoBox.managedProperty().bind(signedIn.visibleProperty());
        spring.visibleProperty().bind(signedIn.visibleProperty().not());
        spring.managedProperty().bind(signedIn.visibleProperty().not());
        VBox right = new VBox(6, sides, logoBox, spring, problem, linkRow);
        right.setPrefWidth(RIGHT_W);
        right.setMinWidth(RIGHT_W);
        right.setMaxWidth(RIGHT_W);
        // James, 11 Nov: "要啊": before login the row shows too, but only as 下载 / 更新, which need no account.
        // Link Start itself still needs the login or 离线进入.
        javafx.beans.binding.BooleanBinding rowShown = Bindings.createBooleanBinding(
                () -> signedIn.isVisible() || service.readiness().get().shownSignedOut(),
                signedIn.visibleProperty(), service.readiness());
        SaoMotion.revealOne(linkRow);
        linkRow.visibleProperty().bind(rowShown);
        linkRow.managedProperty().bind(rowShown);
        // Shown before login, the row takes the signed-in layout's slot and bottom inset: the form's nine gaps close
        // from 10 to 5 so the column is no taller than the signed-in one. Signed out and ready, nothing changes.
        signedOut.spacingProperty().bind(Bindings.when(rowShown.and(signedIn.visibleProperty().not())).then(5.0).otherwise(10.0));
        HBox bodyRow = new HBox(GAP, left, right);

        // 确定退出吗？ in place of the body until answered.
        Text sure = ink("确定退出吗？", 18);
        SaoUiKit.font(sure, 18, true);
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
            startupPending = false;
            service.setPreference(SNAPSHOT_KEY, "");
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
        Text offTitle = ink("离线游玩", 17);
        SaoUiKit.font(offTitle, 17, true);
        Text offWarn = dim("离线进不了誓约服务器，只能玩单人游戏", 13);
        offWarn.getStyleClass().setAll("vr-warn");
        Text offProblem = dim("", 14);
        offProblem.getStyleClass().setAll("vr-warn");
        // Wrapped, or its width would push the column past the window's edge.
        offProblem.setWrappingWidth(RIGHT_W - 6);
        Button offNo = SaoUiKit.button("取消"), offYes = SaoUiKit.accent("离线进入");
        for (Button b : new Button[]{offNo, offYes}) {
            SaoUiKit.font(b, 15, false);
            b.setMinHeight(BUTTON_H);
            b.setPrefHeight(BUTTON_H);
        }
        HBox offAnswers = new HBox(10, offNo, offYes);
        offAnswers.setAlignment(Pos.CENTER);
        // James, 8 Nov: "离线游玩不要开个新的页面，只把右边的页面改了就行": it takes the sign-in's place on the right.
        HBox offHead = new HBox(8, offTitle);
        offHead.setAlignment(Pos.CENTER_LEFT);
        offlineName.setMaxWidth(Double.MAX_VALUE);
        offWarn.setWrappingWidth(RIGHT_W - 6);
        offAnswers.setAlignment(Pos.CENTER_LEFT);
        confirmOffline.getChildren().setAll(offHead, bigKey("名字"), offlineName, offWarn, offProblem, offAnswers);
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
        StackPane body = new StackPane(bodyRow, confirmOut, confirmName);
        VBox.setVgrow(body, Priority.ALWAYS);

        service.oathAccount().addListener((o, a, b) -> refreshAccount());
        service.playerCard().addListener((o, a, b) -> refreshAccount());
        Accounts.getAccounts().addListener((ListChangeListener<Account>) c -> refreshAccount());
        // Shown from the last session's snapshot until the background sign-in answers; if nothing is signing in a few
        // seconds after start (no remembered account), the snapshot goes.
        startupPending = snapshot() != null;
        PauseTransition settle = new PauseTransition(Duration.seconds(4));
        settle.setOnFinished(e -> { if (startupPending && !autoLogging && !microsoftSigningIn() && service.oathAccount().get() == null) { startupPending = false; refreshAccount(); } });
        settle.play();
        refreshAccount();
        autoLogin();
        // 448 x 410 before; still clear of 公告 and 服务器 (their near edges at x 396 and 884) and centred on the
        // same middle, y 391.
        SaoFloatWindow w = new SaoFloatWindow("", PLAYER_W, PLAYER_H, null).add(body);
        w.body.setPadding(new Insets(18, 16, 18, 16));
        return w.home(640, 391 - PLAYER_H / 2, 0, 0, 0);
    }

    /// The player window's size and its parts (James, 8 Nov: "中间这个界面，里面东西搞稍微大点，留白有点多").
    private static final double PLAYER_W = 468, PLAYER_H = 420, STAGE_W = 168, STAGE_H = 300, GAP = 16,
            RIGHT_W = PLAYER_W - 32 - STAGE_W - GAP, FIELD_H = 40, BUTTON_H = 40;

    /// A label over a field in the player window, a step larger than [SaoUiKit#key].
    private static HBox bigKey(String label) {
        HBox h = key(label);
        SaoUiKit.font(h.getChildren().get(0), 14, true);
        return h;
    }

    /// A text link in the player window, a step larger than [SaoAccountApp#link].
    private static Button bigLink(String text, Runnable r) {
        Button b = SaoAccountApp.link(text, r);
        SaoUiKit.font(b, 14, false);
        return b;
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
            SaoMotion.busy(useMicrosoft);
            service.oathPremiumLogin().whenComplete((ok, err) -> Platform.runLater(() -> {
                SaoMotion.idle(useMicrosoft);
                problem.setText(err == null ? "" : "! " + SaoSkinApp.message(err));
            }));
        });
    }

    /// Link Start inside the player window, bottom right. While the pack downloads or installs, only a progress bar
    /// across the row (James, 7 Nov: "那个就绪可以删了，下载的时候linkstart别显示然后只有进度条就行").
    private HBox buildLinkRow() {
        double bw = RIGHT_W;
        Region track = new Region();
        track.getStyleClass().add("vr-track");
        track.setPrefSize(bw, 4);
        track.setMaxSize(bw, 4);
        fill.getStyleClass().add("vr-fill");
        fill.setPrefHeight(4);
        fill.setMaxHeight(4);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        // The fill glides to each new amount (James, 14 Nov: "可以给所有东西都加动效/动画吗").
        SaoMotion.follow(fill, Bindings.createDoubleBinding(() -> {
            SaoPackState s = service.state().get();
            return s == SaoPackState.WORKING ? bw * Math.max(0.03, fraction()) : 0.0;
        }, service.state(), service.progress(), service.installProgress()));
        StackPane track2 = new StackPane(track, fill);
        track2.setAlignment(Pos.CENTER_LEFT);
        track2.setMaxWidth(bw);
        // James, 7 Nov: "write more stuff on the progress bar while downloading": the step and how far over it,
        // the speed and how many files are left under it.
        Text step = SaoUiKit.ink("", 13), percent = dim("", 13), speed = dim("", 12), left = dim("", 12);
        SaoUiKit.font(percent, 14, false);
        SaoUiKit.font(speed, 13, false);
        HBox above = new HBox(6, step, grow(), percent);
        above.setAlignment(Pos.BASELINE_LEFT);
        HBox below = new HBox(6, speed, grow(), left);
        below.setAlignment(Pos.BASELINE_LEFT);
        VBox bar = new VBox(5, above, track2, below);
        bar.setMaxWidth(bw);
        bar.setAlignment(Pos.CENTER_LEFT);
        Runnable words = () -> {
            SaoPackState s = service.state().get();
            double p = fraction();
            org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
            boolean measured = ip.stage() != org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE;
            String label = measured && !ip.label().isBlank() ? ip.label() : service.stepLabel().get();
            step.setText(s == SaoPackState.CHECKING ? "检查文件" : label.isEmpty() ? "准备中" : label);
            percent.setText(p >= 0 ? Math.round(p * 100) + "%" : "");
            if (measured && (ip.totalFiles() > 0 || ip.bytesDone() > 0)) {
                // The backend's measured numbers: real throughput, bytes against the index total when it is known.
                speed.setText(ip.bytesPerSecond() > 0 ? speedText((long) ip.bytesPerSecond()) : "");
                left.setText(ip.totalBytes() > 0 ? size(ip.bytesDone()) + " / " + size(ip.totalBytes())
                        : ip.bytesDone() > 0 ? size(ip.bytesDone()) : "");
                return;
            }
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
        service.installProgress().addListener((o, a, b) -> words.run());
        service.tasks().addListener((ListChangeListener<SaoData.Task>) c -> words.run());
        words.run();
        start.getStyleClass().add("vr-start");
        SaoUiKit.font(start, 22, true);
        start.setMinWidth(Region.USE_PREF_SIZE);
        start.setOnAction(e -> pressMain());
        start.defaultButtonProperty().bind(signedIn.visibleProperty());
        // James, 11 Nov: "link start 是启动游戏，下载还是老实叫下载吧": 下载 / 更新 until everything is there.
        Runnable label = () -> {
            SaoReadiness r = service.readiness().get();
            if (r.kind() != SaoReadiness.Kind.CHECKING) start.setText(r.buttonText());
        };
        service.readiness().addListener((o, a, b) -> label.run());
        label.run();
        // Installed: only the version beside Link Start ("安装完了那个进度条也删了，只剩v0.x").
        Text version = dim("", 14);
        // What needs doing before Link Start, as one small button beside the version: import the pack, a pack update,
        // a launcher update (mandatory or not) or one waiting for a restart.
        Button notice = SaoUiKit.button("");
        notice.getStyleClass().add("vr-chip-tight");
        HBox versionBox = new HBox(10, version, notice);
        versionBox.setAlignment(Pos.CENTER_LEFT);
        versionBox.setPickOnBounds(false);
        StackPane.setAlignment(versionBox, Pos.CENTER_LEFT);
        StackPane slot = new StackPane(bar, versionBox, start);
        StackPane.setAlignment(start, Pos.CENTER_RIGHT);
        slot.setMinHeight(48);
        HBox.setHgrow(slot, Priority.ALWAYS);
        Runnable show = () -> {
            SaoPackState s = service.state().get();
            boolean busy = s == SaoPackState.WORKING || s == SaoPackState.CHECKING;
            bar.setVisible(busy);
            start.setVisible(!busy);
            @Nullable String v = service.installedVersion().get();
            // installedVersion() only: a thin launcher has no pack of its own ("v未内置整合包" never shows).
            version.setText(v != null ? "v" + v : "未安装");
            versionBox.setVisible(!busy);
            SaoData.UpdateStatus u = service.updateStatus().get();
            SaoData.LauncherUpdate lu = service.launcherUpdate().get();
            String what = "";
            SaoPackApp.Mode mode = SaoPackApp.Mode.PACK;
            if (u.launcherBlocked()) {
                what = "更新启动器";
                mode = SaoPackApp.Mode.LAUNCHER;
            } else if (lu.restartRequired()) {
                what = "重启生效";
                mode = SaoPackApp.Mode.LAUNCHER;
            } else if (v == null) what = "导入整合包";
            else if (u.packUpdateAvailable() && u.metadata() != null) what = "更新 v" + u.metadata().pack().version();
            else if (u.launcherUpdateAvailable()) {
                what = "启动器更新";
                mode = SaoPackApp.Mode.LAUNCHER;
            }
            SaoPackApp.Mode m = mode;
            notice.setText(what);
            notice.setOnAction(e -> openPack(m));
            notice.setVisible(!what.isEmpty());
            notice.setManaged(notice.isVisible());
        };
        service.state().addListener((o, a, b) -> show.run());
        service.installedVersion().addListener((o, a, b) -> show.run());
        service.updateStatus().addListener((o, a, b) -> show.run());
        service.launcherUpdate().addListener((o, a, b) -> show.run());
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

    /// The player card's friends line, when friends are online (screenshot checks).
    @Nullable FriendsLine friendsLine;

    /// Opens 好友 (the +N after the friends who fit on the player card).
    private void openFriends() {
        for (int i = 0; i < SaoApps.APPS.length; i++)
            if (SaoApps.APPS[i].label().equals("好友")) openApp(SaoApps.APPS[i], dockButtons[i]);
    }

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
        SaoUiKit.font(sure, 16, true);
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
        if (!before.isEmpty()) h.getChildren().add(dim(before, 12));
        h.getChildren().add(sao(value, 21, true));
        if (!after.isEmpty()) h.getChildren().add(dim(after, 12));
        return h;
    }

    /// A short upright line between stats.
    private static Node statRule() {
        Region r = new Region();
        r.getStyleClass().add("vr-rule");
        r.setMinSize(1, 16);
        r.setPrefSize(1, 16);
        r.setMaxSize(1, 16);
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
        Text version = dim(SaoUi.LAUNCHER_NAME + " " + Metadata.VERSION, 10);
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
        packWin = null;
        if (appButton != null) appButton.getStyleClass().remove("vr-dock-on");
        appButton = null;
        w.close(() -> windows.getChildren().remove(w));
        for (SaoFloatWindow home : new SaoFloatWindow[]{news, server, player}) home.moveBack(0, 1, 320);
        // A skin put on or an account changed in the app shows on the player at once.
        refreshAccount();
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
        // James, 14 Nov: "呼吸很奇怪，还是最好跟着鼠标来吧": the head follows the mouse again, with the 8 Nov rule kept
        // ("为什么要乱晃"): it moves only when the mouse moves, eases there like a spring and stops dead when the mouse
        // stops. No drift of its own.
        double dt = lastFrameT < 0 ? 0 : Math.max(0, Math.min(0.1, t - lastFrameT));
        lastFrameT = t;
        double follow = 1 - Math.exp(-dt / FOLLOW_S);
        lookX += (headX - lookX) * follow;
        lookY += (headY - lookY) * follow;
        if (Math.abs(headX - lookX) < 1e-4) lookX = headX;
        if (Math.abs(headY - lookY) < 1e-4) lookY = headY;
        // Only the finished picture moves (James, 8 Nov: "请优化"): the space stays still, so JavaFX keeps its 3D
        // render and only shifts it, in whole screen pixels so the text stays sharp.
        world.setTranslateY(BREATHE ? Math.round(s * BREATH_PX * breath(t) * k) / k : 0);
        turnY.setPivotX(EW / 2);
        turnY.setPivotY(EH / 2);
        turnY.setPivotZ(eye);
        turnX.setPivotX(EW / 2);
        turnX.setPivotY(EH / 2);
        turnX.setPivotZ(eye);
        turnY.setAngle(lookX * 3.2);
        turnX.setAngle(lookY * 2.4);

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
    private final StackPane characterSlot = new StackPane(), characterBox = new StackPane();
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

    /// Link Start: the windows put themselves away and the launcher stays over the game's window until the game shows
    /// its own visor (see SaoBoot); kept on top meanwhile so the game's first windows open behind it.
    private void linkStartOut() {
        if (!HANDOFF) {
            powerDown();
            return;
        }
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage st) st.setAlwaysOnTop(true);
        if (app == null) {
            boot.putAway();
            return;
        }
        // An app in front closes first and the home windows come back; then they all go together.
        closeApp();
        PauseTransition p = new PauseTransition(Duration.millis(340));
        p.setOnFinished(e -> {
            if (service.state().get() == SaoPackState.RUNNING) boot.putAway();
        });
        p.play();
    }

    /// The screenshot tool: closes the app in front.
    /// For the screenshot tool: the account panel again, as after a service event.
    void debugRefreshAccount() {
        refreshAccount();
    }

    void debugCloseApp() {
        closeApp();
    }

    /// The launcher has left the game's screen: dark until it comes back.
    private void handedOver() {
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
            if (err != null) startupPending = false;
            refreshAccount();
            if (err != null) problem.setText("! " + SaoSkinApp.message(err) + "，请重新登录");
        }));
    }

    /// Stars in the password box only while the name typed is the one whose password is remembered (signing out
    /// forgets it).
    private void refreshPrompt() {
        @Nullable String r = service.rememberedOathName();
        // James, 8 Nov: "密码的那个点可以小一点": the prompt says it in words, no row of big dots.
        pw.setPromptText(r != null && name.getText().strip().equals(r) ? "已记住密码" : "密码");
    }

    /// The signed-in side last shown: null signed out, "" logging in, else the name (and whether it was being renamed).
    private @Nullable String shownAccount;
    /// The big name drawn last, for its success glow.
    private @Nullable Text shownNameText;
    /// The "正在登录…" line breathing now.
    private @Nullable Node busyLine;

    /// Moves the signed-in side only when it changes, never on a plain refresh (James, 14 Nov: "login时，成功等"):
    /// logging in comes in and breathes; signed in (from out, logging in, or another name) comes in row after row and
    /// the name lands with a glow; a rename shows its editor coming in.
    private void accountMotion(boolean waiting, SaoData.@Nullable OathAccount a) {
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
    private boolean microsoftSigningIn() {
        return service.oathAccount().get() == null && service.microsoftIdentity().get() != null
                && service.oathLinking().get().kind() == org.jackhuang.hmcl.sao.SaoOathLinking.Kind.LINKING;
    }

    /// Shows the side that fits, signed out or signed in (or logging in), and the skin worn now.
    private void refreshAccount() {
        SaoData.@Nullable OathAccount live = service.oathAccount().get();
        if (live != null) startupPending = false;
        // James, 14 Nov: "登录可以慢慢来，但是显示必须先显示好": until the background sign-in answers, the panel is the last
        // session's, drawn from a snapshot saved at every signed-in refresh.
        @Nullable Snapshot cached = live == null && startupPending ? snapshot() : null;
        SaoData.@Nullable OathAccount a = live != null ? live : cached != null ? cached.account() : null;
        SaoData.@Nullable PlayerCard shownCard = live != null ? service.playerCard().get() : cached != null ? cached.card() : null;
        // James, 14 Nov: "为什么进launcher要自己手动点一遍登录microsoft". The remembered Microsoft account signs in by
        // itself at startup (4–6 s); the panel says so instead of showing the sign-in form, which invited a click
        // that restarted the whole chain.
        @Nullable String microsoftName = microsoftSigningIn() ? java.util.Objects.requireNonNull(service.microsoftIdentity().get()).name() : null;
        boolean waiting = a == null && (autoLogging || microsoftName != null);
        boolean in = a != null || waiting;
        signedOut.setVisible(!in);
        signedIn.setVisible(in);
        if (!in) {
            confirmOut.setVisible(false);
            refreshPrompt();
        }
        if (waiting) {
            @Nullable String r = microsoftName != null ? microsoftName : service.rememberedOathName();
            signedIn.getChildren().setAll(sao("ARGUS ID", 17, true), sao(r == null ? "" : r, 33, true),
                    dim(microsoftName != null ? "正在用 Microsoft 登录…" : "正在登录…", 14));
        } else if (a != null) {
            Button out = SaoUiKit.button("退出登录");
            out.setOnAction(e -> confirmOut.setVisible(true));
            // James, 7 Nov: "Sign out 右边加个Settings": the account's own settings.
            Button settings = SaoUiKit.button("设置");
            settings.setOnAction(e -> openAccount(SaoAccountApp.Page.MANAGE));
            for (Button b : new Button[]{out, settings}) SaoUiKit.font(b, 14, false);
            HBox head = new HBox(8, sao("ARGUS ID", 17, true), out, settings);
            head.setAlignment(Pos.CENTER_LEFT);
            // James, 7 Nov: "让名字大一点", then "a bit too big now"; a long name shrinks to stay in the column.
            Text n = sao(a.name(), 42, true);
            shownNameText = n;
            double nw = n.getLayoutBounds().getWidth(), room = RIGHT_W - 38;
            if (nw > room) SaoUiKit.font(n, 42 * room / nw, true);
            n.getStyleClass().add("vr-link-text");
            n.setOnMouseClicked(e -> openAccount(SaoAccountApp.Page.MANAGE));
            // The mailbox and Microsoft live in 设置 only (James, 7 Nov).
            // A pencil beside the name: it can be changed (James, 7 Nov), in 设置.
            // James, 7 Nov: "点击铅笔可以直接在那个界面换昵称".
            Button rename = round(SVG.EDIT, 28, 14);
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
                SaoUiKit.font(taken, 13, false);
                Button change = bigLink("更换", () -> {
                    editingName = true;
                    refreshAccount();
                });
                HBox takenRow = new HBox(6, taken, grow(), change);
                takenRow.setAlignment(Pos.CENTER_LEFT);
                signedIn.getChildren().add(takenRow);
            }
            // Under the name (James, 7 Nov, picked 1 3 4 5): LV, floor and Col; the last time in the game and the
            // play time; friends in the game now; and a red line while the mailbox waits for its code.
            SaoData.@Nullable PlayerCard card = shownCard;
            if (card != null) {
                // No floor (James, 7 Nov: "那就不显示floor").
                HBox tiles = new HBox(12,
                        stat("LV", card.level() < 0 ? "-" : Integer.toString(card.level()), ""), statRule(),
                        stat("", card.col() < 0 ? "-" : String.format(java.util.Locale.ROOT, "%,d", card.col()), "Col"));
                tiles.setAlignment(Pos.CENTER_LEFT);
                // James, 7 Nov: "上次进入：昨天22:18", "累计游玩时长：_._小时 （不到一小时，_分钟）".
                Text last = dim(card.lastPlayed() <= 0 ? "上次进入：还没进过游戏" : "上次进入：" + whenText(card.lastPlayed()), 14);
                Text total = dim("累计游玩时长：" + playText(card.playSeconds()), 14);
                // James, 9 Nov: "好友在线的多了就+N": one line, as many names as fit, then +N (opens 好友).
                Region friends;
                friendsLine = null;
                if (card.friendsOnline().isEmpty()) {
                    HBox none = new HBox(dim("没有好友在线", 14));
                    none.setAlignment(Pos.CENTER_LEFT);
                    friends = none;
                } else {
                    List<Node> chips = new ArrayList<>();
                    for (String f : card.friendsOnline()) {
                        HBox chip = new HBox(5, light(true), sao(f, 14, false));
                        chip.setAlignment(Pos.CENTER_LEFT);
                        chip.getStyleClass().addAll("vr-name", "vr-row");
                        chip.setOnMouseClicked(e -> openFriendChat(f));
                        chips.add(chip);
                    }
                    Text moreText = sao("", 14, false);
                    HBox more = new HBox(moreText);
                    more.setAlignment(Pos.CENTER);
                    more.getStyleClass().addAll("vr-name", "vr-row");
                    more.setId("sao-friends-more");
                    more.setOnMouseClicked(e -> openFriends());
                    friends = friendsLine = new FriendsLine(dim("好友在线", 14), chips, more, moreText);
                }
                signedIn.getChildren().addAll(tiles, new VBox(2, last, total), friends);
            }
            // A Microsoft-linked (正版) account signs in with Microsoft and has no password to recover: no mailbox nag
            // (James, 14 Nov: "正版玩家不用提醒这个东西").
            boolean premiumNoMail = a.premiumName() != null && a.maskedEmail().isEmpty();
            if (!a.emailVerified() && !premiumNoMail) {
                Label w = new Label(a.maskedEmail().isEmpty() ? "还没有邮箱，绑定后才能找回密码"
                        : a.locked() ? "邮箱没验证，验证后才能进游戏"
                        : "邮箱待验证 · 还剩 " + SaoAccountApp.hours(a.graceSeconds()));
                w.getStyleClass().add("vr-warn-label");
                SaoUiKit.font(w, 13, false);
                // The text wraps and the link keeps its full width inside the panel (James, 14 Nov: "这东西怎么在外面").
                w.setWrapText(true);
                w.setMinWidth(0);
                w.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(w, Priority.ALWAYS);
                Button go = bigLink(a.maskedEmail().isEmpty() ? "去设置" : "去验证",
                        () -> openAccount(a.maskedEmail().isEmpty() ? SaoAccountApp.Page.MANAGE : SaoAccountApp.Page.VERIFY));
                go.setMinWidth(Region.USE_PREF_SIZE);
                HBox warnRow = new HBox(6, w, go);
                warnRow.setAlignment(Pos.CENTER_LEFT);
                warnRow.setMinWidth(0);
                warnRow.setMaxWidth(Double.MAX_VALUE);
                signedIn.getChildren().add(warnRow);
            }
        }
        // The snapshot's buttons wait for the real account.
        if (cached != null) for (Node n : signedIn.getChildren()) n.setDisable(true);
        accountMotion(waiting, a);
        problem.setText(cached != null ? "正在登录…" : "");
        @Nullable Path worn = service.oathSkin() != null ? service.oathSkin() : service.skin();
        boolean slim = service.skinSlim();
        if (cached != null && cached.skin() != null && Files.isRegularFile(Path.of(cached.skin()))) { worn = Path.of(cached.skin()); slim = cached.slim(); }
        skin.show(worn, slim);
        if (live != null) saveSnapshot(live, service.playerCard().get(), service.oathSkin(), service.skinSlim());
    }

    /// Whether the last session's snapshot is shown until the startup sign-in answers.
    private boolean startupPending;
    /// Preference key of the signed-in panel snapshot.
    private static final String SNAPSHOT_KEY = "account.snapshot";

    /// The signed-in panel as last shown: name, card and skin file. No token, password or email address.
    private record Snapshot(String name, String profileId, @Nullable String premiumName, int level, long col,
                            long lastPlayed, long playSeconds, @Nullable String skin, boolean slim) {
        SaoData.OathAccount account() { return new SaoData.OathAccount(name, profileId, "", true, false, 0, premiumName); }
        SaoData.PlayerCard card() { return new SaoData.PlayerCard(level, 0, col, lastPlayed, playSeconds, List.of()); }
    }

    private @Nullable Snapshot snapshot() {
        String json = service.preference(SNAPSHOT_KEY, "");
        if (json.isBlank()) return null;
        try { return new com.google.gson.Gson().fromJson(json, Snapshot.class); }
        catch (RuntimeException broken) { return null; }
    }

    private void saveSnapshot(SaoData.OathAccount a, SaoData.@Nullable PlayerCard card, @Nullable Path skinFile, boolean slim) {
        Snapshot s = new Snapshot(a.name(), a.profileId(), a.premiumName(), card == null ? -1 : card.level(), card == null ? -1 : card.col(),
                card == null ? 0 : card.lastPlayed(), card == null ? 0 : card.playSeconds(), skinFile == null ? null : skinFile.toString(), slim);
        String json = new com.google.gson.Gson().toJson(s);
        if (!json.equals(service.preference(SNAPSHOT_KEY, ""))) service.setPreference(SNAPSHOT_KEY, json);
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
        start.setDisable(s == SaoPackState.RUNNING);
        // First run with no pack: the 安装整合包 window comes up by itself, once.
        if (!firstRunShown && s != SaoPackState.CHECKING && SaoPackApp.notInstalled(service)) {
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
    private double fraction() {
        org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
        if (ip.stage() != org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE) {
            if (ip.totalBytes() > 0) return Math.min(1, (double) ip.bytesDone() / ip.totalBytes());
            if (ip.totalFiles() > 0) return Math.min(1, (double) ip.completedFiles() / ip.totalFiles());
        }
        return service.progress().get();
    }

    /// Whether a launch has to wait: no pack, a launcher below the minimum, or a BLOCK check. Says why and opens
    /// the window that fixes it.
    private boolean gated() {
        SaoData.UpdateStatus u = service.updateStatus().get();
        if (u.launcherBlocked()) {
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
    private void askOffline() {
        if (offlineName.getText().isBlank() && !name.getText().isBlank()) offlineName.setText(name.getText().strip());
        confirmOffline.setVisible(true);
        offlineName.requestFocus();
    }

    /// The main button: Link Start when ready; otherwise 下载 / 更新, which only prepares and needs no account.
    private void pressMain() {
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
        if (gated()) return;
        // James, 14 Nov: "按linkstart没有反应……我根本不知道". A click while something runs says what is running.
        SaoPackState now = service.state().get();
        if (now == SaoPackState.RUNNING) { notice("! 游戏已经在运行"); return; }
        if (now == SaoPackState.WORKING || now == SaoPackState.CHECKING) {
            org.jackhuang.hmcl.sao.SaoInstallProgress ip = service.installProgress().get();
            String doing = now == SaoPackState.CHECKING ? "检查文件" : !ip.label().isBlank() ? ip.label() : service.stepLabel().get();
            notice("! 正在" + (doing.isBlank() ? "准备" : doing) + "，完成后再按 Link Start");
            return;
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
        pad.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        cover.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        characterLayer.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = true);
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressOnWindow = false);
        addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            moving = !pressOnWindow && e.isPrimaryButtonDown() && getCursor() == null && getScene() != null
                    && getScene().getWindow() instanceof javafx.stage.Stage st && !st.isFullScreen()
                    && !org.jackhuang.hmcl.ui.decorator.WindowState.isMaximized(st);
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
                super(TexturesLoader.getDefaultSkinImage(), STAGE_W, STAGE_H, true);
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
            if (SWAY) canvas.getAnimationPlayer().addSkinAnimation(new SkinAniWavingArms(100, 2000, 7.5, canvas));
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

        /// Stops the sway while nobody can see it.
        void pause() {
            if (band != null) band.finish();
            canvas.getAnimationPlayer().stop();
        }

        /// Starts the sway again.
        void resume() {
            canvas.getAnimationPlayer().start();
        }

        /// Shows a skin, or the default one.
        ///
        /// @param png    the skin file, or null
        /// @param isSlim Slim arms
        void show(@Nullable Path png, boolean isSlim) {
            String key = skinKey(png, isSlim);
            if (key.equals(shownKey)) return;
            boolean first = shownKey == null;
            shownKey = key;
            Image img = png == null ? TexturesLoader.getDefaultSkinImage() : new Image(png.toUri().toString());
            if (img.isError()) img = TexturesLoader.getDefaultSkinImage();
            Image skinImage = img;
            if (first || !SaoMotion.on() || getScene() == null || !isVisible() || getWidth() <= 0) {
                if (band != null) band.finish();
                canvas.updateSkin(skinImage, isSlim, null);
                return;
            }
            if (band != null) band.finish();
            band = new SkinBand(skinImage, isSlim);
        }

        /// What the preview shows: the file's bytes (not its name or date) and the arm model; a refresh that brings the same
        /// skin back changes nothing and plays nothing.
        @Nullable private String shownKey;

        private static String skinKey(@Nullable Path png, boolean isSlim) {
            String what = "default";
            if (png != null) {
                try {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(java.nio.file.Files.readAllBytes(png));
                    what = Long.toHexString(crc.getValue());
                } catch (java.io.IOException e) {
                    what = png.toString();
                }
            }
            return what + (isSlim ? "/slim" : "/wide");
        }

        @Nullable private SkinBand band;

        /// The skin change of our Progressive opening's character creation (the mod's own opening, filmed 19 Oct,
        /// "film vs game 10-19/02", measured at 30 fps; James, 14 Nov: "参考我们做progressive opening时候的那个效果",
        /// "我不记得是扫过去的啊"): the old figure dims to a dark silhouette, flashes white, turns amber, the amber turns
        /// to white light with a halo, and the new skin comes out of the light from the head down while the halo goes.
        /// Drawn on two pictures of the model (before and after), with the sway held for the moment so they match.
        private final class SkinBand extends AnimationTimer {
            /// Milliseconds, measured on the opening (24.9-25.5 s and 27.1-28.3 s of the film).
            static final double DARK = 150, WHITE_AT = 220, WHITE = 100, AMBER_AT = 320, AMBER = 80, LIGHT_AT = 560,
                    LIGHT = 200, SHAPE_AT = 700, REVEAL_AT = 900, REVEAL = 350, GLOW_OUT = 1400, END = 1400;
            /// The figure's band of change as it takes its colours, a share of its height (FilmLoginFigure.BAND).
            static final double BAND = .22;
            /// The silhouette's colours as filmed: dark, the white flash and the light, amber.
            static final int DARK_INK = 0x2D2A21, LIGHT_INK = 0xFFF3CE, AMBER_INK = 0xC47A0A;
            private final int w, h, top, bottom;
            private final int[] from, to, out;
            private final javafx.scene.image.WritableImage picture;
            private final ImageView view, halo;
            private final boolean swaying;
            private long start = -1;
            private boolean done;

            SkinBand(Image skin, boolean isSlim) {
                double scale = getScene().getWindow() != null ? getScene().getWindow().getOutputScaleX() : 1;
                swaying = canvas.getAnimationPlayer().isRunning();
                if (swaying) canvas.getAnimationPlayer().stop();
                javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
                sp.setFill(Color.TRANSPARENT);
                sp.setTransform(javafx.scene.transform.Transform.scale(scale, scale));
                javafx.scene.image.WritableImage before = canvas.snapshot(sp, null);
                canvas.updateSkin(skin, isSlim, null);
                javafx.scene.image.WritableImage after = canvas.snapshot(sp, null);
                w = (int) Math.min(before.getWidth(), after.getWidth());
                h = (int) Math.min(before.getHeight(), after.getHeight());
                from = new int[w * h];
                to = new int[w * h];
                out = new int[w * h];
                javafx.scene.image.WritablePixelFormat<java.nio.IntBuffer> fmt = javafx.scene.image.PixelFormat.getIntArgbInstance();
                before.getPixelReader().getPixels(0, 0, w, h, fmt, from, 0, w);
                after.getPixelReader().getPixels(0, 0, w, h, fmt, to, 0, w);
                // the figure's own height (head to feet, both skins), so the colours come down the figure
                int t = h, b = -1;
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        if ((from[y * w + x] >>> 24) > 8 || (to[y * w + x] >>> 24) > 8) {
                            if (y < t) t = y;
                            b = y;
                            break;
                        }
                top = t;
                bottom = Math.max(b, t + 1);
                picture = new javafx.scene.image.WritableImage(w, h);
                view = new ImageView(picture);
                halo = new ImageView(picture);
                for (ImageView v : new ImageView[]{halo, view}) {
                    v.setFitWidth(w / scale);
                    v.setFitHeight(h / scale);
                    v.setManaged(false);
                    v.setMouseTransparent(true);
                    v.relocate(canvas.getBoundsInParent().getMinX(), canvas.getBoundsInParent().getMinY());
                }
                // the light's halo: the same picture, blurred and added
                halo.setEffect(new javafx.scene.effect.GaussianBlur(30 * scale));
                halo.setBlendMode(javafx.scene.effect.BlendMode.ADD);
                halo.setOpacity(0);
                paint(0);
                getChildren().addAll(halo, view);
                canvas.setOpacity(0);
                start();
            }

            @Override
            public void handle(long now) {
                if (start < 0) start = now;
                double t = (now - start) / 1e6;
                if (t >= END) {
                    finish();
                    return;
                }
                paint(t);
            }

            private static double ease(double x) {
                x = x < 0 ? 0 : x > 1 ? 1 : x;
                return x * x * (3 - 2 * x);
            }

            /// One moment of the change, t in milliseconds.
            private void paint(double t) {
                // the silhouette's flat colour now (the old figure under it until the light)
                double dark = ease(t / DARK), white = ease((t - WHITE_AT) / WHITE), amber = ease((t - AMBER_AT) / AMBER),
                        light = ease((t - LIGHT_AT) / LIGHT);
                int ink = mixRgb(mixRgb(mixRgb(DARK_INK, LIGHT_INK, white), AMBER_INK, amber), LIGHT_INK, light);
                double flat = t < LIGHT_AT ? dark : 1;
                int[] shape = t < SHAPE_AT ? from : to;
                double c = (t - REVEAL_AT) / REVEAL, front = -BAND + c * (1 + 2 * BAND), span = bottom - top;
                for (int y = 0; y < h; y++) {
                    double k = 0;
                    if (t >= REVEAL_AT) {
                        double s = (y - top) / span;
                        k = ease((front - s) / BAND * .5 + .5);
                    }
                    int row = y * w;
                    for (int x = 0; x < w; x++) {
                        int base = shape[row + x];
                        if ((base >>> 24) == 0 && k <= 0) {
                            out[row + x] = 0;
                            continue;
                        }
                        int sil = base & 0xff000000 | (flat >= 1 ? ink : mixRgb(base & 0xffffff, ink, flat));
                        out[row + x] = k <= 0 ? sil : k >= 1 ? to[row + x] : blend(sil, to[row + x], k);
                    }
                }
                picture.getPixelWriter().setPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), out, 0, w);
                // the halo: a little with the white flash, full with the light, gone as the colours come in
                double glow = Math.max(.35 * white * (1 - amber), light * (1 - ease((t - REVEAL_AT) / (GLOW_OUT - REVEAL_AT))));
                halo.setOpacity(glow);
            }

            private static int mixRgb(int a, int b, double k) {
                int r = (int) Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * k);
                int g = (int) Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * k);
                int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
                return r << 16 | g << 8 | bl;
            }

            private int blend(int a, int b, double k) {
                double aa = (a >>> 24) * (1 - k), ba = (b >>> 24) * k, sum = aa + ba;
                if (sum <= 0) return 0;
                int r = (int) Math.round(((a >> 16 & 255) * aa + (b >> 16 & 255) * ba) / sum);
                int g = (int) Math.round(((a >> 8 & 255) * aa + (b >> 8 & 255) * ba) / sum);
                int bl = (int) Math.round(((a & 255) * aa + (b & 255) * ba) / sum);
                return (int) Math.round(Math.min(255, sum)) << 24 | r << 16 | g << 8 | bl;
            }

            /// Ends at once: the real model again, swaying as before.
            void finish() {
                if (done) return;
                done = true;
                stop();
                getChildren().removeAll(halo, view);
                canvas.setOpacity(1);
                if (swaying) canvas.getAnimationPlayer().start();
                if (band == this) band = null;
            }
        }
    }
}
