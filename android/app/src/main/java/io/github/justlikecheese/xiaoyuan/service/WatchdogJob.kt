package io.github.justlikecheese.xiaoyuan.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.justlikecheese.xiaoyuan.backend
import io.github.justlikecheese.xiaoyuan.core.Guard

/**
 * 保活的兜底：每 15 分钟（系统允许的最短周期）由系统拉起一次。前台服务方式：服务被杀了就重新启动，并检测一次在线状态；
 * 守护方式：守护挂了就重新拉起。
 * 进程被系统或手机管家清掉后，JobScheduler 的任务还在（开机后也会恢复），比只靠前台服务稳。
 * 后台启动前台服务需要应用在电池优化白名单里（安卓 12 起），不在的话这次只检测、不拉起服务。
 */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val b = backend
        if (!b.reconnect.value.enabled) return false
        if (b.guardMode() == Guard.SERVICE) {
            KeepAliveService.sync(this, true)
            b.reconnectTick()
            return false
        }
        // 用守护保活：守护还在跑就什么也不做；挂了就按原方式重新拉起，拉不起来（ADB 方式、没有授权）就开前台服务兜底
        b.refreshGuard()
        if (!Guard.status(this, b.reconnectInterval()).running) {
            b.restartGuard { ok -> if (!ok) KeepAliveService.sync(this, true) }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 1001
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** 开着自动重连就排上（已排上的不重复排），关掉就取消。 */
        fun sync(context: Context, enabled: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)   // 重启手机后还在（需要 RECEIVE_BOOT_COMPLETED）
                    .build(),
            )
        }
    }
}
