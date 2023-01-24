package cc.ioctl.neoauth3bot

import cc.ioctl.misc.InlineBotMsgCleaner
import cc.ioctl.neoauth3bot.svc.FilterService
import cc.ioctl.neoauth3bot.svc.LogDatabaseService
import cc.ioctl.neoauth3bot.svc.SysVmService
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo

object HypervisorCommandHandler {

    private const val TAG = "HypervisorCommandHandler"

    interface HvCmdCallback {
        suspend fun onSupervisorCommand(
            bot: Bot, si: SessionInfo, senderId: Long, serviceCmd: String, args: Array<String>
        ): String?
    }

    suspend fun onSupervisorCommand(
        bot: Bot,
        si: SessionInfo,
        senderId: Long,
        serviceName: String,
        serviceCmd: String,
        args: Array<String>,
        origMsgId: Long
    ) {
        val service: HvCmdCallback? = when (serviceName) {
            "pf" -> FilterService
            "sys" -> SysVmService
            "db" -> LogDatabaseService
            "ic" -> InlineBotMsgCleaner
            else -> null
        }
        if (service != null) {
            val ret = service.onSupervisorCommand(bot, si, senderId, serviceCmd, args)
            if (!ret.isNullOrEmpty()) {
                bot.sendMessageForText(si, ret, replyMsgId = origMsgId)
            }
        } else {
            bot.sendMessageForText(si, "Unknown service: '$serviceName'", replyMsgId = origMsgId)
        }
    }

}
