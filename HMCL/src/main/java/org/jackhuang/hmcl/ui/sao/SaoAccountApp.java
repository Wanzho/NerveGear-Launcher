/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Duration;
import javafx.collections.ListChangeListener;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// The 誓约账号 window (James, 7 Nov: "这样，你做个账号系统行不"): register, the mailed six-digit code, a forgotten
/// password and the signed-in account's own settings, one page at a time. Logging in itself is on the player window.
@NotNullByDefault
final class SaoAccountApp {
    /// Not instantiable.
    private SaoAccountApp() {
    }

    /// Which page opens first.
    enum Page { REGISTER, VERIFY, FORGOT, MANAGE }

    /// A character name: the game's own rule.
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /// A plausible mailbox; the server checks it for real.
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /// Builds the window.
    ///
    /// @param s     the engine
    /// @param close closes the window
    /// @param first the page to open on
    static SaoFloatWindow make(SaoLauncherService s, Runnable close, Page first) {
        StackPane pages = new StackPane();
        VBox.setVgrow(pages, Priority.ALWAYS);
        SaoFloatWindow w = new SaoFloatWindow("ARGUS ID", SaoApps.W, SaoApps.H, close).add(pages);
        Nav nav = new Nav(s, pages, close);
        nav.go(first);
        return w;
    }

    /// Moves between the pages.
    private static final class Nav {
        private final SaoLauncherService s;
        private final StackPane pages;
        private final Runnable close;

        Nav(SaoLauncherService s, StackPane pages, Runnable close) {
            this.s = s;
            this.pages = pages;
            this.close = close;
        }

        void go(Page p) {
            Node n = switch (p) {
                case REGISTER -> register();
                case VERIFY -> verify();
                case FORGOT -> forgot();
                case MANAGE -> manage();
            };
            pages.getChildren().setAll(n);
        }

        // -------------------------------------------------------------- register

        private Node register() {
            TextField name = field("3-16 位字母、数字、下划线");
            TextField email = field("用于接收验证码及找回密码");
            PasswordField pw = password(""), again = password("");
            Text problem = warn();
            Button go = accent("注册");
            go.setDefaultButton(true);
            go.setOnAction(e -> {
                String n = name.getText().strip(), m = email.getText().strip();
                if (!NAME.matcher(n).matches()) {
                    problem.setText("! 昵称 3-16 位：字母、数字、下划线");
                    return;
                }
                if (!EMAIL.matcher(m).matches()) {
                    problem.setText("! 邮箱格式不对");
                    return;
                }
                if (pw.getText().isEmpty()) {
                    problem.setText("! 请设置密码");
                    return;
                }
                if (!pw.getText().equals(again.getText())) {
                    problem.setText("! 两次密码不一样");
                    return;
                }
                char[] p = pw.getText().toCharArray();
                pw.clear();
                again.clear();
                call(go, problem, () -> s.oathRegister(n, m, p), () -> go(Page.VERIFY), p);
            });
            VBox form = form(
                    key("游戏昵称"), name,
                    key("邮箱"), email,
                    key("密码"), pw,
                    key("再输一次"), again,
                    problem, footer(go));
            // James, 7 Nov: "在这里也来个绑定正版账号，这样直接登录微软也可以等于登录sao".
            Button premium = button("绑定正版账号");
            premium.setOnAction(e -> withMicrosoft(() -> call(premium, problem, s::oathPremiumLogin, close)));
            Text premiumWhy = dim("拥有微软正版可以直接登录", 11);
            premiumWhy.setWrappingWidth(230);
            VBox below = new VBox(8, link("已有账号？去登录", close), rule(), premium, premiumWhy);
            return page(SVG.PERSON, "创建 ARGUS ID", "", form, below);
        }

        // -------------------------------------------------------------- verify

        private Node verify() {
            SaoData.@Nullable OathAccount a = s.oathAccount().get();
            String mailbox = a == null || a.maskedEmail().isEmpty() ? "你的邮箱" : a.maskedEmail();
            TextField code = field("6 位数字");
            code.textProperty().addListener((o, x, b) -> {
                String d = b.replaceAll("\\D", "");
                if (d.length() > 6) d = d.substring(0, 6);
                if (!d.equals(b)) code.setText(d);
            });
            code.setFont(SaoAssets.sao(22, true));
            Text problem = warn();
            Button go = accent("确认");
            go.setDefaultButton(true);
            go.setOnAction(e -> {
                if (code.getText().length() != 6) {
                    problem.setText("! 验证码是 6 位数字");
                    return;
                }
                call(go, problem, () -> s.oathVerify(code.getText()), () -> go(Page.MANAGE));
            });
            Button resend = button("重发");
            int[] wait = {60};
            Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                wait[0]--;
                resend.setText(wait[0] > 0 ? "重发 " + wait[0] + "s" : "重发");
                resend.setDisable(wait[0] > 0);
            }));
            tick.setCycleCount(60);
            Runnable startWait = () -> {
                wait[0] = 60;
                resend.setDisable(true);
                resend.setText("重发 60s");
                tick.playFromStart();
            };
            startWait.run();
            resend.setOnAction(e -> call(resend, problem, s::oathResend, startWait));
            String grace = a == null || a.graceSeconds() <= 0 ? "" : "，还剩 " + hours(a.graceSeconds());
            VBox form = form(
                    dim("验证码已发到 " + mailbox, 13),
                    key("验证码"), code,
                    problem, footer(resend, go));
            return page(SVG.CHAT, "验证邮箱", a != null && a.locked() ? "验证后才能进入游戏" : "两天内验证，过期进不了游戏" + grace, form,
                    link("改邮箱", () -> go(Page.MANAGE)));
        }

        // -------------------------------------------------------------- forgot

        private Node forgot() {
            TextField name = field("角色名");
            @Nullable String remembered = s.rememberedOathName();
            if (remembered != null) name.setText(remembered);
            TextField code = field("6 位数字");
            PasswordField pw = password(""), again = password("");
            Text problem = warn();
            Button send = button("发送验证码");
            Button go = accent("重置密码");
            go.setDefaultButton(true);
            VBox second = new VBox(6, key("验证码"), code, key("新密码"), pw, key("再输一次"), again);
            second.setDisable(true);
            send.setOnAction(e -> {
                String n = name.getText().strip();
                if (!NAME.matcher(n).matches()) {
                    problem.setText("! 先填角色名");
                    return;
                }
                call(send, problem, () -> s.oathForgot(n), () -> {
                    second.setDisable(false);
                    problem.setText("");
                    send.setText("已发送到绑定邮箱");
                    send.setDisable(true);
                });
            });
            go.disableProperty().bind(second.disabledProperty());
            go.setOnAction(e -> {
                if (code.getText().strip().length() != 6) {
                    problem.setText("! 验证码是 6 位数字");
                    return;
                }
                if (pw.getText().isEmpty() || !pw.getText().equals(again.getText())) {
                    problem.setText("! 两次密码不一样");
                    return;
                }
                char[] p = pw.getText().toCharArray();
                pw.clear();
                again.clear();
                call(go, problem, () -> s.oathReset(name.getText().strip(), code.getText().strip(), p), () -> {
                    problem.getStyleClass().setAll("vr-ink");
                    problem.setText("密码已重置，用新密码登录");
                }, p);
            });
            VBox form = form(
                    key("角色名"), new HBox(8, name, send),
                    second,
                    problem, footer(go));
            HBox.setHgrow(name, Priority.ALWAYS);
            return page(SVG.HELP, "忘记密码", "验证码发到注册时的邮箱", form, link("想起来了，去登录", close));
        }

        // -------------------------------------------------------------- manage

        private Node manage() {
            SaoData.@Nullable OathAccount a = s.oathAccount().get();
            if (a == null) return page(SVG.PERSON, "ARGUS ID", "还没有登录", empty(SVG.PERSON, "在主界面登录后再来"), link("注册新账号", () -> go(Page.REGISTER)));
            Text name = sao(a.name(), 26, true);
            Node mail = mailRow(a, () -> go(Page.VERIFY));

            TextField email = field("新邮箱");
            Text problem = warn();
            Button changeMail = button("换邮箱");
            changeMail.setOnAction(e -> {
                String m = email.getText().strip();
                if (!EMAIL.matcher(m).matches()) {
                    problem.setText("! 邮箱格式不对");
                    return;
                }
                call(changeMail, problem, () -> s.oathChangeEmail(m), () -> go(Page.VERIFY));
            });
            HBox.setHgrow(email, Priority.ALWAYS);

            // 游戏昵称 (James, 7 Nov: "把名字换成游戏昵称"): the pencil beside it on the home screen opens here. At most
            // three changes a day; the server says so when the fourth is tried.
            TextField newName = field("新昵称");
            Button rename = button("修改");
            rename.setOnAction(e -> {
                String n = newName.getText().strip();
                if (!NAME.matcher(n).matches()) {
                    problem.setText("! 昵称 3-16 位：字母、数字、下划线");
                    return;
                }
                call(rename, problem, () -> s.oathRename(n), () -> go(Page.MANAGE));
            });
            HBox.setHgrow(newName, Priority.ALWAYS);

            PasswordField old = password("当前密码"), neu = password("新密码"), again = password("再输一次");
            Button changePw = button("改密码");
            changePw.setOnAction(e -> {
                if (old.getText().isEmpty() || neu.getText().isEmpty()) {
                    problem.setText("! 填好当前密码和新密码");
                    return;
                }
                if (!neu.getText().equals(again.getText())) {
                    problem.setText("! 两次新密码不一样");
                    return;
                }
                char[] o = old.getText().toCharArray(), n = neu.getText().toCharArray();
                old.clear();
                neu.clear();
                again.clear();
                call(changePw, problem, () -> s.oathChangePassword(o, n), () -> {
                    problem.getStyleClass().setAll("vr-ink");
                    problem.setText("密码已更改");
                }, o, n);
            });

            Node premium;
            if (a.premiumName() != null) {
                HBox r = new HBox(8, light(true), ink("正版 " + a.premiumName() + " 已绑定", 13));
                r.setAlignment(Pos.CENTER_LEFT);
                premium = r;
            } else {
                Button link = button("绑定 Microsoft");
                link.setOnAction(e -> call(link, problem, s::oathLinkPremium, () -> go(Page.MANAGE)));
                premium = link;
            }
            Button out = button("退出登录");
            out.setOnAction(e -> {
                s.oathLogout();
                close.run();
            });

            VBox left = new VBox(10, name, mail, rule(), key("Microsoft"), premium, out);
            left.setMinWidth(260);
            left.setMaxWidth(260);
            VBox right = new VBox(7,
                    key("游戏昵称"), new HBox(8, newName, rename),
                    rule(),
                    key("邮箱"), new HBox(8, email, changeMail),
                    rule(),
                    key("密码"), old, neu, new HBox(8, again, changePw),
                    problem);
            HBox.setHgrow(again, Priority.ALWAYS);
            for (Button b : new Button[]{rename, changeMail, changePw}) b.setMinWidth(76);
            right.setMinWidth(320);
            right.setMaxWidth(320);
            return columns(left, right);
        }

        // -------------------------------------------------------------- helpers

        /// Runs an account call: the button waits, then the next step runs or the message shows. Wipes passwords.
        private static void call(Button b, Text problem, Supplier<CompletableFuture<Void>> f, Runnable then, char[]... wipe) {
            b.setDisable(true);
            problem.getStyleClass().setAll("vr-warn");
            problem.setText("");
            CompletableFuture<Void> c;
            try {
                c = f.get();
            } catch (RuntimeException ex) {
                c = CompletableFuture.failedFuture(ex);
            }
            c.whenComplete((ok, err) -> Platform.runLater(() -> {
                for (char[] p : wipe) Arrays.fill(p, '\0');
                b.setDisable(false);
                if (err == null) then.run();
                else problem.setText("! " + SaoSkinApp.message(err));
            }));
        }
    }

    /// The mailbox line: the address and whether it is verified, with 验证 when it is not.
    static Node mailRow(SaoData.OathAccount a, Runnable verify) {
        if (a.maskedEmail().isEmpty()) {
            Button add = button("绑定邮箱");
            add.setOnAction(e -> verify.run());
            HBox r = new HBox(8, light(false), dim("未绑定邮箱", 12), grow(), add);
            r.setAlignment(Pos.CENTER_LEFT);
            return r;
        }
        if (a.emailVerified()) {
            HBox r = new HBox(8, light(true), ink(a.maskedEmail(), 13), grow(), dim("已验证", 12));
            r.setAlignment(Pos.CENTER_LEFT);
            return r;
        }
        Button v = accent("验证");
        v.setOnAction(e -> verify.run());
        Text left = a.locked() ? warnText("已过期，验证后才能进游戏") : warnText("待验证 · 还剩 " + hours(a.graceSeconds()));
        VBox words = new VBox(2, ink(a.maskedEmail(), 13), left);
        HBox r = new HBox(8, light(false), words, grow(), v);
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    /// The Microsoft account signed in to the launcher, or null.
    static @Nullable MicrosoftAccount microsoftAccount() {
        if (Accounts.getSelectedAccount() instanceof MicrosoftAccount m) return m;
        for (Account a : Accounts.getAccounts()) if (a instanceof MicrosoftAccount m) return m;
        return null;
    }

    /// Runs a step that needs Microsoft: at once when signed in, else after the Microsoft sign-in dialog succeeds.
    static void withMicrosoft(Runnable then) {
        if (microsoftAccount() != null) {
            then.run();
            return;
        }
        @SuppressWarnings("unchecked") ListChangeListener<Account>[] once = new ListChangeListener[1];
        once[0] = c -> {
            if (microsoftAccount() == null) return;
            Accounts.getAccounts().removeListener(once[0]);
            then.run();
        };
        Accounts.getAccounts().addListener(once[0]);
        Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT));
    }

    /// "47 小时" or "12 分钟".
    static String hours(long seconds) {
        return seconds >= 3600 ? seconds / 3600 + " 小时" : Math.max(1, seconds / 60) + " 分钟";
    }

    /// A page: on the left its icon, title and one line; on the right the form.
    private static Node page(SVG icon, String title, String sub, Node form, Node below) {
        Text t = ink(title, 22);
        SaoUiKit.font(t, 22, true);
        Text line = dim(sub, 13);
        line.setWrappingWidth(230);
        StackPane badge = new StackPane(icon(icon, 30));
        badge.getStyleClass().add("vr-stage");
        badge.setMinSize(64, 64);
        badge.setMaxSize(64, 64);
        VBox left = new VBox(12, badge, t, line, below);
        if (sub.isEmpty()) left.getChildren().remove(line);
        left.setMinWidth(240);
        left.setMaxWidth(240);
        return columns(left, (VBox) form);
    }

    /// The form column.
    private static VBox form(Node... nodes) {
        VBox v = new VBox(6, nodes);
        v.setMinWidth(320);
        v.setMaxWidth(320);
        return v;
    }

    /// Two columns with a line between them.
    private static HBox columns(VBox left, VBox right) {
        javafx.scene.layout.Region line = new javafx.scene.layout.Region();
        line.getStyleClass().add("vr-rule");
        line.setMinWidth(1);
        line.setMaxWidth(1);
        line.setPrefHeight(250);
        line.setMaxHeight(Region.USE_PREF_SIZE);
        // The two columns sit together in the middle of the window (James, 7 Nov: "更中间一点").
        for (VBox v : new VBox[]{left, right}) {
            v.setMaxHeight(Region.USE_PREF_SIZE);
            v.setAlignment(Pos.CENTER_LEFT);
        }
        HBox h = new HBox(30, left, line, right);
        h.setAlignment(Pos.CENTER);
        VBox.setVgrow(h, Priority.ALWAYS);
        return h;
    }

    /// Buttons at the bottom right of a form.
    private static HBox footer(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_RIGHT);
        VBox.setMargin(h, new javafx.geometry.Insets(6, 0, 0, 0));
        return h;
    }

    /// A small text button.
    static Button link(String text, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().add("vr-link");
        SaoUiKit.font(b, 12, false);
        b.setFocusTraversable(false);
        b.setOnAction(e -> r.run());
        return b;
    }

    /// A password field in the glass look.
    static PasswordField password(String prompt) {
        PasswordField f = new PasswordField();
        f.getStyleClass().add("vr-field");
        SaoUiKit.font(f, 14, false);
        f.setPromptText(prompt);
        return f;
    }

    /// The line a form's problem shows in.
    private static Text warn() {
        Text t = new Text();
        t.getStyleClass().add("vr-warn");
        SaoUiKit.font(t, 12, false);
        t.setWrappingWidth(310);
        return t;
    }

    /// A warning line with text.
    private static Text warnText(String s) {
        Text t = warn();
        t.setWrappingWidth(0);
        t.setText(s);
        return t;
    }
}
