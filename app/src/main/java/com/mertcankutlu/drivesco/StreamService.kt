package com.mertcankutlu.drivesco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
    private lateinit var audioManager: AudioManager

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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return stopSelf()

        try {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            projection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, data)
                ?: error("MediaProjection alınamadı")
            routeToBluetoothSco(intent.getStringExtra(EXTRA_DEVICE_ADDRESS))
            startAudioBridge()
        } catch (_: Exception) {
            stopStreaming()
        }
    }

    private fun routeToBluetoothSco(address: String?) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val device = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    (address == null || it.address == address)
            } ?: audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            if (device != null && audioManager.setCommunicationDevice(device)) return
        }
        @Suppress("DEPRECATION") audioManager.startBluetoothSco()
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
    }

    private fun startAudioBridge() {
        val p = projection ?: error("Projection yok")
        val sampleRate = 16000
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minRecord = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding
        )
        if (minRecord <= 0) error("AudioRecord buffer alınamadı")
        val bufferSize = maxOf(minRecord * 2, 4096)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        record = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        running = true
        record!!.startRecording()
        track!!.play()
        worker = Thread {
            val pcm = ShortArray(bufferSize / 2)
            while (running) {
                val n = record?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: -1
                if (n > 0) track?.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        }.also { it.name = "DriveSCO-AudioBridge"; it.start() }
    }

    private fun stopStreaming() {
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (::audioManager.isInitialized) {
            if (Build.VERSION.SDK_INT >= 31) try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DriveSCO", NotificationManager.IMPORTANCE_LOW)
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
