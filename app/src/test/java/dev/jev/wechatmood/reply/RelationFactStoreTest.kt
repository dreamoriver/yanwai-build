package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.PersistentAnalysisCacheTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 关系事实的存储、迁移与时间语义。 */
class RelationFactStoreTest {
    private lateinit var file: java.io.File

    @Before fun setUp() {
        file = java.io.File.createTempFile("relation-facts", ".db").also { it.delete() }
    }

    private fun db() = PersistentAnalysisCacheTest.Jdbc(file)

    private fun body(content: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun `legacy single row migrates into items exactly once`() {
        val key = ReplyContactKey("a".repeat(64))
        val legacy = JSONArray()
            .put(JSONObject().put("kind", "工作").put("text", "在游戏公司做运营")
                .put("evidence_time", 100).put("updated_at", 100).put("confidence", 0.8))
            .put(JSONObject().put("kind", "约定").put("text", "周五一起吃饭"))
            .toString()
        val seed = db()
        seed.execute("CREATE TABLE relation_facts (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        seed.execute("INSERT INTO relation_facts(contact_key, payload) VALUES(?, ?)", listOf(key.value, legacy))

        val store = RelationFactStore(seed)
        val migrated = store.list(key)
        assertEquals(2, migrated.size)
        assertTrue(migrated.all { it.factId.matches(Regex("[0-9a-f]{16}")) })
        assertTrue(migrated.all { it.status == RelationFact.STATUS_ACTIVE })
        assertEquals(100, migrated.single { it.kind == "工作" }.evidenceTime)

        // 再开一次不会把同一批旧数据插第二遍。
        val reopened = RelationFactStore(db())
        assertEquals(2, reopened.list(key).size)
    }

    @Test fun `rescheduled promise supersedes the old entry instead of piling up`() {
        val key = ReplyContactKey("b".repeat(64))
        val store = RelationFactStore(db())
        val saturday = FactTimes.parseLocal("2026-10-24T19:00")
        val sunday = FactTimes.parseLocal("2026-10-25T19:00")
        store.replace(key, RelationFacts.merge(emptyList(),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = saturday)), 1000))
        val merged = RelationFacts.merge(store.listAll(key),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = sunday)), 2000)
        store.replace(key, merged)

        val open = store.list(key)
        assertEquals(1, open.size)
        assertEquals(sunday, open.single().occurredAt)
        assertEquals(1, store.listAll(key).count { it.status == RelationFact.STATUS_SUPERSEDED })
    }

    @Test fun `same day change stays one promise`() {
        val saturday = FactTimes.parseLocal("2026-10-24T19:00")
        val laterSameDay = FactTimes.parseLocal("2026-10-24T20:30")
        val merged = RelationFacts.merge(listOf(RelationFact("约定", "周六一起吃饭", occurredAt = saturday)),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = laterSameDay)), 2000)
        assertEquals(1, merged.count { it.isOpen })
        assertEquals(0, merged.count { it.status == RelationFact.STATUS_SUPERSEDED })
        assertEquals(laterSameDay, merged.single { it.isOpen }.occurredAt)
    }

    @Test fun `static facts keep merging by similarity`() {
        val merged = RelationFacts.merge(listOf(RelationFact("工作", "在游戏公司做运营", 100, 100)),
            listOf(RelationFact("工作", "在游戏公司做运营主管", 200, 200)), 300)
        assertEquals(1, merged.size)
        assertEquals(100, merged[0].evidenceTime)
    }

    @Test fun `occurred at only accepts explicit dates`() {
        assertTrue(FactTimes.parseLocal("2026-10-23T19:00") > 0)
        assertTrue(FactTimes.parseLocal("2026-10-23") > 0)
        assertEquals(0L, FactTimes.parseLocal(""))
        assertEquals(0L, FactTimes.parseLocal("过两天"))
        assertEquals(0L, FactTimes.parseLocal("下周三"))
    }

    @Test fun `date only entries are not midnight and are not overdue today`() {
        val now = FactTimes.parseLocal("2026-11-19T10:00")
        val today = FactTimes.parseLocal("2026-11-19")
        val tonight = FactTimes.parseLocal("2026-11-19T19:00")
        val yesterday = FactTimes.parseLocal("2026-11-18")
        assertFalse(FactTimes.hasClock(today))
        assertTrue(FactTimes.hasClock(tonight))
        // 同一天只说日期：还没过完，不算过期。
        assertFalse(FactTimes.overdue(today, now))
        assertFalse(FactTimes.overdue(tonight, now))
        assertTrue(FactTimes.overdue(yesterday, now))
        assertEquals("今天", FactTimes.label(today, now))
        assertEquals("今天 19:00", FactTimes.label(tonight, now))
        assertEquals("昨天", FactTimes.label(yesterday, now))
    }

    @Test fun `extractor maps evidence index to message id and keeps the timestamp`() {
        val facts = FactExtractor().parse(
            body("""[{"kind":"约定","text":"周五一起吃饭","evidence":2,"occurred_at":"2026-10-23T19:00"}]"""),
            messageIdAt = { index -> if (index == 2) 4242L else 0L })
        val fact = facts.single()
        assertEquals(4242L, fact.sourceChatMessageId)
        assertTrue(fact.occurredAt > 0)
    }

    @Test fun `extractor drops unusable timestamps instead of guessing`() {
        val facts = FactExtractor().parse(body("""[{"kind":"约定","text":"改天一起吃饭","occurred_at":"改天"}]"""))
        assertEquals(1, facts.size)
        assertEquals(0L, facts.single().occurredAt)
    }

    @Test fun `todo list only includes timed active promises`() {
        val key = ReplyContactKey("d".repeat(64))
        val store = RelationFactStore(db())
        val soon = FactTimes.parseLocal("2026-11-20T19:00")
        val later = FactTimes.parseLocal("2026-11-25T09:00")
        store.replace(key, RelationFacts.merge(emptyList(), listOf(
            RelationFact("约定", "周五一起吃饭", occurredAt = soon),
            RelationFact("重要日期", "生日", occurredAt = later),
            RelationFact("约定", "有空一起爬山"),
            RelationFact("城市", "在杭州"),
        ), 1000))

        val todos = store.todos(key)
        assertEquals(2, todos.size)
        assertEquals(soon, todos.first().occurredAt)
        assertEquals(later, todos.last().occurredAt)
    }

    @Test fun `manual status changes stick and superseded stays read only`() {
        val key = ReplyContactKey("e".repeat(64))
        val store = RelationFactStore(db())
        val at = FactTimes.parseLocal("2026-11-20T19:00")
        store.replace(key, listOf(RelationFact("约定", "周五一起吃饭", occurredAt = at)))
        val id = store.todos(key).single().factId

        assertTrue(store.setStatus(key, id, RelationFact.STATUS_DONE))
        assertEquals(RelationFact.STATUS_DONE, store.listAll(key).single().status)
        assertEquals(0, store.todos(key).size)

        assertTrue(store.setStatus(key, id, RelationFact.STATUS_ACTIVE))
        assertEquals(1, store.todos(key).size)

        assertFalse(store.setStatus(key, id, RelationFact.STATUS_SUPERSEDED))
        assertFalse(store.setStatus(key, id, "unknown"))
        assertFalse(store.setStatus(key, "0".repeat(16), RelationFact.STATUS_DONE))
        assertEquals(RelationFact.STATUS_ACTIVE, store.listAll(key).single().status)
    }

    @Test fun `manual fact is added once and marked as manual`() {
        val key = ReplyContactKey("f".repeat(64))
        val store = RelationFactStore(db())
        val added = store.addManual(key, "喜好", "喜欢手冲咖啡")
        assertEquals(1, added?.size)
        assertEquals("manual", store.list(key).single().source)
        // 同一句话再添一次不会变成两条。
        store.addManual(key, "喜好", "喜欢手冲咖啡")
        assertEquals(1, store.list(key).size)
        assertEquals(null, store.addManual(key, "不存在的类型", "随便"))
        assertEquals(null, store.addManual(key, "喜好", "长".repeat(RelationFact.MAX_TEXT + 1)))
    }

    @Test fun `manual timed promise lands in the todo list`() {
        val key = ReplyContactKey("ab".repeat(32))
        val store = RelationFactStore(db())
        val at = FactTimes.parseLocal("2026-12-01T18:30")
        assertEquals(1, store.addManual(key, "约定", "一起看展", at)?.size)
        assertEquals(at, store.todos(key).single().occurredAt)
        assertEquals(1, store.list(key).size)
    }

    @Test fun `deleted fact stays deleted after another automatic pass`() {
        val key = ReplyContactKey("12".repeat(32))
        val store = RelationFactStore(db())
        store.replace(key, listOf(RelationFact("喜好", "喜欢手冲咖啡", updatedAt = 1000)))
        val id = store.list(key).single().factId
        assertTrue(store.delete(key, id))
        assertEquals(0, store.listAll(key).size)
        // 下一次抽取又给同一句：不能复活。
        store.replace(key, listOf(RelationFact("喜好", "喜欢手冲咖啡", updatedAt = 2000)))
        assertEquals(0, store.listAll(key).size)
        // 手动添加是明确意图，可以再回来，并且解除忽略标记。
        assertEquals(1, store.addManual(key, "喜好", "喜欢手冲咖啡")?.size)
        assertEquals(0, store.ignored(key).size)
    }

    @Test fun `clearing the archive also clears ignore marks`() {
        val key = ReplyContactKey("34".repeat(32))
        val store = RelationFactStore(db())
        store.replace(key, listOf(RelationFact("喜好", "喜欢手冲咖啡", updatedAt = 1000)))
        assertTrue(store.delete(key, store.list(key).single().factId))
        store.clear(key)
        assertEquals(0, store.ignored(key).size)
        store.replace(key, listOf(RelationFact("喜好", "喜欢手冲咖啡", updatedAt = 3000)))
        assertEquals(1, store.list(key).size)
    }

    @Test fun `clear removes everything`() {
        val key = ReplyContactKey("c".repeat(64))
        val store = RelationFactStore(db())
        store.replace(key, listOf(RelationFact("城市", "在杭州")))
        store.saveMeta(key, RelationMeta(1234, 99))
        store.clear(key)
        assertEquals(0, store.listAll(key).size)
        assertEquals(0L, store.meta(key).lastAt)

        store.replace(key, listOf(RelationFact("城市", "在杭州")))
        store.clearAll()
        assertEquals(0, store.listAll(key).size)
    }
    @Test fun `restore brings back a superseded fact and retires the current one`() {
        val key = ReplyContactKey("c".repeat(64))
        val store = RelationFactStore(db())
        store.replace(key, RelationFacts.merge(emptyList(),
            listOf(RelationFact("工作", "在游戏公司做运营")), 1000))
        // 换工作：新事实指出替换了旧的那条
        store.replace(key, RelationFacts.merge(store.listAll(key),
            listOf(RelationFact("工作", "在游戏公司做运营主管").copy(replaces = "在游戏公司做运营")), 2000))
        val superseded = store.listAll(key).single { it.status == RelationFact.STATUS_SUPERSEDED }
        assertTrue(store.restore(key, superseded.factId))

        val after = store.listAll(key)
        assertEquals(1, after.count { it.isOpen })
        assertEquals("在游戏公司做运营", after.single { it.isOpen }.text)
        assertEquals(1, after.count { it.status == RelationFact.STATUS_SUPERSEDED })
    }


    @Test fun `two contacts keep the same sentence in their own archive`() {
        val store = RelationFactStore(db())
        val a = ReplyContactKey("a".repeat(64))
        val b = ReplyContactKey("b".repeat(64))
        store.replace(a, listOf(RelationFact("城市", "住在杭州")))
        store.replace(b, listOf(RelationFact("城市", "住在杭州")))
        assertEquals(1, store.list(a).size)
        assertEquals(1, store.list(b).size)
        assertEquals("住在杭州", store.list(a).single().text)
    }

    @Test fun `legacy items table with a single column primary key is rebuilt`() {
        val db = db()
        // 旧结构：fact_id 单列主键，A、B 的同一句话只能存一条。
        db.execute("CREATE TABLE relation_fact_items (fact_id TEXT PRIMARY KEY NOT NULL, contact_key TEXT NOT NULL, " +
            "status TEXT NOT NULL, kind TEXT NOT NULL, occurred_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
            "payload TEXT NOT NULL)")
        db.execute("INSERT INTO relation_fact_items VALUES(?, ?, ?, ?, ?, ?, ?)",
            listOf("1234567890abcdef", "a".repeat(64), "active", "城市", "0", "1",
                JSONObject().put("kind", "城市").put("text", "住在杭州").toString()))
        val store = RelationFactStore(db)
        val a = ReplyContactKey("a".repeat(64))
        val b = ReplyContactKey("b".repeat(64))
        // 旧数据要搬过来，否则升级后档案变空。
        assertEquals("住在杭州", store.list(a).single().text)
        store.replace(b, listOf(RelationFact("城市", "住在杭州")))
        assertEquals(1, store.list(a).size)
        assertEquals(1, store.list(b).size)
    }

    @Test fun `export all keeps every contact for the cross database migration`() {
        val store = RelationFactStore(db())
        val a = ReplyContactKey("a".repeat(64))
        val b = ReplyContactKey("b".repeat(64))
        store.replace(a, listOf(RelationFact("城市", "住在杭州"), RelationFact("工作", "在游戏公司做运营")))
        store.replace(b, listOf(RelationFact("宠物", "养了一只橘猫")))
        val exported = store.exportAll()
        assertEquals(2, exported.getJSONArray(a.value).length())
        assertEquals(1, exported.getJSONArray(b.value).length())
        assertFalse(exported.has("c".repeat(64)))
    }
    @Test fun `imported entries keep their settled status`() {
        val key = ReplyContactKey("c".repeat(64))
        val store = RelationFactStore(db())
        val stamp = FactTimes.parseLocal("2026-10-24T19:00")
        val done = RelationFact("约定", "周六一起吃饭", occurredAt = stamp,
            status = RelationFact.STATUS_DONE, source = "manual")
        store.replace(key, RelationFacts.merge(emptyList(), listOf(done), 1000, keepStatus = true))
        assertEquals(RelationFact.STATUS_DONE, store.listAll(key).single().status)

        // 不带 keepStatus 是自动整理的口径：同一句话会被当成新的有效条目，这是设计语义。
        assertEquals(RelationFact.STATUS_ACTIVE,
            RelationFacts.merge(emptyList(), listOf(done), 2000).single().status)
    }

    @Test fun `manual add is not swallowed by a settled similar entry`() {
        val key = ReplyContactKey("d".repeat(64))
        val store = RelationFactStore(db())
        val stamp = FactTimes.parseLocal("2026-10-24T19:00")
        store.replace(key, RelationFacts.merge(emptyList(),
            listOf(RelationFact("约定", "周六一起吃饭", occurredAt = stamp)), 1000))
        val existing = store.list(key).single()
        assertTrue(store.setStatus(key, existing.factId, RelationFact.STATUS_DONE))

        // 以前 merge 的「别复活」规则会把这条手动添加 continue 掉，界面却报「已添加」。
        store.addManual(key, "约定", "周六一起吃饭", stamp)
        val active = store.list(key).filter { it.isOpen }
        assertEquals(1, active.size)
        assertEquals("manual", active.single().source)
    }


    @Test fun `digests are newest first, capped and deletable`() {
        val key = ReplyContactKey("b".repeat(64))
        val store = RelationFactStore(db())
        val total = DigestArchive.MAX_PER_CONTACT + 5
        repeat(total) { index ->
            // 每个时段标签派生一个 id，才能测到 200 条上限。
            val digest = requireNotNull(DigestArchive.build("时段 " + index, 0, index.toLong(), 1, 1,
                "第 " + index + " 条纪要", now = index.toLong()))
            store.saveDigest(key, digest)
        }
        val kept = store.digests(key)
        assertEquals(DigestArchive.MAX_PER_CONTACT, kept.size)
        assertEquals("第 " + (total - 1) + " 条纪要", kept.first().text)
        assertTrue("最旧的几条要被裁掉", kept.none { it.text == "第 0 条纪要" })

        store.deleteDigest(key, kept.first().id)
        assertEquals(DigestArchive.MAX_PER_CONTACT - 1, store.digests(key).size)
    }

    @Test fun `the same digest is stored once and clear removes them`() {
        val key = ReplyContactKey("c".repeat(64))
        val other = ReplyContactKey("d".repeat(64))
        val store = RelationFactStore(db())
        val digest = requireNotNull(DigestArchive.build("今天", 0, 5, 1, 2, "周日一起去看房", now = 9))
        store.saveDigest(key, digest)
        store.saveDigest(key, digest)
        assertEquals("同一时段同一结论只留一条", 1, store.digests(key).size)

        store.saveDigest(other, digest)
        store.clear(key)
        assertTrue(store.digests(key).isEmpty())
        assertEquals("别的会话不受影响", 1, store.digests(other).size)
    }

    @Test fun `deleting a missing digest reports false`() {
        val key = ReplyContactKey("e".repeat(64))
        val store = RelationFactStore(db())
        assertFalse(store.deleteDigest(key, "0".repeat(16)))
        val digest = requireNotNull(DigestArchive.build("今天", 0, 1, 1, 1, "临时约定", now = 2))
        store.saveDigest(key, digest)
        assertTrue(store.deleteDigest(key, digest.id))
        assertFalse("第二次删同一条应当返回 false", store.deleteDigest(key, digest.id))
    }

    @Test fun `only the newest digest survives in one time slot`() {
        val key = ReplyContactKey("f".repeat(64))
        val store = RelationFactStore(db())
        val now = 1_700_000_000_000L
        val first = requireNotNull(DigestArchive.build("最近 3 小时", 0, now, 10, 20, "第一版结论", now = now))
        val again = requireNotNull(DigestArchive.build("最近 3 小时", 0, now, 11, 21, "第二版结论", now = now + 60_000))
        store.saveDigest(key, first)
        store.saveDigest(key, again)
        val kept = store.digests(key)
        assertEquals("同一时段同一天只留一条", 1, kept.size)
        assertEquals("留下的是最新结论", "第二版结论", kept.first().text)
    }

    @Test fun `legacy duplicates in one slot are cleared when the slot is written again`() {
        val key = ReplyContactKey("1".repeat(64))
        val store = RelationFactStore(db())
        val now = 1_700_000_000_000L
        // 旧版按文本派生 id，同一个时段可能已经堆了多条；再写一次要把它们清掉。
        store.saveDigest(key, StoredDigest("a".repeat(16), now, "最近 3 小时", 0, now, 10, 20, "旧结论一"))
        store.saveDigest(key, StoredDigest("b".repeat(16), now + 1000, "最近 3 小时", 0, now, 10, 20, "旧结论二"))
        store.saveDigest(key, requireNotNull(DigestArchive.build("最近 3 小时", 0, now, 12, 22, "新结论", now = now + 2000)))
        val kept = store.digests(key)
        assertEquals(1, kept.size)
        assertEquals("新结论", kept.first().text)
    }

    @Test fun `emotion counts add up and follow the archive lifecycle`() {
        val key = ReplyContactKey("2".repeat(64))
        val store = RelationFactStore(db())
        val session = requireNotNull(EmotionTrend.sessionOf("wxid_demo"))
        // 用真实当前时间：emotionDays 默认按「今天往前 30 天」取，固定时间戳会被当成过期。
        val now = System.currentTimeMillis()
        repeat(3) { store.recordEmotion(session, "平和", "对方", now = now) }
        store.recordEmotion(session, "疑问", "我方口径", now = now)
        val rows = store.emotionDays(session, now = now)
        assertEquals(2, rows.size)
        assertEquals(3, rows.first { it.label == "平和" }.count)
        assertEquals("对方", rows.first { it.label == "平和" }.speaker)

        store.rememberContact(key, "wxid_demo", "阿明")
        store.clear(key)
        assertTrue("清空档案时这个会话的情绪也要一起清掉", store.emotionDays(session).isEmpty())
    }

    @Test fun `contacts list keeps the keys and names for the temperature view`() {
        val key = ReplyContactKey("3".repeat(64))
        val store = RelationFactStore(db())
        store.rememberContact(key, "wxid_abc", "阿明")
        val rows = store.contacts()
        assertEquals(1, rows.size)
        assertEquals(key.value, rows.first().contactKey)
        assertEquals("wxid_abc", rows.first().talker)
        assertEquals("阿明", rows.first().name)
    }
}