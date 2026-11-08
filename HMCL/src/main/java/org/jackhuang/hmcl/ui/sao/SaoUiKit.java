/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/// The small parts the floating windows are built from, all in the glass look (sao.css, `vr-*`).
@NotNullByDefault
final class SaoUiKit {
    /// Not instantiable.
    private SaoUiKit() {
    }

    /// The launcher's UI face: the SAO UI font for Latin letters and digits, the system face for Chinese.
    ///
    /// @param size pixels
    /// @param bold the bold cut
    /// @return the font
    static Font ui(double size, boolean bold) {
        return SaoAssets.sao(readable(size) * textScale(), bold);
    }

    /// The nodes whose font follows 字体大小, with the size and cut they asked for.
    private static final java.util.Map<javafx.scene.Node, double[]> SIZED = new java.util.WeakHashMap<>();

    static {
        // James, 8 Nov: "加几个字体大小控制吧，我眼睛要瞎了": 设置 → 字体大小 sets this, and every text follows at once.
        var settings = settings();
        if (settings != null) settings.saoTextScaleProperty().addListener((o, a, b) -> {
            for (var e : new java.util.ArrayList<>(SIZED.entrySet())) apply(e.getKey(), e.getValue()[0], e.getValue()[1] != 0);
        });
    }

    /// The launcher's settings, or null before they are loaded (the screen-shot tool runs without them).
    static org.jackhuang.hmcl.setting.@org.jetbrains.annotations.Nullable LauncherSettings settings() {
        try {
            return org.jackhuang.hmcl.setting.SettingsManager.settings();
        } catch (IllegalStateException notLoaded) {
            return null;
        }
    }

    /// The 字体大小 factor.
    ///
    /// @return 0.9 to 1.3
    static double textScale() {
        var settings = settings();
        double k = settings == null ? 1 : settings.saoTextScaleProperty().get();
        return k >= 0.8 && k <= 1.5 ? k : 1;
    }

    /// Gives a text, a button or a field the UI face and keeps it in step with 字体大小.
    ///
    /// @param node the node
    /// @param size pixels at 标准
    /// @param bold the bold cut
    static void font(javafx.scene.Node node, double size, boolean bold) {
        SIZED.put(node, new double[]{size, bold ? 1 : 0});
        apply(node, size, bold);
    }

    /// Sets one node's font.
    private static void apply(javafx.scene.Node node, double size, boolean bold) {
        Font f = ui(size, bold);
        if (node instanceof Text t) t.setFont(f);
        else if (node instanceof javafx.scene.control.Labeled l) l.setFont(f);
        else if (node instanceof javafx.scene.control.TextInputControl c) c.setFont(f);
    }

    /// Small text one pixel larger (James, 8 Nov: "部分字体可以适当大一点，看着头疼，部分不要改"): the hint, label, link and
    /// status lines at 12.5 px and under; names, titles, fields and buttons keep their size.
    ///
    /// @param size the size the screen asks for
    /// @return the size drawn
    static double readable(double size) {
        return size <= 12.5 ? size + 1 : size;
    }

    /// An icon from HMCL's set.
    ///
    /// @param icon the icon
    /// @param size pixels
    /// @return the shape
    static Node icon(SVG icon, double size) {
        return icon(icon.getPath(), size);
    }

    /// Material Symbols paths HMCL's set does not have (Apache 2.0, the same family).
    static final String SPEAKER = "M24 24ZM0 0ZM14 20.725V18.65q2.25-.65 3.625-2.5T19 11.975q0-2.325-1.375-4.175T14 5.3V3.225q3.1.7 5.05 3.137T21 11.975q0 3.2-1.95 5.638T14 20.725ZM3 15V9h4l5-5v16l-5-5H3Zm11 1V7.95q1.175.55 1.838 1.65T16.5 12q0 1.275-.663 2.363T14 16Z",
            NOTE = "M24 24ZM0 0ZM10 21q-1.65 0-2.825-1.175T6 17q0-1.65 1.175-2.825T10 13q.575 0 1.063.138T12 13.55V3h6v4h-4v10q0 1.65-1.175 2.825T10 21Z",
            MUTED = "M24 24ZM0 0ZM19.8 22.6 16.775 19.575q-.625.4-1.325.688T14 20.725V18.65q.35-.125.688-.25T15.325 18.1L12 14.775V20l-5-5H3V9h3.2L1.4 4.2 2.8 2.8l18.4 18.4-1.4 1.4ZM19.6 16.8l-1.45-1.45q.425-.775.638-1.625T19 12q0-2.35-1.375-4.2T14 5.275V3.2q3.1.7 5.05 3.138T21 12q0 1.325-.363 2.55T19.6 16.8ZM16.25 13.45 14 11.2V7.95q1.175.55 1.838 1.65T16.5 12q0 .375-.062.738T16.25 13.45ZM12 9.2 9.4 6.6 12 4V9.2Z";

    /// An icon from an SVG path on the 24 x 24 grid.
    ///
    /// @param path the path, starting with the grid's corners
    /// @param size pixels
    /// @return the shape
    static Node icon(String path, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("vr-icon");
        double k = size / SVG.DEFAULT_SIZE;
        p.setScaleX(k);
        p.setScaleY(k);
        StackPane box = new StackPane(p);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    /// A round glass button holding an icon.
    ///
    /// @param icon     the icon
    /// @param size     the button's diameter
    /// @param iconSize the icon's size
    /// @return the button
    static Button round(SVG icon, double size, double iconSize) {
        Button b = new Button();
        b.setGraphic(icon(icon, iconSize));
        b.getStyleClass().add("vr-round");
        b.setMinSize(size, size);
        b.setPrefSize(size, size);
        b.setMaxSize(size, size);
        b.setFocusTraversable(false);
        return b;
    }

    /// A glass button with a label.
    ///
    /// @param text the label
    /// @return the button
    static Button button(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("vr-button");
        font(b, 13, false);
        b.setFocusTraversable(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    /// The lit button for the main action of a window.
    ///
    /// @param text the label
    /// @return the button
    static Button accent(String text) {
        Button b = button(text);
        b.getStyleClass().add("vr-accent");
        return b;
    }

    /// A chip in a group of choices.
    ///
    /// @param text  the label
    /// @param group the group, or null for an on/off chip
    /// @return the chip
    static ToggleButton chip(String text, @org.jetbrains.annotations.Nullable ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("vr-chip");
        font(b, 12, false);
        b.setFocusTraversable(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
        if (group != null) b.setToggleGroup(group);
        return b;
    }

    /// Keeps one chip of a group always selected.
    ///
    /// @param group the group
    static void keepOne(ToggleGroup group) {
        group.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) group.selectToggle(a);
        });
    }

    /// An on/off switch: a small pill with a knob.
    ///
    /// @param on whether it starts on
    /// @return the switch
    static ToggleButton toggle(boolean on) {
        ToggleButton t = new ToggleButton();
        t.getStyleClass().add("vr-switch");
        Region knob = new Region();
        knob.getStyleClass().add("vr-knob");
        StackPane track = new StackPane(knob);
        track.getStyleClass().add("vr-switch-track");
        track.setAlignment(Pos.CENTER_LEFT);
        track.setMouseTransparent(true);
        t.setGraphic(track);
        t.setSelected(on);
        knob.translateXProperty().bind(javafx.beans.binding.Bindings.when(t.selectedProperty()).then(16.0).otherwise(0.0));
        t.setFocusTraversable(false);
        return t;
    }

    /// A text field in the glass look.
    ///
    /// @param prompt the prompt text
    /// @return the field
    static TextField field(String prompt) {
        TextField f = new TextField();
        f.getStyleClass().add("vr-field");
        font(f, 14, false);
        f.setPromptText(prompt);
        return f;
    }

    /// Body text.
    ///
    /// @param s    the text
    /// @param size pixels
    /// @return the text
    static Text ink(String s, double size) {
        Text t = new Text(s);
        t.getStyleClass().add("vr-ink");
        font(t, size, false);
        return t;
    }

    /// Dim body text.
    ///
    /// @param s    the text
    /// @param size pixels
    /// @return the text
    static Text dim(String s, double size) {
        Text t = ink(s, size);
        t.getStyleClass().setAll("vr-dim");
        return t;
    }

    /// A number or Latin word in the SAO face.
    ///
    /// @param s    the text
    /// @param size pixels
    /// @param bold bold face
    /// @return the text
    static Text sao(String s, double size, boolean bold) {
        Text t = new Text(s);
        t.getStyleClass().add("vr-ink");
        t.setFont(ui(size, bold));
        return t;
    }

    /// A small label above a setting.
    ///
    /// @param label the label
    /// @return the row
    static HBox key(String label) {
        Text a = new Text(label);
        a.getStyleClass().add("vr-key");
        font(a, 12.5, true);
        HBox h = new HBox(a);
        h.setAlignment(Pos.BASELINE_LEFT);
        return h;
    }

    /// A line of glass between groups.
    ///
    /// @return the line
    static Region rule() {
        Region r = new Region();
        r.getStyleClass().add("vr-rule");
        r.setMinHeight(1);
        r.setPrefHeight(1);
        r.setMaxHeight(1);
        return r;
    }

    /// A gap that grows.
    ///
    /// @return the gap
    static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    /// A row with content on the left and actions on the right.
    ///
    /// @param left  the content
    /// @param right the actions
    /// @return the row
    static HBox row(Node left, Node... right) {
        HBox h = new HBox(8);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getChildren().add(left);
        h.getChildren().add(grow());
        h.getChildren().addAll(right);
        return h;
    }

    /// A list item: a small tile of glass.
    ///
    /// @param content the content
    /// @return the tile
    static HBox tile(Node... content) {
        HBox h = new HBox(10, content);
        h.getStyleClass().add("vr-tile");
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /// Two lines: a title and a dim line under it.
    ///
    /// @param title the title
    /// @param sub   the line under it
    /// @return the pair
    static VBox lines(String title, String sub) {
        VBox v = new VBox(2, ink(title, 13.5), dim(sub, 11.5));
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    /// A scrolling column with a transparent viewport.
    ///
    /// @param content the column
    /// @return the scroll pane
    static ScrollPane scroll(Node content) {
        ScrollPane s = new ScrollPane(content);
        s.getStyleClass().add("vr-scroll");
        s.setFitToWidth(true);
        s.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        s.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(s, Priority.ALWAYS);
        return s;
    }

    /// An empty state: an icon and one short line.
    ///
    /// @param icon the icon
    /// @param text the line
    /// @return the state
    static VBox empty(SVG icon, String text) {
        VBox v = new VBox(8, icon(icon, 34), dim(text, 13));
        v.setAlignment(Pos.CENTER);
        v.setOpacity(0.8);
        VBox.setVgrow(v, Priority.ALWAYS);
        return v;
    }

    /// A status light.
    ///
    /// @param on green when on, amber when off
    /// @return the light
    static Region light(boolean on) {
        Region r = new Region();
        r.getStyleClass().add(on ? "vr-light-on" : "vr-light-off");
        r.setMinSize(9, 9);
        r.setMaxSize(9, 9);
        return r;
    }

    /// A thin progress bar.
    ///
    /// @param width  pixels
    /// @param amount 0 to 1, or below 0 for unknown
    /// @return the bar
    static StackPane bar(double width, double amount) {
        Region track = new Region();
        track.getStyleClass().add("vr-track");
        track.setPrefSize(width, 3);
        track.setMaxSize(width, 3);
        Region fill = new Region();
        fill.getStyleClass().add("vr-fill");
        fill.setPrefSize(width * Math.max(0.03, Math.min(1, amount < 0 ? 0.3 : amount)), 3);
        fill.setMaxSize(Region.USE_PREF_SIZE, 3);
        StackPane bar = new StackPane(track, fill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(width);
        return bar;
    }

    /// Bytes as people read them.
    ///
    /// @param bytes the size
    /// @return for example "182 MB"
    static String size(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return bytes / (1024 * 1024) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /// A time as people read it.
    ///
    /// @param millis epoch milliseconds
    /// @return for example "3 天前"
    static String ago(long millis) {
        long m = (System.currentTimeMillis() - millis) / 60000;
        if (m < 1) return "刚刚";
        if (m < 60) return m + " 分钟前";
        if (m < 60 * 24) return m / 60 + " 小时前";
        return m / (60 * 24) + " 天前";
    }
}
