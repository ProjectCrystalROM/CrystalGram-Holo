# Forum topics on TDLib 1.8.71

Telegram uses the MTProto `channel` constructor for both broadcast channels and supergroups. A group forum is a supergroup with the forum flag. General is a real topic, and other topics are anchored to their creation messages at the protocol level. TDLib exposes an explicit `ForumTopicInfo.forumTopicId` and maps General to a `MessageTopicForum` in its message objects. Use the returned ID even for General. Ordinary message threads and channel discussions use different routing. [Forum protocol](https://core.telegram.org/api/forum) · [Message threads](https://core.telegram.org/api/threads) · [Read acknowledgments](https://core.telegram.org/method/messages.readDiscussion)

## Scope

The routing decision requires the chat and, for a supergroup, its matching `Supergroup` metadata. Either object identifying a broadcast channel blocks access. Channel direct-message groups are also outside scope. Only `Supergroup.isForum` on a supported supergroup enables a topic list. `isBroadcastGroup` has a distinct meaning in TDLib and is separate from `isChannel`. The same eligibility applies to conversation entry, topic operations and notification previews. There is no channel discovery, channel navigation, or sponsored-message infrastructure. [Telegram API Terms of Service §3.3](https://core.telegram.org/api/terms)

## Callable APIs and update behavior

The application bundles TDLib revision [`1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d`](https://github.com/tdlib/td/blob/1b9efe84d6e9a2e2f6eadeeea32b0726d2b8c25d/td/generate/scheme/td_api.tl). Its schema and the generated Java bindings are the signature authority. The public HTML page for `getForumTopics` omits the pinned icon and open/closed filters, which are also present in the retrieved master schema. The Java constructor takes these three arguments before the cursor. [TDLib documentation](https://core.telegram.org/tdlib/docs/) · [Published topic list reference](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_forum_topics.html) · [Master schema](https://github.com/tdlib/td/blob/master/td/generate/scheme/td_api.tl)

| Integration | Pinned API and behavior |
| --- | --- |
| Eligibility | `GetChat(chatId)`, `GetSupergroup(supergroupId)` and `UpdateSupergroup`. Use both `ChatTypeSupergroup.isChannel` and `Supergroup.isChannel` plus the direct-message flags. |
| Topic list | `GetForumTopics(chatId, "", 0, false, false, date, messageId, topicId, limit)`. Start with three zero offsets. Use all returned `nextOffset*` fields and stop on a zero topic cursor or a repeated cursor. A short page and the approximate total count are insufficient end markers. Sort by descending `ForumTopic.order`. |
| Topic metadata | `GetForumTopic(chatId, topicId)` and `UpdateForumTopicInfo` provide the name and closed or hidden state. Closed topics allow the topic creator or a member with topic-management rights to send. Hidden General stays out of the default list. `UpdateForumTopic` carries read markers, pin and notification settings, but omits the unread-message count and list order. Refresh list snapshots on relevant updates. |
| Topic history | `GetForumTopicHistory(chatId, topicId, fromMessageId, 0, limit)` returns newest first and can include the anchor. Use the topic-specific method, deduplicate and order by message ID. `OpenChat` and `CloseChat` are chat-level. The pinned API has no open/close topic pair. |
| Sending | Use `MessageTopicForum(topicId)` in `SendMessage` for text and attachments, and in `SendChatAction` for typing. Route new messages and send acknowledgments using both chat ID and `Message.topicId`. Typing uses `UpdateChatAction.topicId`. Apply edits and deletions only to matching message IDs in the selected history. Root deletion uses the pinned TDLib conversion from forum topic ID to server message ID (shift left by 20), with General excluded. |
| Reading | Use `ViewMessages(chatId, visibleIds, new MessageSourceForumTopicHistory(), false)`. The pinned `MessagesManager::view_messages` derives the topic from the IDs and rejects mixed topics. Opening a topic list alone sends no read acknowledgment. |
| Notifications | `UpdateActiveNotifications`, `UpdateNotificationGroup` and `UpdateNotification` identify a chat and its notifications. A full `NotificationTypeNewMessage.message` supplies the topic. Push previews have no topic field in this revision. Unknown topic routes go to the forum list. Filter chat eligibility before rendering and suppress locally only messages from the visible topic. |

TDLib delivers ordered updates alongside asynchronous results. Use main-looper UI changes and reject results for obsolete screen or pagination requests. [TDLib getting started](https://core.telegram.org/tdlib/getting-started) · [Topic history](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_forum_topic_history.html) · [Sending](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1send_message.html) · [Viewing messages](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1view_messages.html) · [Notification updates](https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1update_notification_group.html)

## Validation

Run `scripts/test-forum-routing.sh` with JDK 17. The host regression checks cover
ordinary supergroups, General and named topics, sibling-topic message and
notification isolation, closed-topic permissions, root deletion, and fail-closed
behavior for broadcast channels, channel direct-message groups and missing or
mismatched metadata.

Device checks should cover opening an ordinary supergroup, switching between
General and a named topic, loading older history, sending text and an attachment,
reading only the visible topic, opening a sibling-topic notification, and
verifying that a subscribed broadcast channel cannot appear in the list, produce
a notification preview, or open through any forum route. Topic administration
and the optional unified “View as messages” mode are outside this implementation.

The signed APK installed over the previous release on the Galaxy S III running
API 18. The on-device APK hash matched the release build. Android denied
external launches of the conversation activity, the forum activity and the
private notification alias.

The project maintainer confirmed forum browsing and sent a message to a topic
on the API 18 device. A modern Telegram client confirmed delivery to the
intended topic.
