package com.mertcankutlu.drivesco

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private var selectedDevice: BluetoothDevice? = null
    private var testTrack: AudioTrack? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val device = selectedDevice ?: return@registerForActivityResult
            startForegroundService(Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(StreamService.EXTRA_DEVICE_ADDRESS, device.address)
            })
            status.text = "Durum: HFP müzik aktarımı başlatılıyor…"
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) requestProjection()
        else Toast.makeText(this, "Gerekli izin verilmedi.", Toast.LENGTH_LONG).show()
    }

    private val connectPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) chooseDevice() else Toast.makeText(this, "Yakındaki cihaz izni gerekli.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        stopHfpTest()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "DriveSCO"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Ford HFP/SCO — kalite ve bağlantı teşhisi"
            textSize = 15f
            alpha = .75f
            setPadding(0, dp(4), 0, dp(20))
        })
        root.addView(MaterialButton(this).apply {
            text = "Bluetooth cihazı seç"
            setOnClickListener { chooseDevice() }
        })
        status = TextView(this).apply {
            text = "Durum: Hazır"
            textSize = 16f
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(status)
        diagnostics = TextView(this).apply {
            text = "HFP bilgisi: Henüz ölçülmedi"
            textSize = 13f
            alpha = .78f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(diagnostics)
        root.addView(MaterialButton(this).apply {
            text = "HFP bağlantısını test et"
            setOnClickListener { runHfpTest() }
        })
        root.addView(MaterialButton(this).apply {
            text = "Müziği HFP'ye aktar"
            setOnClickListener { startStreaming() }
        })
        root.addView(MaterialButton(this).apply {
            text = "Durdur"
            setOnClickListener {
                stopHfpTest()
                startService(Intent(this@MainActivity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
                status.text = "Durum: Durduruldu"
            }
        })
        root.addView(TextView(this).apply {
            text = "HFP testinde MediaProjection kullanılmaz. Müzik aktarımı ise Android'in başka uygulama sesini yakalama API'sini kullandığı için sistem onayı ister."
            textSize = 13f
            alpha = .65f
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(root)
    }

    private fun chooseDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            Toast.makeText(this, "Önce aracı eşleştir.", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bluetooth cihazı")
            .setItems(devices.map { "${it.name ?: "Bilinmeyen"}\n${it.address}" }.toTypedArray()) { _, which ->
                selectedDevice = devices[which]
                status.text = "Cihaz: ${devices[which].name ?: devices[which].address}"
                updateDiagnostics()
            }.show()
    }

    private fun updateDiagnostics() {
        val manager = getSystemService(AudioManager::class.java)
        val devices = manager.availableCommunicationDevices
        val sco = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val current = manager.communicationDevice
        val selectedName = selectedDevice?.name ?: "seçilmedi"
        val lines = mutableListOf("Seçilen: $selectedName")
        lines += "İletişim cihazları: ${devices.joinToString { it.productName.toString() }}"
        lines += if (sco != null) {
            val rates = sco.sampleRates.joinToString().ifBlank { "bilinmiyor" }
            val encodings = sco.encodings.joinToString().ifBlank { "bilinmiyor" }
            "HFP/SCO: ${sco.productName} — rate: $rates Hz — PCM: $encodings"
        } else "HFP/SCO: Android iletişim cihazları arasında görünmüyor"
        lines += "Aktif iletişim: ${current?.productName ?: "yok"}"
        diagnostics.text = lines.joinToString("\n")
    }

    private fun runHfpTest() {
        if (selectedDevice == null) {
            chooseDevice()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        stopHfpTest()
        val manager = getSystemService(AudioManager::class.java)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        var routeOk = false
        if (Build.VERSION.SDK_INT >= 31) {
            val sco = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (sco != null) routeOk = manager.setCommunicationDevice(sco)
        } else {
            @Suppress("DEPRECATION") manager.startBluetoothSco()
            @Suppress("DEPRECATION") manager.isBluetoothScoOn = true
            routeOk = true
        }
        updateDiagnostics()
        if (!routeOk) {
            status.text = "HFP testi: Bluetooth SCO iletişim cihazı seçilemedi"
            return
        }
        val device = if (Build.VERSION.SDK_INT >= 31) manager.communicationDevice else null
        val sampleRate = chooseTestRate(device)
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            status.text = "HFP testi: ses tamponu oluşturulamadı"
            return
        }
        val buffer = ShortArray(maxOf(1024, minBuffer / 2))
        for (i in buffer.indices) {
            val t = i.toDouble() / sampleRate
            buffer[i] = (sin(2.0 * PI * 440.0 * t) * 7000.0).toInt().toShort()
        }
        testTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minBuffer, buffer.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        testTrack?.play()
        testTrack?.write(buffer, 0, buffer.size)
        status.text = "HFP testi: ${if (device != null) "SCO seçildi" else "SCO başlatıldı"} — ${sampleRate} Hz test sesi gönderildi"
    }

    private fun chooseTestRate(device: AudioDeviceInfo?): Int {
        val rates = device?.sampleRates?.toSet().orEmpty()
        return when {
            16000 in rates -> 16000
            8000 in rates -> 8000
            else -> 16000
        }
    }

    private fun stopHfpTest() {
        try { testTrack?.stop() } catch (_: Exception) {}
        try { testTrack?.release() } catch (_: Exception) {}
        testTrack = null
        val manager = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            try { manager.clearCommunicationDevice() } catch (_: Exception) {}
        } else {
            @Suppress("DEPRECATION") manager.isBluetoothScoOn = false
            @Suppress("DEPRECATION") manager.stopBluetoothSco()
        }
        manager.mode = AudioManager.MODE_NORMAL
    }

    private fun startStreaming() {
        if (selectedDevice == null) {
            chooseDevice()
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
