/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.OfflineAccountFactory;
import org.jackhuang.hmcl.sao.SaoAccountKind;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.regex.Pattern;

/// The blue log-in panel, the same panel as the game's sao_auth log-in (James 4 Nov: "replica of the opening
/// screen"), with the Link Start button under it.
///
/// Left: the title in Chinese and English, the pack status and a thin progress line. Right: the account kind,
/// `:account` and `:password`. The password is only for the 誓约 server's sao_auth and goes to the game in memory.
@NotNullByDefault
public final class SaoLoginPanel extends VBox {
    /// Minecraft's rule for player names.
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /// The service the panel drives.
    private final SaoLauncherService service;
    /// Offline or Microsoft.
    private final ToggleGroup kind = new ToggleGroup();
    /// The offline chip.
    private final ToggleButton offline = chip("离线 · Offline");
    /// The Microsoft chip.
    private final ToggleButton microsoft = chip("正版 · Verified");
    /// The player name for offline play.
    private final TextField name = new TextField();
    /// The signed-in Microsoft name, shown read-only.
    private final TextField msName = new TextField();
    /// Opens HMCL's Microsoft sign-in.
    private final Button msSignIn = new Button("登录 Microsoft · Sign in");
    /// The sao_auth password.
    private final PasswordField password = new PasswordField();
    /// Remember the password on this device.
    private final CheckBox remember = new CheckBox("记住密码");
    /// The status line under the title.
    private final Text status = new Text();
    /// The error line, empty when fine.
    private final Text error = new Text();
    /// The progress line's fill.
    private final Region fill = new Region();
    /// The big Link Start button.
    private final Button start = new Button("Link Start");

    /// Builds the panel for a service.
    ///
    /// @param service the pack engine (or the screen-work fake)
    public SaoLoginPanel(SaoLauncherService service) {
        this.service = service;
        setAlignment(Pos.TOP_CENTER);
        setSpacing(14);
        setMaxWidth(Region.USE_PREF_SIZE);

        // Left column: title, status, progress.
        Text title = new Text("登录");
        title.getStyleClass().add("sao-title");
        title.setFont(Font.font(null, FontWeight.BOLD, 30));
        Text titleEn = new Text("Login");
        titleEn.getStyleClass().add("sao-title-en");
        titleEn.setFont(SaoAssets.sao(22, true));
        VBox heading = new VBox(0, title, titleEn);

        status.getStyleClass().add("sao-small");
        status.setFont(SaoAssets.sao(12, false));
        error.getStyleClass().add("sao-error");
        error.setFont(Font.font(null, FontWeight.BOLD, 13));
        TextFlow errorFlow = new TextFlow(error);
        errorFlow.setMaxWidth(210);
        Region track = new Region();
        track.getStyleClass().add("sao-progress");
        track.setPrefSize(140, 2);
        track.setMaxSize(140, 2);
        fill.getStyleClass().add("sao-progress-fill");
        fill.setPrefHeight(2);
        fill.setMaxHeight(2);
        fill.setMaxWidth(Region.USE_PREF_SIZE); // the stack must not stretch the fill to the full track
        StackPane bar = new StackPane(track, fill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxWidth(140);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox left = new VBox(6, heading, grow, errorFlow, status, bar);
        left.setPrefWidth(220);

        // Right column: kind, account, password.
        offline.setToggleGroup(kind);
        microsoft.setToggleGroup(kind);
        HBox chips = new HBox(8, offline, microsoft);
        for (TextField f : new TextField[]{name, msName, password}) {
            f.getStyleClass().add("sao-field");
            f.setFont(SaoAssets.sao(15, false));
            f.setPrefWidth(250);
        }
        name.setPromptText("Player name");
        msName.setEditable(false);
        msName.setFocusTraversable(false);
        msSignIn.getStyleClass().add("sao-link");
        msSignIn.setFont(SaoAssets.sao(13, false));
        msSignIn.setOnAction(e -> Controllers.dialog(new CreateAccountPane(Accounts.FACTORY_MICROSOFT)));
        password.setPromptText("sao_auth");
        remember.getStyleClass().add("sao-tick");
        remember.setFont(Font.font(12));
        StackPane accountField = new StackPane(name, msName, msSignIn);
        accountField.setAlignment(Pos.CENTER_LEFT);
        VBox right = new VBox(4,
                chips,
                gap(6),
                label(":account", "名称"), accountField,
                gap(6),
                label(":password", "密码"), password,
                remember);
        right.setPrefWidth(250);

        HBox body = new HBox(28, left, right);
        body.getStyleClass().add("sao-panel");
        body.setPadding(new Insets(22, 26, 20, 30));
        body.setPrefHeight(236);

        start.getStyleClass().add("sao-start");
        start.setFont(SaoAssets.welcome(26));
        start.setOnAction(e -> linkStart());
        start.setDefaultButton(true);

        getChildren().setAll(body, start);

        kind.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                kind.selectToggle(a);
                return;
            }
            refreshAccount();
        });
        Accounts.getAccounts().addListener((javafx.collections.ListChangeListener<Account>) c -> refreshAccount());
        preselect();

        fill.prefWidthProperty().bind(Bindings.createDoubleBinding(
                () -> service.state().get() == SaoPackState.WORKING
                        ? 140 * Math.max(0.04, service.progress().get())
                        : service.state().get() == SaoPackState.READY || service.state().get() == SaoPackState.RUNNING ? 140 : 0,
                service.state(), service.progress()));
        service.state().addListener((o, a, b) -> refreshState());
        service.stepLabel().addListener((o, a, b) -> refreshState());
        service.progress().addListener((o, a, b) -> refreshState());
        service.lastError().addListener((o, a, b) -> refreshState());
        refreshState();
    }

    /// The account kind chosen now.
    ///
    /// @return offline or Microsoft
    public SaoAccountKind kind() {
        return kind.getSelectedToggle() == microsoft ? SaoAccountKind.MICROSOFT : SaoAccountKind.OFFLINE;
    }

    /// Starts on the kind and name of the account the launcher used last.
    private void preselect() {
        @Nullable Account sel = Accounts.getSelectedAccount();
        if (sel instanceof MicrosoftAccount) {
            kind.selectToggle(microsoft);
        } else {
            kind.selectToggle(offline);
            if (sel instanceof OfflineAccount) name.setText(sel.getProfileName());
        }
        refreshAccount();
    }

    /// Shows the field that fits the chosen kind.
    private void refreshAccount() {
        boolean ms = kind() == SaoAccountKind.MICROSOFT;
        @Nullable MicrosoftAccount msAccount = microsoftAccount();
        name.setVisible(!ms);
        msName.setVisible(ms && msAccount != null);
        msSignIn.setVisible(ms && msAccount == null);
        if (msAccount != null) msName.setText(msAccount.getProfileName());
        error.setText("");
    }

    /// The Microsoft account to use: the selected one if it is Microsoft, else the first.
    ///
    /// @return the account, or null when none is signed in
    private static @Nullable MicrosoftAccount microsoftAccount() {
        if (Accounts.getSelectedAccount() instanceof MicrosoftAccount m) return m;
        for (Account a : Accounts.getAccounts()) if (a instanceof MicrosoftAccount m) return m;
        return null;
    }

    /// Updates the status line, the error and the button from the service.
    private void refreshState() {
        SaoPackState s = service.state().get();
        @Nullable String err = service.lastError().get();
        String v = service.embeddedVersion();
        switch (s) {
            case CHECKING -> status.setText("检查中 · Checking");
            case NOT_INSTALLED -> status.setText("首次连接自动安装 · v" + v);
            case UPDATE_READY -> status.setText("自动更新至 v" + v);
            case WORKING -> {
                double p = service.progress().get();
                String label = service.stepLabel().get();
                status.setText((label.isEmpty() ? "准备中" : label) + (p >= 0 ? "  " + Math.round(p * 100) + "%" : ""));
            }
            case READY -> status.setText("就绪 · Ready · v" + v);
            case RUNNING -> status.setText("连接中 · Linked");
            case FAILED -> status.setText("失败 · Failed");
        }
        if (s == SaoPackState.FAILED && err != null) error.setText(err);
        else if (s != SaoPackState.FAILED && !error.getText().startsWith("!")) error.setText("");
        boolean busy = s == SaoPackState.CHECKING || s == SaoPackState.WORKING || s == SaoPackState.RUNNING;
        start.setDisable(busy);
    }

    /// Link Start: resolves the account, then hands it and the password to the service.
    private void linkStart() {
        @Nullable Account account;
        if (kind() == SaoAccountKind.MICROSOFT) {
            account = microsoftAccount();
            if (account == null) {
                showError("请先登录 Microsoft");
                return;
            }
        } else {
            String n = name.getText().strip();
            if (!NAME.matcher(n).matches()) {
                showError("名称 3-16 位：字母、数字、下划线");
                return;
            }
            account = offlineAccount(n);
        }
        Accounts.setSelectedAccount(account);
        char @Nullable [] pw = password.getText().isEmpty() ? null : password.getText().toCharArray();
        if (!remember.isSelected()) password.clear();
        service.linkStart(account, pw);
        if (pw != null) Arrays.fill(pw, '\0');
    }

    /// The offline account with this name, created and added when it is new.
    ///
    /// @param n the player name
    /// @return the account
    private static Account offlineAccount(String n) {
        for (Account a : Accounts.getAccounts())
            if (a instanceof OfflineAccount o && o.getProfileName().equals(n)) return o;
        OfflineAccount a = Accounts.FACTORY_OFFLINE.create(n, OfflineAccountFactory.getUUIDFromUserName(n));
        Accounts.getAccounts().add(a);
        return a;
    }

    /// Shows a problem under the title (marked so state changes don't clear it).
    ///
    /// @param msg the message
    void showError(String msg) {
        error.setText("! " + msg);
    }

    /// A chip for the account kind.
    ///
    /// @param text the label
    /// @return the chip
    private static ToggleButton chip(String text) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("sao-chip");
        b.setFont(SaoAssets.sao(13, false));
        b.setFocusTraversable(false);
        return b;
    }

    /// A field label: the SAO-style English key and the Chinese word at the right.
    ///
    /// @param key the key, for example ":account"
    /// @param cn  the Chinese word
    /// @return the label row
    private static HBox label(String key, String cn) {
        Text k = new Text(key);
        k.getStyleClass().add("sao-label");
        k.setFont(SaoAssets.sao(18, false));
        Text c = new Text(cn);
        c.getStyleClass().add("sao-label-cn");
        c.setFont(Font.font(12));
        Region g = new Region();
        HBox.setHgrow(g, Priority.ALWAYS);
        HBox row = new HBox(k, g, c);
        row.setAlignment(Pos.BOTTOM_LEFT);
        return row;
    }

    /// A fixed vertical gap.
    ///
    /// @param h the height
    /// @return the spacer
    private static Region gap(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        r.setPrefHeight(h);
        return r;
    }
}
