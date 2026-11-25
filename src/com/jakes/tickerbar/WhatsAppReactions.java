package com.jakes.tickerbar;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.util.regex.Pattern;

/** Recognizes reaction-only WhatsApp cards, which can share a channel with real messages. */
final class WhatsAppReactions {
    private static final Pattern REACTION_CHANNEL = Pattern.compile(
            "(?i)(?:(?:group|message|individual)[-_ ])?reactions?(?:[-_ ]notifications?)?(?:[-_ ]\\d+)?");
    private static final Pattern REACTION_TEXT = Pattern.compile(
            "(?is)^reacted\\s+(?:with\\s+)?"
                    + "(?=[^\\s]*[\\p{So}\\u20e3])[^\\s\\p{L}]+\\s+to\\b(?:\\s|:).*");

    static boolean matches(StatusBarNotification sbn) {
        if (sbn == null || !("com.whatsapp".equals(sbn.getPackageName())
                || "com.whatsapp.w4b".equals(sbn.getPackageName()))) return false;
        Notification n = sbn.getNotification();
        if (n == null || !sbn.isClearable() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
            return false;

        String channel = n.getChannelId();
        boolean reactionChannel = channel != null && REACTION_CHANNEL.matcher(channel).matches();
        Bundle extras = n.extras;
        if (extras == null) return reactionChannel;

        // Cancellation removes the entire card. Inspect every current MessagingStyle or
        // InboxStyle entry before trusting the collapsed preview of the latest one.
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        boolean hasEntries = false;
        if (messages != null) for (Parcelable entry : messages) {
            if (!(entry instanceof Bundle)) return false;
            Bundle message = (Bundle) entry;
            Parcelable person = message.getParcelable("sender_person");
            CharSequence sender = person instanceof Person ? ((Person) person).getName()
                    : message.getCharSequence("sender");
            if (!reactionText(message.getCharSequence("text"), sender)) return false;
            hasEntries = true;
        }
        if (lines != null) for (CharSequence line : lines) {
            if (!reactionText(line, title)) return false;
            hasEntries = true;
        }
        if (hasEntries || reactionChannel) return true;
        CharSequence expanded = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        return reactionText(expanded != null && expanded.length() > 0
                ? expanded : extras.getCharSequence(Notification.EXTRA_TEXT), title);
    }

    private static boolean reactionText(CharSequence text, CharSequence sender) {
        if (text == null) return false;
        String clean = text.toString().replace("\u200e", "").replace("\u200f", "").trim();
        if (sender != null && sender.length() > 0 && clean.startsWith(sender.toString())) {
            String suffix = clean.substring(sender.length());
            if (suffix.startsWith(":") || suffix.startsWith(" ")) clean =
                    suffix.substring(1).trim();
        }
        return REACTION_TEXT.matcher(clean).matches();
    }
}
