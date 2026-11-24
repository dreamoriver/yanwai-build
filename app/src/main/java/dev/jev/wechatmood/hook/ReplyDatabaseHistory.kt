package dev.jev.wechatmood.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.PendingReply
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.AnalysisAccountIdentity
import dev.jev.wechatmood.core.AnalysisAccountScopes

/** Reuses WeChat's open WCDB handle. Never opens files, obtains keys, writes, or closes its database. */
object ReplyDatabaseHistory {
    /** 每个库是不是主库；弱键，库对象回收后一起消失。命中路径只碰这张表，不占全局监视器。 */
    private val known: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val handles = ArrayList<WeakReference<Any>>()
    private val moduleQuery = ThreadLocal<Boolean>()
    private val accountWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "yanwai-account") }
    private val accountScopes = AnalysisAccountScopes({ accountWorker.execute(it) }, ::findAnalysisAccount)
    fun accountScope(input: AnalysisInput): String = accountScopes.scope(input)
    fun pendingAccountScope(): String = accountScopes.pendingScope()
    fun resetAccountScope() = accountScopes.reset()
    private fun scope(db: Any): String? = (db.javaClass.getMethod("getPath").invoke(db) as? String)
        ?.replace('\\', '/')?.takeIf { it.endsWith("/EnMicroMsg.db") }?.let { AnalysisCacheKey.digest(it) }

    /**
     * 只保留与当前账号匹配的库。
     * 切账号后旧库可能还开着，不过滤就会把另一个账号的聊天算进统计、或把别人的消息当依据。
     * 账号确认不了时返回空，调用方退回当前可见页面。
     */
    internal fun databasesForScope(databases: List<Any>, accountScope: String?): List<Any> {
        if (accountScope == null || !accountScope.matches(Regex("[0-9a-f]{64}"))) return emptyList()
        return databases.filter { runCatching { scope(it) }.getOrNull() == accountScope }
    }

    fun install(loader: ClassLoader) {
        var hooks = 0
        for (name in listOf("com.tencent.wcdb.compat.SQLiteDatabase", "com.tencent.wcdb.database.SQLiteDatabase")) {
            runCatching {
                val clazz = Class.forName(name, false, loader)
                for (method in clazz.declaredMethods.filter { it.name == "rawQuery" || it.name == "rawQueryWithFactory" }) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.thisObject?.let { db -> runCatching { observe(db) } }
                        }
                    })
                    hooks++
                }
            }.onFailure { MoodLog.w("REPLY_HISTORY_HOOK_UNAVAILABLE ${it.javaClass.simpleName}") }
        }
        MoodLog.i("REPLY_HISTORY_HOOKS count=$hooks")
    }

    /**
     * 这个 hook 挂在每一次 rawQuery 上，所以命中缓存的热路径不能抢锁：
     * 旧的 @Synchronized 让微信每个查询都排同一个监视器，锁里还在做反射。
     * 现在只有增删 handles 时才进 synchronized，反射与重试都在锁外。
     */
    private fun observe(db: Any) {
        if (moduleQuery.get() == true) return
        val main = known[db] ?: isMainDatabase(db).also { known[db] = it }
        if (!main) return
        val firstObservation = synchronized(this) {
            val seen = handles.none { it.get() === db }
            handles.removeAll { it.get() == null || it.get() === db }
            handles.add(0, WeakReference(db))
            while (handles.size > 4) handles.removeAt(handles.lastIndex)
            seen
        }
        if (firstObservation) accountScopes.retryUnverified()
    }

    /** 主库文件名判断；拿不到 path 时照旧往外抛，由 install 的 runCatching 记一次。 */
    private fun isMainDatabase(db: Any): Boolean =
        (db.javaClass.getMethod("getPath").invoke(db) as? String)
            ?.replace('\\', '/')?.substringAfterLast('/') == "EnMicroMsg.db"

    private fun query(db: Any, sql: String, args: Array<String>): Cursor {
        check(db.javaClass.getMethod("isOpen").invoke(db) == true)
        val method = db.javaClass.methods.first { it.name == "rawQuery" && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java && it.parameterTypes[1].isAssignableFrom(Array<String>::class.java) }
        val previous = moduleQuery.get()
        moduleQuery.set(true)
        return try { method.invoke(db, sql, args) as? Cursor ?: error("Unsupported history cursor") }
        finally { moduleQuery.set(previous) }
    }

    /** Verify the visible target against its account before reusing or saving a durable result. */
    private fun findAnalysisAccount(input: AnalysisInput): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        return databases.mapNotNull { db -> runCatching {
            val account = scope(db) ?: return@runCatching null
            query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? LIMIT 1",
                arrayOf(input.talker, input.messageId.toString())).use {
                account.takeIf { _ -> it.moveToFirst() && AnalysisAccountIdentity.matches(input, metadata(it)) }
            }
        }.getOrNull() }.distinct().singleOrNull()
    }
    fun matchesAnalysisAccount(input: AnalysisInput): Boolean =
        input.accountScope.matches(Regex("[0-9a-f]{64}")) && findAnalysisAccount(input) == input.accountScope

    /** Worker-thread only. A visible anchor must match exactly one account, including outgoing messages. */
    fun replyAccount(page: ReplyContext): String? {
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        val sources = databases.mapNotNull { db -> runCatching {
            val account = scope(db) ?: return@runCatching null
            account to ReplyHistoryQuery { sql, args -> query(db, sql, args).use {
                if (it.moveToFirst()) listOf(metadata(it)) else emptyList()
            } }
        }.getOrNull() }
        return dev.jev.wechatmood.reply.ReplyAccountIdentity.resolve(page, sources)
    }

    private fun metadata(cursor: Cursor): MessageMetadata {
        fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name)).orEmpty()
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return MessageMetadata(number("type").toInt(), number("isSend").toInt(), string("content"),
            string("talker"), number("msgId"), number("createTime"), string("imgPath"), number("msgSvrId"))
    }

    /** A voice is tied to the exact open account database before reading text or invoking the host. */
    internal class VerifiedVoice(val db: Any, val source: VoiceSource, val message: Any?)

    internal fun voiceRecord(source: VoiceSource, messageClass: Class<*>?): VerifiedVoice? {
        if (source.id <= 0 || source.time <= 0 || source.fileToken.isBlank()) return null
        val databases = synchronized(this) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            val verified = runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? AND type = 34 LIMIT 1",
                    arrayOf(source.talker, source.id.toString())).use { cursor ->
                    if (!cursor.moveToFirst() || metadata(cursor).voiceSource() != source) return@use null
                    val instance = messageClass?.getDeclaredConstructor()?.apply { isAccessible = true }?.newInstance()
                    instance?.let { messageClass.getMethod("convertFrom", Cursor::class.java).invoke(it, cursor) }
                    VerifiedVoice(db, source, instance)
                }
            }.getOrNull()
            if (verified != null) return verified
        }
        return null
    }

    internal fun voiceStillMatches(record: VerifiedVoice): Boolean = runCatching {
        query(record.db, "SELECT * FROM message WHERE talker = ? AND msgId = ? AND type = 34 LIMIT 1",
            arrayOf(record.source.talker, record.source.id.toString())).use {
            it.moveToFirst() && metadata(it).voiceSource() == record.source
        }
    }.getOrDefault(false)

    internal fun voiceText(record: VerifiedVoice): String? = runCatching {
        if (!voiceStillMatches(record)) return@runCatching null
        voiceTextUnchecked(record)
    }.getOrNull()

    /** 调用方已经确认过语音仍然有效时用这个，避免每轮轮询多两次全表查询。 */
    internal fun voiceTextUnchecked(record: VerifiedVoice): String? = runCatching {
        query(record.db, "SELECT content FROM VoiceTransText WHERE msgId = ? AND cmsgId = ? LIMIT 1",
            arrayOf(record.source.id.toString(), record.source.fileToken)).use {
            if (it.moveToFirst()) it.getString(0)?.trim()?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    /** 按时间范围取统计用消息（不限已加载页），用于「今天 / 最近 N 小时」。
     *  [untilMillis] 不为空时只取早于它的消息，用来单独数一遍「上一周期」而不混进当前窗口。 */
    suspend fun statMessages(talker: String, sinceMillis: Long, limit: Int,
        accountScope: String? = null, untilMillis: Long? = null): List<MessageMetadata> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MessageMetadata>()
        // 账号无法确认时这里为空，调用方改用当前可见页面的数据。
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        var since = sinceMillis
        for (db in databases) {
            // 微信不同版本 createTime 有秒也有毫秒，先看一条样本再决定。
            val sample = runCatching {
                var value = 0L
                query(db, "SELECT createTime FROM message WHERE talker = ? LIMIT 1", arrayOf(talker)).use { cursor ->
                    if (cursor.moveToFirst()) value = cursor.getLong(0)
                }
                value
            }.getOrDefault(0L)
            since = if (sample > 1_000_000_000_000L) sinceMillis else sinceMillis / 1000
            // 上界跟下界一起换算，两个边界必须同一口径，否则会漏掉一半窗口。
            val until = untilMillis?.let { if (sample > 1_000_000_000_000L) it else it / 1000 }
            runCatching {
                val sql = "SELECT msgId, type, isSend, content, talker, createTime, imgPath, msgSvrId FROM message " +
                    "WHERE talker = ? AND createTime >= ?" + (if (until != null) " AND createTime < ?" else "") +
                    " ORDER BY createTime DESC LIMIT ?"
                val args = mutableListOf(talker, since.toString())
                if (until != null) args += until.toString()
                args += limit.coerceAtLeast(1).toString()
                query(db, sql, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
        }
        dev.jev.wechatmood.core.MoodLog.i(
            "STATS_RANGE talker=${talker.hashCode()} since=$since count=${out.size} " +
                "first=${out.lastOrNull()?.createdAt} last=${out.firstOrNull()?.createdAt}")
        out.reversed()
    }

    /**
     * 每个会话最后一条消息的时间，用于「谁很久没联系」。
     *
     * 只查档案目录里已经记下的 talker，不做全表 GROUP BY：老用户的 message 表百万行，
     * 全表聚合会卡住聊天。结果统一归一化成毫秒，查不到的不出现在返回值里。
     */
    suspend fun lastMessageTimes(talkers: List<String>, accountScope: String? = null): Map<String, Long> =
        withContext(Dispatchers.IO) {
            val wanted = talkers.filter { it.isNotBlank() }.distinct().take(200)
            if (wanted.isEmpty()) return@withContext emptyMap()
            val out = HashMap<String, Long>()
            val databases = databasesForScope(
                synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
            val placeholders = wanted.joinToString(",") { "?" }
            for (db in databases) {
                runCatching {
                    query(db, "SELECT talker, MAX(createTime) FROM message WHERE talker IN ($placeholders) " +
                        "GROUP BY talker", wanted.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) {
                            val talker = cursor.getString(0) ?: continue
                            val raw = cursor.getLong(1)
                            val ms = if (raw > 1_000_000_000_000L) raw else raw * 1000
                            if (ms > 0 && ms > (out[talker] ?: 0L)) out[talker] = ms
                        }
                    }
                }
            }
            dev.jev.wechatmood.core.MoodLog.i(
                "RELATION_TEMP_LAST asked=" + wanted.size + " found=" + out.size)
            out
        }

    /** 档案条目回指依据：按 localId 取一条消息，只读，不改游标以外的任何状态。 */
    suspend fun messageById(talker: String, msgId: Long,
        accountScope: String? = null): MessageMetadata? = withContext(Dispatchers.IO) {
        if (msgId <= 0) return@withContext null
        // 依据消息也只在当前账号的库里找，不能拿另一个账号的同 msgId 顶上。
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        for (db in databases) {
            val found = runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND msgId = ? LIMIT 1",
                    arrayOf(talker, msgId.toString())).use { if (it.moveToFirst()) metadata(it) else null }
            }.getOrNull()
            if (found != null) return@withContext found
        }
        null
    }

    /**
     * 依据消息前后的文字消息，用来给档案里的「依据」补上下文。
     * 只按本地 msgId 排序取最近的一条，查不到就返回空，不猜。
     */
    suspend fun nearbyText(talker: String, msgId: Long, accountScope: String? = null,
        limit: Int = 2): List<MessageMetadata> = withContext(Dispatchers.IO) {
        if (msgId <= 0) return@withContext emptyList()
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val out = mutableListOf<MessageMetadata>()
        for (db in databases) {
            runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND type IN (1, 34) AND msgId < ? " +
                    "ORDER BY msgId DESC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND type IN (1, 34) AND msgId > ? " +
                    "ORDER BY msgId ASC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            if (out.isNotEmpty()) break
        }
        out.sortedBy { it.createdAt }
    }

    /**
     * 依据弹窗用：微信已经保存的语音转写。
     *
     * 只读 VoiceTransText，不调用微信原生转写，也不等待；查不到就当作没有，
     * 界面会写「没有已保存的转写」，而不是把 [dev.jev.wechatmood.voice.VoiceText.WAITING] 当成正文。
     */
    suspend fun voiceTranscripts(talker: String, msgIds: List<Long>,
        accountScope: String? = null): Map<Long, String> = withContext(Dispatchers.IO) {
        if (msgIds.isEmpty()) return@withContext emptyMap()
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val out = LinkedHashMap<Long, String>()
        for (db in databases) {
            for (id in msgIds) {
                if (id <= 0 || out.containsKey(id)) continue
                runCatching {
                    query(db, "SELECT content FROM VoiceTransText WHERE msgId = ? LIMIT 1",
                        arrayOf(id.toString())).use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)?.let { out[id] = it }
                        }
                    }
                }
            }
            if (out.size == msgIds.size) break
        }
        out
    }

    /**
     * 诊断：一条消息在 message 表里的非空列（值截断）。
     * 用来确认「群里的表情/图片/视频」到底有没有存发送者，以及存在哪一列。
     */
    suspend fun messageRow(talker: String, msgId: Long, accountScope: String? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            val databases = databasesForScope(
                synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
            for (db in databases) {
                val row = runCatching {
                    query(db, "SELECT * FROM message WHERE msgId = ? AND talker = ? LIMIT 1",
                        arrayOf(msgId.toString(), talker)).use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val out = LinkedHashMap<String, String>()
                        for (index in 0 until cursor.columnCount) {
                            val value = runCatching { cursor.getString(index) }.getOrNull() ?: continue
                            if (value.isBlank()) continue
                            out[cursor.getColumnName(index)] = value.take(48)
                        }
                        out
                    }
                }.getOrNull()
                if (row != null) return@withContext row
            }
            emptyMap()
        }

    /** 诊断：列出主库的表名，便于确认群昵称实际存在哪张表。 */
    suspend fun atNames(talker: String, sinceMillis: Long, accountScope: String? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (!talker.endsWith("@chatroom")) return@withContext emptyMap()
            val databases = databasesForScope(
                synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
            val out = LinkedHashMap<String, String>()
            for (db in databases) {
                val sample = runCatching {
                    var value = 0L
                    query(db, "SELECT createTime FROM message WHERE talker = ? LIMIT 1", arrayOf(talker)).use { cursor ->
                        if (cursor.moveToFirst()) value = cursor.getLong(0)
                    }
                    value
                }.getOrDefault(0L)
                val since = if (sample > 1_000_000_000_000L) sinceMillis else sinceMillis / 1000
                runCatching {
                    query(db, "SELECT content FROM message WHERE talker = ? AND type = 1 AND content LIKE '%atuserlist%' " +
                        "AND createTime >= ? ORDER BY createTime DESC LIMIT 500",
                        arrayOf(talker, since.toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            val content = runCatching { cursor.getString(0) }.getOrNull().orEmpty()
                            val list = dev.jev.wechatmood.reply.GroupMemberList.field(content, "atuserlist")
                                ?.split(",")?.map { it.trim() }?.filter { it.startsWith("wxid") } ?: continue
                            if (list.size != 1) continue
                            val mentions = Regex("@([^\\s\\u2005@]{1,32})[\\u2005\\s]").findAll(content).toList()
                            if (mentions.size != 1) continue
                            val name = dev.jev.wechatmood.core.TextClean.name(mentions.first().groupValues[1])
                            if (name.isNotEmpty() && list[0] !in out) out[list[0]] = name
                        }
                    }
                }
            }
            if (out.isNotEmpty()) MoodLog.i("STATS_AT_NAMES count=" + out.size)
            out
        }

    /** 全库搜索单独一段跑：慢，但不该拖住面板和上一步的诊断。 */
    suspend fun quotedNames(talker: String, sinceMillis: Long, accountScope: String? = null): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (!talker.endsWith("@chatroom")) return@withContext emptyMap()
            val databases = databasesForScope(
                synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
            val out = LinkedHashMap<String, String>()
            for (db in databases) {
                val sample = runCatching {
                    var value = 0L
                    query(db, "SELECT createTime FROM message WHERE talker = ? LIMIT 1", arrayOf(talker)).use { cursor ->
                        if (cursor.moveToFirst()) value = cursor.getLong(0)
                    }
                    value
                }.getOrDefault(0L)
                val since = if (sample > 1_000_000_000_000L) sinceMillis else sinceMillis / 1000
                runCatching {
                    query(db, "SELECT content FROM message WHERE talker = ? AND (type & 65535) = 49 " +
                        "AND createTime >= ? ORDER BY createTime DESC LIMIT 400",
                        arrayOf(talker, since.toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            val content = runCatching { cursor.getString(0) }.getOrNull().orEmpty()
                            val block = Regex("<refermsg>(.*?)</refermsg>", RegexOption.DOT_MATCHES_ALL)
                                .find(content)?.groupValues?.get(1) ?: continue
                            val user = dev.jev.wechatmood.reply.GroupMemberList.field(block, "chatusr")
                                ?: dev.jev.wechatmood.reply.GroupMemberList.field(block, "fromusr") ?: continue
                            val name = dev.jev.wechatmood.reply.GroupMemberList.field(block, "displayname")
                                ?: continue
                            if (user !in out) out[user] = name
                        }
                    }
                }
            }
            if (out.isNotEmpty()) MoodLog.i("STATS_QUOTED_NAMES count=" + out.size)
            out
        }

    /** 单库表名列表，供昵称兜底扫描用。 */
    private fun tableNamesOf(db: Any): List<String> = runCatching {
        query(db, "SELECT name FROM sqlite_master WHERE type='table'", emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) } }
        }
    }.getOrDefault(emptyList())

    /**
     * 每个库的列名缓存；库对象被回收时一起回收。
     * 统计、昵称、待回各走各的 IO 线程，裸 WeakHashMap 并发写会在扩容时损坏结构；
     * 外层同步只保护弱表本身，内层用 ConcurrentHashMap，读命中不进锁。
     */
    private val columnCache: MutableMap<Any, MutableMap<String, List<String>>> =
        Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, List<String>>>())

    /** 最近一次昵称解析的分层命中数，给界面和诊断用。 */
    @Volatile var lastLayers: Map<String, Int> = emptyMap()
        private set

    /**
     * 群成员昵称。依次尝试：群名片（chatroom.memberlist）→ rcontact（备注 / 昵称）
     * → rcontact.alias（群消息里 speaker 有时是微信号）→ stranger → 兜底扫描带用户名列的联系人类表。
     * 每层只补还没查到的名字，前三层失败也不影响后面的来源。
     */
    suspend fun displayNames(usernames: List<String>, chatroom: String? = null,
        accountScope: String? = null): Map<String, String> = withContext(Dispatchers.IO) {
        val wanted = usernames.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return@withContext emptyMap()
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val result = LinkedHashMap<String, String>()
        var memberlist = 0
        var contact = 0
        var byAlias = 0
        var stranger = 0
        var scanned = 0
        if (chatroom != null && chatroom.endsWith("@chatroom")) {
            for (db in databases) {
                val parsed = runCatching { chatroomMemberNames(db, chatroom) }
                    .onFailure { MoodLog.w("STATS_MEMBERLIST_FAILED ${it.javaClass.simpleName} ${it.message}") }
                    .getOrDefault(emptyMap())
                if (parsed.isEmpty()) continue
                parsed.forEach { (user, name) -> if (user in wanted && user !in result) result[user] = name }
                if (wanted.all { it in result }) break
            }
            memberlist = result.size
            MoodLog.i("STATS_MEMBERLIST wanted=${wanted.size} members=$memberlist")
        }
        if (result.size < wanted.size) {
            for (db in databases) contact += fillFromTable(db, "rcontact", wanted, result, CONTACT_NAMES)
        }
        if (result.size < wanted.size) {
            for (db in databases) byAlias += fillByAlias(db, "rcontact", wanted, result)
        }
        if (result.size < wanted.size) {
            for (db in databases) stranger += fillFromTable(db, "stranger", wanted, result, STRANGER_NAMES)
        }
        if (result.size < wanted.size) {
            for (db in databases) scanned += fillFromAnyTable(db, wanted, result)
        }
        lastLayers = linkedMapOf(
            "memberlist" to memberlist, "contact" to contact, "alias" to byAlias,
            "stranger" to stranger, "scanned" to scanned, "wanted" to wanted.size,
            "found" to result.size,
        )
        MoodLog.i("STATS_NAMES wanted=${wanted.size} found=${result.size} memberlist=$memberlist " +
            "contact=$contact alias=$byAlias stranger=$stranger scanned=$scanned dbs=${databases.size}")
        // 还没查到的：把「库里到底有什么」写清楚，别再靠猜是昵称本来为空还是解析没命中。
        if (result.size < wanted.size && chatroom != null) {
            val missing = wanted.filter { it !in result }
            MoodLog.w("STATS_NAMES_MISS n=" + missing.size + " ids=" + missing.take(5))
            for (id in missing.take(3)) {
                runCatching { diagnoseName(databases, chatroom, id) }
                    .onFailure { MoodLog.w("STATS_NAME_DIAG_FAILED " + it.javaClass.simpleName) }
            }
        }
        result
    }

    /** 诊断：某个没查到名字的人，在 roomdata 和各联系人表里到底是什么状态。只读，值截断。 */
    private fun diagnoseName(databases: List<Any>, chatroom: String, username: String) {
        for (db in databases) {
            val blob = runCatching {
                query(db, "SELECT roomdata FROM chatroom WHERE chatroomname = ? LIMIT 1",
                    arrayOf(chatroom)).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
            }.getOrNull()
            val ids = dev.jev.wechatmood.reply.GroupRoomData.memberIds(blob)
            val names = dev.jev.wechatmood.reply.GroupRoomData.parse(blob)
            MoodLog.i("STATS_NAME_DIAG_ROOMDATA id=" + username + " inMembers=" + (username in ids) +
                " withName=" + (names[username]?.length ?: 0))
            val fields = dev.jev.wechatmood.reply.GroupRoomData.memberFields(blob, username)
            MoodLog.i("STATS_NAME_DIAG_FIELDS id=" + username + " " + fields.entries.joinToString(";") { entry ->
                entry.key.toString() + "=" + entry.value.joinToString("|") { it.take(16) }
            })
            for (table in listOf("rcontact", "stranger", "contact")) {
                MoodLog.i("STATS_NAME_DIAG_ROW table=" + table + " id=" + username + " " +
                    rowSummary(db, table, username))
            }
        }
    }

    /** 一行里有哪些非空字段，值截断到 24 字；表 / 列不存在时明说。 */
    private fun rowSummary(db: Any, table: String, username: String): String {
        val columns = tableColumns(db, table)
        if (columns.isEmpty()) return "no_table"
        val userColumn = USER_COLUMNS.firstOrNull { it in columns } ?: return "no_user_column"
        return runCatching {
            query(db, "SELECT * FROM " + table + " WHERE " + userColumn + " = ? LIMIT 1",
                arrayOf(username)).use { cursor ->
                if (!cursor.moveToFirst()) return@use "missing"
                (0 until cursor.columnCount).mapNotNull { index ->
                    val value = runCatching { cursor.getString(index) }.getOrNull()
                    if (value.isNullOrBlank()) null else cursor.getColumnName(index) + "=" + value.take(24)
                }.joinToString(" ")
            }
        }.getOrElse { "query_failed:" + it.javaClass.simpleName }
    }

    /** 昵称类字段：这里的「null」可能就是用户自己取的名字。 */
    private val NICKNAME_COLUMNS = setOf("nickname", "nickName", "displayName", "displayname",
        "groupNickname", "group_nickname", "nick_name")
    private val PLACEHOLDER_NAMES = setOf("null", "uinull")

    /** rcontact 的显示优先级：备注 > 备注字段别名 > 昵称。 */
    private val CONTACT_NAMES = listOf("conRemark", "remark", "nickname", "nickName", "displayName")
    private val STRANGER_NAMES = listOf("nickname", "nickName", "remark", "displayName")
    private val USER_COLUMNS = listOf("username", "userName", "user_name")

    /** 一次批量查一张表：以前逐个 wxid 拼 OR，一条列名对不上整批就全失败。 */
    private fun fillFromTable(db: Any, table: String, wanted: List<String>,
        result: MutableMap<String, String>, preferred: List<String>): Int {
        val columns = tableColumns(db, table)
        val userColumn = USER_COLUMNS.firstOrNull { it in columns } ?: return 0
        val nameColumns = preferred.filter { it in columns && it != userColumn }
        if (nameColumns.isEmpty()) {
            MoodLog.w("STATS_NAMES_NO_COLUMN table=$table columns=${columns.take(12)}")
            return 0
        }
        var filled = 0
        for (chunk in wanted.filter { it !in result }.chunked(80)) {
            runCatching {
                query(db, "SELECT $userColumn, ${nameColumns.joinToString(",")} FROM $table " +
                    "WHERE $userColumn IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val user = cursor.getString(0)?.trim().orEmpty()
                        if (user.isEmpty() || user in result) continue
                        val name = bestName(cursor, 1, nameColumns) ?: continue
                        result[user] = name
                        filled++
                    }
                }
            }.onFailure { MoodLog.w("STATS_NAMES_QUERY_FAILED table=$table ${it.javaClass.simpleName} ${it.message}") }
        }
        return filled
    }

    /** 群消息里的 speaker 有时是微信号（alias），拿 alias 再认一次。 */
    private fun fillByAlias(db: Any, table: String, wanted: List<String>,
        result: MutableMap<String, String>): Int {
        val columns = tableColumns(db, table)
        val userColumn = USER_COLUMNS.firstOrNull { it in columns } ?: return 0
        if ("alias" !in columns) return 0
        val nameColumns = listOf("conRemark", "remark", "nickname", "nickName").filter { it in columns }
        if (nameColumns.isEmpty()) return 0
        var filled = 0
        for (chunk in wanted.filter { it !in result }.chunked(80)) {
            runCatching {
                query(db, "SELECT alias, ${nameColumns.joinToString(",")} FROM $table " +
                    "WHERE alias IN (${chunk.joinToString(",") { "?" }})", chunk.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val alias = cursor.getString(0)?.trim().orEmpty()
                        if (alias.isEmpty() || alias !in chunk || alias in result) continue
                        val name = bestName(cursor, 1, nameColumns) ?: continue
                        result[alias] = name
                        filled++
                    }
                }
            }.onFailure { MoodLog.w("STATS_ALIAS_QUERY_FAILED ${it.javaClass.simpleName} ${it.message}") }
        }
        return filled
    }

    /**
     * 版本兜底：不同微信版本把群成员资料搬到过不同的表。
     * 只碰名字里带 contact / nick / member / user 的表，且必须同时有用户名列和昵称列，最多 8 张。
     */
    private fun fillFromAnyTable(db: Any, wanted: List<String>,
        result: MutableMap<String, String>): Int {
        val tables = runCatching { tableNamesOf(db) }.getOrDefault(emptyList())
        var filled = 0
        val tries = tables.filter { table ->
            val lower = table.lowercase()
            lower != "rcontact" && lower != "stranger" && lower != "chatroom" &&
                (lower.contains("contact") || lower.contains("nick") || lower.contains("member") ||
                    lower.contains("user") || lower.contains("profile"))
        }.take(8)
        for (table in tries) {
            val columns = tableColumns(db, table)
            if (USER_COLUMNS.none { it in columns }) continue
            if (CONTACT_NAMES.none { it in columns }) continue
            val before = filled
            filled += fillFromTable(db, table, wanted, result, CONTACT_NAMES)
            if (filled > before) MoodLog.i("STATS_NAMES_SCAN table=$table hit=${filled - before}")
        }
        return filled
    }

    /** 列名探一次就缓存：不同微信用不同版本，靠 PRAGMA，读不到再退回 SELECT * LIMIT 1。 */
    private fun tableColumns(db: Any, table: String): List<String> {
        columnCache[db]?.get(table)?.let { return it }
        var columns = runCatching {
            query(db, "PRAGMA table_info($table)", emptyArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) cursor.getString(1)?.let { add(it) } }
            }
        }.getOrDefault(emptyList())
        if (columns.isEmpty()) {
            columns = runCatching {
                query(db, "SELECT * FROM $table LIMIT 1", emptyArray()).use { it.columnNames.toList() }
            }.getOrDefault(emptyList())
        }
        // 探测失败时别写空表：写进去之后这个库的列名就永远是空的了。
        if (columns.isNotEmpty()) {
            columnCache.getOrPut(db) { java.util.concurrent.ConcurrentHashMap() }[table] = columns
        }
        return columns
    }

    /**
     * 取第一个能用的名字。
     *
     * 「null」「uinull」曾经一律当成没名字，可昵称是用户自己取的：有人就叫 null，
     * 微信上明明白白显示着，却被我们归到「未获取昵称」。所以按字段区分：
     * 昵称类字段的 null 当真名，备注类字段的 null 仍算占位。
     */
    private fun bestName(cursor: Cursor, offset: Int, columns: List<String>): String? =
        columns.indices.asSequence()
            .mapNotNull { index ->
                runCatching { cursor.getString(offset + index) }.getOrNull()?.let { columns[index] to it }
            }
            .map { (column, raw) -> column to dev.jev.wechatmood.core.TextClean.name(raw) }
            .firstOrNull { (column, value) ->
                value.isNotEmpty() && (column in NICKNAME_COLUMNS || value.lowercase() !in PLACEHOLDER_NAMES)
            }?.second

    /** 群名片原文：老版本是 XML，新版本可能换成别的编码，原样取出来交给上层判断。 */
    private fun rawMemberlist(db: Any, chatroom: String): String {
        if (chatroom.isBlank()) return ""
        var xml = ""
        runCatching {
            query(db, "SELECT memberlist FROM chatroom WHERE chatroomname = ? LIMIT 1",
                arrayOf(chatroom)).use { cursor -> if (cursor.moveToFirst()) xml = cursor.getString(0).orEmpty() }
        }.onFailure { MoodLog.w("STATS_CHATROOM_QUERY_FAILED ${it.javaClass.simpleName} ${it.message}") }
        if (xml.isBlank()) {
            // 列名对不上时退回整行，找那个装着成员列表的列。
            runCatching {
                query(db, "SELECT * FROM chatroom WHERE chatroomname = ? LIMIT 1",
                    arrayOf(chatroom)).use { cursor ->
                    if (cursor.moveToFirst()) {
                        for (index in 0 until cursor.columnCount) {
                            val value = runCatching { cursor.getString(index) }.getOrNull().orEmpty()
                            if (value.contains("<member") || value.contains("wxid")) { xml = value; break }
                        }
                    }
                }
            }
        }
        return xml
    }

    /**
     * 群昵称：先读 `chatroom.roomdata`，老版本再退回 `memberlist` 的 XML。
     *
     * 新版微信（实测 8.0.80）把 memberlist 变成了 `wxid;wxid;…` 的纯 id 串，昵称只在
     * roomdata 这个 protobuf 列里；只认 XML 时这一层永远空，非好友群成员就全成了
     * 「未获取昵称」。
     */
    private fun chatroomMemberNames(db: Any, chatroom: String): Map<String, String> {
        val fromRoom = roomDataNames(db, chatroom)
        if (fromRoom.isNotEmpty()) return fromRoom
        val xml = rawMemberlist(db, chatroom)
        val parsed = dev.jev.wechatmood.reply.GroupMemberList.parse(xml)
        if (parsed.isNotEmpty()) {
            MoodLog.i("STATS_CHATROOM members=${parsed.size} xml=${xml.length}")
        } else {
            // 解析不出人时也要留痕：以前这里一声不响，日志里只剩 STATS_NAMES members=0，
            // 分不清是表里没有这行，还是 XML 换了格式。
            MoodLog.w("STATS_CHATROOM_EMPTY xml=${xml.length} members=${xml.contains("<member")} room=${chatroom.hashCode()}")
        }
        return parsed
    }

    /**
     * `chatroom.roomdata`（protobuf）里的群昵称。
     * 只读，解析失败或列不存在都返回空，让上层继续走 rcontact 兜底。
     */
    private fun roomDataNames(db: Any, chatroom: String): Map<String, String> {
        val columns = tableColumns(db, "chatroom")
        if ("roomdata" !in columns) {
            MoodLog.w("STATS_ROOMDATA_NO_COLUMN columns=${columns.take(12)}")
            return emptyMap()
        }
        var names: Map<String, String> = emptyMap()
        runCatching {
            query(db, "SELECT roomdata FROM chatroom WHERE chatroomname = ? LIMIT 1",
                arrayOf(chatroom)).use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val blob = runCatching { cursor.getBlob(0) }.getOrNull()
                names = dev.jev.wechatmood.reply.GroupRoomData.parse(blob)
                MoodLog.i("STATS_ROOMDATA blob=${blob?.size ?: 0} members=${names.size}")
            }
        }.onFailure { MoodLog.w("STATS_ROOMDATA_FAILED ${it.javaClass.simpleName} ${it.message}") }
        return names
    }

    /**
     * 通知标题用的显示名（备注优先，其次昵称），不按账号筛库。
     * 只服务到期提醒的标题，不参与分析、不进缓存、日志里也不记内容。
     */
    suspend fun contactNames(wanted: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val names = wanted.filter { it.isNotBlank() && it.length <= 256 }.distinct()
        if (names.isEmpty()) return@withContext emptyMap()
        val result = LinkedHashMap<String, String>()
        val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            if (result.size >= names.size) break
            runCatching { fillFromTable(db, "rcontact", names, result, CONTACT_NAMES) }
            if (result.size >= names.size) break
            runCatching { fillByAlias(db, "rcontact", names, result) }
        }
        result
    }

    /**
     * 群名：rcontact 通常拿不到 @chatroom 的昵称，单独查 chatroom 表。
     * 表或字段缺失时返回空，调用方回退到「待办」而不是编一个名字。
     */
    suspend fun groupNames(wanted: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val names = wanted.filter { it.endsWith("@chatroom") }.distinct()
        if (names.isEmpty()) return@withContext emptyMap()
        val out = LinkedHashMap<String, String>()
        val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        for (db in databases) {
            for (name in names) {
                if (out.containsKey(name)) continue
                runCatching {
                    // chatroom 表的主键是 chatroomname，群名在 displayname；没有 username/nickname 列。
                    query(db, "SELECT displayname FROM chatroom WHERE chatroomname = ? LIMIT 1", arrayOf(name)).use {
                        if (it.moveToFirst()) {
                            val raw = it.getString(0)?.trim().orEmpty()
                            val value = dev.jev.wechatmood.core.TextClean.name(raw).trim()
                            if (value.isNotEmpty()) out[name] = value
                        }
                    }
                }
            }
            if (out.size == names.size) break
        }
        out
    }

    /**
     * 把联系人的哈希键反解回聊天对象。
     *
     * contact_key 是「账号 + 聊天对象」的摘要，只有本机 rcontact 能对回去：提醒要显示名字、
     * 点通知进会话，缺了它就只能写「未获取备注名」。旧档案没有目录记录时在这里补一次。
     */
    suspend fun resolveContacts(keys: Set<String>,
        evidence: Map<String, List<Long>> = emptyMap()): Map<String, Pair<String, String>> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext emptyMap()
            val result = LinkedHashMap<String, Pair<String, String>>()
            val opened = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }

            fun scan(db: Any, account: String) {
                val columns = tableColumns(db, "rcontact")
                // 列名按 PRAGMA 实测结果拼：写死下标时，某个版本少了 nickname 就整条查询失败、
                // 被外面的 runCatching 吞成空结果；现在缺名字列也还能只靠 username 反解。
                val selected = listOf("username", "alias", "conRemark", "nickname").filter { it in columns }
                if ("username" !in selected && "alias" !in selected) return
                val keyNames = listOf("username", "alias").filter { it in selected }
                val nameNames = listOf("conRemark", "nickname", "alias").filter { it in selected }
                runCatching {
                    query(db, "SELECT " + selected.joinToString(",") + " FROM rcontact", arrayOf()).use { cursor ->
                        val keyColumns = keyNames.mapNotNull { cursor.getColumnIndex(it).takeIf { i -> i >= 0 } }
                        val nameColumns = nameNames.mapNotNull { cursor.getColumnIndex(it).takeIf { i -> i >= 0 } }
                        while (result.size < keys.size && cursor.moveToNext()) {
                            // username 和 alias 都可能是消息里的 talker：对方改过微信号时，
                            // 同一个 rcontact 行有两个可用标识，只按 username 算会永远对不上。
                            val candidates = ArrayList<String>()
                            for (index in keyColumns) {
                                val value = runCatching { cursor.getString(index) }.getOrNull()?.trim()
                                if (!value.isNullOrEmpty() && value.length <= 256 &&
                                    value.matches(Regex("[A-Za-z0-9_@.\\-]{1,256}"))) candidates.add(value)
                            }
                            var matched: Pair<String, String>? = null
                            for (candidate in candidates) {
                                val key = ReplyContactKey.of(account, candidate, allowGroup = true)?.value ?: continue
                                if (key in keys && key !in result) {
                                    matched = key to candidate
                                    break
                                }
                            }
                            val hit = matched ?: continue
                            val name = nameColumns.toList().asSequence()
                                .mapNotNull { index -> runCatching { cursor.getString(index) }.getOrNull() }
                                .map { dev.jev.wechatmood.core.TextClean.plain(it).trim() }
                                .firstOrNull { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }
                                .orEmpty()
                            result[hit.first] = hit.second to name
                        }
                    }
                }.onFailure { MoodLog.w("RELATION_CONTACT_RESOLVE_FAILED " + it.javaClass.simpleName) }
            }

            for (db in opened) {
                val account = runCatching { scope(db) }.getOrNull() ?: continue
                scan(db, account)
            }
            // 还认不出来的，用档案里存过的依据消息反推：拿到的 talker 算出的哈希必须一致才认。
            if (result.size < keys.size && evidence.isNotEmpty()) {
                val accounts = opened.mapNotNull { db -> runCatching { scope(db) }.getOrNull() }
                for ((key, ids) in evidence) {
                    if (key !in keys || key in result) continue
                    for (id in ids.take(8)) {
                        if (id <= 0) continue
                        val talkers = opened.mapNotNull { db ->
                            runCatching {
                                query(db, "SELECT talker FROM message WHERE msgId = ? LIMIT 4", arrayOf(id.toString()))
                                    .use { cursor ->
                                        val list = ArrayList<String>(4)
                                        while (cursor.moveToNext()) {
                                            runCatching { cursor.getString(0) }.getOrNull()?.let { list.add(it) }
                                        }
                                        list
                                    }
                            }.getOrNull()
                        }.flatten()
                        val candidate = talkers.firstOrNull { talker ->
                            accounts.any { account -> ReplyContactKey.of(account, talker, allowGroup = true)?.value == key }
                        }
                        if (candidate != null) {
                            val name = contactNames(listOf(candidate))[candidate].orEmpty()
                            result[key] = candidate to name
                            MoodLog.i("RELATION_CONTACT_EVIDENCE_MATCH")
                            break
                        }
                    }
                }
            }
            MoodLog.i("RELATION_CONTACT_RESOLVE wanted=" + keys.size + " found=" + result.size)
            result
        }

    /**
     * 给「别人的会话」找话题用：按 talker 从本机聊天库取最近 [limit] 条，返回能直接进提示词的上下文。
     *
     * 不复用 [load] 是因为它必须有一个页面锚点来核对「这个库是不是当前会话」；关系温度里
     * 点开的是久未联系的人，他不在前台，没有锚点可核。这里改成用账号哈希筛库
     * （[databasesForScope]）：同一个账号的库装着全部会话，筛对了就不会串到别人的聊天。
     * 账号优先用页面核出来的那个，核不出来时只在「只开着一个库」这种没有歧义的情况下兜底。
     *
     * 返回 null 表示读不到（库没打开、账号核不出来、每个库都查询失败），调用方要如实区分；
     * 消息为空的上下文才是「这个会话本机确实没有可读记录」。
     */
    /**
     * 待回清单：对方最后说的、我还没接的会话。
     *
     * 直接写死 rconversation 的列名会在别的微信版本上整条路径失灵：表名大小写、
     * conversationTime / unReadCount 都变过。所以先用 sqlite_master 找真实表名，
     * 再用 PRAGMA 拿列名，缺列就退化，不去猜某一版的 schema。
     *
     * 返回 null = 一个库都没读通（要跟「真的没有待回」分开）；
     * 空列表 = 读到了，但确实没有超过阈值的会话。
     */
    suspend fun pendingReplies(minHours: Int = 6, limit: Int = 40,
        accountScope: String? = null): List<PendingReply>? = withContext(Dispatchers.IO) {
        val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        if (databases.isEmpty()) return@withContext null
        // 没有账号哈希时只在「只开着一个库」这种无歧义情况兜底：切过账号后旧库还开着的话，
        // 把两个号的会话混进一张待回清单，比读不到更糟。
        val scoped = if (accountScope != null) {
            databasesForScope(databases, accountScope)
        } else {
            val scopes = databases.mapNotNull { db -> runCatching { scope(db) }.getOrNull() }.distinct()
            if (scopes.size == 1) databases else emptyList()
        }
        if (scoped.isEmpty()) return@withContext null
        val active = coroutineContext
        val now = System.currentTimeMillis()
        val threshold = minHours.coerceIn(1, 24 * 30) * 3_600_000L
        val found = LinkedHashMap<String, PendingReply>()
        var read = false
        // 分开记原因：整条路径失灵时，日志里要能看出是表名、列名还是 message 表的问题。
        var noTable = 0
        var noUsername = 0
        var noMessage = 0
        for (db in scoped) {
            active.ensureActive()
            val table = conversationTable(db)
            if (table == null) { noTable++; continue }
            val columns = columnsOf(db, table)
            val usernameCol = columns["username"]
            if (usernameCol == null) { noUsername++; continue }
            val timeCol = columns["conversationtime"]
            val unreadCol = columns["unreadcount"]
            val sql = "SELECT " + usernameCol +
                (timeCol?.let { ", " + it } ?: ", 0") +
                (unreadCol?.let { ", " + it } ?: ", 0") +
                " FROM " + table + (timeCol?.let { " ORDER BY " + it + " DESC" } ?: "") +
                // 先取最近 400 个会话：只取 80 个会把「两周前对方问完就没动静」的会话整个漏掉，
                // 而那恰恰是最该出现在清单里的。
                " LIMIT 400"
            val rows = runCatching {
                query(db, sql, emptyArray()).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(Triple(cursor.getString(0) ?: "", cursor.getLong(1), cursor.getInt(2)))
                        }
                    }
                }
            }.getOrNull() ?: continue
            // message 表读不了时，「最后一条是谁发的」无从判断；继续下去会把整库当成「都回过了」。
            if (!messageTableReadable(db)) { noMessage++; continue }
            read = true
            for ((talker, _, unread) in rows) {
                active.ensureActive()
                if (!usableConversation(talker)) continue
                val last = lastMessage(db, talker) ?: continue
                // 最后一条是我发的：已经回过，不进清单。
                if (last.isSend != 0) continue
                if (last.type in SYSTEM_TYPES) continue
                val at = millis(last.time)
                if (at <= 0 || now - at < threshold) continue
                val previous = found[talker]
                if (previous != null && previous.lastAt >= at) continue
                found[talker] = PendingReply(talker, "", at, previewOf(last.type, last.content),
                    talker.endsWith("@chatroom"), unread.coerceAtLeast(0))
            }
        }
        if (noTable + noUsername + noMessage > 0) {
            MoodLog.w("REPLY_INBOX_SCHEMA no_table=" + noTable + " no_username=" + noUsername +
                " no_message=" + noMessage + " dbs=" + scoped.size)
        }
        if (!read) return@withContext null
        val ordered = found.values.sortedBy { it.lastAt }.take(limit.coerceIn(1, 200))
        MoodLog.i("REPLY_INBOX dbs=" + scoped.size + " pending=" + ordered.size + " minHours=" + minHours)
        ordered
    }

    private class LastMessage(val isSend: Int, val time: Long, val type: Int, val content: String)

    /** 每库探一次 message 表；失败说明 schema 不符，不能拿空结果当「没有待回」。 */
    private fun messageTableReadable(db: Any): Boolean = runCatching {
        query(db, "SELECT isSend, createTime, type, content FROM message LIMIT 1", emptyArray()).use { true }
    }.getOrDefault(false)

    /**
     * 会话最后一条。createTime 有一部分是秒级来源，同一秒里有多行时单按它排序顺序不定，
     * 会判错「最后一句是谁发的」；msgId 单调递增，补成稳定次级键。
     */
    private fun lastMessage(db: Any, talker: String): LastMessage? = runCatching {
        query(db, "SELECT isSend, createTime, type, content FROM message WHERE talker = ? " +
            "ORDER BY createTime DESC, msgId DESC LIMIT 1", arrayOf(talker)).use { cursor ->
            if (!cursor.moveToFirst()) null
            else LastMessage(cursor.getInt(0), cursor.getLong(1), cursor.getInt(2),
                cursor.getString(3) ?: "")
        }
    }.getOrNull()

    /** 会话表的真实名字：有的版本叫 rconversation，有的 rConversation，不一致就返回 null。 */
    private fun conversationTable(db: Any): String? = runCatching {
        query(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND lower(name) = 'rconversation' LIMIT 1",
            emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /** 小写列名到真实列名：不同版本大小写不一致，SQL 里必须用真实写法。 */
    private fun columnsOf(db: Any, table: String): Map<String, String> = runCatching {
        val out = LinkedHashMap<String, String>()
        // table 来自 sqlite_master 查出的真实名字，不是外部输入，才敢拼进 PRAGMA。
        query(db, "PRAGMA table_info(" + table + ")", emptyArray()).use { cursor ->
            val index = cursor.getColumnIndex("name")
            if (index >= 0) {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(index) ?: continue
                    out[name.lowercase()] = name
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    /** rconversation 的 conversationTime 有秒也有毫秒；createTime 同理，统一成毫秒再比。 */
    private fun millis(raw: Long): Long = when {
        raw <= 0 -> 0
        raw < 1_000_000_000_000L -> raw * 1000
        else -> raw
    }

    private fun usableConversation(talker: String): Boolean =
        talker.isNotBlank() && !talker.startsWith("@") && !talker.startsWith("gh_") &&
            talker.lowercase() !in RESERVED_TALKERS

    /** 按码点截断：take(60) 数的是 UTF-16 单元，emoji 会被切成孤立代理字符，通知里显示成方块。 */
    private fun String.takePoints(max: Int): String =
        if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))

    private fun previewOf(type: Int, content: String): String {
        val text = content.trim().replace('\n', ' ').replace('\r', ' ')
        return when (type) {
            1 -> text.takePoints(60).ifBlank { "【空消息】" }
            3 -> "【图片】"
            34 -> "【语音】"
            42 -> "【名片】"
            43 -> "【视频】"
            47 -> "【表情】"
            48 -> "【位置】"
            49 -> "【链接或卡片】"
            50 -> "【通话】"
            else -> text.takePoints(60).ifBlank { "【消息】" }
        }
    }

    private val SYSTEM_TYPES = setOf(10000, 10002, 10003, 10004)

    /** 微信自己发的系统会话，不该出现在「你还没回」的清单里。 */
    private val RESERVED_TALKERS = setOf(
        "filehelper", "fmessage", "medianote", "newsapp", "weixin", "floatbottle",
        "notifymessage", "qqmail", "tmessage", "qmessage", "brandsessionholder",
        "exmail_tool", "weibo", "facebook", "twitter", "linkedin", "qqsync", "qqfriend",
        "voipapp", "voicevoipapp", "officialaccounts")

    suspend fun recentContext(talker: String, limit: Int = 20, accountScope: String? = null): ReplyContext? =
        withContext(Dispatchers.IO) {
            if (talker.isBlank()) return@withContext null
            val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
            if (databases.isEmpty()) return@withContext null
            val account = accountScope?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: databases.mapNotNull { db -> runCatching { scope(db) }.getOrNull() }.distinct().singleOrNull()
                ?: return@withContext null
            val scoped = databasesForScope(databases, account)
            val active = coroutineContext
            val sources = scoped.map { db -> ReplyHistoryQuery { sql, args ->
                active.ensureActive()
                query(db, sql, args).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            active.ensureActive()
                            add(metadata(cursor))
                        }
                    }
                }
            } }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val read = ReplyHistoryReader.readRecent(talker, sources, limit) { active.ensureActive() }
            // 只记条数与库数量，不记会话和正文；群聊还要把裸 wxid 换成昵称，和抽屉里一致。
            MoodLog.i("REPLY_RECENT_RESULT requested=$limit dbs=${scoped.size} found=${read != null} " +
                "count=${read?.messages?.size ?: -1} took=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            if (read == null) return@withContext null
            nameGroupSpeakers(read, account)
        }

    suspend fun load(loaded: ReplyContext, limit: Int = ReplyContext.MAX_MESSAGES,
        accountScope: String? = null): ReplyContext = withContext(Dispatchers.IO) {
        require(limit in 1..ReplyContext.MAX_MESSAGES)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val active = coroutineContext
        val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
        val sources = databases.map { db -> ReplyHistoryQuery { sql, args ->
            active.ensureActive()
            check(db.javaClass.getMethod("isOpen").invoke(db) == true)
            val cursor = query(db, sql, args)
            cursor.use {
                buildList {
                    while (size < limit + 1 && it.moveToNext()) {
                        active.ensureActive()
                        add(metadata(it))
                    }
                }
            }
        } }
        val read = ReplyHistoryReader.read(loaded, sources, limit) { active.ensureActive() }
        // 昵称解析只认当前账号的库：displayNames 在 accountScope 为空时会拒绝所有 handle，
        // 表现就是日志里 dbs=0、群消息全是裸 wxid。调用方没给账号时用页面锚点自己核一次。
        // 私聊不需要改名，别为它白查一遍库。
        val group = read.talker.endsWith("@chatroom")
        val scope = accountScope?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: if (group) replyAccount(read) else null
        val named = nameGroupSpeakers(read, scope)
        // 真正被换掉名字的条数：替换前 speaker 必须还是 senderId，不能拿「本来就有名字」的页面消息充数。
        val renamed = named.messages.zip(read.messages).count { (after, before) -> after.speaker != before.speaker }
        MoodLog.i("REPLY_HISTORY_RESULT requested=$limit handles=${databases.size} source=${named.source} " +
            "count=${named.messages.size} trimmed=${named.trimmed} " +
            "took=${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
            "quoted=${named.messages.count { message -> message.text.startsWith("【引用") }} " +
            "renamed=$renamed account=${scope != null}")
        // 口吻画像要用更长的历史，不能只看本次参考的十几条：单独取「我」最近的真实文字。
        val styleScope = scope ?: replyAccount(read)
        named.copy(styleTexts = readStyleTexts(read.talker, databases, styleScope).orEmpty())
    }

    /**
     * 口吻画像窗口用：只要「我」在这个聊天最近的真实文字，不加载整页上下文、不改任何游标。
     *
     * 返回值故意做成三态：null 表示这次读不到（没有已打开的库、账号核不出来、或每个库的
     * 查询都失败了），空列表才是「真的没发过文字」。窗口要分别说「本机聊天库暂时读不到」
     * 和「文字还不到 8 条」，把读失败说成 0 条等于给用户一个假结论。
     *
     * [anchor] 只用来核对账号（这些消息属于哪个库），不参与取文字：真正查的是 [talker]。
     * 只开着一个库时它就是当前账号，不必再拿页面去核，所以 [anchor] 允许为空。
     */
    suspend fun ownStyleTexts(talker: String, anchor: ReplyContext? = null): List<String>? =
        withContext(Dispatchers.IO) {
            if (talker.isBlank()) return@withContext null
            val databases = synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }
            if (databases.isEmpty()) return@withContext null
            // 页面锚点能核出账号就信它；核不出来时只在「唯一一个库」这种没有歧义的情况下兜底。
            val resolved = anchor?.let { page -> runCatching { replyAccount(page) }.getOrNull() }
                ?: databases.mapNotNull { db -> runCatching { scope(db) }.getOrNull() }
                    .distinct().singleOrNull()
                ?: return@withContext null
            readStyleTexts(talker, databases, resolved)
        }

    /**
     * 「我」最近的真实文字消息，只给口吻画像用。图片/表情/语音占位和卡片不算：
     * 拿它们统计会让画像以为我「一条只发四个字」，媒体多的聊天里口吻描述直接失真。
     *
     * 返回 null 表示每个库的查询都没跑通（库刚关掉、表结构对不上），空列表才是确实没有文字。
     */
    private fun readStyleTexts(talker: String, databases: List<Any>, scope: String?): List<String>? {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val dbs = databasesForScope(databases, scope)
        var read = false
        for (db in dbs) {
            val texts = runCatching {
                // 按 createTime 倒序才能直接用 (talker, createTime) 索引；ORDER BY msgId 在
                // 「我」发言稀少的群里会退化成全表扫描（实测 4.6 秒），拖住整块「读取最近 N 条」。
                query(db, "SELECT content FROM message WHERE talker = ? AND isSend = 1 AND type = 1 " +
                    "AND length(trim(content)) > 0 ORDER BY createTime DESC LIMIT 200", arrayOf(talker)).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
            }.getOrNull()
            if (texts != null) read = true
            dev.jev.wechatmood.core.MoodLog.i("REPLY_STYLE_TEXTS rows=${texts?.size ?: -1} dbs=${dbs.size} " +
                "took=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
            if (!texts.isNullOrEmpty()) return texts.asReversed()
        }
        // 一次都没跑通就是读不到，不能返回空列表让界面说「你没发过文字」。
        return if (read) emptyList() else null
    }

    /**
     * 历史里的群消息只有 wxid，页面上却是昵称。同一段上下文两种称呼会让模型分不清谁是谁，
     * 所以在返回前用群成员资料换一次名字；查不到就保持原样，不编造称呼。
     */
    private suspend fun nameGroupSpeakers(context: ReplyContext, accountScope: String?): ReplyContext {
        if (!context.talker.endsWith("@chatroom")) return context
        // 诊断：说清「没有可用 wxid」还是「已经有昵称」。只记空/长度/是否相等，不记昵称和正文。
        val senders = context.messages.map { it.senderId }
        MoodLog.i("REPLY_GROUP_SPEAKERS count=${context.messages.size} " +
            "blankSender=${senders.count { it.isBlank() }} " +
            "same=${context.messages.count { it.senderId.isNotBlank() && it.speaker == it.senderId }} " +
            "senderLens=${senders.take(6).map { it.length }} " +
            "speakerLens=${context.messages.take(6).map { it.speaker.length }} " +
            "account=${accountScope != null}")
        // 已经有名字的不再查库，只替换 speaker 仍是裸 id 的那些。
        val unnamed = context.messages
            .filter { it.senderId.isNotBlank() && it.speaker == it.senderId }
            .map { it.senderId }.distinct()
        if (unnamed.isEmpty()) return context
        val names = runCatching { displayNames(unnamed, context.talker, accountScope) }.getOrNull().orEmpty()
        MoodLog.i("REPLY_GROUP_NAMES wanted=${unnamed.size} found=${names.size} " +
            "account=${accountScope != null} idLens=${unnamed.take(6).map { it.length }}")
        if (names.isEmpty()) return context
        return context.copy(messages = context.messages.map { message ->
            val name = names[message.senderId]?.takeIf(String::isNotBlank)
            if (name != null && message.speaker == message.senderId) message.copy(speaker = name) else message
        })
    }
}
