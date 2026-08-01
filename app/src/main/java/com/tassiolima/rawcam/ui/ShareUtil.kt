package com.tassiolima.rawcam.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.Color

/** Quick-share targets shown as dedicated buttons on the review screen. */
enum class ShareTarget(val packageName: String, val label: String, val brandColor: Color) {
    WHATSAPP("com.whatsapp", "WhatsApp", Color(0xFF25D366)),
    TELEGRAM("org.telegram.messenger", "Telegram", Color(0xFF29A9EA)),
    INSTAGRAM("com.instagram.android", "Instagram", Color(0xFFE1306C)),
}

object ShareUtil {

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun shareToApp(context: Context, uri: Uri, mimeType: String, target: ShareTarget) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(target.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun shareGeneric(context: Context, uri: Uri, mimeType: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Compartilhar").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
