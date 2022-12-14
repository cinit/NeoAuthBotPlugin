package cc.ioctl.neoauth3bot

import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.Group
import cc.ioctl.telebot.tdlib.obj.User
import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

object SessionManager {

    private const val TAG = "NeoAuth3Bot.SessionManager"
    private const val KEY_GROUP_CONFIG_FOR_GID = "config_for_group_"
    private const val KEY_AUTH_INFO_FOR_UID = "auth_info_for_user_"
    private const val KEY_NEXT_AUTH_SEQUENCE = "next_auth_sequence"

    private val mNextAuthSeqLock = Any()


    private val mPersists = ConcurrentHashMap<String, MMKV>()
    private val mGlobalConfig by lazy {
        MMKV.mmkvWithID("NeoAuth3Bot_global")
    }

    fun nextAuthSequence(): Int {
        synchronized(mNextAuthSeqLock) {
            val seq = mGlobalConfig.getInt(KEY_NEXT_AUTH_SEQUENCE, 10000)
            mGlobalConfig.putInt(KEY_NEXT_AUTH_SEQUENCE, seq + 1)
            return seq
        }
    }

    private fun getConfigForBot(bot: Bot, type: String): MMKV {
        val uid = bot.userId
        if (bot.userId < 0) {
            throw IllegalArgumentException("bot.userId must be positive")
        }
        if (type.isEmpty()) {
            throw IllegalArgumentException("type must not be empty")
        }
        val name = "NeoAuth3Bot_${type}_${uid}"
        return mPersists.computeIfAbsent(name) { MMKV.mmkvWithID(name) }
    }

    fun getGroupConfig(bot: Bot, gid: Long): GroupAuthConfig? {
        val config = getConfigForBot(bot, "GroupConfig")
        val str = config.getString(KEY_GROUP_CONFIG_FOR_GID + gid, null) ?: return null
        return Json.decodeFromString(GroupAuthConfig.serializer(), str)
    }

    fun getOrCreateGroupConfig(bot: Bot, group: Group): GroupAuthConfig {
        return getGroupConfig(bot, group.groupId) ?: GroupAuthConfig(group.groupId, group.name)
    }

    fun saveGroupConfig(bot: Bot, gid: Long, config: GroupAuthConfig) {
        if (gid <= 0) {
            throw IllegalArgumentException("gid must be positive")
        }
        val configForBot = getConfigForBot(bot, "GroupConfig")
        if (config.groupId != gid) {
            throw IllegalArgumentException("config.groupId mismatch, config.groupId: ${config.groupId}, gid: $gid")
        }
        configForBot.putString(
            KEY_GROUP_CONFIG_FOR_GID + gid,
            Json.encodeToString(GroupAuthConfig.serializer(), config)
        )
    }

    fun getAuthSession(bot: Bot, uid: Long): UserAuthSession? {
        val config = getConfigForBot(bot, "UserAuthSession")
        val str = config.getString(KEY_AUTH_INFO_FOR_UID + uid, null) ?: return null
        return Json.decodeFromString(UserAuthSession.serializer(), str)
    }

    fun saveAuthSession(bot: Bot, uid: Long, session: UserAuthSession) {
        val configForBot = getConfigForBot(bot, "UserAuthSession")
        if (session.userId != uid) {
            throw IllegalArgumentException("session.userId mismatch, session.userId: ${session.userId}, uid: $uid")
        }
        configForBot.putString(
            KEY_AUTH_INFO_FOR_UID + uid,
            Json.encodeToString(UserAuthSession.serializer(), session)
        )
    }

    fun dropAuthSession(bot: Bot, uid: Long) {
        val configForBot = getConfigForBot(bot, "UserAuthSession")
        configForBot.removeValueForKey(KEY_AUTH_INFO_FOR_UID + uid)
    }

    object AuthStatus {
        const val RESET = 0
        const val REQUESTED = 1
        const val AUTHENTICATING = 2
        const val FAILED = 3
        const val SUCCESS = 4
    }

    @Serializable
    data class UserAuthSession(
        val userId: Long,
        var userNick: String,
        var targetGroupId: Long,
        var targetGroupName: String,
        var currentAuthId: Int,
        var currentCid: Int,
        var authStatus: Int,
        var requestTime: Long,
        var authStartTime: Long,
        var changesAllowed: Int,
        var originalMessageId: Long,
        var numCountX: Int,
        var numCountY: Int,
        var chiralList: ArrayList<Int>,
        var actualChiralRegion: ArrayList<Int>,
        var selectedRegion: ArrayList<Int>,
    ) {
        init {
            assert(userId > 0)
            assert(targetGroupId >= 0)
            assert(currentAuthId >= 0)
        }

        fun updateAuthInfo(
            authId: Int,
            cid: Int,
            changesAllowed: Int,
            originalMessageId: Long,
            numCountX: Int,
            numCountY: Int,
            chiralList: ArrayList<Int>,
            actualChiralRegion: ArrayList<Int>,
            selectedRegion: ArrayList<Int>,
        ) {
            this.currentAuthId = authId
            this.currentCid = cid
            this.authStartTime = System.currentTimeMillis()
            this.changesAllowed = changesAllowed
            this.originalMessageId = originalMessageId
            this.numCountX = numCountX
            this.numCountY = numCountY
            this.chiralList = chiralList
            this.actualChiralRegion = actualChiralRegion
            this.selectedRegion = selectedRegion
        }
    }

    object EnforceMode {
        const val WITH_HINT = 0
        const val NO_HINT = 1
    }

    @Serializable
    data class GroupAuthConfig(
        val groupId: Long,
        var groutName: String,
        var isEnabled: Boolean = true,
        var enforceMode: Int = EnforceMode.WITH_HINT,
        var startAuthTimeoutSeconds: Int = 600,
        var authProcedureTimeoutSeconds: Int = 600,
    ) {
        init {
            assert(groupId > 0)
        }
    }

    fun handleUserJoinRequest(bot: Bot, user: User, group: Group): Boolean {
        val groupConfig = getOrCreateGroupConfig(bot, group)
        if (!groupConfig.isEnabled) {
            return false
        }
        if (group.isKnown && groupConfig.groutName != group.name) {
            // update group name
            groupConfig.groutName = group.name
            saveGroupConfig(bot, group.groupId, groupConfig)
        }
        val session = getAuthSession(bot, user.userId) ?: UserAuthSession(
            userId = user.userId,
            userNick = user.name,
            targetGroupId = group.groupId,
            targetGroupName = group.name,
            currentAuthId = 0,
            currentCid = 0,
            authStatus = AuthStatus.REQUESTED,
            requestTime = System.currentTimeMillis(),
            authStartTime = 0,
            changesAllowed = 3,
            originalMessageId = 0,
            numCountX = 0,
            numCountY = 0,
            chiralList = ArrayList(),
            actualChiralRegion = ArrayList(),
            selectedRegion = ArrayList(),
        )
        // update session
        session.authStatus = AuthStatus.REQUESTED
        session.requestTime = System.currentTimeMillis()
        session.userNick = user.name
        session.targetGroupId = group.groupId
        session.targetGroupName = group.name
        saveAuthSession(bot, user.userId, session)
        return true
    }

    fun createAuthSessionForTest(bot: Bot, user: User): UserAuthSession {
        var session = getAuthSession(bot, user.userId)
        if (session != null) {
            // just update the session
            session.userNick = user.name
            session.authStatus = AuthStatus.AUTHENTICATING
            session.authStartTime = System.currentTimeMillis()
            session.currentAuthId = nextAuthSequence()
        } else {
            // create a new session
            session = UserAuthSession(
                userId = user.userId,
                userNick = user.name,
                targetGroupId = 0,
                targetGroupName = "",
                currentAuthId = nextAuthSequence(),
                currentCid = 0,
                authStatus = AuthStatus.AUTHENTICATING,
                requestTime = System.currentTimeMillis(),
                authStartTime = System.currentTimeMillis(),
                changesAllowed = 3,
                originalMessageId = 0,
                numCountX = 0,
                numCountY = 0,
                chiralList = ArrayList(),
                actualChiralRegion = ArrayList(),
                selectedRegion = ArrayList(),
            )
        }
        saveAuthSession(bot, user.userId, session)
        return session
    }

}
