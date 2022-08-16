package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.dat.ChemDatabase
import cc.ioctl.neoauth3bot.svc.FilterService
import cc.ioctl.neoauth3bot.svc.SysVmService
import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.telebot.EventHandler
import cc.ioctl.telebot.plugin.PluginBase
import cc.ioctl.telebot.startup.BotStartupMain
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.tlrpc.RemoteApiException
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import cc.ioctl.telebot.util.Base64
import cc.ioctl.telebot.util.Log
import cc.ioctl.telebot.util.TokenBucket
import cc.ioctl.telebot.util.postDelayed
import com.google.gson.JsonObject
import com.moandjiezana.toml.Toml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File

class NeoAuth3Bot : PluginBase(),
    EventHandler.MessageListenerV1,
    EventHandler.CallbackQueryListenerV1,
    EventHandler.GroupMemberJoinRequestListenerV1 {

    private lateinit var mBot: Bot
    private var mBotUid: Long = 0
    private var mBotUsername: String? = null
    private val mPrivateMsgBpf = TokenBucket<Long>(4, 500)
    private val mCallbackQueryBpf = TokenBucket<Long>(3, 500)
    private val mAntiShockBpf = TokenBucket<Int>(3, 100)
    private val mHypervisorIds = ArrayList<Long>()
    private val mCascadeDeleteMsgLock = Any()
    private val mCascadeDeleteMsg = object : LinkedHashMap<String, Long>() {
        // 1000 elements max
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 1000
        }
    }

    companion object {
        // allow up to 5min
        internal val BOT_START_TIME = System.currentTimeMillis()
        private val SYNC_START_TIME = BOT_START_TIME - 5 * 60 * 1000
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
        mHypervisorIds.clear()
        cfg.getList<Long>("hypervisor_ids").forEach { id ->
            if (!Bot.isTrivialUserSender(id) && !Bot.isAnonymousSender(id)) {
                Log.e(TAG, "Invalid hypervisor user id: $id")
            } else {
                if (Bot.isAnonymousSender(id)) {
                    Log.w(TAG, "Using anonymous hypervisor id($id) is discouraged")
                }
                mHypervisorIds.add(id)
            }
        }
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
        mBotUsername = bot.username
        if (mBotUsername.isNullOrEmpty()) {
            throw IllegalStateException("bot $bot has no username")
        }
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
        Log.e(TAG, "hypervisor_ids = [ids of hypervisors]")
        Log.e(TAG, "NeoAuth3Bot not loaded correctly.")
        Log.e(TAG, "Aborting...")
    }

    override fun onReceiveMessage(bot: Bot, chatId: Long, senderId: Long, message: Message): Boolean {
        if (bot != mBot) {
            return false
        }
        val msgId = message.id
        if (message.date < SYNC_START_TIME / 1000L) {
            Log.d(TAG, "message chatId $chatId senderId $senderId msgId $msgId is too old, ignore")
            return true
        }
        if ((chatId == senderId && senderId > 0) || message.content.toString().contains("@" + mBotUsername!!)) {
            if (mAntiShockBpf.consume(0) < 0) {
                Log.w(TAG, "onReceiveMessage: anti-shock filter failed: chatId=$chatId, senderId=$senderId")
                return true
            }
            return runBlocking {
                if (senderId == chatId && FilterService.isBlocked(senderId)) {
                    // ignore messages from blocked users
                    return@runBlocking true
                }
                val msgText = message.content.get("text")?.asJsonObject?.get("text")?.asString
                if (msgText == null) {
                    val d = "onReceiveMessage start, chatId: $chatId, senderId: $senderId, msgId: $msgId, msgType: ${
                        message.content.get("@type").asString
                    }"
                    Log.d(TAG, d)
                    return@runBlocking true
                } else {
                    // truncate message text to avoid flooding logs
                    val dumpShowMsg = msgText.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").let {
                        it.substring(0, it.length.coerceAtMost(150))
                    }
                    val d = "onReceiveMessage start, chatId: $chatId, senderId: $senderId, " +
                            "msgId: $msgId, msgText: $dumpShowMsg"
                    Log.d(TAG, d)
                }
                if (mHypervisorIds.contains(senderId)) {
                    if (msgText.startsWith("/") || msgText.startsWith("!")) {
                        val body = msgText.substring(1)
                        if (body.startsWith("hvcmd")) {
                            val parts = body.split(" ")
                            if (parts.size < 3) {
                                bot.sendMessageForText(
                                    chatId,
                                    "Invalid argument. Usage: /hvcmd SERVICE CMD [ARGS...]",
                                    replyMsgId = msgId
                                ).scheduledCascadeDelete(msgId)
                            } else {
                                val svc = parts[1]
                                val cmd = parts[2]
                                val args = parts.drop(3)
                                try {
                                    Log.d(TAG, "hvcmd $senderId: $svc $cmd $args")
                                    HypervisorCommandHandler.onSupervisorCommand(
                                        bot, chatId, senderId, svc, cmd, args.toTypedArray(), msgId
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "exec hv cmd '$msgText': $e", e)
                                    bot.sendMessageForText(
                                        chatId,
                                        e.message ?: e.toString(),
                                        replyMsgId = msgId
                                    ).scheduledCascadeDelete(msgId)
                                }
                            }
                            return@runBlocking true
                        }
                    }
                }
                if (senderId < 0) {
                    // ignore messages from anonymous users
                    return@runBlocking true
                }
                if (!msgText.startsWith("/")) {
                    return@runBlocking true
                }
                val user = bot.resolveUser(senderId)
                mPrivateMsgBpf.consume(senderId).also {
                    if (it < 0) {
                        return@runBlocking true
                    } else if (it == 0) {
                        bot.sendMessageForText(chatId, LocaleHelper.getTooManyRequestsText(user))
                            .scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    }
                }
                if (message.content.toString().contains("\"/help")) {
                    bot.sendMessageForText(
                        chatId,
                        LocaleHelper.getBotHelpInfoFormattedText(bot, user),
                        replyMsgId = msgId
                    ).scheduledCascadeDelete(msgId)
                } else if (msgText.startsWith("/uptime")) {
                    bot.sendMessageForText(chatId, SysVmService.getUptimeString(), replyMsgId = msgId)
                        .scheduledCascadeDelete(msgId)
                } else if (message.content.toString().contains("\"/about")) {
                    bot.sendMessageForText(chatId, LocaleHelper.getBotAboutInfoFormattedText(user), replyMsgId = msgId)
                        .scheduledCascadeDelete(msgId)
                } else if (message.content.toString().contains("\"/group_id")) {
                    try {
                        val gid = Bot.chatIdToGroupId(chatId)
                        val group = bot.resolveGroup(gid)
                        bot.sendMessageForText(
                            chatId,
                            "Group ID: ${group.groupId}\nGroup Name: ${group.name}",
                            replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                    } catch (e: Exception) {
                        bot.sendMessageForText(chatId, e.message ?: e.toString(), replyMsgId = msgId)
                            .scheduledCascadeDelete(msgId)
                    }
                } else if (
                    message.content.toString().contains("\"/cc1") ||
                    (chatId == senderId && message.content.toString().contains("\"/ccg"))
                ) {
                    if (!Bot.isTrivialPrivateChat(chatId)) {
                        // in group chat
                        bot.sendMessageForText(
                            chatId,
                            LocaleHelper.getCommandOnlyAvailableInPrivateChatText(user),
                            replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    } else {
                        val isForTest = !message.content.toString().contains("\"/ccg")
                        AuthUserInterface.onStartAuthCommand(bot, chatId, user, message, isForTest)
                    }
                } else if (msgText.startsWith("/start")) {
                    if (msgText.length > "/start".length) {
                        val arg = msgText.substring("/start".length).trimStart()
                        when (arg.split("_")[0]) {
                            "admincfg" -> {
                                val sid = arg.split("_")[1]
                                AdminConfigInterface.onStartConfigInterface(bot, chatId, user, message, sid)
                                return@runBlocking true
                            }
                            "admincfgedit" -> {
                                val sid = arg.split("_")[1]
                                val idx = arg.split("_")[2].toInt()
                                AdminConfigInterface.onStartEditValueInterface(bot, chatId, user, message, sid, idx)
                                return@runBlocking true
                            }
                            else -> {
                                bot.sendMessageForText(chatId, "Unknown command", replyMsgId = msgId)
                                    .scheduledCascadeDelete(msgId)
                                return@runBlocking true
                            }
                        }
                    }
                } else if (message.content.toString().contains("\"/config")) {
                    if (chatId > Bot.CHAT_ID_NEGATIVE_NOTATION) {
                        bot.sendMessageForText(
                            chatId,
                            LocaleHelper.getPleaseUseCmdInGroupText(user),
                            replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    } else {
                        val gid = Bot.chatIdToGroupId(chatId)
                        val group = bot.resolveGroup(gid)
                        AdminConfigInterface.onStartConfigCommand(bot, user, group, message)
                        return@runBlocking true
                    }
                } else {
                    bot.sendMessageForText(chatId, "Unknown command.", replyMsgId = msgId)
                        .scheduledCascadeDelete(msgId)
                }
                return@runBlocking true
            }
        }
        return false
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
        Log.d(TAG, "onMemberJoinRequest: chatId: $chatId, userId: $userId")
        runBlocking {
            val gid = Bot.chatIdToGroupId(chatId)
            val group = bot.resolveGroup(gid)
            val user = bot.resolveUser(userId)
            if (SessionManager.handleUserJoinRequest(bot, user, group)) {
                // make TDLib know the PM chat before send msg
                bot.resolveChat(userId)
                val originHintMsgId = bot.sendMessageForText(
                    userId,
                    LocaleHelper.getJoinRequestAuthRequiredText(user, group)
                )
                Log.i(TAG, "send user join request msg to user: $userId, group: $gid")
                val groupConfig = SessionManager.getOrCreateGroupConfig(bot, group)
                val maxWaitTimeSeconds = groupConfig.startAuthTimeoutSeconds
                if (maxWaitTimeSeconds > 0) {
                    // schedule a job to dismiss the request after timeout
                    postDelayed(maxWaitTimeSeconds * 1000L) {
                        // check whether the auth session is still valid
                        val authSession = SessionManager.getAuthSession(bot, userId)
                        if (authSession != null) {
                            if (authSession.currentAuthId == 0 && authSession.authStatus == SessionManager.AuthStatus.REQUESTED) {
                                Log.i(TAG, "dismiss timeout join request: $userId, group: $gid")
                                // drop the auth session if the user didn't start auth in time
                                SessionManager.dropAuthSession(bot, userId)
                                // delete the msg and dismiss the request
                                try {
                                    bot.deleteMessage(chatId, originHintMsgId.id)
                                } catch (e: RemoteApiException) {
                                    Log.w(TAG, "delete request msg: $e")
                                }
                                bot.processChatJoinRequest(chatId, userId, false)
                            }
                        }
                    }.invokeOnCompletion {
                        if (it != null && it !is CancellationException) {
                            Log.e(TAG, "onMemberJoinRequest postDelayed, error: ${it.message}", it)
                        }
                    }
                }
            } else {
                Log.i(TAG, "ignore user join request: $userId, group: $gid because disabled")
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
        val msgToDelete = HashSet<Long>(4)
        val keys = msgIds.map { chatId.toString() + "_" + it.toString() }
        synchronized(mCascadeDeleteMsgLock) {
            keys.forEach { k ->
                mCascadeDeleteMsg.remove(k)?.let { id ->
                    msgToDelete.add(id)
                }
            }
        }
        if (msgToDelete.isNotEmpty()) {
            runBlocking {
                try {
                    bot.deleteMessages(chatId, msgToDelete.toList())
                } catch (e: RemoteApiException) {
                    // we don't really care about the error
                    Log.w(TAG, "cascade delete msg, error: ${e.message}")
                }
            }
        }
        return true
    }

    fun scheduleCascadeDeleteMessage(chatId: Long, origMsgId: Long, targetMsgId: Long) {
        assert(origMsgId != targetMsgId)
        assert(chatId != 0L)
        assert(origMsgId > 0L)
        assert(targetMsgId > 0L)
        val key = chatId.toString() + "_" + origMsgId
        synchronized(mCascadeDeleteMsgLock) {
            mCascadeDeleteMsg[key] = targetMsgId
        }
    }

    internal fun Message.scheduledCascadeDelete(origMsgId: Long) {
        if (this.serverMsgId == 0L) {
            // we don't need to delete the msg if it's not sent to server yet
            return
        }
        scheduleCascadeDeleteMessage(chatId, origMsgId, this.id)
    }

}
