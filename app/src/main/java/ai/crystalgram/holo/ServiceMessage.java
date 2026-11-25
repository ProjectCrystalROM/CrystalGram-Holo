package ai.crystalgram.holo;

import org.drinkless.tdlib.TdApi;

/** Inline chat events in the pinned TDLib message schema. */
final class ServiceMessage {
    private ServiceMessage() {}

    static String describe(TdApi.MessageContent content, boolean secretChat) {
        if (content == null) return null;
        switch (content.getConstructor()) {
            case TdApi.MessageChatSetMessageAutoDeleteTime.CONSTRUCTOR: {
                int seconds = ((TdApi.MessageChatSetMessageAutoDeleteTime) content).messageAutoDeleteTime;
                String timer = secretChat ? "Self-destruct timer" : "Auto-delete";
                return seconds == 0 ? timer + " turned off" : timer + " set to " + duration(seconds);
            }
            case TdApi.MessageBasicGroupChatCreate.CONSTRUCTOR:
            case TdApi.MessageSupergroupChatCreate.CONSTRUCTOR:
                return "Group created";
            case TdApi.MessageChatChangeTitle.CONSTRUCTOR:
                return "Chat name changed to “" + ((TdApi.MessageChatChangeTitle) content).title + "”";
            case TdApi.MessageChatChangePhoto.CONSTRUCTOR: return "Chat photo changed";
            case TdApi.MessageChatDeletePhoto.CONSTRUCTOR: return "Chat photo removed";
            case TdApi.MessageChatAddMembers.CONSTRUCTOR: return "Members added";
            case TdApi.MessageChatJoinByLink.CONSTRUCTOR:
            case TdApi.MessageChatJoinByRequest.CONSTRUCTOR:
            case TdApi.MessageChatJoinFromCommunity.CONSTRUCTOR: return "A member joined the group";
            case TdApi.MessageChatDeleteMember.CONSTRUCTOR: return "A member left or was removed";
            case TdApi.MessageChatOwnerLeft.CONSTRUCTOR:
            case TdApi.MessageChatOwnerChanged.CONSTRUCTOR: return "Group ownership changed";
            case TdApi.MessageChatUpgradeTo.CONSTRUCTOR:
            case TdApi.MessageChatUpgradeFrom.CONSTRUCTOR: return "Group upgraded";
            case TdApi.MessagePinMessage.CONSTRUCTOR: return "Message pinned";
            case TdApi.MessageChatSetBackground.CONSTRUCTOR: return "Chat background changed";
            case TdApi.MessageChatSetTheme.CONSTRUCTOR: return "Chat theme changed";
            case TdApi.MessageScreenshotTaken.CONSTRUCTOR: return "Screenshot taken";
            case TdApi.MessageChatHasProtectedContentToggled.CONSTRUCTOR:
                return ((TdApi.MessageChatHasProtectedContentToggled) content).newHasProtectedContent
                        ? "Content protection turned on" : "Content protection turned off";
            case TdApi.MessageChatHasProtectedContentDisableRequested.CONSTRUCTOR:
                return "Request to turn off content protection";
            case TdApi.MessageForumTopicCreated.CONSTRUCTOR:
                return "Topic created: “" + ((TdApi.MessageForumTopicCreated) content).name + "”";
            case TdApi.MessageForumTopicEdited.CONSTRUCTOR: {
                String name = ((TdApi.MessageForumTopicEdited) content).name;
                return name == null || name.isEmpty() ? "Topic updated" : "Topic renamed to “" + name + "”";
            }
            case TdApi.MessageForumTopicIsClosedToggled.CONSTRUCTOR:
                return ((TdApi.MessageForumTopicIsClosedToggled) content).isClosed ? "Topic closed" : "Topic reopened";
            case TdApi.MessageForumTopicIsHiddenToggled.CONSTRUCTOR:
                return ((TdApi.MessageForumTopicIsHiddenToggled) content).isHidden ? "Topic hidden" : "Topic made visible";
            case TdApi.MessageCall.CONSTRUCTOR:
                return ((TdApi.MessageCall) content).isVideo ? "Video call" : "Voice call";
            case TdApi.MessageVideoChatScheduled.CONSTRUCTOR: return "Video chat scheduled";
            case TdApi.MessageVideoChatStarted.CONSTRUCTOR: return "Video chat started";
            case TdApi.MessageVideoChatEnded.CONSTRUCTOR: return "Video chat ended";
            case TdApi.MessageContactRegistered.CONSTRUCTOR: return "Contact joined Telegram";
            case TdApi.MessageCustomServiceAction.CONSTRUCTOR: {
                String text = ((TdApi.MessageCustomServiceAction) content).text;
                return text == null || text.trim().isEmpty() ? "Chat updated" : text;
            }
            default: return null;
        }
    }

    private static String duration(int seconds) {
        int[] sizes = {30 * 86400, 7 * 86400, 86400, 3600, 60};
        String[] names = {"month", "week", "day", "hour", "minute"};
        for (int i = 0; i < sizes.length; i++) {
            if (seconds >= sizes[i] && seconds % sizes[i] == 0) {
                int count = seconds / sizes[i];
                return count + " " + names[i] + (count == 1 ? "" : "s");
            }
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }
}
