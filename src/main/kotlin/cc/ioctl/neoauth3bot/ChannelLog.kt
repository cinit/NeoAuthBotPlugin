package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.Channel
import cc.ioctl.telebot.tdlib.obj.Group
import cc.ioctl.telebot.tdlib.tlrpc.RemoteApiException
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.FormattedTextBuilder
import cc.ioctl.telebot.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.*

object ChannelLog {

    private const val TAG = "ChannelLog"

    private var mErrorLogChannel: Channel? = null
    private var mDefaultLogChannelId: Long = 0

    suspend fun getLogChannelForGroup(bot: Bot, group: Group): Channel? {
        // TODO: 2022-08-23 add support for per-group log channels
        val cid = mDefaultLogChannelId
        if (cid == 0L) {
            return null
        }
        return try {
            val ch = bot.getChannel(cid)
            check(ch.canPostMessages(bot)) { "bot $bot can't post messages to channel $ch" }
            ch
        } catch (e: Exception) {
            Log.e(TAG, "getLogChannelForGroup", e)
            null
        }
    }

    suspend fun getErrorLogChannel(bot: Bot): Channel? {
        return mErrorLogChannel ?: try {
            val ch = bot.getChannel(mDefaultLogChannelId)
            check(ch.canPostMessages(bot)) { "bot $bot can't post messages to channel $ch" }
            mErrorLogChannel = ch
            ch
        } catch (e: Exception) {
            Log.e(TAG, "getErrorLogChannel", e)
            null
        }
    }

    suspend fun setDefaultLogChannelId(bot: Bot, cid: Long) {
        mDefaultLogChannelId = if (cid == 0L) {
            0L
        } else {
            val channel = bot.getChannel(cid)
            check(channel.canPostMessages(bot)) { "bot $bot can't post messages to channel $channel" }
            cid
        }
    }

    suspend fun setErrorLogChannel(bot: Bot, channel: Channel) {
        check(channel.canPostMessages(bot)) { "bot $bot can't post messages to channel $channel" }
        mErrorLogChannel = channel
    }

    suspend fun doPostLogToChannel(
        bot: Bot,
        channel: Channel,
        category: String,
        group: Group?,
        adminId: Long = 0,
        userId: Long = 0,
        args: Map<String, String> = emptyMap()
    ) {
        val userName = if (userId > 0) try {
            bot.getUser(userId).name
        } catch (e: RemoteApiException) {
            Log.e(TAG, "failed to resolve user $userId: $e")
            null
        } else null
        val adminName = if (adminId > 0) try {
            bot.getUser(adminId).name
        } catch (e: RemoteApiException) {
            Log.e(TAG, "failed to resolve user $adminId: $e")
            null
        } else null
        val msgBody = FormattedTextBuilder().apply {
            this + "#" + category + "\n"
            if (group != null) {
                this + Bold("Group") + ": " + group.name + " [" + Code("${group.groupId}") + "]\n"
            }
            if (adminId != 0L) {
                this + Bold("Admin") + ": "
                if (adminName != null) {
                    this + MentionName(adminName, adminId)
                }
                this + " [" + Code("$adminId") + "]\n"
            }
            if (userId != 0L) {
                this + Bold("User") + ": "
                if (userName != null) {
                    this + MentionName(userName, userId)
                }
                this + " [" + Code("$userId") + "]\n"
            }
            for ((key, value) in args) {
                this + Bold(key) + ": " + value + "\n"
            }
        }.build()
        bot.sendMessageForText(channel.sessionInfo, msgBody, options = JsonObject().apply {
            addProperty("@type", "messageSendOptions")
            addProperty("disable_notification", true)
        })
    }

    fun postLogToChannelAsync(
        bot: Bot,
        channel: Channel,
        category: String,
        group: Group?,
        adminId: Long = 0,
        userId: Long = 0,
        args: Map<String, String> = emptyMap()
    ) {
        runBlocking {
            coroutineScope {
                launch {
                    yield()
                    doPostLogToChannel(bot, channel, category, group, adminId, userId, args)
                }.logErrorIfFail()
            }
        }
    }

    suspend fun onJoinRequest(bot: Bot, group: Group, userId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "JOIN_REQUEST",
                group,
                0,
                userId
            )
        }
    }

    suspend fun onStartAuthTimeout(bot: Bot, group: Group, userId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "AUTH_TIMEOUT_START",
                group,
                0,
                userId
            )
        }
    }

    suspend fun onHideRequesterMissing(bot: Bot, group: Group, userId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "HIDE_REQUESTER_MISSING",
                group,
                0,
                userId
            )
        }
    }

    suspend fun onAuthPassed(bot: Bot, group: Group, userId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "AUTH_PASSED",
                group,
                0,
                userId
            )
        }
    }

    suspend fun onManualApproveJoinRequest(bot: Bot, group: Group, userId: Long, adminId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "APPROVE",
                group,
                adminId,
                userId
            )
        }
    }

    suspend fun onManualDenyJoinRequest(bot: Bot, group: Group, userId: Long, adminId: Long) {
        getLogChannelForGroup(bot, group)?.let {
            postLogToChannelAsync(
                bot,
                it,
                "DISMISS",
                group,
                adminId,
                userId
            )
        }
    }

    private fun Job.logErrorIfFail() {
        invokeOnCompletion {
            if (it != null && it !is CancellationException) {
                Log.e(TAG, "job error: ${it.message}", it)
            }
        }
    }

    fun onError(tag: String, bot: Bot, err: Throwable) {
        onError(tag, bot, err.toString())
    }

    private val mTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @JvmStatic
    fun onError(tag: String, bot: Bot, errMsg: String) {
        val time = System.currentTimeMillis()
        val timeString = mTimeFormat.format(time)
        bot.server.executor.execute {
            runBlocking {
                try {
                    getErrorLogChannel(bot)?.let {
                        val msg = "#ERROR\n$tag: $errMsg\n\n$timeString"
                        bot.sendMessageForText(it.sessionInfo, msg)
                    }
                } catch (e: Exception) {
                    // sigh
                    Log.e(TAG, "ChannelLog.logError: $errMsg, but $e", e)
                }
            }
        }
    }
}
