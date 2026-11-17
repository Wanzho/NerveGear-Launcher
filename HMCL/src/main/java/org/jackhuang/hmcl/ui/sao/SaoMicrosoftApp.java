/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import io.nayuki.qrcodegen.QrCode;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.jackhuang.hmcl.sao.SaoMicrosoftFlow;
import org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind;
import org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.util.QrCodeUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// The 微软正版登录 window (James, 13 Nov: "这你为什么不直接做个新界面，我不喜欢这个", "什么叫做你一点没restyle"):
/// the launcher's own look in place of HMCL's 添加微软账户 dialog. Same two columns as the ARGUS ID pages: what this
/// is on the left; on the right only the one thing to do now (the browser, or a code for the phone), then progress.
@NotNullByDefault
final class SaoMicrosoftApp {
    /// Not instantiable.
    private SaoMicrosoftApp() {
    }

    /// The accent of the lit buttons (sao.css .theme-sao .vr-accent).
    private static final Color ACCENT = Color.web("#f2a43a");

    /// Builds the window.
    ///
    /// @param start starts a sign-in (again, for 重试)
    /// @param close closes the window
    static SaoFloatWindow make(Supplier<SaoMicrosoftFlow> start, Runnable close) {
        return make(start, close, () -> { });
    }

    /// Keeps the caller's cancellation connected even after a terminal failed attempt.
    static SaoFloatWindow make(Supplier<SaoMicrosoftFlow> start, Runnable close, Runnable cancel) {
        StackPane body = new StackPane();
        VBox.setVgrow(body, Priority.ALWAYS);
        View v = new View(start, body, close, cancel);
        SaoFloatWindow w = new SaoFloatWindow("微软正版登录", SaoApps.W, SaoApps.H, v::closeByUser).add(body);
        v.begin();
        return w;
    }

    /// One window's sign-ins.
    private static final class View {
        private final Supplier<SaoMicrosoftFlow> start;
        private final StackPane body;
        private final Runnable close;
        /// Ends the caller across all retries when the user closes this window.
        private final Runnable cancel;
        private @Nullable SaoMicrosoftFlow flow;
        /// The state kind shown last, so only a new kind comes in.
        private @Nullable Kind shownKind;
        private final ChangeListener<State> follow = (o, a, b) -> show(b);

        View(Supplier<SaoMicrosoftFlow> start, StackPane body, Runnable close, Runnable cancel) {
            this.start = start;
            this.body = body;
            this.close = close;
            this.cancel = cancel;
        }

        void begin() {
            @Nullable SaoMicrosoftFlow old = flow;
            if (old != null) old.state().removeListener(follow);
            SaoMicrosoftFlow f = start.get();
            flow = f;
            f.state().addListener(follow);
            show(f.state().get());
        }

        /// The window's X: the sign-in stops with it.
        void closeByUser() {
            @Nullable SaoMicrosoftFlow f = flow;
            if (f != null) {
                f.state().removeListener(follow);
                f.cancel();
            }
            cancel.run();
            close.run();
        }

        private void show(State s) {
            SaoMicrosoftFlow f = flow;
            if (f == null) return;
            Node right = switch (s.kind()) {
                case STARTING -> working("正在打开登录页");
                case BROWSER -> browser(f, s);
                case DEVICE_CODE -> device(f, s);
                case SIGNING_IN -> working(s.step().isEmpty() ? "正在登录" : s.step());
                case DONE -> done(s);
                case FAILED -> failed(s);
                case CANCELLED -> working("已取消");
            };
            Text why = dim("没有正版也能玩：关掉这个窗口，用 ARGUS ID 登录", 11);
            why.setWrappingWidth(230);
            Node page = SaoAccountApp.page(SVG.PERSON, "微软正版登录", "用买了 Minecraft 的微软账号直接登录", right, why);
            body.getChildren().setAll(page);
            // A new state comes in on the right; a new step of the same state (正在验证 → 正在保存) just changes.
            if (s.kind() != shownKind) SaoMotion.enter(right);
            shownKind = s.kind();
            if (s.kind() == Kind.DONE || s.kind() == Kind.CANCELLED) {
                PauseTransition p = new PauseTransition(Duration.millis(s.kind() == Kind.DONE ? 1400 : 300));
                p.setOnFinished(e -> close.run());
                p.play();
            }
        }

        // -------------------------------------------------------------- browser

        private Node browser(SaoMicrosoftFlow f, State s) {
            Text head = ink("已在浏览器打开登录页", 18);
            SaoUiKit.font(head, 18, true);
            Text how = dim("在浏览器里登录微软账号，登录完会自动回到这里", 12.5);
            how.setWrappingWidth(330);
            Button again = accent("重新打开");
            again.setOnAction(e -> f.openBrowser());
            Text copied = copied();
            Button copy = button("复制链接");
            copy.setDisable(s.url().isEmpty());
            copy.setOnAction(e -> copy(s.url(), copied));
            HBox actions = new HBox(8, again, copy, copied);
            actions.setAlignment(Pos.CENTER_LEFT);
            Button phone = SaoAccountApp.link("用手机扫码登录", f::useDeviceCode);
            HBox other = new HBox(6, dim("浏览器打不开？", 12), phone);
            other.setAlignment(Pos.CENTER_LEFT);
            VBox v = SaoAccountApp.form(head, how, gap(10), actions, gap(14), rule(), gap(6), other);
            v.setSpacing(6);
            return v;
        }

        // -------------------------------------------------------------- device code

        private Node device(SaoMicrosoftFlow f, State s) {
            String uri = s.verificationUri().isEmpty() ? "https://www.microsoft.com/link" : s.verificationUri();
            Node qr = qr(uri);
            Text step1 = dim("用手机扫码，或在任意设备打开", 12);
            Button site = SaoAccountApp.link(uri.replaceFirst("^https?://(www\\.)?", ""), () -> SaoUi.linkOpener.accept(uri));
            Text step2 = dim("输入这个代码：", 12);
            Text code = sao(spaced(s.userCode()), 26, true);
            StackPane codeTile = new StackPane(code);
            codeTile.getStyleClass().add("vr-tile");
            codeTile.setMaxWidth(USE_PREF);
            codeTile.setPadding(new javafx.geometry.Insets(6, 14, 6, 14));
            Text copied = copied();
            Button copy = button("复制代码");
            copy.setOnAction(e -> copy(s.userCode(), copied));
            HBox codeRow = new HBox(8, codeTile, copy, copied);
            codeRow.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(6, step1, site, gap(4), step2, codeRow);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox top = new HBox(16, qr, text);
            top.setAlignment(Pos.CENTER_LEFT);
            HBox waiting = new HBox(8, spinner(12), dim("等你在手机上登录…", 12));
            waiting.setAlignment(Pos.CENTER_LEFT);
            Button back = SaoAccountApp.link("改用这台电脑的浏览器", f::openBrowser);
            VBox v = SaoAccountApp.form(top, gap(10), waiting, gap(6), rule(), gap(4), back);
            v.setSpacing(4);
            v.setMinWidth(360);
            v.setMaxWidth(360);
            return v;
        }

        /// The QR code, dark modules on a white tile so every phone reads it, in either look.
        private static Node qr(String uri) {
            QrCode code = QrCode.encodeText(uri, QrCode.Ecc.MEDIUM);
            SVGPath p = new SVGPath();
            p.setContent(QrCodeUtils.toSVGPath(code, 0));
            p.setFill(Color.web("#1d1e22"));
            double side = 112;
            double k = side / code.size;
            p.setScaleX(k);
            p.setScaleY(k);
            StackPane modules = new StackPane(new javafx.scene.Group(p));
            modules.setMinSize(side, side);
            modules.setMaxSize(side, side);
            Rectangle card = new Rectangle(side + 20, side + 20, Color.WHITE);
            card.setArcWidth(14);
            card.setArcHeight(14);
            return new StackPane(card, modules);
        }

        // -------------------------------------------------------------- progress, done, failed

        private Node working(String what) {
            Text t = ink(what, 15);
            HBox h = new HBox(12, spinner(18), t);
            h.setAlignment(Pos.CENTER_LEFT);
            return SaoAccountApp.form(h);
        }

        private Node done(State s) {
            Node ok = icon(SVG.CHECK_CIRCLE, 22);
            // Success lands with a glow (James, 14 Nov: "login时，成功等").
            SaoMotion.success(ok, 140);
            Text t = ink(s.name().isEmpty() ? "已登录" : "已登录：" + s.name(), 16);
            SaoUiKit.font(t, 16, true);
            HBox h = new HBox(10, ok, t);
            h.setAlignment(Pos.CENTER_LEFT);
            return SaoAccountApp.form(h);
        }

        private Node failed(State s) {
            Text head = ink("没有登录上", 18);
            SaoUiKit.font(head, 18, true);
            Text why = SaoAccountApp.warn();
            why.setText("! " + (s.message().isEmpty() ? "登录失败，请重试" : s.message()));
            Button retry = accent("重试");
            retry.setOnAction(e -> begin());
            Button phone = button("用手机扫码");
            phone.setOnAction(e -> {
                begin();
                @Nullable SaoMicrosoftFlow f = flow;
                if (f != null) f.useDeviceCode();
            });
            HBox actions = new HBox(8, retry, phone);
            actions.setAlignment(Pos.CENTER_LEFT);
            return SaoAccountApp.form(head, why, gap(10), actions);
        }

        // -------------------------------------------------------------- bits

        /// "已复制" next to the button that copied, fading out: no pop-up over the helmet.
        private static Text copied() {
            Text t = dim("已复制", 12);
            t.setOpacity(0);
            return t;
        }

        private static void copy(String text, Text note) {
            if (text.isEmpty()) return;
            ClipboardContent c = new ClipboardContent();
            c.putString(text);
            Clipboard.getSystemClipboard().setContent(c);
            note.setOpacity(1);
            FadeTransition f = new FadeTransition(Duration.millis(400), note);
            f.setDelay(Duration.millis(1400));
            f.setToValue(0);
            f.play();
        }
    }

    /// A thin turning arc in the accent colour.
    static Node spinner(double radius) {
        Arc a = new Arc(0, 0, radius, radius, 90, 270);
        a.setType(ArcType.OPEN);
        a.setFill(null);
        a.setStroke(ACCENT);
        a.setStrokeWidth(Math.max(2, radius / 6));
        a.setStrokeLineCap(StrokeLineCap.ROUND);
        StackPane box = new StackPane(a);
        box.setMinSize(radius * 2 + 4, radius * 2 + 4);
        box.setMaxSize(radius * 2 + 4, radius * 2 + 4);
        RotateTransition r = new RotateTransition(Duration.millis(900), a);
        r.setByAngle(360);
        r.setCycleCount(Animation.INDEFINITE);
        r.setInterpolator(Interpolator.LINEAR);
        r.play();
        return box;
    }

    private static final double USE_PREF = javafx.scene.layout.Region.USE_PREF_SIZE;

    private static javafx.scene.layout.Region gap(double h) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(h);
        r.setPrefHeight(h);
        return r;
    }

    /// The device code in two groups of four, easier to read off a screen.
    private static String spaced(String code) {
        String c = code.strip();
        return c.length() == 8 ? c.substring(0, 4) + " " + c.substring(4) : c;
    }

}
