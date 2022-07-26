package cc.ioctl.neoauth3bot.dat

import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.neoauth3bot.util.SdfUtils
import cc.ioctl.telebot.util.IoUtils
import cc.ioctl.telebot.util.Log
import com.vivimice.bgzfrandreader.RandomAccessBgzFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

object ChemDatabase {

    private const val TAG = "ChemDatabase"

    private lateinit var mCandidateList: IntArray
    private lateinit var mIndexByteBuffer: RandomAccessFile
    private lateinit var mDatabaseFile: File
    private var mDatabaseIsBgzf: Boolean = false
    private var mInitialized = false
    private val mLock = Any()

    @Throws(IOException::class)
    fun initialize(candidateFile: File, indexFile: File, sdfFile: File) {
        if (!candidateFile.exists()) {
            throw IOException("Candidate file does not exist: ${candidateFile.absolutePath}")
        }
        if (!indexFile.exists()) {
            throw IOException("Index file does not exist: ${indexFile.absolutePath}")
        }
        if (!sdfFile.exists()) {
            throw IOException("Database file does not exist: ${sdfFile.absolutePath}")
        }
        synchronized(mLock) {
            FileInputStream(candidateFile).use {
                val arrlen = BinaryUtils.readLe32(it)
                val array = IntArray(arrlen)
                for (i in 0 until arrlen) {
                    array[i] = BinaryUtils.readLe32(it)
                }
                mCandidateList = array
            }
            mIndexByteBuffer = RandomAccessFile(indexFile, "r")
            mDatabaseIsBgzf = SdfUtils.isBgzFile(sdfFile)
            mDatabaseFile = sdfFile
            mInitialized = true
        }
    }

    fun nextRandomCid(): Int {
        ensureInitialized()
        val r = Random.nextInt(0, mCandidateList.size)
        return mCandidateList[r]
    }

    @Throws(IOException::class)
    fun loadChemTableString(cid: Int): String? {
        ensureInitialized()
        val indexItem = ChemTableIndex(ByteArray(ChemTableIndex.OBJECT_SIZE)).also { idx ->
            synchronized(mLock) {
                mIndexByteBuffer.seek((cid * ChemTableIndex.OBJECT_SIZE).toLong())
                IoUtils.readExact(mIndexByteBuffer, idx.ptrBytes, 0, ChemTableIndex.OBJECT_SIZE)
            }
        }
        if (indexItem.size == 0 || indexItem.cid == 0) {
            Log.w(TAG, "error: Compound ID $cid not found in index file")
            return null
        }
        val sdfString: String
        if (mDatabaseIsBgzf) {
            sdfString = RandomAccessBgzFile(mDatabaseFile).use {
                it.seek(indexItem.offset)
                val buf = ByteArray(indexItem.size)
                var remaining = indexItem.size
                var read = 0
                while (remaining > 0) {
                    read = it.read(buf, read, remaining)
                    remaining -= read
                }
                String(buf)
            }
        } else {
            sdfString = FileInputStream(mDatabaseFile).use {
                IoUtils.skipExact(it, indexItem.offset)
                val buf = ByteArray(indexItem.size)
                IoUtils.readExact(it, buf, 0, indexItem.size)
                String(buf)
            }
        }
        return sdfString
    }

    private fun ensureInitialized() {
        if (!mInitialized) {
            throw IllegalStateException("Not initialized")
        }
    }

}
