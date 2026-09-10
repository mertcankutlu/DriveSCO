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
import android.os.Process
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
            notification("HFP/SCO müzik aktarımı hazırlanıyor…"),
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
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    stopSelf()
                }
            }, null)
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
                    (address.isNullOrBlank() || it.address == address)
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
        val outputDevice = if (Build.VERSION.SDK_INT >= 31) audioManager.communicationDevice else null
        val sampleRate = chooseSampleRate(outputDevice)
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minRecord = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, encoding
        )
        val minTrack = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, encoding
        )
        if (minRecord <= 0 || minTrack <= 0) error("Ses tamponu alınamadı")
        val bufferSize = maxOf(minRecord * 2, minTrack * 2, 4096)

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
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (Build.VERSION.SDK_INT >= 23 && outputDevice != null) {
            track?.setPreferredDevice(outputDevice)
        }

        running = true
        record!!.startRecording()
        track!!.play()
        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val pcm = ShortArray(bufferSize / 2)
            while (running && !Thread.currentThread().isInterrupted) {
                val n = record?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: -1
                if (n > 0) {
                    var offset = 0
                    while (offset < n && running && !Thread.currentThread().isInterrupted) {
                        val written = track?.write(pcm, offset, n - offset, AudioTrack.WRITE_BLOCKING) ?: -1
                        if (written <= 0) break
                        offset += written
                    }
                }
            }
        }.also { it.name = "DriveSCO-AudioBridge"; it.start() }
    }

    private fun chooseSampleRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.toSet().orEmpty()
        return when {
            16000 in rates -> 16000
            8000 in rates -> 8000
            else -> 16000
        }
    }

    private fun stopStreaming() {
        running = false
        worker?.interrupt()
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
            if (Build.VERSION.SDK_INT >= 31) {
                try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
            }
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
