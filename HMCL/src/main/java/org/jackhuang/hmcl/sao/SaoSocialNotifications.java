/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import javafx.beans.property.*;
import org.jetbrains.annotations.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/// Ephemeral social event stream, with session fencing, live-delivery deduplication and protocol-2 mute enforcement.
/// History/search rows never enter this class. Foreground deliveries are consumed without later notification replay.
@NotNullByDefault
final class SaoSocialNotifications {
    /// Stable UI-listenable event property; no chat bodies are written to disk.
    private final ReadOnlyObjectWrapper<SaoData.@Nullable Notification> event = new ReadOnlyObjectWrapper<>();
    /// Dispatcher supplied by the existing social publication seam.
    private final Consumer<Runnable> publish;
    /// Injectable wall clock for exact invite-expiry tests.
    private final LongSupplier clock;
    /// Seen live IDs by chat, bounded to avoid growth during a very long session.
    private final LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
    /// Current incoming pending-request names; initial snapshot is a baseline, not a new event.
    private Set<String> requests = new HashSet<>();
    /// True after the first authenticated snapshot.
    private boolean initialized;
    /// API/history snapshots cannot consume the first live-delivery baseline.
    private boolean liveInitialized;
    /// True only after a session fence has explicitly enabled delivery.
    private boolean active;
    /// Actual visibility/focus, provided by the window owner without polling JavaFX state on worker threads.
    private volatile boolean visible = true;
    /// Foreground focus gate, independent of whether the window is on screen.
    private volatile boolean focused = true;
    /// Cancels queued old-account notifications immediately on signout or account replacement.
    private long generation;
    /// Monotonic local event identity across all accounts in this process.
    private long sequence;

    /// Uses the frontend publication dispatcher and a deterministic testable clock.
    SaoSocialNotifications(Consumer<Runnable> publish, LongSupplier clock) { this.publish = publish; this.clock = clock; }

    /// Stable read-only stream; listeners receive individual events, never a bulk inbox replay.
    ReadOnlyObjectProperty<SaoData.@Nullable Notification> event() { return event.getReadOnlyProperty(); }

    /// Called by the window owner on visibility or focus changes.
    void attention(boolean visible, boolean focused) { this.visible = visible; this.focused = focused; }

    /// Resets all message/request deduplication before the account engine clears or replaces its session.
    synchronized void session(boolean signedIn) {
        long expected = ++generation; active = signedIn; initialized = false; liveInitialized = false; seen.clear(); requests.clear();
        publish.accept(() -> { synchronized (this) { if (expected == generation) event.set(null); } });
    }

    /// Applies a current-authority snapshot; caller passes only messages carried by this live/poll batch.
    synchronized void accept(List<SaoData.Chat> chats, List<SaoData.FriendRequest> pending,
                             List<SaoData.ChatMessage> deliveries) {
        accept(chats, pending, deliveries, true);
    }

    /// Separates the first live inbox baseline from ordinary API snapshot refreshes.
    synchronized void accept(List<SaoData.Chat> chats, List<SaoData.FriendRequest> pending,
                             List<SaoData.ChatMessage> deliveries, boolean liveDelivery) {
        if (!active) return;
        Map<String, SaoData.Chat> conversations = new HashMap<>(); chats.forEach(chat -> conversations.put(chat.id(), chat));
        Set<String> incoming = new HashSet<>(); pending.stream().filter(SaoData.FriendRequest::incoming).forEach(request -> incoming.add(request.name()));
        boolean baseline = !initialized;
        for (SaoData.ChatMessage message : deliveries) {
            if (message.id() <= 0 || message.pending()) continue;
            String key = message.chat() + ":" + message.id();
            boolean duplicate = seen.put(key, Boolean.TRUE) != null;
            while (seen.size() > 20000) seen.remove(seen.keySet().iterator().next());
            @Nullable SaoData.Chat chat = conversations.get(message.chat());
            if (!liveDelivery || !liveInitialized || duplicate || message.mine() || !message.shouldNotify() || chat == null || chat.muted()
                    || message.kind() == SaoData.MessageKind.RECALLED) continue;
            if (message.kind() == SaoData.MessageKind.INVITE
                    && (message.invite() == null || clock.getAsLong() >= message.invite().expiresAt())) continue;
            SaoData.NotificationKind kind = message.kind() == SaoData.MessageKind.INVITE ? SaoData.NotificationKind.INVITE : SaoData.NotificationKind.MESSAGE;
            emit(kind, message.chat(), chat.title(), message.text());
        }
        if (!baseline) incoming.stream().sorted().filter(name -> !requests.contains(name))
                .forEach(name -> emit(SaoData.NotificationKind.FRIEND_REQUEST, "", "好友申请", name + " 想添加你为好友"));
        requests = incoming; initialized = true; if (liveDelivery) liveInitialized = true;
    }

    /// Rechecks both session and actual attention at publication time; callback values contain plain bounded text.
    private void emit(SaoData.NotificationKind kind, String chat, String title, String preview) {
        if (visible && focused) return;
        long expected = generation;
        SaoData.Notification next = new SaoData.Notification(++sequence, kind, chat, bounded(title, 80), bounded(preview, 160), clock.getAsLong());
        publish.accept(() -> {
            synchronized (this) {
                if (active && expected == generation && !(visible && focused)) event.set(next);
            }
        });
    }

    /// Removes controls and truncates by Unicode code point so emoji are never cut in half.
    private static String bounded(String text, int limit) {
        String clean = text.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return clean.substring(0, clean.offsetByCodePoints(0, Math.min(limit, clean.codePointCount(0, clean.length()))));
    }
}
