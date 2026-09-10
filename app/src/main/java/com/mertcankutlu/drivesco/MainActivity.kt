package com.mertcankutlu.drivesco

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var selectedDevice: BluetoothDevice? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val device = selectedDevice ?: return@registerForActivityResult
            startForegroundService(Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(StreamService.EXTRA_DEVICE_ADDRESS, device.address)
            })
            status.text = "Durum: Başlatılıyor…"
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) requestProjection()
        else Toast.makeText(this, "Gerekli izin verilmedi.", Toast.LENGTH_LONG).show()
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
            text = "Ford HFP/SCO müzik aktarımı"
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
            setPadding(0, dp(18), 0, dp(18))
        }
        root.addView(status)
        root.addView(MaterialButton(this).apply {
            text = "Müziği HFP'ye aktar"
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
            text = "İlk sürüm, Android medya sesini HFP/SCO iletişim kanalına yönlendirmeyi test eder."
            textSize = 13f
            alpha = .65f
            setPadding(0, dp(24), 0, 0)
        })
        setContentView(root)
    }

    private fun chooseDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO))
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
            }.show()
    }

    private fun startStreaming() {
        if (selectedDevice == null) {
            chooseDevice()
            return
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 31) permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
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
