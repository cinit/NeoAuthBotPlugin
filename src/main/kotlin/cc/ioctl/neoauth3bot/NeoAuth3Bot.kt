package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.dat.ChemDatabase
import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.telebot.EventHandler
import cc.ioctl.telebot.plugin.PluginBase
import cc.ioctl.telebot.startup.BotStartupMain
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import cc.ioctl.telebot.util.Base64
import cc.ioctl.telebot.util.Log
import cc.ioctl.telebot.util.TokenBucket
import com.google.gson.JsonObject
import com.moandjiezana.toml.Toml
import kotlinx.coroutines.runBlocking
import java.io.File

class NeoAuth3Bot : PluginBase(),
    EventHandler.MessageListenerV1,
    EventHandler.CallbackQueryListenerV1,
    EventHandler.GroupMemberJoinRequestListenerV1 {

    private lateinit var mBot: Bot
    private var mBotUid: Long = 0
    private val mPrivateMsgBpf = TokenBucket<Long>(4, 500)
    private val mCallbackQueryBpf = TokenBucket<Long>(3, 500)
    private val mAntiShockBpf = TokenBucket<Int>(2, 100)

    companion object {
        private val SERVER_START_TIME = System.currentTimeMillis()
        private const val TAG = "NeoAuth3Bot"

        @JvmStatic
        fun main(args: Array<String>) {
            BotStartupMain.main(args)
        }
    }

    override fun onLoad() {
        Log.d(TAG, "onLoad")
    }

    override fun onEnable() {
        Log.d(TAG, "onEnable")
    }

    override fun onServerStart() {
        Log.d(TAG, "onServerStart")
        val cfgPath = File(server.pluginsDir, "NeoAuth3Bot.toml")
        if (!cfgPath.exists()) {
            askUserToUpdateConfigFile()
            return
        }
        val cfg = Toml().read(cfgPath)
        val sdfPath: String? = cfg.getString("sdf_path")
        val indexPath: String? = cfg.getString("index_path")
        val candidatePath: String? = cfg.getString("candidate_path")
        val botUid = cfg.getLong("bot_uid", 0)
        if (sdfPath == null || indexPath == null || candidatePath == null || botUid == 0L) {
            askUserToUpdateConfigFile()
            return
        }
        mBotUid = botUid
        ChemDatabase.initialize(File(candidatePath), File(indexPath), File(sdfPath))
        Log.d(TAG, "onServerStart: ChemDatabase initialized")
    }

    override fun onLoginFinish(bots: Map<Long, Bot>) {
        val bot = bots[mBotUid] ?: throw IllegalStateException("bot not found with uid $mBotUid")
        mBot = bot
        Log.i(TAG, "bot: $bot")
        bot.registerOnReceiveMessageListener(this)
        bot.registerCallbackQueryListener(this)
        bot.registerGroupMemberJoinRequestListenerV1(this)
    }

    private fun askUserToUpdateConfigFile() {
        Log.e(TAG, "Config file not loaded correctly.")
        Log.e(TAG, "Please create a config file with the following content:")
        Log.e(TAG, "bot_uid = your bot's user id")
        Log.e(TAG, "sdf_path = \"/path/to/sdf\"")
        Log.e(TAG, "index_path = \"/path/to/index\"")
        Log.e(TAG, "candidate_path = \"/path/to/candidate\"")
        Log.e(TAG, "NeoAuth3Bot not loaded correctly.")
        Log.e(TAG, "Aborting...")
    }

    override fun onReceiveMessage(bot: Bot, chatId: Long, senderId: Long, message: Message): Boolean {
        if (bot != mBot) {
            return false
        }
        if (mAntiShockBpf.consume(0) < 0) {
            Log.e(TAG, "onReceiveMessage: anti-shock filter failed: chatId=$chatId, senderId=$senderId")
            return true
        }
        return runBlocking {
            val msgId = message.id
            if (message.date < SERVER_START_TIME / 1000L) {
                Log.d(TAG, "message chatId $chatId senderId $senderId msgId $msgId is too old, ignore")
                return@runBlocking true
            }
            if ((chatId == senderId && senderId > 0) || message.content.toString().contains("@NeoAuthBot")) {
                Log.d(TAG, "onReceiveMessage start, chatId: $chatId, senderId: $senderId, msgId: $msgId")
                if (senderId < 0) {
                    // ignore messages from anonymous users
                    return@runBlocking true
                }
                val user = bot.resolveUser(senderId)
                mPrivateMsgBpf.consume(senderId).also {
                    if (it < 0) {
                        return@runBlocking true
                    } else if (it == 0) {
                        bot.sendMessageForText(chatId, LocaleHelper.getTooManyRequestsText(user))
                        return@runBlocking true
                    }
                }
                if (message.content.toString().contains("\"/help")) {
                    bot.sendMessageForText(chatId, LocaleHelper.getBotHelpInfoFormattedText(user), replyMsgId = msgId)
                } else if (message.content.toString().contains("\"/about")) {
                    bot.sendMessageForText(chatId, LocaleHelper.getBotAboutInfoFormattedText(user), replyMsgId = msgId)
                } else if (message.content.toString().contains("\"/group_id")) {
                    try {
                        val gid = Bot.chatIdToGroupId(chatId)
                        val group = bot.resolveGroup(gid)
                        bot.sendMessageForText(
                            chatId,
                            "Group ID: ${group.groupId}\nGroup Name: ${group.name}",
                            replyMsgId = msgId
                        )
                    } catch (e: Exception) {
                        bot.sendMessageForText(chatId, e.message ?: e.toString(), replyMsgId = msgId)
                    }
                } else if (
                    message.content.toString().contains("\"/cc1") ||
                    (chatId == senderId && message.content.toString().contains("\"/ccg"))
                ) {
                    // PM
                    val isForTest = !message.content.toString().contains("\"/ccg")
                    AuthUserInterface.onStartAuthCommand(bot, chatId, user, message, isForTest)
                } else if (message.content.toString().contains("\"/start")) {
                    // ignore
                } else {
                    bot.sendMessageForText(chatId, "Unknown command.")
                }
                return@runBlocking true
            }
            return@runBlocking false
        }
    }

    private suspend fun errorAlert(bot: Bot, queryId: String, msg: String) {
        bot.answerCallbackQuery(queryId, msg, true)
    }

    override fun onCallbackQuery(
        bot: Bot,
        query: JsonObject,
        queryId: String,
        chatId: Long,
        senderId: Long,
        msgId: Long
    ): Boolean {
        if (bot.userId == mBotUid) {
            if (mAntiShockBpf.consume(0) < 0) {
                Log.e(TAG, "onCallbackQuery: anti-shock filter failed: chatId=$chatId, senderId=$senderId")
                return true
            }
            runBlocking {
                val rttiType = query["@type"].asString
                if (rttiType == "updateNewCallbackQuery") {
                    val user = bot.resolveUser(senderId)
                    mCallbackQueryBpf.consume(senderId).also {
                        if (it < 0) {
                            return@runBlocking
                        } else if (it == 0) {
                            errorAlert(bot, queryId, LocaleHelper.getTooManyRequestsText(user))
                            return@runBlocking
                        }
                    }
                    try {
                        val payloadData = query["payload"].asJsonObject["data"].asString
                        val bytes8 = Base64.decode(payloadData, Base64.NO_WRAP)
                        if (bytes8.size != 8) {
                            errorAlert(bot, queryId, "unexpected payload data, expected 8 bytes")
                            return@runBlocking
                        }
                        val authId = BinaryUtils.readLe32(bytes8, 0)
                        val authInfo = SessionManager.getAuthSession(bot, user.userId)
                        if (authInfo == null) {
                            errorAlert(bot, queryId, LocaleHelper.getAuthSessionNotFoundForCallbackQueryText(user))
                            return@runBlocking
                        }
                        AuthUserInterface.onBtnClick(bot, user, chatId, authInfo, bytes8, queryId)
                    } catch (e: Exception) {
                        Log.e(TAG, "onCallbackQuery, error: ${e.message}", e)
                        errorAlert(bot, queryId, e.toString())
                    }
                } else {
                    Log.e(TAG, "unexpected callback query type: $rttiType")
                }
            }
            return true
        }
        return false
    }

    override fun onMemberJoinRequest(bot: Bot, chatId: Long, userId: Long, event: JsonObject): Boolean {
        Log.d(TAG, "onMemberJoinRequest: chatId: $chatId, userId: $userId, event: $event")
        runBlocking {
            val gid = Bot.chatIdToGroupId(chatId)
            val group = bot.resolveGroup(gid)
            val user = bot.resolveUser(userId)
            if (SessionManager.handleUserJoinRequest(bot, user, group)) {
                // make TDLib know the PM chat before send msg
                bot.resolveChat(userId)
                bot.sendMessageForText(userId, LocaleHelper.getJoinRequestAuthRequiredText(user, group))
            }
        }
        return true
    }

    override fun onUpdateMessageContent(bot: Bot, chatId: Long, msgId: Long, content: JsonObject): Boolean {
        // Log.d(TAG, "onUpdateMessageContent, chatId: $chatId, msgId: $msgId")
        return false
    }

    override fun onMessageEdited(bot: Bot, chatId: Long, msgId: Long, editDate: Int): Boolean {
        // Log.d(TAG, "onMessageEdited, chatId: $chatId, msgId: $msgId, editDate: $editDate")
        return false
    }

    override fun onDeleteMessages(bot: Bot, chatId: Long, msgIds: List<Long>): Boolean {
        return false
    }

}
