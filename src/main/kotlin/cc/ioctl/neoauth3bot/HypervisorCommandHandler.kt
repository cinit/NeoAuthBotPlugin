package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.svc.FilterService
import cc.ioctl.neoauth3bot.svc.SysVmService
import cc.ioctl.telebot.tdlib.obj.Bot

object HypervisorCommandHandler {

    private const val TAG = "HypervisorCommandHandler"

    interface HvCmdCallback {
        suspend fun onSupervisorCommand(
            bot: Bot, chatId: Long, senderId: Long, serviceCmd: String, args: Array<String>
        ): String?
    }

    suspend fun onSupervisorCommand(
        bot: Bot,
        chatId: Long,
        senderId: Long,
        serviceName: String,
        serviceCmd: String,
        args: Array<String>,
        origMsgId: Long
    ) {
        val service: HvCmdCallback? = when (serviceName) {
            "pf" -> FilterService
            "sys" -> SysVmService
            else -> null
        }
        if (service != null) {
            val ret = service.onSupervisorCommand(bot, chatId, senderId, serviceCmd, args)
            if (!ret.isNullOrEmpty()) {
                bot.sendMessageForText(chatId, ret, replyMsgId = origMsgId)
            }
        } else {
            bot.sendMessageForText(chatId, "Unknown service: '$serviceName'", replyMsgId = origMsgId)
        }
    }

}
