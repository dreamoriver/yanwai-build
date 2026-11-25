package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All persistence goes through the existing UID-restricted provider, never host storage. */
object ReplyIdentityBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-reply-identity") }
    private val backgrounds = ContactBackgroundCache()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<BackgroundScope, ReplyContactKey>>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Pair<BackgroundScope, ReplyContactKey>, Long>()
    private class BackgroundScope(val generation: String, val revision: Long) {
        val token get() = "$generation:$revision"
        fun current() = ModulePrefs.analysisSettings()?.let { it.generation == generation && it.rolesRevision == revision } == true
        override fun equals(other: Any?) = other is BackgroundScope && token == other.token
        override fun hashCode() = token.hashCode()
    }
    private fun generation(): BackgroundScope = requireNotNull(ModulePrefs.analysisSettings()) { "设置尚未连接" }
        .let { BackgroundScope(it.generation, it.rolesRevision) }.also { backgrounds.selectGeneration(it.token) }
    private fun accept(generation: BackgroundScope, key: ReplyContactKey, value: ContactBackground): ContactBackground {
        check(generation.current() && backgrounds.put(generation.token, key, value)) { "角色或设置已变化，请重新打开" }
        return value
    }
    private fun readBackground(context: Context, key: ReplyContactKey, generation: BackgroundScope): ContactBackground {
        val result = SettingsTransport.call(context, "contact_background_get", key.value,
            Bundle().apply { putString(SettingsProvider.KEY_GENERATION, generation.generation) })
        val payload = requireNotNull(result?.getString("payload"))
        return accept(generation, key, ContactBackground.decode(payload)).also {
            ReplySnapshotCache.save(context, key.value, "bg", payload)
        }
    }
    fun background(context: Context, key: ReplyContactKey): ContactBackground? {
        val generation = runCatching { generation() }.getOrNull() ?: return null
        backgrounds.get(generation.token, key)?.let { return it }
        // 主进程拉不起来时直接用上次成功读到的背景，别让分析整块停掉。
        ReplySnapshotCache.load(context, key.value, "bg")?.let { cached ->
            runCatching { ContactBackground.decode(cached) }.getOrNull()?.let { value ->
                return runCatching { accept(generation, key, value) }.getOrDefault(value)
            }
        }
        val scope = generation to key
        if (failedAt[scope]?.let { System.nanoTime() - it < 5_000_000_000L } == true) return null
        if (loading.add(scope)) worker.execute {
            try { readBackground(context.applicationContext, key, generation); failedAt.remove(scope) }
            catch (_: Exception) { failedAt[scope] = System.nanoTime() }
            finally { loading.remove(scope) }
        }
        return null
    }
    suspend fun loadBackground(context: Context, key: ReplyContactKey): ContactBackground = suspendCancellableCoroutine { c ->
        val app = context.applicationContext
        val generation = generation()
        worker.execute {
            val result = runCatching { readBackground(app, key, generation) }.recoverCatching {
                // 没有缓存也降级成「无背景」，不让抽屉打不开。
                ReplySnapshotCache.load(app, key.value, "bg")
                    ?.let { runCatching { ContactBackground.decode(it) }.getOrNull() } ?: ContactBackground()
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun saveBackground(context: Context, owner: ReplyIdentityOwner, text: String,
        verify: () -> String?): ContactBackground = suspendCancellableCoroutine { c ->
        val generation = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
                check(generation.current()) { "设置已重置，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = SettingsTransport.call(context, "contact_background_put", key.value,
                    Bundle().apply { putString("text", text); putString(SettingsProvider.KEY_GENERATION, generation.generation); putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, generation.revision) })
                ContactBackground.decode(requireNotNull(response?.getString("payload"))).also {
                    accept(generation, key, it)
                    ModulePrefs.backgroundChanged(owner.talker)
                }
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun roles(context: Context): List<ReplyRole> = suspendCancellableCoroutine { c ->
        val captured = generation()
        worker.execute {
            val app = context.applicationContext
            var online = false
            val result = runCatching {
                val response = SettingsTransport.call(app, "reply_role_list", null,
                    Bundle().apply { putString(SettingsProvider.KEY_GENERATION, captured.generation) })
                check(captured.current()) { "角色已变化，请重试" }
                online = true
                requireNotNull(response?.getStringArrayList("roles")).also {
                    ReplySnapshotCache.saveRoles(app, it)
                }.map(ReplyRole::decode)
            }.recoverCatching {
                ReplySnapshotCache.loadRoles(app)?.map(ReplyRole::decode) ?: emptyList()
            }
            // 服务通了就把离线期间攒下的角色应用补写回主库。
            if (online) runCatching { flushPending(app) }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun saveRole(context: Context, owner: ReplyIdentityOwner, identity: ReplyIdentitySetting, text: String,
        verify: () -> String?): AppliedReplyRole = suspendCancellableCoroutine { c ->
        val captured = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify()) && captured.current()) { "账号或角色已变化，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = requireNotNull(SettingsTransport.call(context, "reply_role_put", key.value,
                    Bundle().apply {
                        putString(SettingsProvider.KEY_GENERATION, captured.generation)
                        putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
                        putString("identity", identity.encode()); putString("text", text)
                    }))
                val revision = response.getLong(ReplyIdentityProvider.KEY_ROLE_REVISION)
                // A role edit affects every contact explicitly using that role. Adopt its new catalog
                // revision before filling the cache, and reject a concurrent newer edit or reset.
                ModulePrefs.reload(force = true)
                val updated = generation()
                check(updated.generation == captured.generation && updated.revision == revision) { "角色或设置已变化，请重新打开" }
                val background = accept(updated, key, ContactBackground.decode(requireNotNull(response.getString("background"))))
                ModulePrefs.backgroundChanged(owner.talker)
                AppliedReplyRole(ReplyIdentitySetting.decode(requireNotNull(response.getString("identity"))), background, revision)
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun applyRole(context: Context, owner: ReplyIdentityOwner, role: ReplyRole, verify: () -> String?): AppliedReplyRole =
        suspendCancellableCoroutine { c ->
            // 角色库被迁移或新增过时，ModulePrefs 里的 catalog revision 可能还停在旧值；
            // 拿旧值去和库里的比对一定被拒，所以这里先强制拉一次再取。
            val app = context.applicationContext
            worker.execute {
                val result = runCatching {
                    runCatching { ModulePrefs.reload(force = true) }
                    val captured = generation()
                    check(owner.acceptsAccount(verify()) && captured.current()) { "账号或角色已变化，请重新打开" }
                    val key = requireNotNull(owner.key)
                    val response = requireNotNull(SettingsTransport.call(app, "reply_role_apply", key.value,
                        Bundle().apply {
                            putString(SettingsProvider.KEY_GENERATION, captured.generation)
                            putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
                            putString("role_id", role.id); putString("role_revision", role.revision)
                        }))
                    val identity = ReplyIdentitySetting.decode(requireNotNull(response.getString("identity")))
                    val background = accept(captured, key, ContactBackground.decode(requireNotNull(response.getString("background"))))
                    ModulePrefs.backgroundChanged(owner.talker)
                    AppliedReplyRole(identity, background)
                }.recoverCatching { error ->
                    // 言外被系统停掉时主库写不进去：先本地生效，等下次连上服务再补写。
                    check(ReplyPendingWrites.isOffline(error)) { throw error }
                    val key = requireNotNull(owner.key) { throw error }
                    applyLocally(app, key, role)
                }
                if (c.isActive) c.resumeWith(result)
            }
        }
    /** 服务连不上时本地生效：写快照并排队，面板和后续回复立刻按新身份走。 */
    private fun applyLocally(context: Context, key: ReplyContactKey, role: ReplyRole): AppliedReplyRole {
        val identity = role.identity()
        val background = ContactBackground(role.background)
        ReplySnapshotCache.save(context, key.value, "id", identity.encode())
        ReplySnapshotCache.save(context, key.value, "bg", background.encode())
        ReplyPendingWrites.enqueue(context, key.value, role.id, role.revision)
        MoodLog.i("REPLY_ROLE_LOCAL relationship=" + role.relationship.id)
        return AppliedReplyRole(identity, background)
    }

    /** 把离线期间排队的角色应用补写回主库，写不成的留着下次再试。 */
    private fun flushPending(context: Context) {
        val settings = ModulePrefs.analysisSettings() ?: return
        val pending = ReplyPendingWrites.take(context)
        if (pending.isEmpty()) return
        val left = pending.filterNot { entry ->
            runCatching {
                SettingsTransport.call(context, "reply_role_apply", entry.key, Bundle().apply {
                    putString(SettingsProvider.KEY_GENERATION, settings.generation)
                    putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, settings.rolesRevision)
                    putString("role_id", entry.roleId)
                    putString("role_revision", entry.roleRevision)
                })
            }.isSuccess
        }
        ReplyPendingWrites.save(context, left)
        MoodLog.i("REPLY_PENDING_FLUSH done=" + (pending.size - left.size) + " left=" + left.size)
    }

    private fun queue(context: Context): ReplyIdentityQueue {
        val captured = generation()
        fun extras() = Bundle().apply {
            putString(SettingsProvider.KEY_GENERATION, captured.generation)
            putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
        }
        return ReplyIdentityQueue({ worker.execute(it) }, { key ->
            val result = SettingsTransport.call(context, "reply_identity_get", key.value, extras())
            check(captured.current()) { "角色已变化，请重新打开" }
            ReplyIdentitySetting.decode(requireNotNull(result?.getString("payload")))
        }, { key, value ->
            check(captured.current()) { "角色已变化，请重新打开" }
            val result = SettingsTransport.call(context, "reply_identity_put", key.value,
                extras().apply { putString("payload", value.encode()) })
            check(result?.getBoolean("saved") == true)
            readBackground(context, key, captured)
        })
    }
    suspend fun load(context: Context, key: ReplyContactKey): ReplyIdentitySetting = suspendCancellableCoroutine { continuation ->
        val app = context.applicationContext
        fun fallback() {
            if (!continuation.isActive) return
            val cached = ReplySnapshotCache.load(app, key.value, "id")
                ?.let { runCatching { ReplyIdentitySetting.decode(it) }.getOrNull() }
            // 读不到就按「未指定身份」继续，用户仍可在抽屉里手动选。
            continuation.resume(cached ?: ReplyIdentitySetting())
        }
        // queue() 里的 generation() 在设置还没连上时会抛（requireNotNull）。这是抽屉入口，
        // 抛出去会一路带回宿主；设置未连上时不能崩宿主，退回缓存或空身份。
        val queued = runCatching {
            queue(app).load(key) callback@{ result ->
                if (!continuation.isActive) return@callback
                result.fold({ value ->
                    ReplySnapshotCache.save(app, key.value, "id", value.encode())
                    continuation.resume(value)
                }, { fallback() })
            }
        }
        if (queued.isFailure) {
            MoodLog.w("REPLY_IDENTITY_LOAD_NO_SETTINGS " + queued.exceptionOrNull()?.javaClass?.simpleName)
            fallback()
        }
    }
    fun save(context: Context, owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        // 同上：设置未连上时 queue() 会抛，这里是 UI 回调，必须按回调约定报失败而不是让微信进程崩溃。
        val queued = runCatching {
            queue(context.applicationContext).save(owner, value, verify) { saved ->
                if (saved) ModulePrefs.backgroundChanged(owner.talker)
                complete(saved)
            }
        }
        if (queued.isFailure) {
            MoodLog.w("REPLY_IDENTITY_SAVE_NO_SETTINGS " + queued.exceptionOrNull()?.javaClass?.simpleName)
            complete(false)
        }
    }
}
