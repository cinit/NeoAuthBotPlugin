package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.Group
import cc.ioctl.telebot.tdlib.obj.User
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedTextBuilder
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.ReplyMarkup
import cc.ioctl.telebot.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.reflect.KMutableProperty1


object AdminConfigInterface {

    private const val TAG = "AdminConfigInterface"

    object PropertyType {
        const val STRING = 1
        const val INT32 = 2
        const val INT64 = 3
        const val BOOL = 4
        const val FLOAT = 5
        const val DOUBLE = 6
    }

    data class AdminSession(
        val groupId: Long,
        val adminId: Long,
        var theMessageId: Long = 0L,

        )

    // key is "gid_uid"
    private val mAdminSessionMap = mutableMapOf<String, AdminSession>()

    data class ConfigPropertyMetadata<T>(
        val name: String,
        val type: Int,
        val description: String,
        val accessor: KMutableProperty1<SessionManager.GroupAuthConfig, T>,
        val validator: (T) -> Boolean
    )

    val configProperties: Array<ConfigPropertyMetadata<*>> = arrayOf(
        ConfigPropertyMetadata(
            "总开关",
            PropertyType.BOOL,
            "是否启用本机器人; false 时则不会自动处理入群申请",
            SessionManager.GroupAuthConfig::isEnabled
        ) { true },
        ConfigPropertyMetadata(
            "难度",
            PropertyType.INT32,
            "只能为 0 或 1; 0: 在题目中直接将所有手性碳直接标出以便降低验证难度; 1: 不标出手性碳",
            SessionManager.GroupAuthConfig::enforceMode
        ) { it in 0..1 },
        ConfigPropertyMetadata(
            "等待开始验证时间",
            PropertyType.INT32,
            "单位: 秒; 如果用户在发送加群审核后指定的时间内未发送 /ccg 开始验证, 则自动拒绝; " +
                    "设置为 0 则不会自动拒绝; 取值范围 {0}U[60, 86400]",
            SessionManager.GroupAuthConfig::startAuthTimeoutSeconds
        ) { it == 0 || it in 60..86400 },
        ConfigPropertyMetadata(
            "验证最长允许时间",
            PropertyType.INT32,
            "单位: 秒; 用户在一次验证过程中最长允许的时间; 取值范围 [60, 10800], 默认 600 秒 (目前还没有实现)",
            SessionManager.GroupAuthConfig::authProcedureTimeoutSeconds
        ) { it in 60..10800 },
    )

    suspend fun onStartConfigCommand(bot: Bot, user: User, group: Group, origMsgId: Long) {
        val chatId = Bot.groupIdToChatId(group.groupId)
        val botUserName = bot.username
        if (botUserName.isNullOrEmpty()) {
            throw IllegalStateException("bot user name is null")
        }
        // check whether the user is admin
        val isAdmin = group.isMemberHasAdminRight(bot, user.userId)
        if (!isAdmin) {
            bot.sendMessageForText(chatId, "你不是群管理员, 不能使用本命令", replyMsgId = origMsgId)
            return
        } else {
            val key = UUID.randomUUID().toString()
            mAdminSessionMap[key] = AdminSession(group.groupId, user.userId)
            val msgText = "请点击下方按钮打开机器人私聊配置界面，本消息将在 30 秒后自动删除。"
            val startLink = "https://t.me/${botUserName}?start=admincfg_$key"
            val replyMarkup = ReplyMarkup.InlineKeyboard(
                arrayOf(
                    arrayOf(
                        ReplyMarkup.InlineKeyboard.Button(
                            "打开配置界面",
                            ReplyMarkup.InlineKeyboard.Button.Type.Url(startLink)
                        )
                    )
                )
            )
            val tmpMsgId = bot.sendMessageForText(chatId, msgText, replyMarkup = replyMarkup, replyMsgId = origMsgId)
            // schedule delete the message after 30 seconds
            bot.server.executor.execute {
                try {
                    runBlocking {
                        withContext(Dispatchers.IO) {
                            delay(30_000)
                            bot.deleteMessage(chatId, tmpMsgId.id)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "failed to delete msg: $e")
                }
            }
        }
    }

    suspend fun onStartConfigInterface(bot: Bot, chatId: Long, user: User, message: Message, sid: String) {
        val botUserName = bot.username
        if (botUserName.isNullOrEmpty()) {
            throw IllegalStateException("bot user name is null")
        }
        val startUrlPrefix = "https://t.me/${botUserName}?start="
        val adminSession = mAdminSessionMap[sid]
        if (adminSession == null) {
            bot.sendMessageForText(chatId, "链接已过期。", replyMsgId = message.id)
            return
        }
        if (user.userId != adminSession.adminId) {
            bot.sendMessageForText(chatId, "你不是生成链接的管理员。", replyMsgId = message.id)
            return
        }
        if (chatId != user.userId) {
            bot.sendMessageForText(chatId, "请使用私聊会话进行配置。", replyMsgId = message.id)
            return
        }
        val groupId = adminSession.groupId
        val group = bot.resolveGroup(adminSession.groupId)
        val groupConfig = SessionManager.getOrCreateGroupConfig(bot, group)
        val msgBody: FormattedTextBuilder = FormattedTextBuilder().apply {
            this + "配置 " + group.name + " (" + Code(groupId.toString()) + ")\n\n"
        }
        for (idx in configProperties.indices) {
            val prop = configProperties[idx]
            val valueStr = getConfigValue(groupConfig, prop).toString()
            msgBody.apply {
                this + prop.name + " [" + TextUrl("修改", startUrlPrefix + "admincfgedit_${sid}_${idx}") + "]\n"
                this + "当前值: " + Code(valueStr) + "\n"
                this + prop.description + "\n\n"
            }
        }
        msgBody + "[" + msgBody.TextUrl("刷新", startUrlPrefix + "admincfg_${sid}") + "] "
        msgBody + "[" + msgBody.TextUrl("结束当前配置", startUrlPrefix + "admincfgend_${sid}") + "]"
        bot.sendMessageForText(chatId, msgBody.build(), replyMsgId = message.id)
    }

    private fun <T> getConfigValue(cfg: SessionManager.GroupAuthConfig, property: ConfigPropertyMetadata<T>): T {
        val accessor = property.accessor
        return accessor.getValue(cfg, accessor)
    }

    private fun <T> parseStringInput(input: String, property: ConfigPropertyMetadata<T>): T? {
        if (input.isEmpty()) {
            return null
        }
        when (property.type) {
            PropertyType.INT32 -> {
                return input.toIntOrNull() as T?
            }
            PropertyType.INT64 -> {
                return input.toLongOrNull() as T?
            }
            PropertyType.FLOAT -> {
                return input.toFloatOrNull() as T?
            }
            PropertyType.DOUBLE -> {
                return input.toDoubleOrNull() as T?
            }
            PropertyType.STRING -> {
                return input as T
            }
            PropertyType.BOOL -> {
                return when (input[0].lowercaseChar()) {
                    'y', '1', 't', '是', '开' -> {
                        true as T
                    }
                    'n', '0', 'f', '否', '关' -> {
                        false as T
                    }
                    else -> {
                        null
                    }
                }
            }
            else -> {
                return null
            }
        }
    }

    private fun <T> checkUpdateConfigValue(
        cfg: SessionManager.GroupAuthConfig,
        property: ConfigPropertyMetadata<T>,
        value: T
    ): Boolean {
        val validator = property.validator
        if (validator(value)) {
            val accessor = property.accessor
            accessor.setValue(cfg, accessor, value)
            return true
        }
        return false
    }

    suspend fun onStartEditValueInterface(bot: Bot, chatId: Long, user: User, message: Message, sid: String, idx: Int) {
        val botUserName = bot.username
        if (botUserName.isNullOrEmpty()) {
            throw IllegalStateException("bot user name is null")
        }
        val startUrlPrefix = "https://t.me/${botUserName}?start="
        val adminSession = mAdminSessionMap[sid]
        if (adminSession == null) {
            bot.sendMessageForText(chatId, "链接已过期。", replyMsgId = message.id)
            return
        }
        if (user.userId != adminSession.adminId) {
            bot.sendMessageForText(chatId, "你不是生成链接的管理员。", replyMsgId = message.id)
            return
        }
        if (chatId != user.userId) {
            bot.sendMessageForText(chatId, "请使用私聊会话进行配置。", replyMsgId = message.id)
            return
        }
        val groupId = adminSession.groupId
        val group = bot.resolveGroup(adminSession.groupId)
        val groupConfig = SessionManager.getOrCreateGroupConfig(bot, group)
        val prop = configProperties[idx]
        val valueStr = getConfigValue(groupConfig, prop).toString()
        val msgBody: FormattedTextBuilder = FormattedTextBuilder().apply {
            this + prop.name + "当前值: " + Code(valueStr) + "\n"
            this + prop.description + "\n\n"
            this + "请输入(发送)新的值  [" + TextUrl("取消", startUrlPrefix + "admincfgcancel_${sid}") + "]"
        }
        bot.doOnNextMessage(chatId) { senderId, msg ->
            if (senderId != user.userId) {
                throw IllegalStateException("senderId != user.userId")
            }
            return@doOnNextMessage runBlocking {
                val msgText = msg.content.get("text")?.asJsonObject?.get("text")?.asString
                if (msgText == null) {
                    bot.sendMessageForText(chatId, "无效输入，取消修改。", replyMsgId = msg.id)
                    return@runBlocking true
                }
                if (msgText.startsWith("/")) {
                    // silently ignore command
                    return@runBlocking true
                }
                val value: Any? = parseStringInput(msgText, prop)
                if (value == null) {
                    bot.sendMessageForText(chatId, "数据格式不正确，取消修改。", replyMsgId = msg.id)
                    return@runBlocking true
                }
                if (checkUpdateConfigValue(groupConfig, prop as ConfigPropertyMetadata<Any>, value)) {
                    SessionManager.saveGroupConfig(bot, groupId, groupConfig)
                    bot.sendMessageForText(
                        chatId,
                        "已将 " + prop.name + " 设置为 " + value.toString(),
                        replyMsgId = msg.id
                    )
                } else {
                    bot.sendMessageForText(chatId, "值不在范围内，取消修改。", replyMsgId = msg.id)
                }
                return@runBlocking true
            }
        }
        bot.sendMessageForText(chatId, msgBody.build(), replyMsgId = message.id)
    }
}
