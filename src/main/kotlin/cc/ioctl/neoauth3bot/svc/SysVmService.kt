package cc.ioctl.neoauth3bot.svc

import cc.ioctl.neoauth3bot.HypervisorCommandHandler
import cc.ioctl.neoauth3bot.NeoAuth3Bot
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo

object SysVmService : HypervisorCommandHandler.HvCmdCallback {

    override suspend fun onSupervisorCommand(
        bot: Bot,
        si: SessionInfo,
        senderId: Long,
        serviceCmd: String,
        args: Array<String>
    ): String {
        when (serviceCmd) {
            "exit" -> {
                System.exit(0)
                error("System.exit() returned")
            }
            else -> {
                return "ENOSYS"
            }
        }
    }

    fun getUptimeString(): String {
        val start = NeoAuth3Bot.BOT_START_TIME
        val now = System.currentTimeMillis()
        val uptime = (now - start) / 1000
        val d = uptime / (60 * 60 * 24)
        val h = (uptime / (60 * 60)) % 24
        val m = (uptime / 60) % 60
        val s = uptime % 60
        val sb = StringBuilder("Uptime: ")
        if (d > 0) {
            sb.append(d).append("d ")
        }
        if (h > 0) {
            sb.append(h).append("h ")
        }
        if (m > 0) {
            sb.append(m).append("m ")
        }
        sb.append(s).append("s")
        return sb.toString()
    }

}
