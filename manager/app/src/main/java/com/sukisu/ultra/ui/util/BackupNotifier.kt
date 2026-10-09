package com.sukisu.ultra.ui.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sukisu.ultra.R
import com.sukisu.ultra.data.backup.AutoBackupOutcome
import com.sukisu.ultra.data.backup.AutoBackupRecord
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.MainActivity

/**
 * 自动备份没跑完时的提示。
 *
 * **只在失败时发**：成功不该打扰用户；而"以为有备份其实没有"是这套功能最糟的失败模式——
 * 自动备份跑完时用户早已离开刷入页，除了通知没有别的办法主动告诉他。
 *
 * 用独立渠道而不是复用 `download_channel`：Android 的静音是按渠道给的，混进"下载"里，
 * 用户想关掉备份通知就得连模块下载通知一起关。
 *
 * 权限是尽力而为：`POST_NOTIFICATIONS` 的申请发生在模块页（`ModuleScreen`），用户拒过就
 * 永远不会出现——所以备份页那条「上次自动备份」记录才是底账，通知只是加一层主动提醒。
 */
class BackupNotifier(private val context: Context = ksuApp) {

    fun notifyIfNeeded(record: AutoBackupRecord) {
        if (record.outcome == AutoBackupOutcome.OK) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        val text = BackupText.autoBackupNotificationText(context, record)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.backup_auto_notify_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setContentIntent(openBackupPendingIntent())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /** 点通知直接落在备份页：那条「上次自动备份」记录就在那里，用户能接着看详情或重试。 */
    private fun openBackupPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_BACKUP
            putExtra(EXTRA_TOKEN, SettingsRepositoryImpl().intentToken)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.backup_channel_name),
            // 失败提示：默认重要度，用户想安静可以单独把这个渠道静音。
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "backup_channel"
        const val ACTION_OPEN_BACKUP = "com.sukisu.ultra.action.OPEN_BACKUP"
        const val EXTRA_TOKEN = "token"

        private const val NOTIFICATION_ID = 0x5355_4201
        private const val REQUEST_CODE = 0x5355
    }
}
