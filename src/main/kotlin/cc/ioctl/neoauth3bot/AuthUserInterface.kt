package cc.ioctl.neoauth3bot

import cc.ioctl.neoauth3bot.chiral.ChiralCarbonHelper
import cc.ioctl.neoauth3bot.chiral.MdlMolParser
import cc.ioctl.neoauth3bot.chiral.Molecule
import cc.ioctl.neoauth3bot.chiral.MoleculeRender
import cc.ioctl.neoauth3bot.dat.ChemDatabase
import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.User
import cc.ioctl.telebot.tdlib.tlrpc.RemoteApiException
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.ReplyMarkup
import cc.ioctl.telebot.util.Base64
import cc.ioctl.telebot.util.Log
import cc.ioctl.telebot.util.TokenBucket
import java.io.File

object AuthUserInterface {

    private const val TAG = "NeoAuth3Bot.CUI"
    private const val BTN_TYPE_REGION = 0
    private const val BTN_TYPE_CHANGE = 1
    private const val BTN_TYPE_CLEAR = 2
    private const val BTN_TYPE_SUBMIT = 3

    private val mNewAuthBpf = TokenBucket<Long>(2, 5000)

    fun checkAuthRate(userId: Long): Boolean {
        if (userId == 0L) {
            throw IllegalArgumentException("userId must not be 0")
        }
        return mNewAuthBpf.consume(userId) >= 0
    }

    fun buildMatrixButtonMarkup(user: User, uniqueId: Int, info: SessionManager.UserAuthSession): ReplyMarkup {
        val numX = info.numCountX
        val numY = info.numCountY
        // { i32 id, u8 unused, u8 flags, u8 type, u8 pos }
        val bytes8 = ByteArray(8)
        val rows = ArrayList<Array<ReplyMarkup.InlineKeyboard.Button>>()
        for (y in 0 until numY) {
            val cols = ArrayList<ReplyMarkup.InlineKeyboard.Button>()
            for (x in 0 until numX) {
                val btnId = ((x shl 4) or y)
                val isSelected = info.selectedRegion.contains(btnId)
                val sb = StringBuilder().apply {
                    if (isSelected) appendCodePoint('['.code)
                    appendCodePoint((('A' + x).code))
                    appendCodePoint((('1' + y).code))
                    if (isSelected) appendCodePoint(']'.code)
                }
                BinaryUtils.writeLe32(bytes8, 0, uniqueId)
                bytes8[4] = 0x00.toByte()
                bytes8[5] = if (isSelected) 0x01.toByte() else 0x00.toByte()
                bytes8[6] = 0x00.toByte()
                bytes8[7] = btnId.toByte()
                val button = ReplyMarkup.InlineKeyboard.Button(
                    sb.toString(), ReplyMarkup.InlineKeyboard.Button.Type.Callback(
                        Base64.encodeToString(bytes8, Base64.NO_WRAP)
                    )
                )
                cols.add(button)
            }
            rows.add(cols.toTypedArray())
        }
        val texts = arrayOf(
            LocaleHelper.getBtnChangeQuizText(user),
            LocaleHelper.getBtnResetText(user),
            LocaleHelper.getBtnSubmitText(user)
        )
        ArrayList<ReplyMarkup.InlineKeyboard.Button>().apply {
            for (i in texts.indices) {
                bytes8[6] = (i + 1).toByte()
                add(
                    ReplyMarkup.InlineKeyboard.Button(
                        texts[i], ReplyMarkup.InlineKeyboard.Button.Type.Callback(
                            Base64.encodeToString(bytes8, Base64.NO_WRAP)
                        )
                    )
                )
            }
        }.let {
            rows.add(it.toTypedArray())
        }
        return ReplyMarkup.InlineKeyboard(rows.toTypedArray())
    }

    private suspend fun errorAlert(bot: Bot, queryId: String, msg: String) {
        bot.answerCallbackQuery(queryId, msg, true)
    }

    suspend fun onBtnClick(
        bot: Bot,
        user: User,
        chatId: Long,
        auth3Info: SessionManager.UserAuthSession,
        bytes8: ByteArray,
        queryId: String
    ) {
        var isInvalidateRequired: Boolean = false
        val msg: String
        val flags = bytes8[5].toInt()
        val type = bytes8[6].toInt()
        val btnArgs = bytes8[7].toInt()
        when (type) {
            BTN_TYPE_REGION -> {
                val x = (btnArgs shr 4) and 0x0f
                val y = btnArgs and 0x0f
                val regionName = StringBuilder().apply {
                    appendCodePoint((('A' + x).code))
                    appendCodePoint((('1' + y).code))
                }.toString()
                if (x >= auth3Info.numCountX || y >= auth3Info.numCountY) {
                    val msg = "Invalid button, x: $x, y: $y, w: ${auth3Info.numCountX}, x: ${auth3Info.numCountY}"
                    errorAlert(bot, queryId, msg)
                    return
                }
                val originalMessageId = auth3Info.originalMessageId
                if (originalMessageId == 0L) {
                    errorAlert(bot, queryId, "Invalid argument, originalMessageId is 0")
                    return
                }
                if (flags and 1 == 0) {
                    // unchecked -> checked
                    auth3Info.selectedRegion.add(x shl 4 or y)
                    msg = LocaleHelper.getCallbackHintForSelectRegion(user, regionName)
                } else {
                    // checked -> unchecked
                    auth3Info.selectedRegion.remove(x shl 4 or y)
                    msg = LocaleHelper.getCallbackHintForUnselectRegion(user, regionName)
                }
                isInvalidateRequired = true
            }
            BTN_TYPE_CHANGE -> {
                // change request
                errorAlert(bot, queryId, LocaleHelper.getCallbackHintForChangeRequestTodo(user))
                return
            }
            BTN_TYPE_CLEAR -> {
                // clear
                if (auth3Info.selectedRegion.isNotEmpty()) {
                    auth3Info.selectedRegion.clear()
                    isInvalidateRequired = true
                }
                msg = LocaleHelper.getCallbackHintForResetRegion(user)
            }
            BTN_TYPE_SUBMIT -> {
                // submit
                val got = ArrayList<Int>(auth3Info.selectedRegion).also {
                    it.sort()
                }
                val expected = auth3Info.actualChiralRegion
                Log.d(TAG, "got: $got, expected: $expected")
                var isCorrect = false
                if (got.size == expected.size) {
                    isCorrect = true
                    for (i in got) {
                        if (!expected.contains(i)) {
                            isCorrect = false
                            break
                        }
                    }
                }
                if (isCorrect) {
                    onAuthenticationSuccess(bot, user, chatId, auth3Info)
                    bot.answerCallbackQuery(queryId, LocaleHelper.getAuthSuccessShortText(user), false)
                    return
                } else {
                    bot.answerCallbackQuery(queryId, LocaleHelper.getAuthFailWrongAnswerText(user), true)
                }
                return
            }
            else -> {
                // unknown
                errorAlert(bot, queryId, "Invalid button type: $type")
                return
            }
        }
        try {
            SessionManager.saveAuthSession(bot, user.userId, auth3Info)
            if (isInvalidateRequired) {
                // invalidate
                val targetGid = auth3Info.targetGroupId
                val groupConfig = SessionManager.getGroupConfig(bot, targetGid)
                val maxDuration = groupConfig?.authProcedureTimeoutSeconds ?: 600
                bot.resolveMessage(chatId, auth3Info.originalMessageId, false)
                bot.editMessageCaption(
                    chatId,
                    auth3Info.originalMessageId,
                    LocaleHelper.createFormattedMsgText(auth3Info, user, maxDuration),
                    AuthUserInterface.buildMatrixButtonMarkup(user, auth3Info.currentAuthId, auth3Info)
                )
            }
            bot.answerCallbackQuery(queryId, msg, false)
        } catch (e: Exception) {
            if (e.message.toString().contains("MESSAGE_NOT_MODIFIED")) {
                // ignore
                return
            }
            Log.e(TAG, "onBtnClick: editMessageCaption error: " + e.message, e)
            errorAlert(bot, queryId, e.message ?: e.toString())
        }
    }

    suspend fun onStartAuthCommand(bot: Bot, chatId: Long, user: User, msg: Message, isForTest: Boolean) {
        val senderId = user.userId
        if (!checkAuthRate(senderId)) {
            bot.sendMessageForText(chatId, LocaleHelper.getTooManyRequestsText(user))
            return
        }
        var auth3Info = SessionManager.getAuthSession(bot, senderId)
        val targetGid = auth3Info?.targetGroupId ?: 0L
        if (!isForTest && targetGid == 0L) {
            bot.sendMessageForText(chatId, LocaleHelper.getNoAuthRequiredBczNoRequestText(user))
            return
        }
        if (auth3Info == null) {
            auth3Info = SessionManager.createAuthSessionForTest(bot, user)
        }
        startNewAuth(bot, chatId, user, auth3Info, requestMsgId = msg.id, previousMsgId = 0)
    }

    suspend fun startNewAuth(
        bot: Bot,
        chatId: Long,
        user: User,
        auth3Info: SessionManager.UserAuthSession,
        requestMsgId: Long,
        previousMsgId: Long
    ) {
        val tmpMsgId = bot.sendMessageForText(chatId, LocaleHelper.getLoadingText(user), replyMsgId = requestMsgId).id
        try {
            val authId = SessionManager.nextAuthSequence()
            val targetGid = auth3Info.targetGroupId
            val groupConfig = if (targetGid > 0) {
                val group = bot.resolveGroup(targetGid)
                SessionManager.getOrCreateGroupConfig(bot, group)
            } else null
            Log.d(TAG, "startNewAuth: authId: $authId, user: ${user.userId}, gid: ${targetGid}")
            val t0 = System.currentTimeMillis()
            val cid = ChemDatabase.nextRandomCid()
            val molecule = MdlMolParser.parseString(
                ChemDatabase.loadChemTableString(cid)!!
            )
            val t1 = System.currentTimeMillis()
            val cfg = initMoleculeConfig(molecule)
            val chirals = ChiralCarbonHelper.getMoleculeChiralCarbons(molecule)
            val actualChiralRegions = HashSet<Int>(5).also { regions ->
                chirals.forEach { cidx ->
                    val gridWidth = cfg.width / cfg.gridCountX
                    val gridHeight = cfg.height / cfg.gridCountY
                    val x = cfg.transformX(molecule, molecule.atomX(cidx))
                    val y = cfg.transformY(molecule, molecule.atomY(cidx))
                    val xn = (x / gridWidth).toInt()
                    val yn = (y / gridHeight).toInt()
                    val gridId = (xn shl 4) or yn
                    regions.add(gridId)
                }
            }.let {
                ArrayList(it).also {
                    it.sort()
                }
            }
            if (groupConfig?.enforceMode == SessionManager.EnforceMode.WITH_HINT) {
                cfg.shownChiralCarbons = ArrayList(chirals)
            } else {
                cfg.shownChiralCarbons = ArrayList()
            }
            val maxDuration = groupConfig?.authProcedureTimeoutSeconds ?: 600
            val t2 = System.currentTimeMillis()
            val tmpFile: File = MoleculeRender.renderMoleculeAsImage(molecule, cfg).use { img ->
                img.encodeToData()?.use {
                    val f = File.createTempFile(System.currentTimeMillis().toString(), ".png")
                    f.writeBytes(it.bytes)
                    f
                } ?: throw RuntimeException("failed to encode image")
            }
            // update auth info
            auth3Info.updateAuthInfo(
                authId = authId,
                cid = cid,
                originalMessageId = 0L,
                changesAllowed = 2,
                numCountX = cfg.gridCountX,
                numCountY = cfg.gridCountY,
                chiralList = ArrayList(chirals),
                actualChiralRegion = actualChiralRegions,
                selectedRegion = ArrayList()
            )
            val markup = buildMatrixButtonMarkup(user, auth3Info.currentAuthId, auth3Info)
            val ret = bot.sendMessageForPhoto(
                chatId,
                tmpFile,
                LocaleHelper.createFormattedMsgText(auth3Info, user, maxDuration),
                markup,
                replyMsgId = requestMsgId
            )
            val t3 = System.currentTimeMillis()
            auth3Info.originalMessageId = ret.id
            SessionManager.saveAuthSession(bot, user.userId, auth3Info)
            println("msg id = " + ret.id + ", serverMsgId = " + ret.serverMsgId)
            Log.d(TAG, "load cost: ${t1 - t0}, render cost: ${t2 - t1}, send cost: ${t3 - t2}")
        } catch (e: Exception) {
            val msg = "create auth3 error: " + (e.message ?: e.toString())
            Log.e(TAG, msg, e)
            bot.sendMessageForText(chatId, msg)
        }
        bot.deleteMessage(chatId, tmpMsgId)
    }

    private fun initMoleculeConfig(molecule: Molecule): MoleculeRender.MoleculeRenderConfig {
        val cfg = MoleculeRender.calculateRenderRect(molecule, 720)
        cfg.gridCountX = 5
        cfg.gridCountY = 3
        cfg.drawGrid = true
        return cfg
    }

    private suspend fun onAuthenticationSuccess(
        bot: Bot,
        user: User,
        chatId: Long,
        auth3Info: SessionManager.UserAuthSession
    ) {
        val targetGroupId = auth3Info.targetGroupId
        val now = System.currentTimeMillis()
        val cost = ((now - auth3Info.authStartTime) / 1000).toInt()
        bot.sendMessageForText(chatId, LocaleHelper.getAuthSuccessText(user, cost))
        bot.deleteMessage(chatId, auth3Info.originalMessageId)
        if (targetGroupId != 0L) {
            // approve user to join group
            val targetChatId = Bot.groupIdToChatId(targetGroupId)
            // resolve chat and user to make TDLib happy
            bot.resolveChat(targetChatId)
            bot.resolveUser(auth3Info.userId)
            try {
                bot.processChatJoinRequest(targetChatId, auth3Info.userId, true)
            } catch (e: RemoteApiException) {
                if (e.message?.contains("USER_ALREADY_PARTICIPANT") == true) {
                    // ignore
                } else {
                    // rethrow
                    throw e
                }
            }
        }
        Log.d(TAG, "approved user ${auth3Info.userId} into group ${targetGroupId}")
        SessionManager.dropAuthSession(bot, auth3Info.userId)
        auth3Info.originalMessageId = 0L
        if (targetGroupId != 0L) {
            bot.sendMessageForText(chatId, LocaleHelper.getApproveSuccessText(user))
        }
    }

}
