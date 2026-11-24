package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.HttpClients
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Shared by the Android app and opt-in live verification. Never reports raw service bodies. */
class JevHttpClient(private val client: Call.Factory = sharedClient) {

    suspend fun exchangeSuspending(payload: JSONObject, settings: ApiSettings): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(payload, settings))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkFailure())
            }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                continuation.resumeWith(runCatching {
                    try { response.use(::readResponse) } catch (_: IOException) { throw networkFailure() }
                })
            }
        })
    }

    fun exchange(payload: JSONObject, settings: ApiSettings): String {
        try {
            return client.newCall(request(payload, settings)).execute().use(::readResponse)
        } catch (_: IOException) {
            throw networkFailure()
        }
    }

    private fun request(payload: JSONObject, settings: ApiSettings): Request {
        check(settings.isConfigured) { "请先填写并保存 API Key" }
        return Request.Builder().url(settings.endpoint)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
    }

    private fun readResponse(response: Response): String {
        // 网关的错误页可能几 MB：按上限读，超限直接当错误，别让 OOM 落在微信进程里。
        val body = response.body?.let { readWhole(it) }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (!response.isSuccessful || json?.has("error") == true) {
            val error = json?.optJSONObject("error")
            val code = if (response.isSuccessful) error?.optInt("code", response.code) ?: response.code else response.code
            val reason = when {
                error?.optString("type") == "customer_verification_required" ->
                    "Vercel 账户尚未验证，请到 AI Gateway 控制台绑定信用卡后重试"
                code == 401 -> "API Key 无效，请检查所选渠道与密钥是否对应"
                code == 403 -> "账户或模型尚未授权，请到所选渠道控制台检查"
                code == 402 -> "模型账户额度不足，请到所选渠道充值或检查额度"
                code == 429 -> "请求过于频繁，请稍后重试"
                code == 400 || code == 404 || code == 422 -> "接口地址或模型不受支持，请检查渠道和配置"
                code in 300..399 -> "接口发生重定向，请填写最终的 Jev 接口地址"
                else -> "模型服务暂不可用，请稍后重试"
            }
            throw IllegalStateException("$reason（HTTP ${response.code}）")
        }
        // 200 但不是 JSON：多半地址填成了网页或网关的错误页，不能当成功交给解析。
        if (json == null) throw IllegalStateException("接口返回的不是 Jev JSON，请检查地址")
        val usage = json.optJSONObject("usage")
        // JEV 只做情绪与意图分析，用量统一记到「分析」。
        dev.jev.wechatmood.core.UsageReporter.report(
            usage?.optLong("prompt_tokens", 0L) ?: 0L,
            usage?.optLong("completion_tokens", 0L) ?: 0L,
            kind = dev.jev.wechatmood.core.UsageKind.ANALYSIS)
        return body
    }

    /** 多读 1 字节探边界：超了说明是异常响应（错误页、超大 JSON），不去猜里面是什么。 */
    private fun readWhole(body: ResponseBody): String {
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        check(source.buffer.size <= MAX_BODY_BYTES) { "接口返回内容过长（超过 1 MB），请检查接口地址与模型" }
        return source.readUtf8()
    }

    private fun networkFailure() = IllegalStateException("连接超时或网络不可用，请稍后重试")

    companion object {
        /** 单次响应上限，与回复链路的读法保持一致。 */
        private const val MAX_BODY_BYTES = 1024 * 1024L

        /** 共用一份客户端：检测按钮反复点，不应该每次再建一套连接池和线程池。 */
        private val sharedClient: OkHttpClient = HttpClients.shared.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

        /** 进程内复用实例；构造函数仍可注入别的 Call.Factory 供测试使用。 */
        val shared = JevHttpClient(sharedClient)
    }
}
