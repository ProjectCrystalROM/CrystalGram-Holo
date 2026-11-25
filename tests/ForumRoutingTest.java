package ai.crystalgram.holo;

import org.drinkless.tdlib.TdApi;

public final class ForumRoutingTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup(42, false);
        TdApi.Supergroup group = new TdApi.Supergroup();
        group.id = 42;

        ChatScope scope = ChatScope.of(type, group, null);
        check(scope == ChatScope.CONVERSATION && scope.allowsHistory(0), "ordinary supergroup opens chat");
        check(!scope.allowsHistory(5), "ordinary supergroup cannot enter topic history");
        TdApi.ChatTypePrivate privateChat = new TdApi.ChatTypePrivate(12);
        TdApi.User peer = new TdApi.User();
        peer.id = 12;
        peer.type = new TdApi.UserTypeRegular();
        check(ChatScope.of(privateChat, null, peer) == ChatScope.CONVERSATION, "human private chat");
        peer.type = new TdApi.UserTypeDeleted();
        check(ChatScope.of(privateChat, null, peer) == ChatScope.CONVERSATION, "deleted account chat");
        peer.type = new TdApi.UserTypeBot();
        check(ChatScope.of(privateChat, null, peer) == ChatScope.EXCLUDED, "private bot chat");
        peer.type = new TdApi.UserTypeUnknown();
        check(ChatScope.of(privateChat, null, peer) == ChatScope.EXCLUDED, "unknown private peer");
        check(ChatScope.of(privateChat, null, null) == ChatScope.EXCLUDED, "uncached private peer");
        check(ChatScope.of(new TdApi.ChatTypeBasicGroup(), null, null) == ChatScope.CONVERSATION,
                "basic group remains available");

        group.isForum = true;
        scope = ChatScope.of(type, group, null);
        check(scope == ChatScope.FORUM && !scope.allowsHistory(0), "forum opens its topic list");
        check(scope.allowsHistory(1) && scope.allowsHistory(5), "General and named topics open");
        check(!scope.allowsHistory(-1), "invalid topic ID");

        TdApi.Message message = new TdApi.Message();
        message.chatId = 101;
        message.topicId = new TdApi.MessageTopicForum(5);
        check(ChatScope.matches(101, 5, message), "message in same topic");
        check(!ChatScope.matches(101, 1, message), "message in another topic");
        check(!ChatScope.matches(102, 5, message), "same topic ID in another chat");
        message.topicId = new TdApi.MessageTopicThread(5);
        check(!ChatScope.matches(101, 5, message), "normal thread is separate from forum topic");
        check(scope.suppressesNotification(true, 5, 5), "visible topic suppresses its own notification");
        check(!scope.suppressesNotification(true, 5, 6), "sibling topic can notify");
        check(!scope.suppressesNotification(true, 5, 0), "unknown topic can notify");
        check(!scope.suppressesNotification(false, 5, 5), "other chat can notify");

        TdApi.ForumTopicInfo info = new TdApi.ForumTopicInfo();
        info.forumTopicId = 5;
        info.isClosed = true;
        check(!ChatScope.canSendToTopic(info, group), "member cannot send to a closed topic");
        info.isOutgoing = true;
        check(ChatScope.canSendToTopic(info, group), "topic creator can send to a closed topic");
        info.isOutgoing = false;
        group.status = new TdApi.ChatMemberStatusCreator();
        check(ChatScope.canSendToTopic(info, group), "owner can send to a closed topic");
        check(ChatScope.isDeletedTopicRoot(info, 5L << 20), "TDLib server-message ID for topic root");
        check(!ChatScope.isDeletedTopicRoot(info, 5), "forum ID is not a TDLib message ID");
        info.isGeneral = true;
        check(!ChatScope.isDeletedTopicRoot(info, 5L << 20), "General cannot be deleted");

        assertExcluded(new TdApi.ChatTypeSupergroup(42, true), group);
        group.isChannel = true;
        assertExcluded(type, group);
        group.isChannel = false;
        group.isDirectMessagesGroup = true;
        assertExcluded(type, group);
        group.isDirectMessagesGroup = false;
        group.isAdministeredDirectMessagesGroup = true;
        assertExcluded(type, group);
        group.isAdministeredDirectMessagesGroup = false;
        assertExcluded(type, null);
        group.id = 99;
        assertExcluded(type, group);
        System.out.println("Forum routing regression checks passed.");
    }

    private static void assertExcluded(TdApi.ChatType type, TdApi.Supergroup group) {
        ChatScope scope = ChatScope.of(type, group, null);
        check(scope == ChatScope.EXCLUDED && !scope.allowsHistory(0) && !scope.allowsHistory(5),
                "excluded chat must not appear or open through chat or topic navigation");
    }
}
