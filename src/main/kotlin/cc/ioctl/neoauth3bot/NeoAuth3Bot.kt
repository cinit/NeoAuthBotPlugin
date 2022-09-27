package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.dat.AnointedManager
import cc.ioctl.neoauth3bot.dat.ChemDatabase
import cc.ioctl.neoauth3bot.res.ResImpl
import cc.ioctl.neoauth3bot.svc.FilterService
import cc.ioctl.neoauth3bot.svc.SysVmService
import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.telebot.EventHandler
import cc.ioctl.telebot.plugin.PluginBase
import cc.ioctl.telebot.startup.BotStartupMain
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo
import cc.ioctl.telebot.tdlib.obj.User
import cc.ioctl.telebot.tdlib.tlrpc.RemoteApiException
import cc.ioctl.telebot.tdlib.tlrpc.api.channel.ChannelMemberStatusEvent
import cc.ioctl.telebot.tdlib.tlrpc.api.channel.ChatJoinRequest
import cc.ioctl.telebot.tdlib.tlrpc.api.channel.ChatPermissions
import cc.ioctl.telebot.tdlib.tlrpc.api.channel.MemberStatus
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.ReplyMarkup
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.inlineKeyboardCallbackButton
import cc.ioctl.telebot.tdlib.tlrpc.api.query.CallbackQuery
import cc.ioctl.telebot.util.Log
import cc.ioctl.telebot.util.TokenBucket
import cc.ioctl.telebot.util.postDelayed
import com.google.gson.JsonObject
import com.moandjiezana.toml.Toml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class NeoAuth3Bot : PluginBase(), EventHandler.MessageListenerV1, EventHandler.CallbackQueryListenerV2,
    EventHandler.GroupMemberJoinRequestListenerV2, EventHandler.GroupPermissionListenerV2 {

    private lateinit var mBot: Bot
    private var mBotUid: Long = 0
    private var mBotUsername: String? = null
    private val mPrivateMsgBpf = TokenBucket<Long>(4, 500)
    private val mCallbackQueryBpf = TokenBucket<Long>(3, 500)
    private val mAntiShockBpf = TokenBucket<Int>(3, 100)
    private val mHypervisorIds = ArrayList<Long>()
    private val mNextAnonymousAdminVerificationId = AtomicInteger(1)
    private val mAnonymousAdminVerifications = HashMap<Int, AnonymousAdminVerification>(1)
    private val mCascadeDeleteMsgLock = Any()
    private var mDefaultLogChannelId: Long = 0L
    private val mCascadeDeleteMsg = object : LinkedHashMap<String, Long>() {
        // 1000 elements max
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 1000
        }
    }

    data class AnonymousAdminVerification(
        val id: Int, val si: SessionInfo, val origMsgId: Long, var tmpMsgId: Long = 0
    ) {
        fun getMagicBytes(cmd: Int): ByteArray {
            val type = 3
            val subType = 0
            val len = 4
            val ret = ByteArray(8)
            ret[0] = type.toByte()
            ret[1] = subType.toByte()
            ret[2] = len.toByte()
            ret[3] = cmd.toByte()
            BinaryUtils.writeLe32(ret, 4, id)
            return ret
        }

        companion object {
            @JvmStatic
            fun getIdFromMagicBytes(bytes: ByteArray): Int {
                if (bytes.size != 8) {
                    return 0
                }
                if (bytes[0] != 3.toByte()) {
                    return 0
                }
                if (bytes[1] != 0.toByte()) {
                    return 0
                }
                if (bytes[2] != 4.toByte()) {
                    return 0
                }
                return BinaryUtils.readLe32(bytes, 4)
            }

            @JvmStatic
            fun getCmdFromMagicBytes(bytes: ByteArray): Int {
                if (bytes.size != 8) {
                    return 0
                }
                if (bytes[0] != 3.toByte()) {
                    return 0
                }
                if (bytes[1] != 0.toByte()) {
                    return 0
                }
                if (bytes[2] != 4.toByte()) {
                    return 0
                }
                return bytes[3].toInt()
            }
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
        mDefaultLogChannelId = cfg.getLong("default_log_channel_id", 0)
        LocaleHelper.discussionGroupLink = cfg.getString("discussion_group_link", null)
        mHypervisorIds.clear()
        cfg.getList<Long>("hypervisor_ids").forEach { id ->
            if (id < 0) {
                Log.w(TAG, "Using anonymous hypervisor id($id) is discouraged")
            }
            mHypervisorIds.add(id)
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
        val bot = bots[mBotUid] ?: error("bot not found with uid $mBotUid")
        mBot = bot
        Log.i(TAG, "bot: $bot")
        mBotUsername = bot.username
        check(mBotUsername?.isNotEmpty() == true) { "bot $bot has no username" }
        runBlocking { ChannelLog.setDefaultLogChannelId(bot, mDefaultLogChannelId) }
        bot.registerOnReceiveMessageListener(this)
        bot.registerCallbackQueryListener(this)
        bot.registerGroupMemberJoinRequestListenerV1(this)
        bot.registerGroupEventListener(this)
//        server.executeRequestBlocking(JsonObject().apply {
//            addProperty("@type","searchPublicChat")
//            addProperty("username","Telegrzm")
//        }.toString(), bot, server.defaultTimeout).let {
//            Log.d(TAG, "ret: $it")
//        }
    }

    private fun askUserToUpdateConfigFile() {
        Log.e(TAG, "Config file not loaded correctly.")
        Log.e(TAG, "Please create a config file with the following content:")
        Log.e(TAG, "bot_uid = your bot's user id")
        Log.e(TAG, "sdf_path = \"/path/to/sdf\"")
        Log.e(TAG, "index_path = \"/path/to/index\"")
        Log.e(TAG, "candidate_path = \"/path/to/candidate\"")
        Log.e(TAG, "hypervisor_ids = [ids of hypervisors]")
        Log.e(TAG, "default_log_channel_id = channel_id (which is greater than 0)")
        Log.e(TAG, "discussion_group_link = \"Your discussion group link, optional\"")
        Log.e(TAG, "NeoAuth3Bot not loaded correctly.")
        Log.e(TAG, "Aborting...")
    }

    override fun onReceiveMessage(bot: Bot, si: SessionInfo, senderId: Long, message: Message): Boolean {
        if (bot != mBot) {
            return false
        }
        val msgId = message.id
        if (message.date < SYNC_START_TIME / 1000L) {
            Log.d(TAG, "message $si senderId $senderId msgId $msgId is too old, ignore")
            return true
        }
        if ((si.isTrivialPrivateChat && senderId > 0) || message.content.toString().contains("@" + mBotUsername!!)) {
            if (mAntiShockBpf.consume(0) < 0) {
                Log.w(TAG, "onReceiveMessage: anti-shock filter failed: $si, senderId=$senderId")
                return true
            }
            return runBlocking {
                if (si.isTrivialPrivateChat && FilterService.isBlocked(senderId)) {
                    // ignore messages from blocked users
                    return@runBlocking true
                }
                val msgText = message.content.get("text")?.asJsonObject?.get("text")?.asString
                if (msgText == null) {
                    val d = "onReceiveMessage start, $si, senderId: $senderId, msgId: $msgId, msgType: ${
                        message.content.get("@type").asString
                    }"
                    Log.d(TAG, d)
                    return@runBlocking true
                } else {
                    // truncate message text to avoid flooding logs
                    val dumpShowMsg = msgText.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").let {
                        it.substring(0, it.length.coerceAtMost(150))
                    }
                    val d = "onReceiveMessage start, $si, senderId: $senderId, msgId: $msgId, msgText: $dumpShowMsg"
                    Log.d(TAG, d)
                }
                if (mHypervisorIds.contains(senderId)) {
                    if (msgText.startsWith("/") || msgText.startsWith("!")) {
                        val body = msgText.substring(1)
                        if (body.startsWith("hvcmd")) {
                            val parts = body.split(" ")
                            if (parts.size < 3) {
                                bot.sendMessageForText(
                                    si, "Invalid argument. Usage: /hvcmd SERVICE CMD [ARGS...]", replyMsgId = msgId
                                ).scheduledCascadeDelete(msgId)
                            } else {
                                val svc = parts[1]
                                val cmd = parts[2]
                                val args = parts.drop(3)
                                try {
                                    Log.d(TAG, "hvcmd $senderId: $svc $cmd $args")
                                    HypervisorCommandHandler.onSupervisorCommand(
                                        bot, si, senderId, svc, cmd, args.toTypedArray(), msgId
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "exec hv cmd '$msgText': $e", e)
                                    bot.sendMessageForText(
                                        si, e.message ?: e.toString(), replyMsgId = msgId
                                    ).scheduledCascadeDelete(msgId)
                                }
                            }
                            return@runBlocking true
                        }
                    }
                }
                if (senderId < 0) {
                    if (si.isGroupOrChannel && senderId == -si.id) {
                        if (msgText.startsWith("/config")) {
                            val aaaId = mNextAnonymousAdminVerificationId.getAndIncrement()
                            val session = AnonymousAdminVerification(aaaId, si, msgId)
                            val r = ResImpl.eng
                            val tmpMsgId = bot.sendMessageForText(
                                si,
                                r.msg_text_anonymous_admin_identity_verification_required,
                                replyMsgId = msgId,
                                replyMarkup = ReplyMarkup.InlineKeyboard(
                                    arrayOf(
                                        arrayOf(
                                            inlineKeyboardCallbackButton(
                                                r.btn_text_verify_anony_identity, session.getMagicBytes(1)
                                            ), inlineKeyboardCallbackButton(
                                                r.btn_text_cancel, session.getMagicBytes(2)
                                            )
                                        )
                                    )
                                )
                            )
                            session.tmpMsgId = tmpMsgId.id
                            mAnonymousAdminVerifications[aaaId] = session
                            // schedule deletion of the message after 30 seconds
                            postDelayed(30_000) {
                                mAnonymousAdminVerifications.remove(aaaId)
                                try {
                                    bot.deleteMessage(si, tmpMsgId.id)
                                } catch (e: RemoteApiException) {
                                    Log.i(TAG, "deleteMessage failed: $e")
                                }
                            }.logErrorIfFail()
                        }
                    }
                    return@runBlocking true
                }
                if (!msgText.startsWith("/")) {
                    return@runBlocking true
                }
                val user = bot.getUser(senderId)
                val r = ResImpl.getResourceForUser(user)
                mPrivateMsgBpf.consume(senderId).also {
                    if (it < 0) {
                        return@runBlocking true
                    } else if (it == 0) {
                        bot.sendMessageForText(si, r.msg_text_too_many_requests).scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    }
                }
                if (message.content.toString().contains("\"/help")) {
                    bot.sendMessageForText(
                        si, LocaleHelper.getBotHelpInfoFormattedText(bot, user), replyMsgId = msgId
                    ).scheduledCascadeDelete(msgId)
                } else if (msgText.startsWith("/uptime")) {
                    bot.sendMessageForText(si, SysVmService.getUptimeString(), replyMsgId = msgId)
                        .scheduledCascadeDelete(msgId)
                } else if (message.content.toString().contains("\"/about")) {
                    bot.sendMessageForText(si, LocaleHelper.getBotAboutInfoFormattedText(user), replyMsgId = msgId)
                        .scheduledCascadeDelete(msgId)
                } else if (message.content.toString().contains("\"/group_id")) {
                    try {
                        val group = bot.getGroup(si.id)
                        bot.sendMessageForText(
                            si, "Group ID: ${group.groupId}\nGroup Name: ${group.name}", replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                    } catch (e: Exception) {
                        bot.sendMessageForText(si, e.message ?: e.toString(), replyMsgId = msgId)
                            .scheduledCascadeDelete(msgId)
                    }
                } else if (message.content.toString()
                        .contains("\"/cc1") || (si.isTrivialPrivateChat && message.content.toString()
                        .contains("\"/ccg"))
                ) {
                    if (!si.isTrivialPrivateChat) {
                        // in group chat
                        bot.sendMessageForText(
                            si, r.msg_text_command_use_in_private_chat_only, replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    } else {
                        val isForTest = !message.content.toString().contains("\"/ccg")
                        AuthUserInterface.onStartAuthCommand(bot, si, user, message, isForTest)
                    }
                } else if (msgText.startsWith("/start")) {
                    if (msgText.length > "/start".length) {
                        val arg = msgText.substring("/start".length).trimStart()
                        when (arg.split("_")[0]) {
                            "admincfg" -> {
                                val sid = arg.split("_")[1]
                                AdminConfigInterface.onStartConfigInterface(bot, si, user, message, sid)
                                return@runBlocking true
                            }
                            "admincfgedit" -> {
                                val sid = arg.split("_")[1]
                                val idx = arg.split("_")[2].toInt()
                                AdminConfigInterface.onStartEditValueInterface(bot, si, user, message, sid, idx)
                                return@runBlocking true
                            }
                            else -> {
                                bot.sendMessageForText(si, "Unknown command", replyMsgId = msgId)
                                    .scheduledCascadeDelete(msgId)
                                return@runBlocking true
                            }
                        }
                    }
                } else if (message.content.toString().contains("\"/config")) {
                    if (!si.isGroupOrChannel) {
                        bot.sendMessageForText(
                            si, r.msg_text_command_use_in_group_only, replyMsgId = msgId
                        ).scheduledCascadeDelete(msgId)
                        return@runBlocking true
                    } else {
                        val gid = si.id
                        val group = bot.getGroup(gid)
                        AdminConfigInterface.onStartConfigCommand(bot, user, group, message.id)
                        return@runBlocking true
                    }
                } else {
                    bot.sendMessageForText(si, "Unknown command.", replyMsgId = msgId).scheduledCascadeDelete(msgId)
                }
                return@runBlocking true
            }
        }
        return false
    }

    private suspend fun errorAlert(bot: Bot, queryId: Long, msg: String) {
        bot.answerCallbackQuery(queryId, msg, true)
    }

    override fun onCallbackQuery(
        bot: Bot, query: CallbackQuery
    ): Boolean {
        val queryId = query.queryId
        if (bot.userId == mBotUid) {
            if (mAntiShockBpf.consume(0) < 0) {
                Log.e(
                    TAG,
                    "onCallbackQuery: anti-shock filter failed: ${query.sessionInfo}, senderId=${query.senderUserId}"
                )
                return true
            }
            runBlocking {
                val senderId = query.senderUserId
                val user = bot.getUser(senderId)
                val r = ResImpl.getResourceForUser(user)
                mCallbackQueryBpf.consume(senderId).also {
                    if (it < 0) {
                        return@runBlocking
                    } else if (it == 0) {
                        errorAlert(bot, query.queryId, r.msg_text_too_many_requests)
                        return@runBlocking
                    }
                }
                try {
                    val bytes = query.payloadData
                    if (query.sessionInfo.isTrivialPrivateChat) {
                        // PM
                        if (bytes.size != 8) {
                            errorAlert(bot, queryId, "unexpected payload data, expected 8 bytes")
                            return@runBlocking
                        }
                        val authId = BinaryUtils.readLe32(bytes, 0)
                        val authInfo = SessionManager.getAuthSession(bot, user.userId)
                        if (authInfo == null) {
                            errorAlert(bot, queryId, r.cb_query_auth_session_not_found)
                            return@runBlocking
                        }
                        AuthUserInterface.onBtnClick(bot, user, query.sessionInfo, authInfo, bytes, queryId)
                    } else {
                        // group
                        when (val type = bytes[0].toInt()) {
                            3 -> {
                                onAnonymousAdminCallback(bot, user, query.sessionInfo, bytes, queryId)
                                return@runBlocking
                            }
                            else -> {
                                errorAlert(bot, queryId, "unknown payload type: $type")
                                return@runBlocking
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onCallbackQuery, error: ${e.message}", e)
                    errorAlert(bot, queryId, e.toString())
                }
            }
            return true
        }
        return false
    }

    private suspend fun onAnonymousAdminCallback(
        bot: Bot, user: User, si: SessionInfo, bytes: ByteArray, queryId: Long
    ) {
        val aaaId = AnonymousAdminVerification.getIdFromMagicBytes(bytes)
        val cmd = AnonymousAdminVerification.getCmdFromMagicBytes(bytes)
        if (aaaId == 0 || cmd == 0 || !si.isGroupOrChannel) {
            errorAlert(bot, queryId, "query data is invalid")
            return
        }
        val r = ResImpl.getResourceForUser(user)
        // check if the user is an admin
        val gid = si.id
        val group = bot.getGroup(gid)
        val isAdmin = group.isMemberAdministrative(bot, user.userId)
        if (!isAdmin) {
            errorAlert(bot, queryId, r.cb_query_nothing_to_do_with_you)
            return
        }
        val session = mAnonymousAdminVerifications[aaaId]
        if (session == null) {
            errorAlert(bot, queryId, r.cb_query_auth_session_not_found)
            return
        }
        if (si != session.si) {
            errorAlert(bot, queryId, r.cb_query_auth_session_not_found)
            return
        }
        when (cmd) {
            1 -> {
                AdminConfigInterface.onStartConfigCommand(bot, user, group, session.origMsgId)
                mAnonymousAdminVerifications.remove(aaaId)
                try {
                    bot.deleteMessage(si, session.tmpMsgId)
                } catch (ignored: RemoteApiException) {
                    // ignore
                }
            }
            2 -> {
                // cancel
                mAnonymousAdminVerifications.remove(aaaId)
                try {
                    bot.deleteMessage(si, session.tmpMsgId)
                } catch (ignored: RemoteApiException) {
                    // ignore
                }
            }
            else -> {
                errorAlert(bot, queryId, "unknown command: $cmd")
                return
            }
        }
    }

    override fun onMemberJoinRequest(bot: Bot, groupId: Long, userId: Long, request: ChatJoinRequest): Boolean {
        runBlocking {
            val group = bot.getGroup(groupId)
            val user = bot.getUser(userId)
            val r = ResImpl.getResourceForUser(user)
            if (SessionManager.handleUserJoinRequest(bot, user, group)) {
                val pmsi = SessionInfo.forUser(userId)
                ChannelLog.onJoinRequest(bot, group, userId)
                // make TDLib know the PM chat before send msg
                bot.getChat(userId)
                if (AnointedManager.getAnointedStatus(groupId, userId) == 1) {
                    // pre-approved
                    Log.i(TAG, "onMemberJoinRequest: pre-approved: groupId: $groupId, userId: $userId")
                    bot.processChatJoinRequest(groupId, userId, true)
                    SessionManager.dropAuthSession(bot, user.userId)
                    ChannelLog.onAutomaticApprove(bot, group, userId)
                    // done
                    return@runBlocking
                } else {
                    Log.d(TAG, "onMemberJoinRequest: groupId: $groupId, userId: $userId, not pre-approved")
                }
                val originHintMsgId = bot.sendMessageForText(
                    pmsi, r.format(r.msg_text_join_auth_required_notice_va2, user.name, group.name)
                )
                Log.i(TAG, "send user join request msg to user: $userId, group: $groupId")
                val groupConfig = SessionManager.getOrCreateGroupConfig(bot, group)
                val maxWaitTimeSeconds = groupConfig.startAuthTimeoutSeconds
                if (maxWaitTimeSeconds > 0) {
                    // schedule a job to dismiss the request after timeout
                    postDelayed(maxWaitTimeSeconds * 1000L) {
                        // check whether the auth session is still valid
                        val authSession = SessionManager.getAuthSession(bot, userId)
                        if (authSession != null) {
                            if (authSession.currentAuthId == 0 && authSession.authStatus == SessionManager.AuthStatus.REQUESTED) {
                                Log.i(TAG, "dismiss timeout join request: $userId, group: $groupId")
                                ChannelLog.onStartAuthTimeout(bot, group, userId)
                                // drop the auth session if the user didn't start auth in time
                                SessionManager.dropAuthSession(bot, userId)
                                // delete the msg and dismiss the request
                                try {
                                    bot.deleteMessage(pmsi, originHintMsgId.id)
                                } catch (e: RemoteApiException) {
                                    Log.w(TAG, "delete request msg: $e")
                                }
                                try {
                                    bot.processChatJoinRequest(groupId, userId, false)
                                } catch (e: RemoteApiException) {
                                    if (e.message == "HIDE_REQUESTER_MISSING") {
                                        // nothing serious, just ignore it
                                    } else {
                                        throw e
                                    }
                                }
                            }
                        }
                    }.invokeOnCompletion {
                        if (it != null && it !is CancellationException) {
                            Log.e(TAG, "onMemberJoinRequest postDelayed, error: ${it.message}", it)
                        }
                    }
                }
            } else {
                Log.i(TAG, "ignore user join request: $userId, group: $groupId because disabled")
            }
        }
        return true
    }

    override fun onUpdateMessageContent(bot: Bot, si: SessionInfo, msgId: Long, content: JsonObject): Boolean {
        // Log.d(TAG, "onUpdateMessageContent, chatId: $chatId, msgId: $msgId")
        return false
    }

    override fun onMessageEdited(bot: Bot, si: SessionInfo, msgId: Long, editDate: Int): Boolean {
        // Log.d(TAG, "onMessageEdited, chatId: $chatId, msgId: $msgId, editDate: $editDate")
        return false
    }

    override fun onGroupDefaultPermissionsChanged(bot: Bot, groupId: Long, permissions: ChatPermissions): Boolean {
        Log.d(TAG, "onGroupDefaultPermissionsChanged, groupId: $groupId")
        return false
    }

    override fun onMemberStatusChanged(
        bot: Bot,
        groupId: Long,
        userId: Long,
        event: ChannelMemberStatusEvent
    ): Boolean {
        Log.d("onMemberStatusChanged", "groupId: $groupId, userId: $userId, event: $event")
        if (bot != mBot) {
            return false
        }
        val session = SessionManager.getAuthSession(bot, userId) ?: return false
        if (session.targetGroupId != groupId) {
            return false
        }
        if (session.authStatus in listOf(
                SessionManager.AuthStatus.REQUESTED,
                SessionManager.AuthStatus.AUTHENTICATING
            )
        ) return runBlocking {
            val user = bot.getUser(userId)
            val group = bot.getGroup(groupId)
            bot.getChat(userId)
            val operatorId = event.actorUserId
            val r = ResImpl.getResourceForUser(user)
            val pmsi = SessionInfo.forUser(userId)
            // notify the user
            when (val newStatus = event.newStatus!!.javaClass) {
                MemberStatus.Member::class.java,
                MemberStatus.Creator::class.java,
                MemberStatus.Restricted::class.java,
                MemberStatus.Administrator::class.java -> {
                    if (operatorId != bot.userId) {
                        // if user get approved by an admin, notify the user
                        bot.sendMessageForText(pmsi, r.format(r.msg_text_approved_manually_by_admin_va1, group.name))
                    }
                    val oldMsgId = session.originalMessageId
                    SessionManager.dropAuthSession(bot, userId)
                    ChannelLog.onManualApproveJoinRequest(bot, group, userId, operatorId)
                    if (oldMsgId != 0L) {
                        bot.deleteMessage(pmsi, oldMsgId)
                    }
                    return@runBlocking true
                }
                MemberStatus.Banned::class.java -> {
                    bot.sendMessageForText(pmsi, r.format(r.msg_text_banned_manually_by_admin_va1, group.name))
                    val oldMsgId = session.originalMessageId
                    SessionManager.dropAuthSession(bot, userId)
                    ChannelLog.onManualDenyJoinRequest(bot, group, userId, operatorId)
                    if (oldMsgId != 0L) {
                        bot.deleteMessage(pmsi, oldMsgId)
                    }
                    return@runBlocking true
                }
                else -> {
                    Log.e(TAG, "unexpected status: $newStatus, group: $groupId, user: $userId")
                    return@runBlocking false
                }
            }
        } else {
            return false
        }
    }

    override fun onDeleteMessages(bot: Bot, si: SessionInfo, msgIds: List<Long>): Boolean {
        val msgToDelete = HashSet<Long>(4)
        val keys = msgIds.map { si.toTDLibChatId().toString() + "_" + it.toString() }
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
                    bot.deleteMessages(si, msgToDelete.toList())
                } catch (e: RemoteApiException) {
                    // we don't really care about the error
                    Log.w(TAG, "cascade delete msg, error: ${e.message}")
                }
            }
        }
        return true
    }

    fun scheduleCascadeDeleteMessage(si: SessionInfo, origMsgId: Long, targetMsgId: Long) {
        assert(origMsgId != targetMsgId)
        assert(si.id != 0L)
        assert(origMsgId > 0L)
        assert(targetMsgId > 0L)
        val key = si.toTDLibChatId().toString() + "_" + origMsgId
        synchronized(mCascadeDeleteMsgLock) {
            mCascadeDeleteMsg[key] = targetMsgId
        }
    }

    internal fun Message.scheduledCascadeDelete(origMsgId: Long) {
        if (this.serverMsgId == 0L) {
            // we don't need to delete the msg if it's not sent to server yet
            return
        }
        scheduleCascadeDeleteMessage(sessionInfo, origMsgId, this.id)
    }

    internal fun Job.logErrorIfFail() {
        invokeOnCompletion {
            if (it != null && it !is CancellationException) {
                Log.e(TAG, "job error: ${it.message}", it)
            }
        }
    }

}
