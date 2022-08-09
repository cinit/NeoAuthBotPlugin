package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.EventHandler
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import com.google.gson.JsonObject


fun Bot.doOnNextMessage(targetChatId: Long, receiver: (senderId: Long, message: Message) -> Boolean) {
    if (targetChatId == 0L) {
        throw IllegalArgumentException("chatId must be non-zero")
    }
    if (!this.isAuthenticated || this.userId == 0L) {
        throw IllegalStateException("bot is not authenticated")
    }
    val listener = object : EventHandler.MessageListenerV1 {
        override fun onReceiveMessage(bot: Bot, chatId: Long, senderId: Long, message: Message): Boolean {
            return if (chatId == targetChatId) {
                bot.unregisterOnReceiveMessageListener(this)
                receiver(senderId, message)
            } else {
                false
            }
        }

        override fun onDeleteMessages(bot: Bot, chatId: Long, msgIds: List<Long>): Boolean {
            return false
        }

        override fun onUpdateMessageContent(bot: Bot, chatId: Long, msgId: Long, content: JsonObject): Boolean {
            return false
        }

        override fun onMessageEdited(bot: Bot, chatId: Long, msgId: Long, editDate: Int): Boolean {
            return false
        }
    }
    this.registerOnReceiveMessageListener(listener)
}
