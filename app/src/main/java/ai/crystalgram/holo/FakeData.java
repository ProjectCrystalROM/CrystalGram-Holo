package ai.crystalgram.holo;

/**
 * Placeholder conversations, used ONLY so the chat list and message view can be
 * evaluated on real hardware before TDLib is linked. Nothing here talks to a
 * network and nothing is persisted.
 *
 * Replaced wholesale once TDLib's Client is wired up.
 */
public final class FakeData {

    private FakeData() {}

    public static final class Chat {
        public final String title;
        public final String preview;
        public final Message[] messages;
        Chat(String title, String preview, Message[] messages) {
            this.title = title;
            this.preview = preview;
            this.messages = messages;
        }
    }

    public static final class Message {
        public final String text;
        public final boolean outgoing;
        public final String time;
        public Message(String text, boolean outgoing, String time) {
            this.text = text;
            this.outgoing = outgoing;
            this.time = time;
        }
    }

    private static Message in(String t, String tm)  { return new Message(t, false, tm); }
    private static Message out(String t, String tm) { return new Message(t, true, tm); }

    public static final Chat[] CHATS = {
        new Chat("Alice", "see you at 7 then", new Message[] {
            in("are you around tonight?",    "18:02"),
            out("should be, what's up?",      "18:04"),
            in("dinner? there's a place near the station", "18:05"),
            out("sounds good",               "18:06"),
            in("see you at 7 then",          "18:07"),
        }),
        new Chat("Bob", "sent a photo", new Message[] {
            in("sent a photo", "12:30"),
        }),
        new Chat("CrystalBTS build", "ci: 3 warnings", new Message[] {
            in("ci: 3 warnings", "09:14"),
            out("which files?", "09:20"),
            in("cdma-femto and a11", "09:21"),
        }),
        new Chat("Dan", "ok", new Message[] {
            out("ping", "yesterday"),
            in("ok", "yesterday"),
        }),
        new Chat("Saved Messages", "notes on the ROM", new Message[] {
            out("notes on the ROM", "Mon"),
        }),
        new Chat("Eve", "thanks!", new Message[] {
            in("thanks!", "Sun"),
        }),
    };
}
