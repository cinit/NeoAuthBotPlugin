package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.EventHandler
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import com.google.gson.JsonObject


fun Bot.doOnNextMessage(si0: SessionInfo, receiver: (senderId: Long, message: Message) -> Boolean) {
    check(this.isAuthenticated && this.userId > 0) { "bot is not authenticated" }
    val listener = object : EventHandler.MessageListenerV1 {
        override fun onReceiveMessage(bot: Bot, si1: SessionInfo, senderId: Long, message: Message): Boolean {
            return if (si0 == si1) {
                bot.unregisterOnReceiveMessageListener(this)
                receiver(senderId, message)
            } else {
                false
            }
        }

        override fun onDeleteMessages(bot: Bot, si: SessionInfo, msgIds: List<Long>): Boolean {
            return false
        }

        override fun onUpdateMessageContent(bot: Bot, si: SessionInfo, msgId: Long, content: JsonObject): Boolean {
            return false
        }

        override fun onMessageEdited(bot: Bot, si: SessionInfo, msgId: Long, editDate: Int): Boolean {
            return false
        }
    }
    this.registerOnReceiveMessageListener(listener)
}
