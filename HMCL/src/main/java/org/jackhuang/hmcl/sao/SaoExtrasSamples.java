/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.*;
import javafx.collections.*;
import org.jetbrains.annotations.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/// Offline common-feature decorator for the UI-owned SaoFakeService, following the existing SaoSocialSamples pattern.
/// Construct `SaoExtrasSamples.decorate(fake)` for screen work; it never writes files, opens windows or performs HTTP.
@NotNullByDefault
public final class SaoExtrasSamples implements InvocationHandler {
    /// Wrapped UI fake; no production registry ever installs this decorator automatically.
    private final SaoLauncherService base;
    /// Reversible fixture preferences, including versioned user-notice acceptance.
    private final Map<String, String> preferences = new HashMap<>();
    /// Stable synthetic public links; the defaults are real, placeholder community links are intentionally excluded.
    private final @UnmodifiableView ObservableList<SaoData.CommunityLink> links = FXCollections.unmodifiableObservableList(
            FXCollections.observableArrayList(SaoCommunityLinks.DEFAULTS));
    /// In-memory selected visibility behavior.
    private SaoData.LauncherBehavior behavior = SaoData.LauncherBehavior.HIDE;
    /// In-memory pending graphics preset.
    private SaoData.GraphicsPreset preset = SaoData.GraphicsPreset.AUTO;
    /// Stable event property supports frontend OS-notification wiring without a social account.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable Notification> event = new ReadOnlyObjectWrapper<>();
    /// Three representative event kinds, emitted on successive hidden/unfocused attention reports.
    private long sequence;

    /// Creates one isolated, resettable set of synthetic settings and checks.
    private SaoExtrasSamples(SaoLauncherService base) { this.base = base; }

    /// Wraps the existing fake while preserving every unrelated UI-owned method.
    public static SaoLauncherService decorate(SaoLauncherService fake) {
        return (SaoLauncherService) Proxy.newProxyInstance(SaoLauncherService.class.getClassLoader(),
                new Class<?>[]{SaoLauncherService.class}, new SaoExtrasSamples(fake));
    }

    /// Implements only the additive extras seam; feedback returns an explicitly labelled synthetic artifact path.
    @Override public @Nullable Object invoke(Object proxy, Method method, Object @Nullable [] args) throws Throwable {
        Object[] values = args == null ? new Object[0] : args;
        try {
            return switch (method.getName()) {
                case "launcherBehavior" -> behavior;
                case "setLauncherBehavior" -> { behavior = (SaoData.LauncherBehavior) values[0]; yield null; }
                case "graphicsPreset" -> preset;
                case "setGraphicsPreset" -> { preset = (SaoData.GraphicsPreset) values[0]; yield null; }
                case "suggestedGraphicsPreset" -> CompletableFuture.completedFuture(SaoData.GraphicsPreset.BALANCED);
                case "links" -> links;
                case "refreshLinks" -> null;
                case "buildFeedbackZip" -> CompletableFuture.completedFuture(new SaoData.FeedbackZip(Path.of("DEMO-NOT-A-REAL-FILE", "SAO-feedback-demo.zip"), 1048576));
                case "systemCheck" -> CompletableFuture.completedFuture(List.of(
                        new SaoData.SystemCheck(SaoData.CheckLevel.OK, "内存充足（示例 16 GB）"),
                        new SaoData.SystemCheck(SaoData.CheckLevel.WARN, "剩余空间不足 6 GB，建议清理（示例）"),
                        new SaoData.SystemCheck(SaoData.CheckLevel.OK, "Java 17 与系统架构兼容（示例）"),
                        new SaoData.SystemCheck(SaoData.CheckLevel.OK, "实例位置可写（示例）")));
                case "preference" -> preferences.getOrDefault((String) values[0], base.preference((String) values[0], (String) values[1]));
                case "setPreference" -> { preferences.put((String) values[0], (String) values[1]); yield null; }
                case "userNoticeVersion" -> "2026-11-09.1";
                case "userNoticeAccepted" -> "2026-11-09.1".equals(preferences.get("notice.accepted"));
                case "acceptUserNotice" -> {
                    if (!"2026-11-09.1".equals(values[0])) throw new IllegalArgumentException("用户须知已更新，请重新阅读");
                    preferences.put("notice.accepted", (String) values[0]); yield null;
                }
                case "notification" -> event.getReadOnlyProperty();
                case "setLauncherAttention" -> {
                    if (!(boolean) values[0] || !(boolean) values[1]) {
                        long id = ++sequence;
                        SaoData.NotificationKind kind = SaoData.NotificationKind.values()[(int) ((id - 1) % 3)];
                        event.set(new SaoData.Notification(id, kind, kind == SaoData.NotificationKind.FRIEND_REQUEST ? "" : "demo-direct",
                                kind == SaoData.NotificationKind.FRIEND_REQUEST ? "好友申请（示例）" : "攻略搭档（示例）",
                                kind == SaoData.NotificationKind.INVITE ? "邀请你一起 Link Start（示例）" : "收到一条新消息（示例）", System.currentTimeMillis()));
                    }
                    yield null;
                }
                default -> method.invoke(base, args);
            };
        } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
