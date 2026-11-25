package ai.crystalgram.holo;

import org.drinkless.tdlib.TdApi;

/** Shared eligibility and routing for all conversation entry points. */
enum ChatScope {
    EXCLUDED, CONVERSATION, FORUM;

    static ChatScope of(TdApi.ChatType type, TdApi.Supergroup group) {
        if (type instanceof TdApi.ChatTypeSupergroup) {
            TdApi.ChatTypeSupergroup supergroup = (TdApi.ChatTypeSupergroup) type;
            if (supergroup.isChannel || group == null || group.id != supergroup.supergroupId
                    || group.isChannel || group.isDirectMessagesGroup
                    || group.isAdministeredDirectMessagesGroup) return EXCLUDED;
            return group.isForum ? FORUM : CONVERSATION;
        }
        if (type instanceof TdApi.ChatTypePrivate || type instanceof TdApi.ChatTypeBasicGroup
                || type instanceof TdApi.ChatTypeSecret) return CONVERSATION;
        return EXCLUDED;
    }

    boolean allowsHistory(int topicId) {
        return this == FORUM ? topicId > 0 : this == CONVERSATION && topicId == 0;
    }

    boolean suppressesNotification(boolean matchingChat, int openTopic, int notificationTopic) {
        if (!matchingChat) return false;
        if (this == CONVERSATION) return true;
        return this == FORUM && openTopic > 0 && openTopic == notificationTopic;
    }

    static boolean canManageTopics(TdApi.ChatMemberStatus status) {
        if (status instanceof TdApi.ChatMemberStatusCreator) return true;
        if (!(status instanceof TdApi.ChatMemberStatusAdministrator)) return false;
        TdApi.ChatAdministratorRights rights = ((TdApi.ChatMemberStatusAdministrator) status).rights;
        return rights != null && rights.canManageTopics;
    }

    static boolean canSendToTopic(TdApi.ForumTopicInfo info, TdApi.Supergroup group) {
        return info != null && (!info.isClosed || info.isOutgoing
                || (group != null && canManageTopics(group.status)));
    }

    static boolean isDeletedTopicRoot(TdApi.ForumTopicInfo topic, long messageId) {
        // ForumTopicId::to_top_thread_message_id and MessageId::SERVER_ID_SHIFT at TDLib 1.8.71.
        return topic != null && !topic.isGeneral && messageId == ((long) topic.forumTopicId << 20);
    }

    static int forumTopic(TdApi.MessageTopic topic) {
        return topic instanceof TdApi.MessageTopicForum
                ? ((TdApi.MessageTopicForum) topic).forumTopicId : 0;
    }

    static boolean matches(long chatId, int topicId, TdApi.Message message) {
        return message != null && message.chatId == chatId
                && (topicId == 0 || forumTopic(message.topicId) == topicId);
    }
}
