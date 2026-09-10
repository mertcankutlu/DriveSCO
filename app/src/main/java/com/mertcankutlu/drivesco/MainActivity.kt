package com.mertcankutlu.drivesco

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var selectedDevice: BluetoothDevice? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val device = selectedDevice ?: return@registerForActivityResult
            val intent = Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_PROJECTION_DATA, result.data)
                putExtra(StreamService.EXTRA_DEVICE_ADDRESS, device.address)
            }
            startForegroundService(intent)
            status.text = "Durum: Başlatılıyor…"
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.RECORD_AUDIO] == true &&
            (android.os.Build.VERSION.SDK_INT < 31 || permissions[Manifest.permission.BLUETOOTH_CONNECT] == true)
        if (ok) requestProjection() else Toast.makeText(this, "Gerekli izinler verilmedi.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            gravity = Gravity.TOP
        }

        val title = TextView(this).apply {
            text = "DriveSCO"
            textSize = 30f
            setTextColor(Color.WHITE)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))

        val subtitle = TextView(this).apply {
            text = "HFP/SCO üzerinden müzik aktarımı"
            textSize = 15f
            alpha = 0.75f
            setPadding(0, dp(4), 0, dp(20))
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, -2))

        val deviceButton = MaterialButton(this).apply {
            text = "Bluetooth cihazı seç"
            setOnClickListener { chooseDevice() }
        }
        root.addView(deviceButton, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "Durum: Hazır"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(18))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val start = MaterialButton(this).apply {
            text = "Müziği HFP'ye aktar"
            setOnClickListener { startStreaming() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, -2))

        val stop = MaterialButton(this).apply {
            text = "Durdur"
            setOnClickListener {
                startService(Intent(this@MainActivity, StreamService::class.java).setAction(StreamService.ACTION_STOP))
                status.text = "Durum: Durduruldu"
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, -2))

        val info = TextView(this).apply {
            text = "İlk sürüm: Android sesini HFP/SCO iletişim kanalına yönlendirmeyi test eder. Gerçek codec ve araç desteği sonraki aşamada ölçülecek."
            textSize = 13f
            alpha = 0.65f
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun chooseDevice() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth desteklenmiyor.", Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO))
            return
        }
        val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: it.address }
        if (devices.isEmpty()) {
            Toast.makeText(this, "Önce aracı Bluetooth ile eşleştir.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { "${it.name ?: "Bilinmeyen cihaz"}\n${it.address}" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Bluetooth cihazı")
            .setItems(labels.toTypedArray()) { _, which ->
                selectedDevice = devices[which]
                status.text = "Cihaz: ${devices[which].name ?: devices[which].address}"
            }
            .show()
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
