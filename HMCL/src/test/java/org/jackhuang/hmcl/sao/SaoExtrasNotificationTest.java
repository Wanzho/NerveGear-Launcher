/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import org.jetbrains.annotations.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Pure offline delivery tests cover foreground/mute gates, invite expiry, replay suppression and account fencing.
@NotNullByDefault
class SaoExtrasNotificationTest {
    /// Fixed test clock, independent of execution speed.
    private static final long NOW = 1000000;

    /// Produces a conversation with explicit protocol-2 mute/notification flags.
    private static SaoData.Chat chat(boolean muted) {
        return new SaoData.Chat("direct", "攻略搭档", false, List.of("self", "peer"), 1, "new", NOW, 0, muted, false, !muted, 0, List.of());
    }

    /// Produces a confirmed authoritative delivery; no local/history IDs are manufactured by production.
    private static SaoData.ChatMessage message(long id, boolean mine, boolean notify, SaoData.MessageKind kind, long expires) {
        return new SaoData.ChatMessage("peer", "new message 😀", NOW, mine, false, id, "direct", "peer-id", "nonce", id,
                kind, null, kind == SaoData.MessageKind.INVITE ? new SaoData.PlayInvite("mc.saooath.com", expires) : null, List.of(), notify);
    }

    /// Starts from an authenticated baseline with no synthetic old-inbox alerts.
    private static SaoSocialNotifications ready(List<SaoData.Notification> events) {
        var notifications = new SaoSocialNotifications(Runnable::run, () -> NOW);
        notifications.event().addListener((observable, previous, next) -> { if (next != null) events.add(next); });
        notifications.session(true); notifications.accept(List.of(chat(false)), List.of(), List.of()); return notifications;
    }

    /// Foreground messages are consumed, and focus loss enables only subsequent incoming non-muted deliveries.
    @Test void attentionAndProtocolMute() {
        List<SaoData.Notification> events = new ArrayList<>(); var notifications = ready(events);
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(1, false, true, SaoData.MessageKind.TEXT, 0))); assertTrue(events.isEmpty());
        notifications.attention(true, false);
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(1, false, true, SaoData.MessageKind.TEXT, 0), message(2, false, true, SaoData.MessageKind.TEXT, 0)));
        assertEquals(1, events.size()); assertEquals(SaoData.NotificationKind.MESSAGE, events.get(0).kind());
        notifications.attention(false, true);
        notifications.accept(List.of(chat(true)), List.of(), List.of(message(3, false, true, SaoData.MessageKind.TEXT, 0)));
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(4, false, false, SaoData.MessageKind.TEXT, 0), message(5, true, true, SaoData.MessageKind.TEXT, 0), message(6, false, true, SaoData.MessageKind.RECALLED, 0)));
        assertEquals(1, events.size());
    }

    /// Requests alert only on new incoming transitions; outgoing and repeated snapshots do not alert.
    @Test void requestTransitionsAndInitialInbox() {
        List<SaoData.Notification> events = new ArrayList<>(); var notifications = new SaoSocialNotifications(Runnable::run, () -> NOW);
        notifications.event().addListener((observable, old, next) -> { if (next != null) events.add(next); });
        notifications.session(true); notifications.attention(false, false);
        notifications.accept(List.of(chat(false)), List.of(new SaoData.FriendRequest("old", true)), List.of(message(10, false, true, SaoData.MessageKind.TEXT, 0)));
        assertTrue(events.isEmpty());
        var pending = List.of(new SaoData.FriendRequest("old", true), new SaoData.FriendRequest("new", true), new SaoData.FriendRequest("outgoing", false));
        notifications.accept(List.of(chat(false)), pending, List.of()); notifications.accept(List.of(chat(false)), pending, List.of());
        assertEquals(1, events.size()); assertEquals(SaoData.NotificationKind.FRIEND_REQUEST, events.get(0).kind()); assertEquals("", events.get(0).chat());
        notifications.accept(List.of(chat(false)), List.of(), List.of()); notifications.accept(List.of(chat(false)), List.of(new SaoData.FriendRequest("new", true)), List.of());
        assertEquals(2, events.size());
    }

    /// An API state refresh before first SSE/poll delivery does not turn the old inbox into new message alerts.
    @Test void apiRefreshCannotConsumeLiveBaseline() {
        List<SaoData.Notification> events = new ArrayList<>();
        var notifications = new SaoSocialNotifications(Runnable::run, () -> NOW);
        notifications.event().addListener((observable, previous, next) -> { if (next != null) events.add(next); });
        notifications.session(true); notifications.attention(false, false);
        notifications.accept(List.of(chat(false)), List.of(), List.of(), false);
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(1, false, true, SaoData.MessageKind.TEXT, 0)), true);
        assertTrue(events.isEmpty());
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(2, false, true, SaoData.MessageKind.TEXT, 0)), true);
        assertEquals(1, events.size());
    }

    /// Invite expiry is inclusive and replayed message revisions never create a second notification.
    @Test void invitationsExpiryAndRevisionReplay() {
        List<SaoData.Notification> events = new ArrayList<>(); var notifications = ready(events); notifications.attention(false, false);
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(1, false, true, SaoData.MessageKind.INVITE, NOW), message(2, false, true, SaoData.MessageKind.INVITE, NOW + 1)));
        assertEquals(1, events.size()); assertEquals(SaoData.NotificationKind.INVITE, events.get(0).kind());
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(2, false, true, SaoData.MessageKind.INVITE, NOW + 1000)));
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(2, false, true, SaoData.MessageKind.RECALLED, 0)));
        assertEquals(1, events.size());
    }

    /// Signout/account replacement invalidates queued callbacks; becoming foreground also suppresses queued alerts.
    @Test void publicationFencesAndStableProperty() {
        List<Runnable> queue = new ArrayList<>(); List<SaoData.Notification> events = new ArrayList<>();
        var notifications = new SaoSocialNotifications(queue::add, () -> NOW);
        assertSame(notifications.event(), notifications.event()); notifications.event().addListener((observable, old, next) -> { if (next != null) events.add(next); });
        notifications.session(true); notifications.attention(false, false); notifications.accept(List.of(chat(false)), List.of(), List.of());
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(1, false, true, SaoData.MessageKind.TEXT, 0))); notifications.session(false);
        queue.forEach(Runnable::run); queue.clear(); assertTrue(events.isEmpty()); assertNull(notifications.event().get());
        notifications.session(true); notifications.accept(List.of(chat(false)), List.of(), List.of());
        notifications.accept(List.of(chat(false)), List.of(), List.of(message(2, false, true, SaoData.MessageKind.TEXT, 0))); notifications.attention(true, true);
        queue.forEach(Runnable::run); assertTrue(events.isEmpty());
    }
}
