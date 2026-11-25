package ai.crystalgram.holo;

import org.drinkless.tdlib.TdApi;

enum OutgoingReceipt {
    NONE, SENDING, SENT, SEEN, FAILED, SCHEDULED;

    static OutgoingReceipt of(TdApi.Message message, boolean failed, long lastReadOutboxId) {
        if (!message.isOutgoing) return NONE;
        if (failed || message.sendingState instanceof TdApi.MessageSendingStateFailed) return FAILED;
        if (message.sendingState instanceof TdApi.MessageSendingStatePending) return SENDING;
        if (message.schedulingState != null) return SCHEDULED;
        return message.id > 0 && message.id <= lastReadOutboxId ? SEEN : SENT;
    }
}
