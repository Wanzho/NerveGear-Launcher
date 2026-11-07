/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.sao.SaoAccountException;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.skin.SkinCube;
import org.jackhuang.hmcl.ui.skin.SkinHelper;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// The skin page (James, 7 Nov: "把classic slim等等塞到一个新的页面，玩家可以自定义皮肤，可以储存皮肤，可以直接在上面画个皮肤"):
/// the character in 3D to paint on directly, the tools and colours beside it, and the player's skin library.
/// Drag on the character to paint, drag beside it (or with the right button) to turn it.
@NotNullByDefault
final class SaoSkinApp {
    /// Not instantiable.
    private SaoSkinApp() {
    }

    /// The colours under the brush to start with: plain tones, skin, hair, and the anime's own (Kirito's black and
    /// grey, Asuna's red and white, the SAO orange).
    private static final String[] SWATCHES = {
            "#000000", "#3a3a3c", "#8e8e93", "#ffffff", "#f2c9a0", "#c68642", "#6b3e26", "#f5d76e",
            "#1c1c24", "#2c3e64", "#b0242c", "#e9e9ef", "#f39c12", "#2ecc71", "#3498db", "#9b59b6",
    };

    /// Builds the window.
    static SaoFloatWindow make(SaoLauncherService s, Runnable close) {
        Editor ed = new Editor();
        @Nullable Path start = s.oathSkin() != null ? s.oathSkin() : s.skin();
        ed.load(start == null ? TexturesLoader.getDefaultSkinImage() : new Image(start.toUri().toString()), s.skinSlim());

        // ---- tools
        ToggleGroup tools = new ToggleGroup();
        ToggleButton pen = chip("画笔", tools), erase = chip("橡皮", tools), pick = chip("吸管", tools), fill = chip("填充", tools);
        // Shade the colour already there, as the web skin editors do (James: "you can refer to minecraftskins.com").
        ToggleButton darker = chip("加深", tools), lighter = chip("提亮", tools);
        // Paint one side and the other side gets the same (right arm to left arm, the head and body across).
        ToggleButton mirror = chip("镜像", null);
        keepOne(tools);
        tools.selectToggle(pen);
        ToggleGroup layers = new ToggleGroup();
        ToggleButton inner = chip("内层", layers), outer = chip("外层", layers);
        keepOne(layers);
        layers.selectToggle(inner);
        // Shows or hides the outer layer while painting the base.
        ToggleButton showOuter = chip("", null);
        showOuter.setGraphic(icon(SVG.VISIBILITY, 14));
        showOuter.setSelected(true);
        ToggleGroup arms = new ToggleGroup();
        ToggleButton classic = chip("Classic", arms), slim = chip("Slim", arms);
        keepOne(arms);
        arms.selectToggle(ed.slim ? slim : classic);
        // The base layer has no see-through in the game: the eraser works on the outer layer only.
        erase.disableProperty().bind(inner.selectedProperty());
        inner.selectedProperty().addListener((o, a, on) -> {
            if (on && erase.isSelected()) tools.selectToggle(pen);
        });

        // ---- colour
        ColourBox colour = new ColourBox(Color.web("#2c3e64"));
        FlowPane swatches = new FlowPane(4, 4);
        swatches.setPrefWrapLength(8 * 18 + 7 * 4);
        for (String hex : SWATCHES) {
            Color c = Color.web(hex);
            Region sw = new Region();
            sw.getStyleClass().add("vr-swatch");
            sw.setStyle("-fx-background-color: " + hex + ";");
            sw.setMinSize(18, 18);
            sw.setMaxSize(18, 18);
            sw.setOnMouseClicked(e -> colour.set(c));
            swatches.getChildren().add(sw);
        }

        for (ToggleButton t : new ToggleButton[]{pen, erase, pick, fill, darker, lighter, mirror, inner, outer, showOuter})
            t.getStyleClass().add("vr-chip-tight");

        // ---- the editor's wiring
        ed.tool = () -> pen.isSelected() ? Tool.PEN : erase.isSelected() ? Tool.ERASE : pick.isSelected() ? Tool.PICK
                : darker.isSelected() ? Tool.DARKER : lighter.isSelected() ? Tool.LIGHTER : Tool.FILL;
        ed.mirror = mirror::isSelected;
        ed.colour = colour::get;
        ed.onPicked = c -> {
            colour.set(c);
            tools.selectToggle(pen);
        };
        layers.selectedToggleProperty().addListener((o, a, b) -> ed.setLayer(b == outer, showOuter.isSelected()));
        showOuter.selectedProperty().addListener((o, a, b) -> ed.setLayer(outer.isSelected(), b));
        arms.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) ed.setSlim(b == slim);
        });

        Button undo = round(SVG.UNDO, 28, 15), redo = round(SVG.REDO, 28, 15), front = round(SVG.RESTORE, 28, 15);
        undo.setOnAction(e -> ed.undo());
        redo.setOnAction(e -> ed.redo());
        front.setOnAction(e -> ed.faceFront());
        HBox viewBar = new HBox(6, undo, redo, grow(), classic, slim, grow(), front);
        viewBar.setAlignment(Pos.CENTER_LEFT);
        viewBar.setMaxWidth(Editor.W);
        Text hint = dim("在人物上拖动画画，旁边拖动转身", 11);
        // Hide a part to reach what it covers (the sides of the body behind the arms), as the web editors do.
        HBox parts = new HBox(3);
        String[] partNames = {"头", "身", "右臂", "左臂", "右腿", "左腿"};
        for (int i = 0; i < partNames.length; i++) {
            int k = i;
            ToggleButton c = chip(partNames[i], null);
            c.getStyleClass().add("vr-chip-small");
            c.setSelected(true);
            c.selectedProperty().addListener((o, a, on) -> ed.showPart(k, on));
            parts.getChildren().add(c);
        }
        parts.setAlignment(Pos.TOP_CENTER);
        parts.setMaxHeight(Region.USE_PREF_SIZE);
        parts.setPickOnBounds(false);
        StackPane.setAlignment(parts, Pos.TOP_CENTER);
        StackPane.setMargin(parts, new Insets(6, 0, 0, 0));
        ed.getChildren().add(parts);
        VBox left = new VBox(6, ed, viewBar, hint);
        left.setMinWidth(Editor.W);
        left.setMaxWidth(Editor.W);

        Text status = dim("", 12);
        status.setWrappingWidth(170);
        VBox tools1 = new VBox(7,
                key("工具"), new HBox(5, pen, erase, pick, fill), new HBox(5, darker, lighter, grow(), mirror),
                key("图层"), new HBox(5, inner, outer, showOuter),
                key("颜色"), colour, swatches);
        tools1.setMinWidth(184);
        tools1.setMaxWidth(184);

        // ---- the library
        TextField name = field("起个名字");
        name.setPrefWidth(110);
        Button keep = button("存入");
        keep.setOnAction(e -> {
            String n = name.getText().strip();
            if (n.isEmpty()) n = "皮肤 " + (s.savedSkins().size() + 1);
            try {
                s.saveSkin(n, ed.png(), ed.slim);
                name.clear();
                status.setText("已存入皮肤库");
            } catch (IllegalArgumentException | IOException ex) {
                status.setText("! 存不进去");
            }
        });
        VBox library = SaoApps.live(s.savedSkins(), k -> libraryRow(s, ed, k, arms, slim, classic, status),
                empty(SVG.CHECKROOM, "还没有存过皮肤"));
        Button importPng = button("导入");
        importPng.setOnAction(e -> {
            FileChooser c = new FileChooser();
            c.setTitle("导入皮肤 (64×64 PNG)");
            c.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
            @Nullable File f = c.showOpenDialog(Controllers.getStage());
            if (f == null) return;
            Image img = new Image(f.toURI().toString());
            if (img.isError() || !SkinHelper.isSkin(img)) {
                status.setText("! 皮肤需为 64×64 PNG");
                return;
            }
            ed.snapshot();
            ed.load(img, ed.slim);
            status.setText("已导入，可以接着画");
        });
        Button exportPng = button("导出");
        exportPng.setOnAction(e -> {
            FileChooser c = new FileChooser();
            c.setTitle("导出皮肤");
            c.setInitialFileName("skin.png");
            c.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
            @Nullable File f = c.showSaveDialog(Controllers.getStage());
            if (f == null) return;
            try {
                Files.write(f.toPath(), ed.png());
                status.setText("已导出");
            } catch (IOException ex) {
                status.setText("! 导出失败");
            }
        });
        Button reset = button("默认");
        reset.setOnAction(e -> {
            ed.snapshot();
            ed.load(TexturesLoader.getDefaultSkinImage(), ed.slim);
        });
        Button apply = accent("穿上");
        apply.setMaxWidth(Double.MAX_VALUE);
        apply.setOnAction(e -> {
            if (s.oathAccount().get() == null) {
                status.setText("! 先登录誓约账号，别人才看得到");
                return;
            }
            try {
                Path tmp = Files.createTempFile("sao-skin-", ".png");
                Files.write(tmp, ed.png());
                apply.setDisable(true);
                status.setText("正在上传…");
                s.uploadSkin(tmp, ed.slim).whenComplete((ok, err) -> javafx.application.Platform.runLater(() -> {
                    apply.setDisable(false);
                    status.setText(err == null ? "已穿上，所有人都看得到" : "! " + message(err));
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // a temporary file
                    }
                }));
            } catch (IOException ex) {
                status.setText("! 上传失败");
            }
        });
        for (Button b : new Button[]{importPng, exportPng, reset}) b.getStyleClass().add("vr-button-tight");
        HBox keepRow = new HBox(6, name, keep);
        keepRow.setAlignment(Pos.CENTER_LEFT);
        VBox right = new VBox(8,
                key("皮肤库"), keepRow, scroll(library),
                new HBox(6, importPng, exportPng, reset),
                status, apply);
        VBox.setVgrow(right.getChildren().get(2), Priority.ALWAYS);
        right.setMinWidth(170);
        right.setMaxWidth(170);

        Region l1 = line(), l2 = line();
        HBox body = new HBox(16, left, l1, tools1, l2, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        SaoFloatWindow w = new SaoFloatWindow("皮肤", SaoApps.W, SaoApps.H, close).add(body);
        w.body.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.Z) {
                if (e.isShiftDown()) ed.redo();
                else ed.undo();
                e.consume();
            }
        });
        return w;
    }

    /// One saved skin in the library: its face, its name, wear it in the editor, or remove it.
    private static Node libraryRow(SaoLauncherService s, Editor ed, SaoData.SavedSkin k, ToggleGroup arms,
                                   ToggleButton slim, ToggleButton classic, Text status) {
        Image img = new Image(k.file().toUri().toString());
        Canvas face = headOf(img, 30);
        Text n = ink(k.name(), 13);
        Text arm = dim(k.slim() ? "Slim" : "Classic", 11);
        VBox words = new VBox(1, n, arm);
        Button del = round(SVG.DELETE, 22, 12);
        del.setOnAction(e -> s.deleteSavedSkin(k));
        HBox row = new HBox(8, face, words, grow(), del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("vr-tile");
        row.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Node t && isInside(t, del)) return;
            ed.snapshot();
            ed.load(img, k.slim());
            arms.selectToggle(k.slim() ? slim : classic);
            status.setText("已载入「" + k.name() + "」");
        });
        return row;
    }

    /// Whether a node is a button or inside it.
    private static boolean isInside(Node n, Node parent) {
        for (@Nullable Node p = n; p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    /// The front of a skin's head (base and hat), drawn pixel for pixel.
    private static Canvas headOf(Image skin, double size) {
        Canvas c = new Canvas(size, size);
        GraphicsContext g = c.getGraphicsContext2D();
        if (skin.isError() || skin.getWidth() < 64) return c;
        PixelReader r = skin.getPixelReader();
        double k = size / 8, sx = skin.getWidth() / 64;
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int base = r.getArgb((int) ((8 + x) * sx), (int) ((8 + y) * sx));
                int hat = r.getArgb((int) ((40 + x) * sx), (int) ((8 + y) * sx));
                g.setFill(argb(base | 0xff000000));
                g.fillRect(x * k, y * k, k, k);
                if ((hat >>> 24) != 0) {
                    g.setFill(argb(hat));
                    g.fillRect(x * k, y * k, k, k);
                }
            }
        return c;
    }

    /// A vertical line between columns.
    private static Region line() {
        Region l = new Region();
        l.getStyleClass().add("vr-rule");
        l.setMinWidth(1);
        l.setMaxWidth(1);
        return l;
    }

    /// The message of a failed account call.
    static String message(Throwable err) {
        Throwable t = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
        return t instanceof SaoAccountException a ? a.getMessage() : "连接账号服务器失败";
    }

    /// An ARGB int as a colour.
    static Color argb(int v) {
        return Color.rgb((v >> 16) & 255, (v >> 8) & 255, v & 255, ((v >>> 24) & 255) / 255.0);
    }

    /// A colour as an opaque ARGB int.
    static int argb(Color c) {
        return 0xff000000 | (int) Math.round(c.getRed() * 255) << 16 | (int) Math.round(c.getGreen() * 255) << 8
                | (int) Math.round(c.getBlue() * 255);
    }

    // ------------------------------------------------------------------ the colour

    /// The brush colour: a hue strip and a saturation/brightness square, the colour itself and its hex.
    private static final class ColourBox extends VBox {
        private static final double SW = 184, SH = 44;
        private final Canvas square = new Canvas(SW, SH), hues = new Canvas(SW, 10);
        private final Region now = new Region();
        private final TextField hex = field("#rrggbb");
        private double hue, sat, bri;

        ColourBox(Color c) {
            super(5);
            now.setMinSize(22, 22);
            now.setMaxSize(22, 22);
            now.getStyleClass().add("vr-swatch");
            hex.setPrefWidth(96);
            hex.setOnAction(e -> {
                try {
                    set(Color.web(hex.getText().strip()));
                } catch (IllegalArgumentException ex) {
                    hex.setText(toHex(get()));
                }
            });
            HBox top = new HBox(8, now, hex);
            top.setAlignment(Pos.CENTER_LEFT);
            square.setOnMousePressed(this::onSquare);
            square.setOnMouseDragged(this::onSquare);
            hues.setOnMousePressed(this::onHue);
            hues.setOnMouseDragged(this::onHue);
            GraphicsContext g = hues.getGraphicsContext2D();
            for (int x = 0; x < SW; x++) {
                g.setFill(Color.hsb(x / SW * 360, 1, 1));
                g.fillRect(x, 0, 1, 10);
            }
            getChildren().addAll(top, square, hues);
            set(c);
        }

        Color get() {
            return Color.hsb(hue, sat, bri);
        }

        void set(Color c) {
            hue = c.getHue();
            sat = c.getSaturation();
            bri = c.getBrightness();
            redraw();
        }

        private void onSquare(MouseEvent e) {
            sat = clamp(e.getX() / SW);
            bri = 1 - clamp(e.getY() / SH);
            redraw();
        }

        private void onHue(MouseEvent e) {
            hue = clamp(e.getX() / SW) * 359.9;
            redraw();
        }

        private void redraw() {
            GraphicsContext g = square.getGraphicsContext2D();
            for (int x = 0; x < SW; x += 2)
                for (int y = 0; y < SH; y += 2) {
                    g.setFill(Color.hsb(hue, x / SW, 1 - y / SH));
                    g.fillRect(x, y, 2, 2);
                }
            g.setStroke(bri > 0.5 ? Color.BLACK : Color.WHITE);
            g.strokeOval(sat * SW - 4, (1 - bri) * SH - 4, 8, 8);
            now.setStyle("-fx-background-color: " + toHex(get()) + ";");
            hex.setText(toHex(get()));
        }

        private static double clamp(double v) {
            return Math.max(0, Math.min(1, v));
        }

        private static String toHex(Color c) {
            return String.format("#%06x", argb(c) & 0xffffff);
        }
    }

    // ------------------------------------------------------------------ the editor

    /// What a press on the character does.
    private enum Tool { PEN, ERASE, PICK, FILL, DARKER, LIGHTER }

    /// The character to paint on: both layers as boxes whose faces carry the skin's pixels, so a press on a face
    /// is a press on a pixel of the 64×64 skin.
    private static final class Editor extends StackPane {
        static final double W = 236, H = 270;
        /// The texture is drawn this many times larger, pixel for pixel, so the boxes show hard pixels.
        private static final int UP = 8;
        /// The skin, 64×64 ARGB.
        private final int[] px = new int[64 * 64];
        private final WritableImage tex = new WritableImage(64 * UP, 64 * UP);
        private final PhongMaterial innerMat = new PhongMaterial(), outerMat = new PhongMaterial();
        private final Group model = new Group();
        private final Rotate yaw = new Rotate(-24, Rotate.Y_AXIS), tilt = new Rotate(-8, Rotate.X_AXIS);
        private final Group innerParts = new Group(), outerParts = new Group();
        private final Deque<int[]> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
        boolean slim;
        private boolean outerLayer, outerShown = true;
        java.util.function.Supplier<Tool> tool = () -> Tool.PEN;
        java.util.function.Supplier<Color> colour = () -> Color.BLACK;
        java.util.function.BooleanSupplier mirror = () -> false;
        /// Each part's base and outer box, head, body, right arm, left arm, right leg, left leg; and which show.
        private final SkinCube[] ins = new SkinCube[6], outs = new SkinCube[6];
        private final boolean[] partShown = {true, true, true, true, true, true};
        /// The pixels shaded in this stroke, so a drag shades each pixel once.
        private final java.util.BitSet shaded = new java.util.BitSet(64 * 64);
        java.util.function.Consumer<Color> onPicked = c -> {
        };
        /// The drag in progress: turning the character, or painting (with the last pixel painted).
        private boolean turning;
        private int lastX = -1, lastY = -1;
        private double pressX, pressY, pressYaw, pressTilt;

        Editor() {
            getStyleClass().add("vr-stage");
            setMinSize(W, H);
            setMaxSize(W, H);
            innerMat.setDiffuseMap(tex);
            outerMat.setDiffuseMap(tex);
            build();
            model.getTransforms().addAll(new Translate(0, 2, 0), yaw, tilt);
            Group root = new Group(new AmbientLight(Color.WHITE), model);
            SubScene view = new SubScene(root, W, H, true, SceneAntialiasing.BALANCED);
            view.setFill(Color.TRANSPARENT);
            PerspectiveCamera cam = new PerspectiveCamera(true);
            cam.setFarClip(1000);
            cam.getTransforms().addAll(new Rotate(180, Rotate.Y_AXIS), new Translate(0, 0, -78));
            view.setCamera(cam);
            getChildren().add(view);
            view.addEventHandler(MouseEvent.MOUSE_PRESSED, this::press);
            view.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::drag);
            view.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> lastX = lastY = -1);
        }

        /// Builds the six parts, base then outer, the outer ones a little larger as in the game.
        private void build() {
            innerParts.getChildren().clear();
            outerParts.getChildren().clear();
            float aw = slim ? 3 : 4, as = slim ? 14F / 64F : 16F / 64F;
            double ax = (8 + aw) / 2.0;
            part(8, 8, 8, 32 / 64F, 16 / 64F, 0, 0, 32 / 64F, 0, 1.0F, 0, -10);
            part(8, 12, 4, 24 / 64F, 16 / 64F, 16 / 64F, 16 / 64F, 16 / 64F, 32 / 64F, 0.5F, 0, 0);
            // The player's right arm and leg are on the screen's left when facing them (the camera looks back at +z).
            part(aw, 12, 4, as, 16 / 64F, 40 / 64F, 16 / 64F, 40 / 64F, 32 / 64F, 0.5F, +ax, 0);
            part(aw, 12, 4, as, 16 / 64F, 32 / 64F, 48 / 64F, 48 / 64F, 48 / 64F, 0.5F, -ax, 0);
            part(4, 12, 4, 16 / 64F, 16 / 64F, 0, 16 / 64F, 0, 32 / 64F, 0.5F, +2, 12);
            part(4, 12, 4, 16 / 64F, 16 / 64F, 16 / 64F, 48 / 64F, 0, 48 / 64F, 0.5F, -2, 12);
            model.getChildren().setAll(innerParts, outerParts);
            setLayer(outerLayer, outerShown);
        }

        /// One body part: the base box and the outer box over it.
        private void part(float w, float h, float d, float scaleX, float scaleY, float inX, float inY,
                          float outX, float outY, float grow, double x, double y) {
            SkinCube in = new SkinCube(w, h, d, scaleX, scaleY, inX, inY, 0, slim);
            in.setMaterial(innerMat);
            SkinCube out = new SkinCube(w, h, d, scaleX, scaleY, outX, outY, grow, slim);
            out.setMaterial(outerMat);
            for (SkinCube c : new SkinCube[]{in, out}) {
                c.setTranslateX(x);
                c.setTranslateY(y);
            }
            int k = innerParts.getChildren().size();
            ins[k] = in;
            outs[k] = out;
            in.setVisible(partShown[k]);
            out.setVisible(partShown[k]);
            innerParts.getChildren().add(in);
            outerParts.getChildren().add(out);
        }

        /// Paints on the base or the outer layer, and whether the outer one shows.
        void setLayer(boolean outer, boolean shown) {
            outerLayer = outer;
            outerShown = shown;
            outerParts.setVisible(outer || shown);
            outerParts.setMouseTransparent(!outer);
            innerParts.setMouseTransparent(outer);
        }

        /// Shows or hides one part, both layers.
        void showPart(int k, boolean on) {
            partShown[k] = on;
            if (ins[k] != null) ins[k].setVisible(on);
            if (outs[k] != null) outs[k].setVisible(on);
        }

        void setSlim(boolean s) {
            slim = s;
            build();
        }

        void faceFront() {
            yaw.setAngle(-24);
            tilt.setAngle(-8);
        }

        /// Loads a skin (64×64, or 64×32 widened the game's way).
        void load(Image img, boolean isSlim) {
            Image full = img.getHeight() * 2 == img.getWidth() ? SkinHelper.x32Tox64(img) : img;
            PixelReader r = full.getPixelReader();
            double k = full.getWidth() / 64;
            for (int y = 0; y < 64; y++)
                for (int x = 0; x < 64; x++) px[y * 64 + x] = r.getArgb((int) (x * k), (int) (y * k));
            if (isSlim != slim) setSlim(isSlim);
            redraw();
        }

        /// Draws the skin into the texture, each pixel as a square of UP × UP.
        private void redraw() {
            int n = 64 * UP;
            int[] big = new int[n * n];
            for (int y = 0; y < n; y++) {
                int row = (y / UP) * 64;
                for (int x = 0; x < n; x++) {
                    int v = px[row + x / UP];
                    // The base layer is always opaque in the game; only the outer layer is see-through.
                    big[y * n + x] = v;
                }
            }
            tex.getPixelWriter().setPixels(0, 0, n, n, PixelFormat.getIntArgbInstance(), big, 0, n);
            // Hand the changed picture to the boxes again so the GPU copy follows.
            innerMat.setDiffuseMap(null);
            innerMat.setDiffuseMap(tex);
            outerMat.setDiffuseMap(null);
            outerMat.setDiffuseMap(tex);
        }

        private void press(MouseEvent e) {
            pressX = e.getX();
            pressY = e.getY();
            pressYaw = yaw.getAngle();
            pressTilt = tilt.getAngle();
            @Nullable Point2D p = pixel(e.getPickResult());
            turning = e.getButton() != MouseButton.PRIMARY || p == null;
            if (turning) return;
            Tool t = tool.get();
            if (t == Tool.PICK) {
                int v = px[(int) p.getY() * 64 + (int) p.getX()];
                onPicked.accept(argb(v | 0xff000000));
                turning = true;
                return;
            }
            snapshot();
            shaded.clear();
            if (t == Tool.FILL) {
                fill((int) p.getX(), (int) p.getY());
                turning = true;
                return;
            }
            paint((int) p.getX(), (int) p.getY());
        }

        private void drag(MouseEvent e) {
            if (turning) {
                yaw.setAngle(pressYaw - (e.getX() - pressX) * 0.6);
                tilt.setAngle(Math.max(-60, Math.min(60, pressTilt + (e.getY() - pressY) * 0.4)));
                return;
            }
            @Nullable Point2D p = pixel(e.getPickResult());
            if (p != null) paint((int) p.getX(), (int) p.getY());
        }

        /// The skin pixel under a press, or null when the press missed the character.
        private static @Nullable Point2D pixel(@Nullable PickResult r) {
            if (r == null || !(r.getIntersectedNode() instanceof SkinCube)) return null;
            @Nullable Point2D uv = r.getIntersectedTexCoord();
            if (uv == null) return null;
            int x = (int) Math.floor(uv.getX() * 64), y = (int) Math.floor(uv.getY() * 64);
            if (x < 0 || y < 0 || x > 63 || y > 63) return null;
            return new Point2D(x, y);
        }

        /// Paints one pixel, joining it to the last one when they are close (a quick stroke on one face).
        private void paint(int x, int y) {
            Tool t = tool.get();
            int v = t == Tool.ERASE ? 0 : argb(colour.get());
            if (lastX >= 0 && Math.max(Math.abs(x - lastX), Math.abs(y - lastY)) <= 3) {
                int dx = Math.abs(x - lastX), dy = -Math.abs(y - lastY), sx = lastX < x ? 1 : -1, sy = lastY < y ? 1 : -1;
                int err = dx + dy, cx = lastX, cy = lastY;
                while (true) {
                    put(cx, cy, t, v);
                    if (cx == x && cy == y) break;
                    int e2 = 2 * err;
                    if (e2 >= dy) {
                        err += dy;
                        cx += sx;
                    }
                    if (e2 <= dx) {
                        err += dx;
                        cy += sy;
                    }
                }
            } else {
                put(x, y, t, v);
            }
            lastX = x;
            lastY = y;
            redraw();
        }

        /// Sets one pixel with the tool, and its mirror twin when mirroring.
        private void put(int x, int y, Tool t, int v) {
            set1(x, y, t, v);
            if (mirror.getAsBoolean()) {
                int @Nullable [] m = twin(x, y);
                if (m != null && (m[0] != x || m[1] != y)) set1(m[0], m[1], t, v);
            }
        }

        private void set1(int x, int y, Tool t, int v) {
            int i = y * 64 + x;
            if (t == Tool.DARKER || t == Tool.LIGHTER) {
                if (shaded.get(i)) return;
                shaded.set(i);
                int o = px[i];
                if ((o >>> 24) == 0) return;
                Color c = argb(o);
                Color d = t == Tool.DARKER ? c.deriveColor(0, 1, 0.88, 1) : c.deriveColor(0, 1, 1.12, 1);
                px[i] = (o & 0xff000000) | (argb(d) & 0xffffff);
            } else {
                px[i] = v;
            }
        }

        /// The boxes of the 64×64 skin: origin x, y, then width, height, depth; and each one's mirror partner.
        private int[][] boxes() {
            int aw = slim ? 3 : 4;
            return new int[][]{
                    {0, 0, 8, 8, 8}, {32, 0, 8, 8, 8}, {16, 16, 8, 12, 4}, {16, 32, 8, 12, 4},
                    {40, 16, aw, 12, 4}, {40, 32, aw, 12, 4}, {32, 48, aw, 12, 4}, {48, 48, aw, 12, 4},
                    {0, 16, 4, 12, 4}, {0, 32, 4, 12, 4}, {16, 48, 4, 12, 4}, {0, 48, 4, 12, 4},
            };
        }

        /// Box index to its partner across the body: head and body to themselves, right limbs to left limbs.
        private static final int[] PARTNER = {0, 1, 2, 3, 6, 7, 4, 5, 10, 11, 8, 9};

        /// The six faces of a box: top, bottom, right side, front, left side, back (x, y, width, height).
        private static int[][] faces(int[] b) {
            int ox = b[0], oy = b[1], w = b[2], h = b[3], d = b[4];
            return new int[][]{
                    {ox + d, oy, w, d}, {ox + d + w, oy, w, d},
                    {ox, oy + d, d, h}, {ox + d, oy + d, w, h}, {ox + d + w, oy + d, d, h}, {ox + 2 * d + w, oy + d, w, h},
            };
        }

        /// The pixel that mirrors this one across the body, or null for a pixel on no face.
        private int @Nullable [] twin(int x, int y) {
            int[][] bs = boxes();
            for (int bi = 0; bi < bs.length; bi++) {
                int[][] fs = faces(bs[bi]);
                for (int fi = 0; fi < 6; fi++) {
                    int[] f = fs[fi];
                    if (x < f[0] || y < f[1] || x >= f[0] + f[2] || y >= f[1] + f[3]) continue;
                    int[] g = faces(bs[PARTNER[bi]])[fi == 2 ? 4 : fi == 4 ? 2 : fi];
                    int u = x - f[0], v = y - f[1];
                    return new int[]{g[0] + (g[2] - 1 - u), g[1] + v};
                }
            }
            return null;
        }

        /// Fills the run of one colour around a pixel, staying on its face of the box.
        private void fill(int x, int y) {
            int[] face = faceOf(x, y);
            if (face == null) return;
            int from = px[y * 64 + x], to = argb(colour.get());
            if (from == to) return;
            Deque<int[]> todo = new ArrayDeque<>();
            todo.push(new int[]{x, y});
            while (!todo.isEmpty()) {
                int[] q = todo.pop();
                int qx = q[0], qy = q[1];
                if (qx < face[0] || qy < face[1] || qx >= face[0] + face[2] || qy >= face[1] + face[3]) continue;
                if (px[qy * 64 + qx] != from) continue;
                px[qy * 64 + qx] = to;
                todo.push(new int[]{qx + 1, qy});
                todo.push(new int[]{qx - 1, qy});
                todo.push(new int[]{qx, qy + 1});
                todo.push(new int[]{qx, qy - 1});
            }
            redraw();
        }

        /// The face rectangle of the 64×64 skin a pixel belongs to: x, y, width, height.
        private int @Nullable [] faceOf(int x, int y) {
            for (int[] b : boxes())
                for (int[] f : faces(b))
                    if (x >= f[0] && y >= f[1] && x < f[0] + f[2] && y < f[1] + f[3]) return f;
            return null;
        }

        /// Keeps the skin as it is now for undo.
        void snapshot() {
            undo.push(px.clone());
            while (undo.size() > 60) undo.removeLast();
            redo.clear();
        }

        void undo() {
            if (undo.isEmpty()) return;
            redo.push(px.clone());
            System.arraycopy(undo.pop(), 0, px, 0, px.length);
            redraw();
        }

        void redo() {
            if (redo.isEmpty()) return;
            undo.push(px.clone());
            System.arraycopy(redo.pop(), 0, px, 0, px.length);
            redraw();
        }

        /// The skin as PNG bytes.
        byte[] png() throws IOException {
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            int[] out = px.clone();
            // The game draws the base layer opaque: store it that way so every viewer sees the same.
            for (int[] b : new int[][]{{0, 0, 32, 16}, {16, 16, 24, 16}, {40, 16, 16, 16}, {0, 16, 16, 16}, {16, 48, 16, 16}, {32, 48, 16, 16}})
                for (int y = b[1]; y < b[1] + b[3]; y++)
                    for (int x = b[0]; x < b[0] + b[2]; x++) out[y * 64 + x] |= 0xff000000;
            img.setRGB(0, 0, 64, 64, out, 0, 64);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return bytes.toByteArray();
        }
    }
}
