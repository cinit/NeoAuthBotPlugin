package cc.ioctl.neoauth3bot.dat

import cc.ioctl.telebot.tdlib.RobotServer
import java.io.File
import java.io.RandomAccessFile

object AnointedManager {

    private val mLock = Any()

    @JvmStatic
    private fun swap64(v: Long): Long {
        val b0 = (v and 0xff).toInt()
        val b1 = ((v shr 8) and 0xff).toInt()
        val b2 = ((v shr 16) and 0xff).toInt()
        val b3 = ((v shr 24) and 0xff).toInt()
        val b4 = ((v shr 32) and 0xff).toInt()
        val b5 = ((v shr 40) and 0xff).toInt()
        val b6 = ((v shr 48) and 0xff).toInt()
        val b7 = ((v shr 56) and 0xff).toInt()
        return (b0.toLong() shl 56) or (b1.toLong() shl 48) or (b2.toLong() shl 40) or (b3.toLong() shl 32) or (b4.toLong() shl 24) or (b5.toLong() shl 16) or (b6.toLong() shl 8) or b7.toLong()
    }

    fun getAnointedStatus(gid: Long, uid: Long): Int {
        check(gid > 0) { "invalid gid: $gid" }
        check(uid > 0) { "invalid uid: $uid" }
        val groupBaseDir = File(RobotServer.instance.pluginsDir, "groups" + File.separator + "g_$gid")
        val anointedFile = File(groupBaseDir, "anointed.bin")
        if (!anointedFile.exists()) {
            return 0
        }
        synchronized(mLock) {
            val size = anointedFile.length().toInt()
            val count = size / 8
            if (size % 8 != 0) {
                error("invalid anointed file size: $size")
            }
            // binary search, little endian int64
            RandomAccessFile(anointedFile, "r").use { raf ->
                var l = 0
                var r = count - 1
                while (l <= r) {
                    val mid = (l + r) / 2
                    raf.seek(mid * 8L)
                    val v = swap64(raf.readLong())
                    if (v == uid) {
                        return 1
                    } else if (v < uid) {
                        l = mid + 1
                    } else {
                        r = mid - 1
                    }
                }
                return 0
            }
        }
    }

}
