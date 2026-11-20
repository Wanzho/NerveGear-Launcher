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

import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.sao.SaoReadiness;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// Builds the helmet home windows and handles their account and friend controls.
@NotNullByDefault
final class SaoHelmetDashboard {
    /// Supplies the existing launcher state and callbacks.
    private final SaoHelmetPage owner;

    /// Creates the component for its owning SaoHelmetPage.
    SaoHelmetDashboard(SaoHelmetPage owner) {
        this.owner = owner;
    }

    /// News, on the left, turned toward the player.
    SaoFloatWindow buildNews() {
        VBox list = SaoApps.live(owner.service.news(), n -> {
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
            v.setOnMouseClicked(e -> owner.openNews(n));
            // Lifts a little under the mouse, like the buttons (SaoMotion).
            v.hoverProperty().addListener((o, x, on) -> SaoMotion.lift(v, on));
            return v;
        }, empty(SVG.CHAT, "暂无公告"));
        return new SaoFloatWindow("公告", 290, 430, null)
                .add(scroll(list))
                .home(246, 175, 40, -24, 0);
    }

    /// The server, on the right, turned toward the player.
    SaoFloatWindow buildServer() {
        VBox box = new VBox(10);
        VBox.setVgrow(box, Priority.ALWAYS);
        // The address under the title, and 其他节点 opening the other nodes (James, 7 Nov: "更多改成其他节点").
        Text address = sao(owner.service.serverAddress(), 19, true);
        VBox nodes = new VBox(4);
        Button[] moreRef = new Button[1];
        Button more = SaoAccountApp.link("其他节点", () -> {
            boolean open = !nodes.isVisible();
            nodes.setVisible(open);
            nodes.setManaged(open);
            moreRef[0].setText(open ? "收起" : "其他节点");
        });
        moreRef[0] = more;
        owner.openNodes = () -> {
            if (!nodes.isVisible()) more.fire();
        };
        // The manifest loads in the background (launcherback5): read the address and nodes again once it has.
        Runnable fillNodes = () -> {
            address.setText(owner.service.serverAddress());
            nodes.getChildren().clear();
            for (String n : owner.service.serverNodes()) {
                // No light: the launcher does not ping the nodes.
                Text t = sao(n, 12, false);
                t.getStyleClass().setAll("vr-dim");
                nodes.getChildren().add(t);
            }
        };
        fillNodes.run();
        owner.service.state().addListener((o, a, b) -> fillNodes.run());
        nodes.setVisible(false);
        nodes.setManaged(false);
        HBox addressRow = new HBox(8, address, grow(), more);
        addressRow.setAlignment(Pos.CENTER_LEFT);
        VBox where = new VBox(6, addressRow, nodes);
        // 维护中 (James, 10 Nov: "如果服务器在维护中，启动器要在右边面板上说明"): the signed notice under the address,
        // whatever the ping says. Its text is the backend's, shown as is; a long one scrolls inside the tile.
        Runnable show = () -> {
            @Nullable SaoData.ServerStatus st = owner.service.serverStatus().get();
            SaoData.@Nullable Maintenance m = owner.service.updateStatus().get().maintenance();
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
                Text t = sao(SaoPlayerNames.display(n), 12, false);
                StackPane chip = new StackPane(t);
                chip.getStyleClass().add("vr-name");
                names.getChildren().add(chip);
            }
            box.getChildren().addAll(state, rule(), players);
            // During maintenance the notice takes the room of the MOTD and the name chips.
            if (m == null) box.getChildren().addAll(motd, names);
        };
        owner.service.serverStatus().addListener((o, a, b) -> show.run());
        // The signed policy can turn maintenance on or off and move the address.
        owner.service.updateStatus().addListener((o, a, b) -> {
            fillNodes.run();
            show.run();
        });
        show.run();
        Button refresh = round(SVG.REFRESH, 26, 14);
        // The button also asks for the signed policy again; the open call and the 60 s timer do not.
        refresh.setOnAction(e -> owner.service.refreshOnline(true));
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

    SaoFloatWindow buildPlayer() {
        // James, 9 Nov: "左边只放人，logo放到好友在线这里": the logo-49 title fills the room under the friends, signed
        // in; it shrinks when warnings take that room and hides when too little is left.
        ImageView logo = SaoAssets.logo(SaoHelmetPage.RIGHT_W, 150);
        logo.getStyleClass().add("vr-logo");
        StackPane logoBox = new StackPane(logo);
        logoBox.setMinHeight(0);
        VBox.setVgrow(logoBox, Priority.ALWAYS);
        logoBox.heightProperty().addListener((o, a, h) -> {
            double fit = Math.min(170, h.doubleValue() - 4);
            logo.setFitHeight(Math.max(1, fit));
            logo.setVisible(SaoHelmetPage.SHOW_PLAYER_LOGO && fit >= 48);
        });

        Button editSkin = round(SVG.EDIT, 30, 15);
        editSkin.setOnAction(e -> owner.openSkin());
        StackPane.setAlignment(editSkin, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(editSkin, new Insets(7));
        // The character and its pencil; drawn over the 3D space while the window rests (see [#placeCharacter()]).
        owner.characterBox.getChildren().setAll(owner.skin, editSkin);
        for (Region b : new Region[]{owner.characterBox, owner.characterSlot}) {
            b.setMinSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
            b.setPrefSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
            b.setMaxSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
        }
        owner.characterSlot.getChildren().setAll(owner.characterBox);
        StackPane stage = owner.characterSlot;
        stage.getStyleClass().add("vr-stage");
        // James, 8 Nov: "中间这个界面，里面东西搞稍微大点，留白有点多，密码的那个点可以小一点": the stage and its
        // turntable grow with the rest (150 x 214 before).
        stage.setMinSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
        stage.setPrefSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
        stage.setMaxSize(SaoHelmetPage.STAGE_W, SaoHelmetPage.STAGE_H);
        // The left: only the character and its pencil (James, 9 Nov: "左边只放人").
        VBox left = new VBox(stage);
        left.setAlignment(Pos.CENTER_LEFT);

        // ---- signed out
        @Nullable String remembered = owner.service.rememberedOathName();
        if (remembered != null) owner.name.setText(remembered);
        owner.name.textProperty().addListener((o, a, b) -> owner.refreshPrompt());
        owner.remember.getStyleClass().addAll("vr-check", "vr-check-big");
        owner.remember.setFocusTraversable(false);
        owner.remember.setSelected(true);
        owner.remember.setGraphic(icon(SVG.CHECK, 14));
        owner.remember.getGraphic().visibleProperty().bind(owner.remember.selectedProperty());
        SaoMotion.press(owner.remember);
        owner.remember.selectedProperty().addListener((o, a, on) -> { if (on) SaoMotion.pop(owner.remember.getGraphic()); });
        Text rememberLabel = dim("记住我", 14);
        rememberLabel.setOnMouseClicked(e -> owner.remember.setSelected(!owner.remember.isSelected()));
        // James, 14 Nov: a new player's first step was a small grey 注册 link; it is a button beside 登录 now.
        HBox rememberRow = new HBox(7, owner.remember, rememberLabel, grow(),
                bigLink("忘记密码", () -> owner.openAccount(SaoAccountApp.Page.FORGOT)));
        rememberRow.setAlignment(Pos.CENTER_LEFT);
        for (javafx.scene.control.TextField f : new javafx.scene.control.TextField[]{owner.name, owner.pw, owner.offlineName}) {
            SaoUiKit.font(f, 16, false);
            f.setMinHeight(SaoHelmetPage.FIELD_H);
            f.setPrefHeight(SaoHelmetPage.FIELD_H);
        }
        Button register = SaoUiKit.button("注册");
        register.setOnAction(e -> owner.openAccount(SaoAccountApp.Page.REGISTER));
        register.setMinWidth(88);
        HBox loginRow = new HBox(8, owner.login, register);
        HBox.setHgrow(owner.login, javafx.scene.layout.Priority.ALWAYS);
        for (Button b : new Button[]{owner.login, register, owner.useMicrosoft}) {
            SaoUiKit.font(b, 15, false);
            b.setMinHeight(SaoHelmetPage.BUTTON_H);
            b.setPrefHeight(SaoHelmetPage.BUTTON_H);
        }
        owner.login.setMaxWidth(Double.MAX_VALUE);
        owner.login.setOnAction(e -> owner.login());
        owner.login.defaultButtonProperty().bind(owner.signedOut.visibleProperty());
        owner.useMicrosoft.setGraphic(microsoftLogo(16));
        owner.useMicrosoft.setMaxWidth(Double.MAX_VALUE);
        owner.useMicrosoft.setOnAction(e -> premiumLogin());
        HBox outHead = new HBox(8, sao("ARGUS ID", 17, true), grow(), dim("未登录", 14));
        outHead.setAlignment(Pos.CENTER_LEFT);
        // James, 8 Nov: "可以选择不登录任何东西，顶着个名字，但是提醒窗口写这样就登录不了咱服务器".
        HBox offlineRow = new HBox(bigLink("不登录，离线游玩", owner::askOffline));
        offlineRow.setAlignment(Pos.CENTER);
        owner.signedOut.getChildren().setAll(outHead, bigKey("用户名／邮箱"), owner.name, bigKey("密码"), owner.pw, rememberRow, loginRow, rule(), owner.useMicrosoft, offlineRow);
        // Every side of the account panel comes in, row after row, when it is shown (James, 14 Nov: "login时，成功等").
        SaoMotion.reveal(owner.signedOut);
        SaoMotion.reveal(owner.confirmOut);
        SaoMotion.reveal(owner.confirmOffline);
        SaoMotion.reveal(owner.confirmName);

        // ---- signed in: filled by refreshAccount; Link Start at its bottom right
        HBox linkRow = buildLinkRow();

        owner.problem.getStyleClass().add("vr-warn");
        SaoMotion.report(owner.problem);
        SaoUiKit.font(owner.problem, 13, false);
        owner.problem.setWrappingWidth(SaoHelmetPage.RIGHT_W - 6);
        StackPane sides = new StackPane(owner.signedOut, owner.signedIn, owner.confirmOffline);
        for (VBox v : new VBox[]{owner.signedOut, owner.signedIn, owner.confirmOffline}) {
            StackPane.setAlignment(v, Pos.TOP_LEFT);
            // Only the side shown takes room: the taller sign-in form, hidden, pushed Link Start out of the window.
            v.managedProperty().bind(v.visibleProperty());
        }
        // Signed in, the logo takes the room between the friends and Link Start; elsewhere a plain spring.
        Region spring = grow();
        SaoMotion.revealOne(logoBox);
        logoBox.visibleProperty().bind(owner.signedIn.visibleProperty());
        logoBox.managedProperty().bind(owner.signedIn.visibleProperty());
        spring.visibleProperty().bind(owner.signedIn.visibleProperty().not());
        spring.managedProperty().bind(owner.signedIn.visibleProperty().not());
        owner.locationRecovery.setVisible(false); owner.locationRecovery.setManaged(false);
        owner.locationRecovery.setOnAction(event -> owner.openApp(new SaoApps.App(SVG.SETTINGS, "游戏位置", SaoApps::settings), null));
        VBox right = new VBox(6, sides, logoBox, spring, owner.problem, owner.locationRecovery, linkRow);
        right.setPrefWidth(SaoHelmetPage.RIGHT_W);
        right.setMinWidth(SaoHelmetPage.RIGHT_W);
        right.setMaxWidth(SaoHelmetPage.RIGHT_W);
        // James, 11 Nov: "要啊": before login the row shows too, but only as 下载 / 更新, which need no account.
        // Link Start itself still needs the login or 离线进入.
        javafx.beans.binding.BooleanBinding rowShown = Bindings.createBooleanBinding(
                () -> owner.signedIn.isVisible() || owner.service.readiness().get().shownSignedOut(),
                owner.signedIn.visibleProperty(), owner.service.readiness());
        SaoMotion.revealOne(linkRow);
        linkRow.visibleProperty().bind(rowShown);
        linkRow.managedProperty().bind(rowShown);
        // Shown before login, the row takes the signed-in layout's slot and bottom inset: the form's nine gaps close
        // from 10 to 5 so the column is no taller than the signed-in one. Signed out and ready, nothing changes.
        owner.signedOut.spacingProperty().bind(Bindings.when(rowShown.and(owner.signedIn.visibleProperty().not())).then(5.0).otherwise(10.0));
        HBox bodyRow = new HBox(SaoHelmetPage.GAP, left, right);

        // 确定退出吗？ in place of the body until answered.
        Text sure = ink("确定退出吗？", 18);
        SaoUiKit.font(sure, 18, true);
        Text after = dim("下次打开要重新输入密码", 12);
        Button no = SaoUiKit.button("取消"), yes = SaoUiKit.button("退出登录");
        yes.getStyleClass().add("vr-danger");
        HBox answers = new HBox(10, no, yes);
        answers.setAlignment(Pos.CENTER);
        owner.confirmOut.getChildren().setAll(sure, after, answers);
        owner.confirmOut.setAlignment(Pos.CENTER);
        owner.confirmOut.setVisible(false);
        owner.confirmOut.visibleProperty().addListener((o, a, shown) -> bodyRow.setVisible(!shown));
        no.setOnAction(e -> owner.confirmOut.setVisible(false));
        yes.setOnAction(e -> {
            owner.confirmOut.setVisible(false);
            owner.startupPending = false;
            owner.service.setPreference(SaoHelmetPage.SNAPSHOT_KEY, "");
            owner.service.oathLogout();
        });
        // 确定改成…吗？, the same way.
        Button nameNo = SaoUiKit.button("取消"), nameYes = SaoUiKit.accent("确定");
        HBox nameAnswers = new HBox(10, nameNo, nameYes);
        nameAnswers.setAlignment(Pos.CENTER);
        owner.confirmName.setAlignment(Pos.CENTER);
        owner.confirmName.setVisible(false);
        owner.confirmName.getProperties().put("answers", nameAnswers);
        owner.confirmName.visibleProperty().addListener((o, a, shown) -> bodyRow.setVisible(!shown && !owner.confirmOut.isVisible()));
        nameNo.setOnAction(e -> cancelRenamePrompt());
        nameYes.setOnAction(e -> {
            owner.confirmName.setVisible(false);
            Runnable action = owner.confirmNameAction;
            owner.confirmNameAction = () -> { };
            action.run();
        });
        // 离线游玩: a name, and the warning that it cannot reach the 誓约 server.
        Text offTitle = ink("离线游玩", 17);
        SaoUiKit.font(offTitle, 17, true);
        Text offWarn = dim("离线进不了誓约服务器，只能玩单人游戏", 13);
        offWarn.getStyleClass().setAll("vr-warn");
        Text offProblem = dim("", 14);
        offProblem.getStyleClass().setAll("vr-warn");
        // Wrapped, or its width would push the column past the window's edge.
        offProblem.setWrappingWidth(SaoHelmetPage.RIGHT_W - 6);
        Button offNo = SaoUiKit.button("取消"), offYes = SaoUiKit.accent("离线进入");
        for (Button b : new Button[]{offNo, offYes}) {
            SaoUiKit.font(b, 15, false);
            b.setMinHeight(SaoHelmetPage.BUTTON_H);
            b.setPrefHeight(SaoHelmetPage.BUTTON_H);
        }
        HBox offAnswers = new HBox(10, offNo, offYes);
        offAnswers.setAlignment(Pos.CENTER);
        // James, 8 Nov: "离线游玩不要开个新的页面，只把右边的页面改了就行": it takes the sign-in's place on the right.
        HBox offHead = new HBox(8, offTitle);
        offHead.setAlignment(Pos.CENTER_LEFT);
        owner.offlineName.setMaxWidth(Double.MAX_VALUE);
        offWarn.setWrappingWidth(SaoHelmetPage.RIGHT_W - 6);
        offAnswers.setAlignment(Pos.CENTER_LEFT);
        owner.confirmOffline.getChildren().setAll(offHead, bigKey("名字"), owner.offlineName, offWarn, offProblem, offAnswers);
        owner.confirmOffline.setAlignment(Pos.TOP_LEFT);
        owner.confirmOffline.setVisible(false);
        owner.confirmOffline.visibleProperty().addListener((o, a, shown) -> {
            if (owner.service.oathAccount().get() == null) owner.signedOut.setVisible(!shown);
        });
        offNo.setOnAction(e -> owner.confirmOffline.setVisible(false));
        offYes.setOnAction(e -> {
            String n = owner.offlineName.getText().strip();
            if (!n.matches("[A-Za-z0-9_]{3,16}")) {
                offProblem.setText("名字要 3 到 16 位，只能用英文字母、数字和下划线");
                return;
            }
            offProblem.setText("");
            owner.confirmOffline.setVisible(false);
            owner.begin(() -> owner.service.linkStartOffline(n), true);
        });
        owner.offlineName.setOnAction(e -> offYes.fire());
        StackPane body = new StackPane(bodyRow, owner.confirmOut, owner.confirmName);
        VBox.setVgrow(body, Priority.ALWAYS);

        owner.service.oathAccount().addListener((o, a, b) -> {
            if (a != null && (b == null || !a.profileId().equals(b.profileId()))) cancelRenamePrompt();
            refreshAccount();
        });
        owner.service.playerCard().addListener((o, a, b) -> refreshAccount());
        Accounts.getAccounts().addListener((ListChangeListener<Account>) c -> refreshAccount());
        // Shown from the last session's snapshot until the background sign-in answers; if nothing is signing in a few
        // seconds after start (no remembered account), the snapshot goes.
        owner.startupPending = snapshot() != null;
        PauseTransition settle = new PauseTransition(Duration.seconds(4));
        settle.setOnFinished(e -> { if (owner.startupPending && !owner.autoLogging && !owner.microsoftSigningIn() && owner.service.oathAccount().get() == null) { owner.startupPending = false; refreshAccount(); } });
        settle.play();
        refreshAccount();
        owner.autoLogin();
        // 448 x 410 before; still clear of 公告 and 服务器 (their near edges at x 396 and 884) and centred on the
        // same middle, y 391.
        SaoFloatWindow w = new SaoFloatWindow("", SaoHelmetPage.PLAYER_W, SaoHelmetPage.PLAYER_H, null).add(body);
        w.body.setPadding(new Insets(18, 16, 18, 16));
        return w.home(640, 391 - SaoHelmetPage.PLAYER_H / 2, 0, 0, 0);
    }

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
            owner.problem.setText("正在用 Microsoft 登录…");
            SaoMotion.busy(owner.useMicrosoft);
            owner.service.oathPremiumLogin().whenComplete((ok, err) -> Platform.runLater(() -> {
                SaoMotion.idle(owner.useMicrosoft);
                owner.problem.setText(err == null ? "" : "! " + SaoSkinApp.message(err));
            }));
        });
    }

    /// Link Start inside the player window, bottom right. While the pack downloads or installs, only a progress bar
    /// across the row (James, 7 Nov: "那个就绪可以删了，下载的时候linkstart别显示然后只有进度条就行").
    private HBox buildLinkRow() {
        double bw = SaoHelmetPage.RIGHT_W;
        Region track = new Region();
        track.getStyleClass().add("vr-track");
        track.setPrefSize(bw, 4);
        track.setMaxSize(bw, 4);
        owner.fill.getStyleClass().add("vr-fill");
        owner.fill.setPrefHeight(4);
        owner.fill.setMaxHeight(4);
        owner.fill.setMaxWidth(Region.USE_PREF_SIZE);
        // The fill glides to each new amount (James, 14 Nov: "可以给所有东西都加动效/动画吗").
        SaoMotion.follow(owner.fill, Bindings.createDoubleBinding(() -> {
            SaoPackState s = owner.service.state().get();
            return s == SaoPackState.WORKING ? bw * Math.max(0.03, owner.fraction()) : 0.0;
        }, owner.service.state(), owner.service.progress(), owner.service.installProgress()));
        StackPane track2 = new StackPane(track, owner.fill);
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
            SaoPackState s = owner.service.state().get();
            double p = owner.fraction();
            org.jackhuang.hmcl.sao.SaoInstallProgress ip = owner.service.installProgress().get();
            boolean measured = ip.stage() != org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE;
            String label = measured && !ip.label().isBlank() ? ip.label() : owner.service.stepLabel().get();
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
            for (SaoData.Task t : owner.service.tasks()) {
                if (t.done()) continue;
                open++;
                bps += t.bytesPerSecond();
            }
            speed.setText(bps > 0 ? speedText(bps) : "");
            left.setText(open > 0 ? "剩 " + open + " 项" : "");
        };
        owner.service.state().addListener((o, a, b) -> words.run());
        owner.service.progress().addListener((o, a, b) -> words.run());
        owner.service.stepLabel().addListener((o, a, b) -> words.run());
        owner.service.installProgress().addListener((o, a, b) -> words.run());
        owner.service.tasks().addListener((ListChangeListener<SaoData.Task>) c -> words.run());
        words.run();
        owner.start.getStyleClass().add("vr-start");
        SaoUiKit.font(owner.start, 22, true);
        owner.start.setMinWidth(Region.USE_PREF_SIZE);
        owner.start.setOnAction(e -> owner.pressMain());
        owner.start.defaultButtonProperty().bind(owner.signedIn.visibleProperty());
        // James, 11 Nov: "link start 是启动游戏，下载还是老实叫下载吧": 下载 / 更新 until everything is there.
        Runnable label = () -> {
            SaoReadiness r = owner.service.readiness().get();
            if (r.kind() != SaoReadiness.Kind.CHECKING) owner.start.setText(r.buttonText());
        };
        owner.service.readiness().addListener((o, a, b) -> label.run());
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
        StackPane slot = new StackPane(bar, versionBox, owner.start);
        StackPane.setAlignment(owner.start, Pos.CENTER_RIGHT);
        slot.setMinHeight(48);
        HBox.setHgrow(slot, Priority.ALWAYS);
        Runnable show = () -> {
            SaoPackState s = owner.service.state().get();
            boolean busy = s == SaoPackState.WORKING || s == SaoPackState.CHECKING;
            bar.setVisible(busy);
            owner.start.setVisible(!busy);
            @Nullable String v = owner.service.installedVersion().get();
            // installedVersion() only: a thin launcher has no pack of its own ("v未内置整合包" never shows).
            version.setText(v != null ? "v" + v : "未安装");
            versionBox.setVisible(!busy);
            SaoData.UpdateStatus u = owner.service.updateStatus().get();
            SaoData.LauncherUpdate lu = owner.service.launcherUpdate().get();
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
            notice.setOnAction(e -> owner.openPack(m));
            notice.setVisible(!what.isEmpty());
            notice.setManaged(notice.isVisible());
        };
        owner.service.state().addListener((o, a, b) -> show.run());
        owner.service.installedVersion().addListener((o, a, b) -> show.run());
        owner.service.updateStatus().addListener((o, a, b) -> show.run());
        owner.service.launcherUpdate().addListener((o, a, b) -> show.run());
        show.run();
        HBox row = new HBox(slot);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// The 游戏昵称 edited in place: the field with the name, save and cancel, and whether the name typed is free
    /// (names never collide: "名字不能撞，永远不能").
    private Node nameEditor(SaoData.OathAccount a) {
        TextField f = field("游戏昵称");
        f.setText(owner.typedName != null ? owner.typedName : a.name());
        owner.typedName = null;
        f.setFont(SaoAssets.sao(20, true));
        HBox.setHgrow(f, Priority.ALWAYS);
        Button ok = round(SVG.CHECK, 28, 14), cancel = round(SVG.CLOSE, 28, 14);
        Text note = dim("", 11);
        Runnable close = () -> {
            owner.editingName = false;
            refreshAccount();
        };
        Runnable save = () -> {
            String n = f.getText().strip();
            if (n.equals(a.name())) {
                close.run();
                return;
            }
            if (!SaoAccountApp.NAME.matcher(n).matches()) {
                owner.problem.setText("! 昵称 3-16 位：字母、数字、下划线");
                return;
            }
            // James, 7 Nov: "确定的时候弹个确认框出来".
            confirmRename(n, password -> {
                ok.setDisable(true);
                owner.service.oathRename(n, password).whenComplete((v, err) -> Platform.runLater(() -> {
                    ok.setDisable(false);
                    if (err != null) {
                        owner.problem.setText("! " + SaoSkinApp.message(err));
                        return;
                    }
                    owner.problem.setText("");
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
                owner.service.oathNameAvailable(n).whenComplete((free, err) -> Platform.runLater(() -> {
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

    /// Opens 好友 (the +N after the friends who fit on the player card).
    private void openFriends() {
        for (int i = 0; i < SaoApps.APPS.length; i++)
            if (SaoApps.APPS[i].label().equals("好友")) owner.openApp(SaoApps.APPS[i], owner.dockButtons[i]);
    }

    /// Opens 好友 on the conversation with a friend (their name under the player's).
    private void openFriendChat(String name) {
        owner.service.directChat(name).whenComplete((id, err) -> Platform.runLater(() -> {
            if (id != null) SaoFriendsApp.openFirst = id;
            for (int i = 0; i < SaoApps.APPS.length; i++)
                if (SaoApps.APPS[i].label().equals("好友")) owner.openApp(SaoApps.APPS[i], owner.dockButtons[i]);
        }));
    }

    /// Asks before saving a new 游戏昵称.
    void askName(String name, Runnable yes) {
        showRenamePrompt(name, null, yes);
    }

    /// Closes confirmation and clears the current credential before leaving the account.
    private void cancelRenamePrompt() {
        owner.confirmName.getChildren().forEach(node -> {
            if (node instanceof PasswordField field) field.clear();
        });
        owner.confirmNameAction = () -> { };
        owner.confirmName.setVisible(false);
    }

    /// Requests the current password or confirms a fresh Microsoft verification.
    private void confirmRename(String name, java.util.function.Consumer<char[]> submit) {
        if (owner.service.oathPremiumOnly()) {
            showRenamePrompt(name, dim("确认时验证 Microsoft 账号", 12), () -> submit.accept(new char[0]));
            return;
        }
        PasswordField password = SaoAccountApp.password("当前密码");
        password.setMaxWidth(220);
        showRenamePrompt(name, password, () -> {
            char[] supplied = password.getText().toCharArray();
            password.clear();
            submit.accept(supplied);
        });
        password.requestFocus();
    }

    /// Presents the existing rename confirmation with any required credential field.
    private void showRenamePrompt(String name, @Nullable Node proof, Runnable yes) {
        Text sure = ink("确定把昵称改成", 16);
        SaoUiKit.font(sure, 16, true);
        Text n = sao(name, 30, true);
        Text after = dim("一天最多改 3 次", 12);
        owner.confirmName.getChildren().setAll(sure, n, after, (Node) owner.confirmName.getProperties().get("answers"));
        if (proof != null) owner.confirmName.getChildren().add(3, proof);
        owner.confirmNameAction = yes;
        owner.confirmName.setVisible(true);
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

    /// Shows the side that fits, signed out or signed in (or logging in), and the skin worn now.
    void refreshAccount() {
        SaoData.@Nullable OathAccount live = owner.service.oathAccount().get();
        if (live != null) owner.startupPending = false;
        // James, 14 Nov: "登录可以慢慢来，但是显示必须先显示好": until the background sign-in answers, the panel is the last
        // session's, drawn from a snapshot saved at every signed-in refresh.
        @Nullable SaoHelmetPage.Snapshot cached = live == null && owner.startupPending ? snapshot() : null;
        SaoData.@Nullable OathAccount a = live != null ? live : cached != null ? cached.account() : null;
        SaoData.@Nullable PlayerCard shownCard = live != null ? owner.service.playerCard().get() : cached != null ? cached.card() : null;
        // James, 14 Nov: "为什么进launcher要自己手动点一遍登录microsoft". The remembered Microsoft account signs in by
        // itself at startup (4–6 s); the panel says so instead of showing the sign-in form, which invited a click
        // that restarted the whole chain.
        @Nullable String microsoftName = owner.microsoftSigningIn() ? java.util.Objects.requireNonNull(owner.service.microsoftIdentity().get()).name() : null;
        boolean waiting = a == null && (owner.autoLogging || microsoftName != null);
        boolean in = a != null || waiting;
        owner.signedOut.setVisible(!in);
        owner.signedIn.setVisible(in);
        if (!in) {
            owner.confirmOut.setVisible(false);
            owner.refreshPrompt();
        }
        if (waiting) {
            @Nullable String r = microsoftName != null ? microsoftName : owner.service.rememberedOathName();
            owner.signedIn.getChildren().setAll(sao("ARGUS ID", 17, true), sao(r == null ? "" : r, 33, true),
                    dim(microsoftName != null ? "正在用 Microsoft 登录…" : "正在登录…", 14));
        } else if (a != null) {
            Button out = SaoUiKit.button("退出登录");
            out.setOnAction(e -> owner.confirmOut.setVisible(true));
            // James, 7 Nov: "Sign out 右边加个Settings": the account's own settings.
            Button settings = SaoUiKit.button("设置");
            settings.setOnAction(e -> owner.openAccount(SaoAccountApp.Page.MANAGE));
            for (Button b : new Button[]{out, settings}) SaoUiKit.font(b, 14, false);
            HBox head = new HBox(8, sao("ARGUS ID", 17, true), out, settings);
            head.setAlignment(Pos.CENTER_LEFT);
            // James, 7 Nov: "让名字大一点", then "a bit too big now"; a long name shrinks to stay in the column.
            Text n = sao(a.name(), 42, true);
            owner.shownNameText = n;
            double nw = n.getLayoutBounds().getWidth(), room = SaoHelmetPage.RIGHT_W - 38;
            if (nw > room) SaoUiKit.font(n, 42 * room / nw, true);
            n.getStyleClass().add("vr-link-text");
            n.setOnMouseClicked(e -> owner.openAccount(SaoAccountApp.Page.MANAGE));
            // The mailbox and Microsoft live in 设置 only (James, 7 Nov).
            // A pencil beside the name: it can be changed (James, 7 Nov), in 设置.
            // James, 7 Nov: "点击铅笔可以直接在那个界面换昵称".
            Button rename = round(SVG.EDIT, 28, 14);
            rename.setOnAction(e -> {
                owner.editingName = true;
                refreshAccount();
            });
            HBox nameRow = new HBox(8, n, rename);
            nameRow.setAlignment(Pos.CENTER_LEFT);
            owner.signedIn.getChildren().setAll(head, owner.editingName ? nameEditor(a) : nameRow);
            if (a.mustRename()) {
                Text taken = new Text("昵称已被正版玩家使用，请更换昵称");
                taken.getStyleClass().add("vr-warn");
                SaoUiKit.font(taken, 13, false);
                Button change = bigLink("更换", () -> {
                    owner.editingName = true;
                    refreshAccount();
                });
                HBox takenRow = new HBox(6, taken, grow(), change);
                takenRow.setAlignment(Pos.CENTER_LEFT);
                owner.signedIn.getChildren().add(takenRow);
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
                owner.friendsLine = null;
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
                    friends = owner.friendsLine = new SaoHelmetPage.FriendsLine(dim("好友在线", 14), chips, more, moreText);
                }
                owner.signedIn.getChildren().addAll(tiles, new VBox(2, last, total), friends);
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
                        () -> owner.openAccount(a.maskedEmail().isEmpty() ? SaoAccountApp.Page.MANAGE : SaoAccountApp.Page.VERIFY));
                go.setMinWidth(Region.USE_PREF_SIZE);
                HBox warnRow = new HBox(6, w, go);
                warnRow.setAlignment(Pos.CENTER_LEFT);
                warnRow.setMinWidth(0);
                warnRow.setMaxWidth(Double.MAX_VALUE);
                owner.signedIn.getChildren().add(warnRow);
            }
        }
        // The snapshot's buttons wait for the real account.
        if (cached != null) for (Node n : owner.signedIn.getChildren()) n.setDisable(true);
        owner.accountMotion(waiting, a);
        owner.problem.setText(cached != null ? "正在登录…" : "");
        @Nullable Path worn = owner.service.oathSkin() != null ? owner.service.oathSkin() : owner.service.skin();
        boolean slim = owner.service.skinSlim();
        if (cached != null && cached.skin() != null && Files.isRegularFile(Path.of(cached.skin()))) { worn = Path.of(cached.skin()); slim = cached.slim(); }
        owner.skin.show(worn, slim);
        if (live != null) saveSnapshot(live, owner.service.playerCard().get(), owner.service.oathSkin(), owner.service.skinSlim());
    }

    private @Nullable SaoHelmetPage.Snapshot snapshot() {
        String json = owner.service.preference(SaoHelmetPage.SNAPSHOT_KEY, "");
        if (json.isBlank()) return null;
        try { return new com.google.gson.Gson().fromJson(json, SaoHelmetPage.Snapshot.class); }
        catch (RuntimeException broken) { return null; }
    }

    private void saveSnapshot(SaoData.OathAccount a, SaoData.@Nullable PlayerCard card, @Nullable Path skinFile, boolean slim) {
        SaoHelmetPage.Snapshot s = new SaoHelmetPage.Snapshot(a.name(), a.profileId(), a.premiumName(), card == null ? -1 : card.level(), card == null ? -1 : card.col(),
                card == null ? 0 : card.lastPlayed(), card == null ? 0 : card.playSeconds(), skinFile == null ? null : skinFile.toString(), slim);
        String json = new com.google.gson.Gson().toJson(s);
        if (!json.equals(owner.service.preference(SaoHelmetPage.SNAPSHOT_KEY, ""))) owner.service.setPreference(SaoHelmetPage.SNAPSHOT_KEY, json);
    }
}
