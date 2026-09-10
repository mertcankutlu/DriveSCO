package com.mertcankutlu.drivesco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class StreamService : Service() {
    companion object {
        const val ACTION_START = "com.mertcankutlu.drivesco.START"
        const val ACTION_STOP = "com.mertcankutlu.drivesco.STOP"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val CHANNEL_ID = "drivesco_stream"
        private const val NOTIFICATION_ID = 1001
    }

    private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent)
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        if (running) return
        startForeground(
            NOTIFICATION_ID,
            notification("HFP/SCO aktarımı hazırlanıyor…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return stopSelf()

        val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
        try {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            projection = projectionManager.getMediaProjection(RESULT_OK, data)
            if (projection == null) throw IllegalStateException("MediaProjection alınamadı")

            routeToBluetoothSco(address)
            startAudioBridge()
        } catch (e: Exception) {
            stopStreaming()
            stopSelf()
        }
    }

    private fun routeToBluetoothSco(address: String?) {
        val am = audioManager ?: error("AudioManager yok")
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= 31) {
            val device = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    (address == null || it.address == address)
            } ?: am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (device != null) {
                am.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION") am.startBluetoothSco()
                @Suppress("DEPRECATION") am.isBluetoothScoOn = true
            }
        } else {
            @Suppress("DEPRECATION") am.startBluetoothSco()
            @Suppress("DEPRECATION") am.isBluetoothScoOn = true
        }
    }

    private fun startAudioBridge() {
        val p = projection ?: error("Projection yok")
        val sampleRate = 16000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minRecord = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minRecord <= 0) error("AudioRecord buffer alınamadı")
        val bufferSize = maxOf(minRecord * 2, 4096)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val outputAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        track = AudioTrack.Builder()
            .setAudioAttributes(outputAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        running = true
        record?.startRecording()
        track?.play()

        worker = Thread {
            val pcm = ShortArray(bufferSize / 2)
            while (running) {
                val n = record?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: -1
                if (n > 0) track?.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        }.also { it.name = "DriveSCO-AudioBridge"; it.start() }
    }

    private fun stopStreaming() {
        if (!running && projection == null) {
            stopSelf()
            return
        }
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= 31) {
                try { am.clearCommunicationDevice() } catch (_: Exception) {}
            }
            @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            @Suppress("DEPRECATION") am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DriveSCO", NotificationManager.IMPORTANCE_LOW).apply {
                description = "HFP/SCO ses aktarımı"
            }
        )
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("DriveSCO")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
