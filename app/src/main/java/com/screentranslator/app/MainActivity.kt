package com.screentranslator.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    companion object {
        private const val REQ_PROJECTION = 1001
        private const val REQ_OVERLAY = 1002
        private const val REQ_NOTIFICATIONS = 1003
    }

    private lateinit var modeSpinner: Spinner
    private lateinit var apiKeyEdit: EditText
    private lateinit var intervalEdit: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeSpinner = findViewById(R.id.modeSpinner)
        apiKeyEdit = findViewById(R.id.apiKeyEdit)
        intervalEdit = findViewById(R.id.intervalEdit)
        statusText = findViewById(R.id.statusText)

        ArrayAdapter.createFromResource(
            this, R.array.modes, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinner.adapter = adapter
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        modeSpinner.setSelection(if (prefs.getString("mode", "offline") == "online") 1 else 0)
        apiKeyEdit.setText(prefs.getString("apiKey", ""))
        intervalEdit.setText((prefs.getLong("intervalMs", 2000L) / 1000).toString())

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    REQ_OVERLAY
                )
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
            savePrefs()
            startProjection()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, TranslationService::class.java).apply {
                action = TranslationService.ACTION_STOP
            })
            statusText.text = "Остановлено"
        }
    }

    private fun savePrefs() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("mode", if (modeSpinner.selectedItemPosition == 1) "online" else "offline")
            .putString("apiKey", apiKeyEdit.text.toString().trim())
            .putLong(
                "intervalMs",
                (intervalEdit.text.toString().toLongOrNull() ?: 2L).coerceIn(1L, 10L) * 1000L
            )
            .apply()
    }

    private fun startProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                // Повторный запуск пользователем после выдачи разрешения
                if (Settings.canDrawOverlays(this)) {
                    savePrefs()
                    startProjection()
                } else {
                    statusText.text = "Нужно разрешение «отображение поверх других приложений»."
                }
            }
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, TranslationService::class.java).apply {
                        action = TranslationService.ACTION_START
                        putExtra(TranslationService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(TranslationService.EXTRA_DATA, data)
                    }
                    startForegroundService(intent)
                    statusText.text =
                        "Работает. Появится пузырёк — тап по нему включает/выключает перевод, перетаскивание двигает."
                } else {
                    statusText.text = "Разрешение на запись экрана не выдано."
                }
            }
        }
    }
}
