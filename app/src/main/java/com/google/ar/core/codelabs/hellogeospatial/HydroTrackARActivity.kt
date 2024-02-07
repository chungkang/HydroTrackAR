/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.codelabs.hellogeospatial.helpers.ARCoreSessionLifecycleHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.GeoPermissionsHelper
import com.google.ar.core.codelabs.hellogeospatial.helpers.HydroTrackARView
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HydroTrackARActivity : AppCompatActivity() {
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var usbSerialPort: UsbSerialPort? = null
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: HydroTrackARView
    lateinit var renderer: HydroTrackARRenderer


    companion object {
        private const val TAG = "HydroTrackARActivity"
        private const val ACTION_USB_PERMISSION = "com.google.ar.core.codelabs.hellogeospatial.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeARCoreSession()
        initializeARComponents()

        // USB 권한 요청에 대한 BroadcastReceiver 등
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbPermissionReceiver, filter)


        var switchLog = findViewById<Switch>(R.id.switch_log) as Switch

        switchLog.setOnClickListener {
            if (switchLog.isChecked) {
                Toast.makeText(this, "Logging Start", Toast.LENGTH_SHORT).show()
                setupUsbSerial()
            } else {
                Toast.makeText(this, "Logging Stop", Toast.LENGTH_SHORT).show()
                closeUsbSerial()
            }
        }
    }

    private fun initializeARCoreSession() {
        // Setup ARCore session lifecycle helper and configuration.
        arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
        arCoreSessionHelper.exceptionCallback = { exception ->
            handleARCoreExceptions(exception)
        }
        // Configure session features before session is resumed.
        arCoreSessionHelper.beforeSessionResume = ::configureSession
    }

    private fun handleARCoreExceptions(exception: Exception) {
        val message = when (exception) {
            is UnavailableUserDeclinedInstallationException ->
                "Please install Google Play Services for AR"

            is UnavailableApkTooOldException -> "Please update ARCore"
            is UnavailableSdkTooOldException -> "Please update this app"
            is UnavailableDeviceNotCompatibleException -> "This device does not support AR"
            is CameraNotAvailableException -> "Camera not available. Try restarting the app."
            else -> "Failed to create AR session: $exception"
        }
        Toast.makeText(this, "ARCore threw an exception: $exception", Toast.LENGTH_SHORT).show()

        view.snackbarHelper.showError(this, message)
    }


    private fun initializeARComponents() {
        renderer = HydroTrackARRenderer(this)
        view = HydroTrackARView(this)
        lifecycle.addObserver(arCoreSessionHelper)
        lifecycle.addObserver(renderer)
        lifecycle.addObserver(view)
        setContentView(view.root)
        SampleRender(view.surfaceView, renderer, assets)
    }

    // USB 권한 요청 결과를 처리하는 BroadcastReceiver
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            // 권한이 부여되었을 때 수행할 작업
                            Log.d(TAG, "USB permission granted for device $device")
                            setupUsbSerial() // 권한이 부여된 후 USB 시리얼 설정을 재시도
                        }
                    } else {
                        Log.d(TAG, "USB permission denied for device $device")
                    }
                }
            }
        }
    }


    // Method to setup USB serial communication
    private fun setupUsbSerial() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "No USB devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers.first()
        val device = driver.device

        if (!manager.hasPermission(device)) {
            requestUsbPermission(manager, device)
        } else {
            connectToDevice(manager, driver)
        }
    }

    private fun requestUsbPermission(manager: UsbManager, device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(device, permissionIntent)
    }

    private fun connectToDevice(manager: UsbManager, driver: UsbSerialDriver) {
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            Toast.makeText(this, "Opening device failed", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            usbSerialPort = driver.ports[0].apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Error setting up device: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }



    private fun closeUsbSerial() {
        usbSerialPort?.close()
        usbSerialPort = null
        backgroundHandler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        backgroundThread.quitSafely()
        usbSerialPort?.close()
    }

    // Configure the session, setting the desired options according to your usecase.
    fun configureSession(session: Session) {
        // TODO: Configure ARCore to use GeospatialMode.ENABLED.
        session.configure(
            session.config.apply {
                // Enable Geospatial Mode.
                geospatialMode = Config.GeospatialMode.ENABLED
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Camera and location permissions are needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                GeoPermissionsHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    private fun readNmeaData(): String {
        usbSerialPort?.let { port ->
            try {
                val buffer = ByteArray(1024)
                val numBytesRead = port.read(buffer, 1000)
                return String(buffer, 0, numBytesRead, StandardCharsets.UTF_8)
            } catch (e: IOException) {
                Toast.makeText(this, "Error reading from device: ${e.message}", Toast.LENGTH_SHORT).show()
                return ""
            }
        }
        return ""
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("USBBackgroundThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
        backgroundHandler.post(object : Runnable {
            override fun run() {
                val data = readNmeaData()
                if (data.isNotEmpty()) {
                    saveDataToDownloadFolder(data)
                }
                backgroundHandler.postDelayed(this, 1000) // 1초 후에 다시 실행
            }
        })
    }

    private fun saveDataToDownloadFolder(data: String) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "NmeaData_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val contentResolver = applicationContext.contentResolver
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                ?: throw IOException("Failed to create new MediaStore record.")

            contentResolver.openFileDescriptor(uri, "w").use { pfd ->
                FileOutputStream(pfd?.fileDescriptor).use { fos ->
                    fos.write((data + "\n").toByteArray())
                }
            }

            Toast.makeText(this, "NMEA data saved.", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save NMEA data to file", e)
            Toast.makeText(this, "Failed to save NMEA data to file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }



    fun parseNmeaData(nmeaData: String): Pair<Double?, Double?> {
        val pattern = Pattern.compile("""\$\GPGGA,[^,]*,([0-9.]+),([NS]),([0-9.]+),([EW]),""")
        val matcher: Matcher = pattern.matcher(nmeaData)
        if (matcher.find()) {
            return Pair(null, null)
        }
        return Pair(null, null)
    }
}
