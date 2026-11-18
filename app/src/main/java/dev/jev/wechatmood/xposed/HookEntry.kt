package dev.jev.wechatmood.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.hook.MessageSniffer
import dev.jev.wechatmood.hook.ReplyDatabaseHistory

/** Direct package entry, independent of the optional initZygote callback. */
class HookEntry : IXposedHookLoadPackage {
    @Volatile private var installed = false
    @Volatile private var scheduled = false

    private companion object {
        /**
         * 第一个页面 resume 之后再等这么久才装：此时首帧已经画完，
         * 不会和 WeKit 这类重模块的启动初始化叠加。
         */
        const val RESUME_SETTLE_MS = 400L
        /**
         * 纯后台进程可能一直不 resume；等这么久兜底装一次。
         * 12 秒已经越过系统的「surface 未绘制」判定窗口，不会造成白屏。
         */
        const val BACKGROUND_FALLBACK_MS = 12_000L
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        MoodLog.frameworkSink = { XposedBridge.log(it) }
        XposedBridge.log("WeChatMood ${BuildConfig.VERSION_NAME}: entered WeChat main process")
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    // 启动路径上什么都不做，只排一个很晚的兜底。
                    // 之前用主线程 idle 回调也会在冷启动时过早触发，正好压在首帧上。
                    val context = p.args[0] as Context
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!installed) {
                            MoodLog.i("HOOK_INIT_FALLBACK 没有页面 resume，按兜底安装")
                            initialize(context)
                        }
                    }, BACKGROUND_FALLBACK_MS)
                }
            })
        // Covers module loaders that attach after Application.attach has already run.
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity = p.args.firstOrNull() as? Activity ?: return
                if (activity.packageName != "com.tencent.mm") return
                scheduleFromResume(activity)
                if (!installed) return
                runCatching { MessageSniffer.resume(activity) }
                    .onFailure { MoodLog.e("HOST_RESUME_FAILED", it) }
            }
        })
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                (p.args.firstOrNull() as? Activity)?.let { MessageSniffer.pause(it) }
            }
        })
    }

    /**
     * 等第一个页面真正展示出来（resume）之后，再延迟一点装 hook。
     *
     * 微信冷启动时，WeKit 这类模块的初始化已经吃掉大半预算；言外只要在主线程上做任何初始化，
     * 两边时间相加就会超过系统的「surface 未绘制」阈值，表现为白屏或黑屏进不去。
     * resume 之后首帧已经完成，再延迟 400ms 安装就不会落在判定窗口里。
     */
    private fun scheduleFromResume(context: Context) {
        if (installed || scheduled) return
        scheduled = true
        Handler(Looper.getMainLooper()).postDelayed({
            scheduled = false
            if (!installed) initialize(context)
        }, RESUME_SETTLE_MS)
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            ModulePrefs.init(context)
            MessageSniffer.install(context)
            installed = true
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            // 环境采集、日志上报、WCDB 反射扫描都不参与首帧，放后台线程。
            Thread({
                runCatching {
                    MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
                    ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
                    ReplyDatabaseHistory.install(context.classLoader)
                }.onFailure { MoodLog.e("HOOK_INIT_BACKGROUND_FAILED", it) }
            }, "yanwai-init-bg").start()
        }.onFailure {
            XposedBridge.log("WeChatMood initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}
