package com.example.data.gmail

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity

object EmailNotificationHelper {

    private const val CHANNEL_ID = "applyflow_email_sent_channel"
    private const val CHANNEL_NAME = "Sent Application Emails"

    fun showEmailSentNotification(
        context: Context,
        toEmail: String,
        companyName: String,
        resumeFileName: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifies immediately when a job application email is sent"
                }
                manager.createNotificationChannel(channel)
            }
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            toEmail.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleTarget = companyName.takeIf { it.isNotBlank() && !it.equals("Hiring Team", ignoreCase = true) }
            ?: toEmail
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Email Sent • $titleTarget")
            .setContentText("Sent to $toEmail with $resumeFileName")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Application emailed to $toEmail with $resumeFileName attached.")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify((System.currentTimeMillis() and 0x7FFFFFFFL).toInt(), notification)
        }
    }
}
