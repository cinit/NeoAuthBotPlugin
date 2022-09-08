package cc.ioctl.neoauth3bot.svc

import cc.ioctl.neoauth3bot.HypervisorCommandHandler
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo
import cc.ioctl.telebot.util.Log
import com.tencent.mmkv.MMKV

object FilterService : HypervisorCommandHandler.HvCmdCallback {

    private const val TAG = "FilterService"
    private val mPersist: MMKV by lazy { MMKV.mmkvWithID("NeoAuth3_FilterService") }

    private val mLock = Any()

    private var mList: ArrayList<Long>? = null

    private const val KEY_BLOCK_UID_LIST = "block_uid_list"

    fun isBlocked(uid: Long): Boolean {
        synchronized(mLock) {
            if (mList == null) {
                mList = loadListLocked()
            }
            return mList!!.contains(uid)
        }
    }

    fun addBlockUid(uid: Long) {
        Log.i(TAG, "addBlockUid: $uid")
        synchronized(mLock) {
            if (mList == null) {
                mList = loadListLocked()
            }
            mList!!.add(uid)
            saveListLocked(mList!!)
        }
    }

    fun removeBlockUid(uid: Long) {
        Log.i(TAG, "removeBlockUid: $uid")
        synchronized(mLock) {
            if (mList == null) {
                mList = loadListLocked()
            }
            if (mList!!.remove(uid)) {
                saveListLocked(mList!!)
            }
        }
    }

    private fun loadListLocked(): ArrayList<Long> {
        mPersist.getString(KEY_BLOCK_UID_LIST, null)?.let { str ->
            return ArrayList(str.split(",").filter { it.isNotEmpty() }.map { it.toLong() })
        }
        return ArrayList()
    }

    private fun saveListLocked(list: ArrayList<Long>) {
        mPersist.putString(KEY_BLOCK_UID_LIST, list.joinToString(","))
    }

    override suspend fun onSupervisorCommand(
        bot: Bot,
        si: SessionInfo,
        senderId: Long,
        serviceCmd: String,
        args: Array<String>
    ): String {
        when (serviceCmd) {
            "b", "block" -> {
                if (args.size != 1) {
                    return "Invalid arguments"
                }
                val uid = try {
                    args[0].toLong()
                } catch (e: NumberFormatException) {
                    return "Invalid arguments"
                }
                if (uid == 0L) {
                    return "Invalid arguments"
                }
                if (isBlocked(uid)) {
                    return "EAGAIN"
                } else {
                    addBlockUid(uid)
                    return "Success"
                }
            }
            "u", "ub", "unblock" -> {
                if (args.size != 1) {
                    return "Invalid arguments"
                }
                val uid = try {
                    args[0].toLong()
                } catch (e: NumberFormatException) {
                    return "Invalid arguments"
                }
                if (uid == 0L) {
                    return "Invalid arguments"
                }
                if (isBlocked(uid)) {
                    removeBlockUid(uid)
                    return "Success"
                } else {
                    return "ENOENT"
                }
            }
            "s", "st", "stat" -> {
                if (args.size != 1) {
                    return "Invalid arguments"
                }
                val uid = try {
                    args[0].toLong()
                } catch (e: NumberFormatException) {
                    return "Invalid arguments"
                }
                if (uid == 0L) {
                    return "Invalid arguments"
                }
                return if (isBlocked(uid)) {
                    "1"
                } else {
                    "0"
                }
            }
            else -> {
                return "ENOSYS"
            }
        }
    }

}
