package com.jmreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.jmreader.core.CoilSetup
import com.jmreader.core.CrashHandler
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * 应用入口，持有全局依赖容器。
 * 干净无广告：不集成任何统计/广告 SDK，无后台保活。
 *
 * 关键初始化：
 * - Logger：日志系统（文件 + 内存 + Logcat）
 * - CrashHandler：全局未捕获异常 → 写日志，避免静默闪退
 * - Coil ImageLoader：降采样 + 缓存，提升阅读器滚动流畅度
 *
 * 注意：DataStore 读取放到后台协程，不在主线程 runBlocking，避免 ANR。
 * 自定义域名合并异步进行，首次启动若未及时合并也只是用内置域名，不影响可用性。
 */
class JMApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    /** App 级别的协程作用域，用于启动阶段的异步初始化任务。
     *  v27.5 稳定性加固：附加 CrashHandler.coroutineHandler 兜底未捕获异常，
     *  否则任意子协程异常会让整个进程崩溃（SupervisorJob 不传播给兄弟但不会捕获异常）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashHandler.coroutineHandler)

    override fun onCreate() {
        super.onCreate()
        // 顺序很重要：Logger 最先
        Logger.init(this)
        CoilSetup.init(this)
        CrashHandler().install()

        instance = this
        container = AppContainer(this)

        // 在绑定 Coil 前应用上次保存的网络设置，避免冷启动首批请求绕过代理。
        // SettingsStore 构造时已有安全快照；这里仅同步读取一次，网络组件随后再被绑定。
        runCatching {
            val s = container.settingsStore.cachedSnapshot
            if (s.customApiDomains.isNotEmpty()) {
                container.directClient.mergeCustomDomains(s.customApiDomains.toList())
            }
            container.directClient.applyProxy(s.proxy)
            container.downloadManager.applyProxy(s.proxy)
            container.directClient.setPinnedImageCdn(s.pinnedImageCdn)
            container.downloadManager.setConcurrency(s.downloadConcurrency)
        }.onFailure { Logger.w("App", "启动网络设置应用失败", it) }

        // 让 Coil 用已应用设置的直连客户端（共享连接池、超时、Cookie 和代理）。
        CoilSetup.bindOkHttp(container.directClient.http)
        CoilSetup.bindDirectClient(container.directClient)

        // 监听后续设置变化，保证运行时切换代理、CDN 和下载并发立即生效。
        appScope.launch {
            try {
                container.settingsStore.settings.collect { s ->
                    container.directClient.applyProxy(s.proxy)
                    container.downloadManager.applyProxy(s.proxy)
                    container.directClient.setPinnedImageCdn(s.pinnedImageCdn)
                    container.downloadManager.setConcurrency(s.downloadConcurrency)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w("App", "设置监听异常", e)
            }
        }

        Logger.i("App", "JMApp 初始化完成，版本=1.3.0，默认直连模式")
    }

    /** Coil 通过 Application 上下文拿 ImageLoader。 */
    override fun newImageLoader(): ImageLoader = CoilSetup.newImageLoader()

    companion object {
        lateinit var instance: JMApp
            private set
    }
}
