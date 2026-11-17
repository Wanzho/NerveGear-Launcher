/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import org.jackhuang.hmcl.sao.SaoData;
import org.jackhuang.hmcl.sao.SaoLauncherService;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.ui.sao.SaoUiKit.*;

/// 好友: the ARGUS ID friends and their conversations, one-to-one and groups (James, 7 Nov: "好友，和好友聊天系统，
/// 能做吗，游戏外也可以发消息的那种", "群聊也要，单人游戏也能收消息"). In the game the same conversations show in
/// e33chat ("可以，就用e33chat"); here they work with the game closed. On the left the friends or the groups, on
/// the right the conversation.
@NotNullByDefault
final class SaoFriendsApp {
    /// The conversation to open first (the player card's friend names and the screenshot tool).
    static @Nullable String openFirst;
    /// The left side's tab to start on: 0 friends, 1 groups (the screenshot tool).
    static int tabFirst;
    /// Start with the add-friend line open (the screenshot tool).
    static boolean addFirst;
    /// Start with 新建群聊 open (the screenshot tool).
    static boolean newGroupFirst;

    private static final double LEFT = 232;

    private final SaoLauncherService s;
    private final ToggleGroup tabs = new ToggleGroup();
    private final ToggleButton friendsTab = chip("好友", tabs), groupsTab = chip("群聊", tabs);
    private final VBox list = new VBox(2);
    private final VBox requests = new VBox(4);
    private final VBox addLine = new VBox(4);
    private final StackPane right = new StackPane();
    private @Nullable String open;
    /// Removes the open chat's message listener; null when no chat is shown.
    private @Nullable Runnable detach;
    /// Closed windows must not receive list updates or delayed service completions.
    private boolean disposed;
    /// Changes whenever navigation invalidates the current page or lookup.
    private long pageEpoch;
    /// Owned subscriptions are removed together when the floating window closes.
    private final ListChangeListener<SaoData.Friend> friendsChanged = c -> fillList();
    /// Conversation metadata subscription owned by this window.
    private final ListChangeListener<SaoData.Chat> chatsChanged = c -> fillList();
    /// Friend request subscription owned by this window.
    private final ListChangeListener<SaoData.FriendRequest> requestsChanged = c -> fillRequests();

    private SaoFriendsApp(SaoLauncherService s) {
        this.s = s;
    }

    /// Builds the window.
    static SaoFloatWindow make(SaoLauncherService s, Runnable close) {
        return new SaoFriendsApp(s).window(close);
    }

    private SaoFloatWindow window(Runnable close) {
        keepOne(tabs);
        // Closing the window stops following its chat.
        right.sceneProperty().addListener((o, a, b) -> { if (a != null && b == null) dispose(); });
        (tabFirst == 1 ? groupsTab : friendsTab).setSelected(true);
        tabs.selectedToggleProperty().addListener((o, a, b) -> fillList());
        Button add = round(SVG.ADD, 26, 14);
        add.setOnAction(e -> {
            boolean show = !addLine.isVisible();
            addLine.setVisible(show);
            addLine.setManaged(show);
        });
        HBox head = new HBox(6, friendsTab, groupsTab, grow(), add);
        head.setAlignment(Pos.CENTER_LEFT);

        // Add a friend by 游戏昵称.
        TextField who = field("对方的游戏昵称");
        HBox.setHgrow(who, Priority.ALWAYS);
        Button ask = accent("申请");
        Text note = dim("", 11);
        Runnable send = () -> {
            String n = who.getText().strip();
            if (!SaoAccountApp.NAME.matcher(n).matches()) {
                warnText(note, "昵称 3-16 位：字母、数字、下划线");
                return;
            }
            ask.setDisable(true);
            s.addFriend(n).whenComplete((v, err) -> Platform.runLater(() -> {
                if (disposed) return;
                ask.setDisable(false);
                if (err != null) {
                    warnText(note, SaoSkinApp.message(err));
                    return;
                }
                who.clear();
                note.getStyleClass().setAll("vr-dim");
                note.setText("已发送，等对方同意");
            }));
        };
        ask.setOnAction(e -> send.run());
        who.setOnAction(e -> send.run());
        note.managedProperty().bind(note.textProperty().isNotEmpty());
        addLine.getChildren().setAll(new HBox(6, who, ask), note);
        addLine.setVisible(addFirst);
        addLine.setManaged(addFirst);

        ScrollPane scroller = scroll(list);
        VBox.setVgrow(scroller, Priority.ALWAYS);
        VBox left = new VBox(8, head, addLine, requests, scroller);
        left.setPrefWidth(LEFT);
        left.setMinWidth(LEFT);
        left.setMaxWidth(LEFT);

        Region line = new Region();
        line.getStyleClass().add("vr-rule");
        line.setMinWidth(1);
        line.setMaxWidth(1);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox body = new HBox(14, left, line, right);
        VBox.setVgrow(body, Priority.ALWAYS);

        s.friends().addListener(friendsChanged);
        s.chats().addListener(chatsChanged);
        s.friendRequests().addListener(requestsChanged);
        fillRequests();
        fillList();
        if (newGroupFirst) showNewGroup();
        else if (openFirst != null) openChat(openFirst);
        else showNothing();
        openFirst = null;
        tabFirst = 0;
        addFirst = false;
        newGroupFirst = false;
        return new SaoFloatWindow("好友", SaoApps.W, SaoApps.H, () -> { dispose(); close.run(); }).add(body);
    }

    // ------------------------------------------------------------------ left

    private void fillRequests() {
        requests.getChildren().clear();
        for (SaoData.FriendRequest r : s.friendRequests()) {
            if (r.incoming()) {
                Button yes = accent("同意"), no = button("拒绝");
                for (Button b : new Button[]{yes, no}) b.getStyleClass().add("vr-button-tight");
                yes.setOnAction(e -> s.answerFriend(r.name(), true));
                no.setOnAction(e -> s.answerFriend(r.name(), false));
                VBox words = new VBox(0, sao(r.name(), 13, true), dim("想加你为好友", 11));
                HBox row = new HBox(6, avatar(r.name(), null, SaoData.Presence.OFFLINE), words, grow(), no, yes);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("vr-request");
                requests.getChildren().add(row);
            } else {
                Text t = dim("已向 " + r.name() + " 发送申请", 11);
                requests.getChildren().add(t);
            }
        }
        requests.setVisible(!requests.getChildren().isEmpty());
        requests.setManaged(requests.isVisible());
    }

    private void fillList() {
        List<Node> rows = new ArrayList<>();
        if (groupsTab.isSelected()) {
            Button make = button("新建群聊");
            make.setMaxWidth(Double.MAX_VALUE);
            make.setOnAction(e -> showNewGroup());
            rows.add(make);
            for (SaoData.Chat c : s.chats()) {
                if (!c.group()) continue;
                rows.add(row(groupAvatar(), c.title(), c.lastText().isEmpty() ? c.members().size() + " 人" : c.lastText(),
                        timeText(c.lastTime()), c.unread(), c.id().equals(open), () -> openChat(c.id())));
            }
            if (rows.size() == 1) rows.add(dim("还没有群聊", 12));
        } else {
            List<SaoData.Friend> sorted = new ArrayList<>(s.friends());
            sorted.sort((a, b) -> a.presence().compareTo(b.presence()));
            for (SaoData.Friend f : sorted) {
                @Nullable SaoData.Chat c = direct(f.name());
                String status = switch (f.presence()) {
                    case GAME -> f.where().isEmpty() ? "游戏中" : "游戏中 · " + f.where();
                    case LAUNCHER -> "在启动器";
                    case OFFLINE -> "离线";
                };
                rows.add(row(avatar(f.name(), f.head(), f.presence()), f.name(), status, "",
                        c == null ? 0 : c.unread(), c != null && c.id().equals(open), () -> openFriend(f.name())));
            }
            if (sorted.isEmpty()) rows.add(dim("还没有好友，点右上角的 + 加一个", 12));
        }
        SaoMotion.list(list, rows);
        // The tab chips carry the unread count of their side.
        int dm = 0, gm = 0;
        for (SaoData.Chat c : s.chats()) {
            if (c.group()) gm += c.unread();
            else dm += c.unread();
        }
        friendsTab.setText(dm > 0 ? "好友 " + dm : "好友");
        groupsTab.setText(gm > 0 ? "群聊 " + gm : "群聊");
    }

    private @Nullable SaoData.Chat direct(String name) {
        for (SaoData.Chat c : s.chats()) if (!c.group() && c.title().equals(name)) return c;
        return null;
    }

    /// One line of the list: a face, the name and a line under it, the time, and the unread count.
    private static Node row(Node face, String title, String sub, String time, int unread, boolean on, Runnable click) {
        Text t = sao(title, 14, true);
        Text u = dim(sub.length() > 15 ? sub.substring(0, 14) + "…" : sub, 11);
        VBox words = new VBox(1, t, u);
        words.setMinWidth(0);
        HBox.setHgrow(words, Priority.ALWAYS);
        VBox end = new VBox(3);
        end.setAlignment(Pos.TOP_RIGHT);
        if (!time.isEmpty()) end.getChildren().add(dim(time, 10));
        if (unread > 0) end.getChildren().add(badge(unread));
        HBox r = new HBox(8, face, words, end);
        r.setAlignment(Pos.CENTER_LEFT);
        r.getStyleClass().add("vr-row");
        if (on) r.getStyleClass().add("vr-row-on");
        r.setOnMouseClicked(e -> click.run());
        return r;
    }

    private static Node badge(int n) {
        Text t = new Text(n > 99 ? "99+" : Integer.toString(n));
        t.setFont(SaoAssets.sao(11, true));
        t.setFill(Color.WHITE);
        StackPane b = new StackPane(t);
        b.getStyleClass().add("vr-badge");
        b.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return b;
    }

    /// A friend's face: their skin's face when there is one, else the first letter; a light for where they are.
    static Node avatar(String name, @Nullable java.nio.file.Path head, SaoData.Presence p) {
        StackPane face = new StackPane();
        face.getStyleClass().add("vr-avatar");
        face.setMinSize(30, 30);
        face.setMaxSize(30, 30);
        if (head != null) {
            ImageView v = new ImageView(new Image(head.toUri().toString(), 30, 30, false, false));
            face.getChildren().add(v);
        } else {
            face.getChildren().add(sao(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), 15, true));
        }
        if (p == SaoData.Presence.OFFLINE) return face;
        Region dot = new Region();
        dot.getStyleClass().add(p == SaoData.Presence.GAME ? "vr-dot-game" : "vr-dot-launcher");
        dot.setMinSize(9, 9);
        dot.setMaxSize(9, 9);
        StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
        StackPane both = new StackPane(face, dot);
        both.setMaxSize(30, 30);
        return both;
    }

    private static Node groupAvatar() {
        StackPane face = new StackPane(icon(SVG.CHAT, 15));
        face.getStyleClass().add("vr-avatar");
        face.setMinSize(30, 30);
        face.setMaxSize(30, 30);
        return face;
    }

    // ------------------------------------------------------------------ right

    /// Stops following the open chat, so a closed or switched chat is no longer marked read.
    /// Releases all service subscriptions; repeated close notifications are harmless.
    private void dispose() {
        if (disposed) return;
        disposed = true;
        closeChat();
        s.friends().removeListener(friendsChanged);
        s.chats().removeListener(chatsChanged);
        s.friendRequests().removeListener(requestsChanged);
    }

    private void closeChat() {
        pageEpoch++;
        if (detach != null) detach.run();
        detach = null;
        open = null;
    }

    private void showNothing() {
        if (disposed) return;
        closeChat();
        SaoMotion.swap(right, empty(SVG.CHAT, "选一个好友或群聊"));
    }

    private void openFriend(String name) {
        if (disposed) return;
        long requested = ++pageEpoch;
        s.directChat(name).whenComplete((id, err) -> Platform.runLater(() -> {
            if (disposed || requested != pageEpoch) return;
            if (err != null) {
                closeChat();
                SaoMotion.swap(right, empty(SVG.CHAT, SaoSkinApp.message(err)));
            } else if (id != null) openChat(id);
        }));
    }

    /// A stale completion cannot dismiss a newer page, and a refusal stays visible in the existing error line.
    private void chatAction(String id, long epoch, Text failed, Button button,
                            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> action) {
        failed.setText("");
        button.setDisable(true);
        java.util.concurrent.CompletableFuture<Void> result;
        try { result = action.get(); }
        catch (RuntimeException error) { result = java.util.concurrent.CompletableFuture.failedFuture(error); }
        result.whenComplete((v, error) -> Platform.runLater(() -> {
            if (disposed || pageEpoch != epoch || !id.equals(open)) return;
            button.setDisable(false);
            if (error == null) showNothing();
            else warnText(failed, SaoSkinApp.message(error));
        }));
    }

    private void openChat(String id) {
        if (disposed) return;
        @Nullable SaoData.Chat c = null;
        for (SaoData.Chat x : s.chats()) if (x.id().equals(id)) c = x;
        if (c == null) {
            showNothing();
            return;
        }
        closeChat();
        open = id;
        if (c.group() && !groupsTab.isSelected()) groupsTab.setSelected(true);
        if (!c.group() && !friendsTab.isSelected()) friendsTab.setSelected(true);
        s.markRead(id);
        fillList();
        SaoData.Chat chat = c;
        long epoch = pageEpoch;
        Text failed = dim("", 12);

        // The head: who, where they are (or the members), and what can be done.
        Text title = sao(chat.title(), 18, true);
        Text sub = dim(chat.group() ? chat.members().size() + " 人 · " + String.join("、", chat.members()) : statusOf(chat.title()), 11);
        VBox words = new VBox(0, title, sub);
        HBox.setHgrow(words, Priority.ALWAYS);
        words.setMinWidth(0);
        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setVisible(false);
        actions.setManaged(false);
        if (chat.group()) {
            Button leave = button("退出群聊");
            leave.getStyleClass().addAll("vr-button-tight", "vr-danger");
            leave.setOnAction(e -> chatAction(chat.id(), epoch, failed, leave, () -> s.leaveGroup(chat.id())));
            actions.getChildren().add(leave);
        } else {
            Button remove = button("删除好友"), block = button("屏蔽");
            for (Button b : new Button[]{remove, block}) b.getStyleClass().add("vr-button-tight");
            block.getStyleClass().add("vr-danger");
            remove.setOnAction(e -> chatAction(chat.id(), epoch, failed, remove, () -> s.removeFriend(chat.title())));
            block.setOnAction(e -> chatAction(chat.id(), epoch, failed, block, () -> s.blockPlayer(chat.title())));
            actions.getChildren().addAll(remove, block);
        }
        Button more = round(SVG.MORE_HORIZ, 26, 14);
        more.setOnAction(e -> {
            actions.setVisible(!actions.isVisible());
            actions.setManaged(actions.isVisible());
        });
        HBox head = new HBox(8, words, actions, more);
        head.setAlignment(Pos.CENTER_LEFT);

        // The messages, newest at the bottom.
        ObservableList<SaoData.ChatMessage> msgs = s.messages(chat.id());
        VBox col = new VBox(6);
        col.setPadding(new Insets(4, 6, 4, 0));
        ScrollPane sp = scroll(col);
        VBox.setVgrow(sp, Priority.ALWAYS);
        long[] firstShown = {0};
        boolean[] firstFill = {true};
        long[] nextOlderRequest = {0};
        sp.setOnScroll(event -> {
            long now = System.nanoTime();
            if (event.getDeltaY() > 0 && sp.getVvalue() <= 0.001 && !disposed
                    && pageEpoch == epoch && now >= nextOlderRequest[0]) {
                nextOlderRequest[0] = now + 500_000_000L;
                s.loadOlderMessages(chat.id());
            }
        });
        Runnable fill = () -> {
            double oldHeight = col.getHeight(), viewport = sp.getViewportBounds().getHeight();
            double offset = sp.getVvalue() * Math.max(0, oldHeight - viewport);
            long first = msgs.isEmpty() ? 0 : msgs.get(0).id();
            boolean prepended = firstShown[0] > 0 && first > 0 && first < firstShown[0];
            boolean followBottom = firstFill[0] || !prepended && sp.getVvalue() >= 0.98;
            firstShown[0] = first;
            firstFill[0] = false;
            List<Node> rows = new ArrayList<>();
            long last = 0;
            for (SaoData.ChatMessage m : msgs) {
                if (m.time() - last > 5 * 60_000L) rows.add(timeLine(m.time()));
                last = m.time();
                rows.add(bubble(m, chat.group()));
            }
            if (rows.isEmpty()) rows.add(dim("还没有消息，打个招呼吧", 12));
            SaoMotion.list(col, rows);
            Platform.runLater(() -> {
                if (disposed || pageEpoch != epoch || !chat.id().equals(open)) return;
                sp.applyCss();
                sp.layout();
                double height = col.getHeight();
                double extent = Math.max(0, height - sp.getViewportBounds().getHeight());
                sp.setVvalue(followBottom ? 1 : extent == 0 ? 0
                        : Math.max(0, Math.min(1, (offset + (prepended ? height - oldHeight : 0)) / extent)));
            });
        };
        ListChangeListener<SaoData.ChatMessage> follow = ch -> {
            fill.run();
            if (chat.id().equals(open)) s.markRead(chat.id());
        };
        msgs.addListener(follow);
        detach = () -> msgs.removeListener(follow);
        fill.run();

        // Writing.
        TextField say = field(chat.group() ? "发到 " + chat.title() : "发给 " + chat.title());
        HBox.setHgrow(say, Priority.ALWAYS);
        Button go = accent("发送");
        Runnable send = () -> {
            String t = say.getText().strip();
            if (t.isEmpty()) return;
            say.clear();
            failed.setText("");
            // A message that did not go out comes back to the field instead of vanishing.
            s.sendMessage(chat.id(), t).whenComplete((ok, failure) -> Platform.runLater(() -> {
                if (disposed || pageEpoch != epoch || failure == null) return;
                if (say.getText().isEmpty()) say.setText(t);
                failed.setText("! 发送失败");
            }));
        };
        go.setOnAction(e -> send.run());
        say.setOnAction(e -> send.run());
        HBox write = new HBox(8, say, failed, go);
        write.setAlignment(Pos.CENTER_LEFT);

        VBox pane = new VBox(8, head, rule(), sp, write);
        SaoMotion.swap(right, pane);
    }

    private String statusOf(String name) {
        for (SaoData.Friend f : s.friends()) {
            if (!f.name().equals(name)) continue;
            return switch (f.presence()) {
                case GAME -> f.where().isEmpty() ? "游戏中" : "游戏中 · " + f.where();
                case LAUNCHER -> "在启动器";
                case OFFLINE -> "离线 · 上线后会收到";
            };
        }
        return "";
    }

    /// One message: this player's on the right in the accent colour, others' on the left; in a group, who wrote it.
    private static Node bubble(SaoData.ChatMessage m, boolean group) {
        Text t = new Text(m.text());
        SaoUiKit.font(t, 13, false);
        double natural = t.getLayoutBounds().getWidth();
        if (natural > 250) t.setWrappingWidth(250);
        if (m.mine()) t.setFill(Color.WHITE);
        else t.getStyleClass().add("vr-ink");
        StackPane b = new StackPane(t);
        b.getStyleClass().add(m.mine() ? "vr-bubble-mine" : "vr-bubble");
        b.setMaxWidth(Region.USE_PREF_SIZE);
        if (m.pending()) b.setOpacity(0.6);
        VBox one = new VBox(2);
        if (group && !m.mine()) one.getChildren().add(dim(m.from(), 10));
        one.getChildren().add(b);
        one.setAlignment(m.mine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        HBox line = new HBox(6);
        line.setAlignment(m.mine() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        if (m.mine()) line.getChildren().addAll(grow(), one);
        else line.getChildren().addAll(avatar(m.from(), null, SaoData.Presence.OFFLINE), one, grow());
        return line;
    }

    private static Node timeLine(long time) {
        HBox h = new HBox(dim(timeText(time), 10));
        h.setAlignment(Pos.CENTER);
        return h;
    }

    /// 12:40 today, 昨天 12:40, 3天前, or the date.
    static String timeText(long epochMs) {
        if (epochMs <= 0) return "";
        ZoneId z = ZoneId.systemDefault();
        LocalDateTime t = Instant.ofEpochMilli(epochMs).atZone(z).toLocalDateTime();
        long days = ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now(z));
        String hm = String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
        if (days <= 0) return hm;
        if (days == 1) return "昨天 " + hm;
        if (days < 7) return days + "天前";
        return t.getMonthValue() + "-" + t.getDayOfMonth();
    }

    // ------------------------------------------------------------------ new group

    private void showNewGroup() {
        if (disposed) return;
        closeChat();
        long epoch = pageEpoch;
        if (!groupsTab.isSelected()) groupsTab.setSelected(true);
        Text title = sao("新建群聊", 18, true);
        TextField name = field("群名");
        Text problem = dim("", 11);
        javafx.scene.layout.FlowPane pick = new javafx.scene.layout.FlowPane(6, 6);
        List<ToggleButton> picks = new ArrayList<>();
        for (SaoData.Friend f : s.friends()) {
            ToggleButton c = chip(f.name(), null);
            picks.add(c);
            pick.getChildren().add(c);
        }
        if (picks.isEmpty()) pick.getChildren().add(dim("先加几个好友", 12));
        else picks.get(0).setSelected(true);
        Button make = accent("创建");
        make.setOnAction(e -> {
            String n = name.getText().strip();
            List<String> who = new ArrayList<>();
            for (ToggleButton c : picks) if (c.isSelected()) who.add(c.getText());
            if (n.isEmpty()) {
                warnText(problem, "给群起个名字");
                return;
            }
            if (who.isEmpty()) {
                warnText(problem, "至少选一个好友");
                return;
            }
            make.setDisable(true);
            s.createGroup(n, who).whenComplete((id, err) -> Platform.runLater(() -> {
                if (disposed || pageEpoch != epoch) return;
                make.setDisable(false);
                if (err != null) warnText(problem, SaoSkinApp.message(err));
                else openChat(id);
            }));
        });
        Button cancel = button("取消");
        cancel.setOnAction(e -> showNothing());
        HBox buttons = new HBox(8, problem, grow(), cancel, make);
        buttons.setAlignment(Pos.CENTER_LEFT);
        name.setPromptText("给群起个名字");
        VBox form = new VBox(10, title, key("群名"), name, key("成员"), pick, grow(), buttons);
        VBox.setVgrow(form.getChildren().get(5), Priority.ALWAYS);
        SaoMotion.swap(right, form);
        fillList();
    }

    private static void warnText(Text t, String message) {
        t.getStyleClass().setAll("vr-warn");
        t.setText(message);
    }
}
