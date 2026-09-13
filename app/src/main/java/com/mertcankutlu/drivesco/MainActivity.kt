package com.mertcankutlu.drivesco

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private var selectedDevice: BluetoothDevice? = null

    private val connectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chooseDevice()
        else Toast.makeText(this, "Yakındaki cihaz izni gerekli.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
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
            text = "Beta V1.0.2 — Ford HFP/SCO"
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
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(diagnostics)

        root.addView(MaterialButton(this).apply {
            text = "HFP ile sesi aktar"
            setOnClickListener { startStreaming() }
        })

        root.addView(MaterialButton(this).apply {
            text = "Durdur"
            setOnClickListener {
                startService(Intent(this@MainActivity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
                status.text = "Durum: Durduruldu"
            }
        })

        root.addView(TextView(this).apply {
            text = "Bu sürüm MediaProjection, ekran paylaşımı veya mikrofon kaydı istemez. DriveSCO yalnızca Android'in iletişim sesini Bluetooth HFP/SCO cihazına yönlendirmeyi dener."
            textSize = 13f
            alpha = .65f
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(root)
    }

    private fun chooseDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
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
            }
            .show()
    }

    private fun updateDiagnostics() {
        val manager = getSystemService(AudioManager::class.java)
        val devices = manager.availableCommunicationDevices
        val sco = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val current = manager.communicationDevice
        val lines = mutableListOf(
            "Seçilen: ${selectedDevice?.name ?: "seçilmedi"}",
            "İletişim cihazları: ${devices.joinToString { it.productName.toString() }}"
        )
        lines += if (sco != null) {
            "HFP/SCO: ${sco.productName} — rate: ${sco.sampleRates.joinToString().ifBlank { "bilinmiyor" }} Hz"
        } else {
            "HFP/SCO: Android iletişim cihazları arasında görünmüyor"
        }
        lines += "Aktif iletişim: ${current?.productName ?: "yok"}"
        diagnostics.text = lines.joinToString("\n")
    }

    private fun startStreaming() {
        val device = selectedDevice
        if (device == null) {
            chooseDevice()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            connectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        startForegroundService(Intent(this, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_DEVICE_ADDRESS, device.address)
        })
        status.text = "Durum: HFP/SCO aktarım rotası etkinleştiriliyor…"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
