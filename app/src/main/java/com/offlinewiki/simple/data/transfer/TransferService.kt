package com.offlinewiki.simple.data.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.offlinewiki.simple.App
import com.offlinewiki.simple.MainActivity
import com.offlinewiki.simple.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [TransferManager] is working, so a
 * multi-gigabyte download is not killed when the user leaves the app.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(TransferState.Idle), type)

        val transfers = (application as App).container.transfers
        scope.launch {
            transfers.state.collect { state ->
                if (state is TransferState.Running) {
                    manager.notify(NOTIFICATION_ID, build(state))
                } else {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // Android 15+ limits dataSync services to six hours a day; pause so the download can be resumed.
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as App).container.transfers.pause()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: TransferState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_book)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state is TransferState.Running) {
            val title = when (state.phase) {
                Phase.CONNECTING, Phase.DOWNLOADING -> "Downloading ${state.kind.label.lowercase()}"
                Phase.COPYING -> "Importing ${state.kind.label.lowercase()}"
                Phase.VERIFYING -> "Checking ${state.kind.label.lowercase()}"
            }
            builder.setContentTitle(title)
            if (state.totalBytes > 0) {
                val percent = (state.doneBytes * 100 / state.totalBytes).toInt()
                builder.setProgress(100, percent, false).setContentText("$percent%")
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setContentTitle("Preparing…").setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }
    }
}
