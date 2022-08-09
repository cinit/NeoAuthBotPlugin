package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.tdlib.obj.Group
import cc.ioctl.telebot.tdlib.obj.User
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedText
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedTextBuilder


object LocaleHelper {


    fun getBtnChangeQuizText(user: User): String {
        return "换一题"
    }

    fun getBtnResetText(user: User): String {
        return "重置"
    }

    fun getBtnSubmitText(user: User): String {
        return "提交"
    }

    fun createFormattedMsgText(info: SessionManager.UserAuthSession, user: User): FormattedText {
        val selectedNames = ArrayList<String>(1)
        for (i in info.selectedRegion) {
            val x = i shr 4
            val y = i and 0x0f
            selectedNames.add(StringBuilder().apply {
                appendCodePoint((('A' + x).code))
                appendCodePoint((('1' + y).code))
            }.toString())
        }
        val msg = FormattedTextBuilder().apply {
            this + "欢迎 " + user.name + " 申请加群，本群已开启验证，请在 " +
                    Underline("720") + " 秒内选择图中所有包含手性碳的区域，并点击提交按钮。" +
                    Bold("超时或验证失败将会自动拒绝申请。") + "\n" +
                    "您还可以更换 " + Underline(info.changesAllowed.toString()) + " 次题目。\n" +
                    "提示：共有 " + Underline(info.actualChiralRegion.size.toString()) + " 个区域。\n" +
                    "当前已选择：" + Underline(if (selectedNames.isEmpty()) "无" else selectedNames.joinToString(", "))
        }.build()
        return msg
    }

    fun getAuthSuccessText(user: User, timeCost: Int): String {
        return "验证通过，用时 $timeCost 秒。"
    }

    fun getApproveSuccessText(user: User): String {
        return "您应该已经是群成员了。";
    }

    fun getJoinRequestAuthRequiredText(user: User, targetGroup: Group): String {
        return "欢迎 ${user.name} 申请加入群组 ${targetGroup.name} ，本群已开启验证，请发送 /ccg 开始验证，完成验证后即可加入。";
    }

    fun getTooManyRequestsText(user: User): String {
        return "您的操作过于频繁，请稍后再试。";
    }

    fun getAuthSessionNotFoundForCallbackQueryText(user: User): String {
        return "太久远了，找不到了。";
    }

    fun getLoadingText(user: User): String {
        return "正在加载..."
    }

    fun getNoAuthRequiredBczNoRequestText(user: User): String {
        return "您没有申请加入任何群，不需要验证。"
    }

    fun getAuthFailWrongAnswerText(user: User): String {
        return "验证失败，请重试。"
    }

    fun getAuthSuccessShortText(user: User): String {
        return "验证通过"
    }

    fun getCallbackHintForSelectRegion(user: User, regionName: String): String {
        return "选择了 $regionName"
    }

    fun getCallbackHintForUnselectRegion(user: User, regionName: String): String {
        return "取消了 $regionName"
    }

    fun getCallbackHintForResetRegion(user: User): String {
        return "已重置"
    }

    fun getCallbackHintForChangeRequestTodo(user: User): String {
        return "暂不开放(请手动发送 /ccg 命令)"
    }

    fun getBotHelpInfoFormattedText(user: User): FormattedText {
        return FormattedTextBuilder().apply {
            this + Bold("验证相关") + "\n" +
                    "/ccg 开始入群验证以及更换题目\n\n" +
                    Bold("测试相关") + "\n" +
                    "/cc1 仅用于测试\n\n" +
                    Bold("其他") + "\n" +
                    "/help 显示本信息\n" +
                    "/about 关于本机器人"
        }.build()
    }


    fun getBotAboutInfoFormattedText(user: User): FormattedText {
        return FormattedTextBuilder().apply {
            this + Bold("NeoAuth3Bot") + "\n" +
                    "使用 " +
                    TextUrl("TeleBotConsole", "https://github.com/cinit/TeleBotConsole") +
                    " 框架开发, \n" +
                    "分子数据库来自 " +
                    TextUrl("PubChem", "https://pubchem.ncbi.nlm.nih.gov/") + " .\n" +
                    "如需在自己群使用，需要将群启用 成员需要批准才能加入(如为公开群，请先打开 只有群成员才能发消息)，" +
                    "或者创建一个需要批准才能加入的邀请链接，然后将机器人拉到群里设置为管理员，" +
                    "并给机器人 can_invite_users (添加成员/生成邀请链接) 管理员权限(别的权限不需要)。提供 0% 的 SLA."
        }.build()
    }
}