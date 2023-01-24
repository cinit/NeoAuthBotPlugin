package cc.ioctl.misc

import cc.ioctl.neoauth3bot.HypervisorCommandHandler
import cc.ioctl.telebot.tdlib.RobotServer
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo
import cc.ioctl.telebot.tdlib.tlrpc.api.msg.Message
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object InlineBotMsgCleaner : HypervisorCommandHandler.HvCmdCallback {

    private const val TAG = "NeoAuth3Bot.InlineBotMsgCleaner"

    override suspend fun onSupervisorCommand(
        bot: Bot, si: SessionInfo, senderId: Long, serviceCmd: String, args: Array<String>
    ): String {
        var groupId: Long = if (si.isGroupOrChannel) {
            si.id
        } else {
            -1L
        }
        val restArgs: Array<String>
        if (args.contains("-g")) {
            // find '-g'
            val index = args.indexOf("-g")
            if (index + 1 < args.size) {
                // find group id
                groupId = args[index + 1].toLong()
            }
            restArgs = args.filterIndexed { i, _ -> i != index && i != index + 1 }.toTypedArray()
        } else {
            restArgs = args
        }
        if (groupId == -1L) {
            return "-g <group_id> is required"
        }
        when (serviceCmd) {
            "reload" -> {
                val p = reloadPoliciesForGroup(bot, groupId)
                return "Reloaded ${p?.policies?.size} policies"
            }
            "p", "print", "get" -> {
                val p = getPoliciesForGroup(bot, groupId)
                return if (p == null || p.policies.isEmpty()) {
                    "No policy found"
                } else {
                    p.toJsonArray().toString()
                }
            }
            "update" -> {
                val jsonText = restArgs.joinToString(" ")
                return try {
                    val policies = parseJsonPolicies(jsonText)
                    savePoliciesForGroup(bot, groupId, policies)
                    "Updated ${policies.policies.size} policies"
                } catch (e: JsonParseException) {
                    "Error parsing json: $e"
                } catch (e: java.lang.IllegalArgumentException) {
                    "Invalid policy: $e"
                } catch (e: Exception) {
                    "Error: $e"
                }
            }
            else -> {
                return "ENOSYS. Available commands: reload, print, update."
            }
        }
    }

    data class RetentionPolicies(
        val policies: HashMap<Long, ActionRule>
    ) {

        data class ActionRule(
            val inlineBotId: Long,
            val retentionSeconds: Int,
        ) {
            init {
                check(inlineBotId > 0) { "inlineBotId must be positive, got $inlineBotId" }
                check(retentionSeconds >= -1) { "retentionSeconds must be >= -1, got $retentionSeconds" }
            }
        }

        /**
         * -1 for not deleting
         */
        fun getRetentionTime(botId: Long): Int {
            if (botId <= 0) {
                return -1
            }
            val policy = policies[botId]
            return policy?.retentionSeconds ?: -1
        }

        companion object {
            @JvmStatic
            fun fromJsonArray(json: JsonArray): RetentionPolicies {
                val policies = HashMap<Long, ActionRule>()
                for (i in 0 until json.size()) {
                    val rule = json[i].asJsonObject
                    val botId = rule["inlineBotId"].asLong
                    val retentionSeconds = rule["retentionSeconds"].asInt
                    policies[botId] = ActionRule(botId, retentionSeconds)
                }
                return RetentionPolicies(policies)
            }
        }

        fun toJsonArray(): JsonArray {
            val json = JsonArray()
            for (rule in policies.values) {
                val ruleJson = JsonObject()
                ruleJson.addProperty("inlineBotId", rule.inlineBotId)
                ruleJson.addProperty("retentionSeconds", rule.retentionSeconds)
                json.add(ruleJson)
            }
            return json
        }

    }

    private val mCachedConfig: ConcurrentHashMap<Long, Optional<RetentionPolicies>> = ConcurrentHashMap(64)

    @JvmStatic
    fun onReceiveMessage(bot: Bot, si: SessionInfo, senderId: Long, message: Message): Boolean {
        if (!si.isGroupOrChannel) {
            return false
        }
        val gid = si.id
        val inlineBotId = message.viaBotUserId
        if (inlineBotId != 0L) {
            val retentionSeconds = getPoliciesForGroup(bot, gid)?.getRetentionTime(inlineBotId) ?: -1
            if (retentionSeconds >= 0) {
                scheduleDeleteMsgAfter(bot, si, message.id, retentionSeconds)
                return false
            }
        }
        return false
    }

    fun getPoliciesForGroup(bot: Bot, uid: Long): RetentionPolicies? {
        val v = mCachedConfig[uid]
        return if (v != null) {
            v.orElse(null)
        } else {
            val p = loadPoliciesForGroupInternal(bot, uid)
            mCachedConfig[uid] = Optional.ofNullable(p)
            p
        }
    }

    private fun loadPoliciesForGroupInternal(bot: Bot, uid: Long): RetentionPolicies? {
        check(uid > 0) { "invalid uid: $uid" }
        val groupBaseDir = File(RobotServer.instance.pluginsDir, "groups" + File.separator + "g_$uid")
        val anointedFile = File(groupBaseDir, "InlineBotCleaner.json")
        if (!anointedFile.exists()) {
            return null
        }
        val json = anointedFile.readText()
        if (json.isEmpty()) {
            return null
        }
        return RetentionPolicies.fromJsonArray(com.google.gson.JsonParser.parseString(json).asJsonArray)
    }

    fun reloadPoliciesForGroup(bot: Bot, uid: Long): RetentionPolicies? {
        check(uid > 0) { "invalid uid: $uid" }
        val p = loadPoliciesForGroupInternal(bot, uid)
        mCachedConfig[uid] = Optional.ofNullable(p)
        return p
    }

    fun savePoliciesForGroup(bot: Bot, uid: Long, policies: RetentionPolicies) {
        check(uid > 0) { "invalid uid: $uid" }
        val groupBaseDir = File(RobotServer.instance.pluginsDir, "groups" + File.separator + "g_$uid")
        val anointedFile = File(groupBaseDir, "InlineBotCleaner.json")
        anointedFile.writeText(policies.toJsonArray().toString())
        mCachedConfig[uid] = Optional.of(policies)
    }

    @JvmStatic
    @Throws(JsonParseException::class, IllegalArgumentException::class)
    fun parseJsonPolicies(json: String): RetentionPolicies {
        return RetentionPolicies.fromJsonArray(com.google.gson.JsonParser.parseString(json).asJsonArray)
    }

    private fun scheduleDeleteMsgAfter(bot: Bot, si: SessionInfo, msgId: Long, delaySeconds: Int) {
        if (delaySeconds <= 0) {
            return
        }
        bot.scheduleTaskDelayedWithContext(Dispatchers.IO, delaySeconds * 1000L) {
            bot.deleteMessage(si, msgId)
        }
    }

}
