package cc.ioctl.neoauth3bot.svc

import cc.ioctl.neoauth3bot.HypervisorCommandHandler
import cc.ioctl.telebot.tdlib.RobotServer
import cc.ioctl.telebot.tdlib.obj.Bot
import cc.ioctl.telebot.tdlib.obj.SessionInfo
import cc.ioctl.telebot.util.Log
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

object LogDatabaseService : HypervisorCommandHandler.HvCmdCallback {

    const val TAG = "LogDatabaseService"

    override suspend fun onSupervisorCommand(
        bot: Bot,
        si: SessionInfo,
        senderId: Long,
        serviceCmd: String,
        args: Array<String>
    ): String {
        return when (serviceCmd) {
            "sql" -> {
                val sql = args.joinToString(" ")
                execNoCheck(bot, sql)
            }

            else -> {
                "unimplemented subcmd: $serviceCmd"
            }
        }
    }

    data class LogEntry(
        val event: String,
        val group: Long,
        val actor: Long,
        val subject: Long,
        val time: Long,
        val extra: String?
    )

    private lateinit var mBaseDir: File
    private val mDatabases: MutableMap<Long, Connection> = mutableMapOf()
    private val mLock = Any()

    private fun init() {
        if (!::mBaseDir.isInitialized) {
            Class.forName("org.sqlite.JDBC")
            mBaseDir = File(RobotServer.instance.pluginsDir, "users")
            mBaseDir.mkdirs()
        }
    }

    private fun initForUser(uid: Long): Connection {
        require(uid > 0)
        synchronized(mLock) {
            init()
            if (!mDatabases.containsKey(uid)) {
                val dbFile = File(mBaseDir, "u_$uid.db")
                val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS channel_logs (evid INTEGER PRIMARY KEY AUTOINCREMENT, event TEXT NOT NULL, gid INTEGER, actor INTEGER, subject INTEGER, time INTEGER NOT NULL, extra TEXT)")
                }
                mDatabases[uid] = conn
                return conn
            } else {
                return mDatabases[uid]!!
            }
        }
    }

    fun getDatabase(bot: Bot): Connection {
        val uid = bot.userId
        require(uid > 0)
        return if (!mDatabases.containsKey(uid)) {
            initForUser(uid)
        } else {
            mDatabases[uid]!!
        }
    }

    fun addLog(bot: Bot, entry: LogEntry) {
        try {
            val conn = getDatabase(bot)
            conn.prepareStatement("INSERT INTO channel_logs (event, gid, actor, subject, time, extra) VALUES (?, ?, ?, ?, ?, ?)")
                .use { stmt ->
                    stmt.setString(1, entry.event)
                    stmt.setLong(2, entry.group)
                    stmt.setLong(3, entry.actor)
                    stmt.setLong(4, entry.subject)
                    stmt.setLong(5, entry.time)
                    stmt.setString(6, entry.extra)
                    stmt.executeUpdate()
                }
        } catch (e: Exception) {
            Log.e(TAG, "fail to execute sql", e)
        }
    }

    fun execNoCheck(bot: Bot, sql: String): String {
        val conn = getDatabase(bot)
        val sb = StringBuilder()
        val maxShowLines = 10
        val maxAllowedLines = 10000
        runCatching {
            checkSingleSqlStatement(sql)
            val TYPE_QUERY = 1
            val TYPE_UPDATE = 2
            val type: Int
            sql.lowercase().trimStart().let {
                type = when {
                    it.startsWith("insert ") || it.startsWith("update ") || it.startsWith("replace ")
                            || it.startsWith("delete ") -> {
                        TYPE_UPDATE
                    }
                    it.matches(Regex("[( ]*select .*")) -> {
                        TYPE_QUERY
                    }
                    else -> {
                        throw IllegalArgumentException("unknown sql type")
                    }
                }
            }
            if (type == TYPE_QUERY) {
                synchronized(conn) {
                    val startTime = System.nanoTime()
                    conn.createStatement().use { stmt ->
                        val rs = stmt.executeQuery(sql)
                        val md = rs.metaData
                        val colCount = md.columnCount
                        var i = 0
                        while (rs.next() && i < maxAllowedLines) {
                            if (i < maxShowLines) {
                                for (j in 1..colCount) {
                                    sb.append(rs.getString(j))
                                    sb.append(",")
                                }
                            }
                            i++
                        }
                        sb.append('\n')
                        if (i >= maxAllowedLines) {
                            sb.append("... more")
                        } else if (i > maxShowLines) {
                            sb.append("... ").append(i).append(" rows in all.")
                        } else {
                            sb.append(i).append(" row(s).")
                        }
                    }
                    val execTime = System.nanoTime() - startTime
                    sb.append('\n')
                    sb.append("Exec time: %.3f ms".format(execTime / 1000000.0))
                }
            } else {
                synchronized(conn) {
                    val startTime = System.nanoTime()
                    val affectedRows: Int
                    conn.createStatement().use { stmt ->
                        affectedRows = stmt.executeUpdate(sql)
                    }
                    val execTime = System.nanoTime() - startTime
                    sb.append('\n')
                    sb.append("%d rows (%.3f ms) affected.".format(affectedRows, execTime / 1e6f))
                }
            }
        }.onFailure {
            sb.append(it)
        }
        return sb.toString()
    }

    private fun getAffectedRowsLocked(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT changes()")
            rs.first()
            return rs.getInt(1)
        }
    }

    @Throws(IllegalArgumentException::class)
    private fun checkSingleSqlStatement(statement: String) {
        val sql = statement.trim().lowercase()
        if (sql.contains(";")) {
            throw IllegalArgumentException("only single sql statement is allowed")
        }
        if (sql.startsWith("drop ") || sql.startsWith("create ")
            || sql.startsWith("truncate ") || sql.startsWith("alter ")
        ) {
            throw IllegalArgumentException("unsafe sql statement")
        }
        if ((sql.startsWith("delete ") || sql.startsWith("update ")) && !sql.contains(" where ")) {
            throw IllegalArgumentException("attempt to execute update without where")
        }
    }

}
