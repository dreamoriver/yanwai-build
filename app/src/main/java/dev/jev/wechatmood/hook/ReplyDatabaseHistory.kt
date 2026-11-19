package dev.jev.wechatmood.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyHistoryReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import dev.jev.wechatmood.voice.VoiceSource
import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.AnalysisAccountIdentity
import dev.jev.wechatmood.core.AnalysisAccountScopes

/** Reuses WeChat's open WCDB handle. Never opens files, obtains keys, writes, or closes its database. */
object ReplyDatabaseHistory {
    private val known = WeakHashMap<Any, Boolean>()
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

    @Synchronized private fun observe(db: Any) {
        if (moduleQuery.get() == true) return
        val main = known.getOrPut(db) {
            val path = db.javaClass.getMethod("getPath").invoke(db) as? String
            path?.replace('\\', '/')?.substringAfterLast('/') == "EnMicroMsg.db"
        }
        if (!main) return
        val firstObservation = handles.none { it.get() === db }
        handles.removeAll { it.get() == null || it.get() === db }
        handles.add(0, WeakReference(db))
        while (handles.size > 4) handles.removeAt(handles.lastIndex)
        if (firstObservation) accountScopes.retryUnverified()
    }

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

    /** 按时间范围取统计用消息（不限已加载页），用于「今天 / 最近 N 小时」。 */
    suspend fun statMessages(talker: String, sinceMillis: Long, limit: Int,
        accountScope: String? = null): List<MessageMetadata> = withContext(Dispatchers.IO) {
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
            runCatching {
                query(db, "SELECT msgId, type, isSend, content, talker, createTime, imgPath, msgSvrId FROM message " +
                    "WHERE talker = ? AND createTime >= ? ORDER BY createTime DESC LIMIT ?",
                    arrayOf(talker, since.toString(), limit.coerceAtLeast(1).toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
        }
        dev.jev.wechatmood.core.MoodLog.i(
            "STATS_RANGE talker=${talker.hashCode()} since=$since count=${out.size} " +
                "first=${out.lastOrNull()?.createdAt} last=${out.firstOrNull()?.createdAt}")
        out.reversed()
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
                query(db, "SELECT * FROM message WHERE talker = ? AND type = 1 AND msgId < ? " +
                    "ORDER BY msgId DESC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            runCatching {
                query(db, "SELECT * FROM message WHERE talker = ? AND type = 1 AND msgId > ? " +
                    "ORDER BY msgId ASC LIMIT ?", arrayOf(talker, msgId.toString(), limit.toString())).use { cursor ->
                    while (cursor.moveToNext()) out += metadata(cursor)
                }
            }
            if (out.isNotEmpty()) break
        }
        out.sortedBy { it.createdAt }
    }

    /** 诊断用：列出主库的表名，便于确认群昵称实际存在哪张表。 */
    suspend fun tableNames(accountScope: String? = null): List<String> = withContext(Dispatchers.IO) {
        val databases = databasesForScope(
            synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
        val names = databases.flatMap { tableNamesOf(it) }.distinct()
        dev.jev.wechatmood.core.MoodLog.i("STATS_TABLES_RAW count=${names.size} sample=${names.take(12)}")
        val related = names.filter {
            it.contains("contact", true) || it.contains("stranger", true) || it.contains("nick", true) ||
                it.contains("userinfo", true) || it.contains("friend", true)
        }
        dev.jev.wechatmood.core.MoodLog.i("STATS_TABLES count=${names.size} related=$related")
        names
    }

    /**
     * 昵称查不到时的现场诊断：写清楚微信这版把名字放哪了。
     * 1) chatroom 整行（memberlist 现在只是分号列表，昵称列另有其人）
     * 2) 一个没命中的 wxid 在 rcontact 的整行
     * 3) 全库定位这个 wxid 出现在哪些表、哪些列
     */
    suspend fun diagNames(chatroom: String?, accountScope: String?, raws: List<String>): String =
        withContext(Dispatchers.IO) {
            val databases = databasesForScope(
                synchronized(this@ReplyDatabaseHistory) { handles.mapNotNull { it.get() } }, accountScope)
            val target = raws.firstOrNull { it.startsWith("wxid") } ?: raws.firstOrNull().orEmpty()
            buildString {
                appendLine("diag_names time=" + System.currentTimeMillis())
                appendLine("chatroom=" + chatroom + " dbs=" + databases.size + " target=" + target)
                appendLine("layers=" + lastLayers)
                for (db in databases.take(1)) {
                    val tables = tableNamesOf(db)
                    appendLine("tables=" + tables.size + " " + tables.joinToString(",").take(4000))
                    val xml = runCatching { rawMemberlist(db, chatroom.orEmpty()) }.getOrDefault("")
                    appendLine("memberlist_len=" + xml.length)
                    appendLine("memberlist_head=" + xml.take(1500).replace("\n", "\\n"))
                    appendLine("chatroom_row=" + rowDump(db, "chatroom", "chatroomname", chatroom.orEmpty()))
                    appendLine("rcontact_row=" + rowDump(db, "rcontact", "username", target))
                    // 全库定位：只扫名字像联系人的表，避免把 message 这种大表翻一遍。
                    var hits = 0
                    for (table in tables) {
                        if (hits >= 12) break
                        val lower = table.lowercase()
                        if (!(lower.contains("contact") || lower.contains("nick") || lower.contains("member") ||
                                lower.contains("room") || lower.contains("user") || lower.contains("stranger") ||
                                lower.contains("profile") || lower.contains("info") || lower.startsWith("openim"))) continue
                        if (lower == "rcontact" || lower == "chatroom") continue
                        val columns = tableColumns(db, table)
                        val idColumns = columns.filter { it.lowercase() in ID_COLUMNS }
                        for (column in idColumns) {
                            val hit = runCatching {
                                query(db, "SELECT * FROM " + table + " WHERE " + column + " = ? LIMIT 1",
                                    arrayOf(target)).use { cursor ->
                                    if (!cursor.moveToFirst()) null else columns.mapIndexed { index, name ->
                                        name to runCatching { cursor.getString(index) }.getOrNull().orEmpty()
                                    }.filter { it.second.isNotBlank() && it.second.length < 120 }
                                        .joinToString("|") { it.first + "=" + it.second }
                                }
                            }.getOrNull()
                            if (!hit.isNullOrBlank()) {
                                appendLine("hit[" + table + "." + column + "]=" + hit.take(400))
                                hits++
                            }
                        }
                    }
                    appendLine("hits=" + hits)
                    // 关键候选表的列名，先确认这版把昵称放哪了。
                    for (table in listOf("contact", "contact_ext", "friend_ext", "getcontactinfov2",
                        "Stranger", "ContactCmdBuf", "MsgQuote", "img_flag")) {
                        appendLine("schema[" + table + "]=" + tableColumns(db, table).joinToString(","))
                    }
                    // 全库找这个 wxid：名字只要本地存过，总会被某一列命中。
                    for (line in searchEverywhere(db, target)) appendLine(line)
                    // 群消息的 lvbuffer 里常带发送者附加信息，看看有没有可读内容。
                    appendLine("lvbuffer=" + lvbufferSample(db, chatroom.orEmpty()))
                }
            }
        }

    /** 跳过消息类大表：这些表几十万行，LIKE 全表扫会把统计面板拖死。 */
    private val BIG_TABLES = setOf("message", "fmessage_msginfo", "bizchatmessage", "appbrandmessage",
        "qmessage", "tmessage", "bottlemessage", "gamemessage", "AppMessage", "DelMessage", "OpLog")

    /** 全库按文本列找目标 wxid，命中就把它那一行的非空字段打出来。 */
    private fun searchEverywhere(db: Any, target: String): List<String> {
        if (target.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (table in tableNamesOf(db)) {
            if (out.size >= 16) break
            if (table in BIG_TABLES) continue
            val columns = tableColumns(db, table)
            if (columns.isEmpty() || columns.size > 40) continue
            val where = columns.joinToString(" OR ") { it + " LIKE ?" }
            val args = Array(columns.size) { "%" + target + "%" }
            val hit = runCatching {
                query(db, "SELECT * FROM " + table + " WHERE " + where + " LIMIT 1", args).use { cursor ->
                    if (!cursor.moveToFirst()) null else columns.mapIndexed { index, name ->
                        name to runCatching { cursor.getString(index) }.getOrNull().orEmpty()
                    }.filter { it.second.isNotBlank() }
                        .joinToString("|") { it.first + "=" + it.second.take(80) }
                }
            }.getOrNull()
            if (!hit.isNullOrBlank()) out += "search[" + table + "]=" + hit.take(300)
        }
        return out
    }

    /** 一条群消息的 lvbuffer：只留可读字符，用来判断昵称是不是塞在这里。 */
    private fun lvbufferSample(db: Any, chatroom: String): String {
        if (chatroom.isBlank()) return ""
        return runCatching {
            query(db, "SELECT lvbuffer FROM message WHERE talker = ? AND isSend = 0 " +
                "ORDER BY createTime DESC LIMIT 1", arrayOf(chatroom)).use { cursor ->
                if (!cursor.moveToFirst()) return@use ""
                val bytes = runCatching { cursor.getBlob(0) }.getOrNull() ?: return@use ""
                String(bytes, Charsets.ISO_8859_1).filter { it.code in 32..126 || it.code > 160 }
                    .replace(Regex("[\\s]{2,}"), " ").take(300)
            }
        }.getOrDefault("")
    }

    /** 整行 dump：列名=值，只留非空且不太长的字段。 */
    private fun rowDump(db: Any, table: String, keyColumn: String, key: String): String {
        if (key.isBlank()) return "none"
        val columns = tableColumns(db, table)
        return runCatching {
            query(db, "SELECT * FROM " + table + " WHERE " + keyColumn + " = ? LIMIT 1", arrayOf(key)).use { cursor ->
                if (!cursor.moveToFirst()) return@use "none"
                columns.mapIndexed { index, name ->
                    name to runCatching { cursor.getString(index) }.getOrNull().orEmpty()
                }.filter { it.second.isNotBlank() }
                    .joinToString("|") { it.first + "=" + it.second.take(60) }
            }
        }.getOrDefault("error")
    }

    private val ID_COLUMNS = setOf("username", "user_name", "wxid", "alias", "userid", "user_id",
        "contactid", "contact_id", "member", "membername")

    /**
     * 群里的引用消息自带被引用人的资料：refermsg 里 chatusr 是 wxid，displayname 是昵称。
     * 非好友的名字库里查不到，但引用里写着，直接拿来用。
     */
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
                    query(db, "SELECT content FROM message WHERE talker = ? AND type IN (49, 822083633) " +
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

    /** 每个库的列名缓存；库对象被回收时一起回收。 */
    private val columnCache = java.util.WeakHashMap<Any, MutableMap<String, List<String>>>()

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
        result
    }

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
                        val name = bestName(cursor, 1, nameColumns.size) ?: continue
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
                        val name = bestName(cursor, 1, nameColumns.size) ?: continue
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
        columnCache.getOrPut(db) { HashMap() }[table] = columns
        return columns
    }

    private fun bestName(cursor: Cursor, offset: Int, count: Int): String? =
        (0 until count).asSequence()
            .mapNotNull { index -> runCatching { cursor.getString(offset + index) }.getOrNull() }
            .map { dev.jev.wechatmood.core.TextClean.name(it) }
            .firstOrNull { it.isNotEmpty() && !it.equals("null", true) && !it.equals("uinull", true) }

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

    /** chatroom.memberlist 是 XML，群昵称和群备注都写在里面。 */
    private fun chatroomMemberNames(db: Any, chatroom: String): Map<String, String> {
        val xml = rawMemberlist(db, chatroom)
        val parsed = dev.jev.wechatmood.reply.GroupMemberList.parse(xml)
        if (parsed.isNotEmpty()) {
            MoodLog.i("STATS_CHATROOM members=${parsed.size} xml=${xml.length}")
        }
        return parsed
    }

    suspend fun load(loaded: ReplyContext, limit: Int = ReplyContext.MAX_MESSAGES): ReplyContext = withContext(Dispatchers.IO) {
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
        ReplyHistoryReader.read(loaded, sources, limit) { active.ensureActive() }.also {
            MoodLog.i("REPLY_HISTORY_RESULT requested=$limit handles=${databases.size} source=${it.source} " +
                "count=${it.messages.size} trimmed=${it.trimmed} took=${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                "quoted=${it.messages.count { message -> message.text.startsWith("【引用") }}")
        }
    }
}
