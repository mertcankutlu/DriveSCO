package com.mertcankutlu.drivesco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat

class StreamService : Service() {
    companion object {
        const val ACTION_START = "com.mertcankutlu.drivesco.START"
        const val ACTION_STOP = "com.mertcankutlu.drivesco.STOP"
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        private const val CHANNEL_ID = "drivesco_stream"
        private const val NOTIFICATION_ID = 1001
    }

    private var running = false
    private var keepAliveTrack: AudioTrack? = null
    private var worker: Thread? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming(intent.getStringExtra(EXTRA_DEVICE_ADDRESS))
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(address: String?) {
        if (running) return

        startForeground(
            NOTIFICATION_ID,
            notification("HFP/SCO bağlantısı aktif"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            requestAudioFocus()
            routeToBluetoothSco(address)
            startKeepAlive()
            running = true
        } catch (_: Exception) {
            stopStreaming()
        }
    }

    private fun requestAudioFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        }
    }

    private fun routeToBluetoothSco(address: String?) {
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

    private fun startKeepAlive() {
        val sampleRate = 8000
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        keepAliveTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, 2048))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (Build.VERSION.SDK_INT >= 31) {
            audioManager.communicationDevice?.let { keepAliveTrack?.setPreferredDevice(it) }
        }

        keepAliveTrack?.play()
        running = true

        worker = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val silence = ShortArray(160)
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    keepAliveTrack?.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                } catch (_: Exception) {
                    break
                }
            }
        }.also {
            it.name = "DriveSCO-HfpKeepAlive"
            it.start()
        }
    }

    private fun stopStreaming() {
        running = false
        worker?.interrupt()
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null

        try { keepAliveTrack?.stop() } catch (_: Exception) {}
        try { keepAliveTrack?.release() } catch (_: Exception) {}
        keepAliveTrack = null

        if (::audioManager.isInitialized) {
            if (Build.VERSION.SDK_INT >= 31) {
                try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            } else {
                @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
            }
            focusRequest?.let {
                if (Build.VERSION.SDK_INT >= 26) {
                    try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {}
                }
            }
            focusRequest = null
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
            .setContentTitle("DriveSCO Beta V1.0.2")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
