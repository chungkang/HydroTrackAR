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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern


class HydroTrackARActivity : AppCompatActivity() {
    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private var usbSerialPort: UsbSerialPort? = null
    lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
    lateinit var view: HydroTrackARView
    lateinit var renderer: HydroTrackARRenderer
    private var isLogging = false
    private val usbDataBuffer = StringBuilder()
    private val geospatialDataBuffer = StringBuilder()

    companion object {
        private const val TAG = "HydroTrackARActivity"
        private const val ACTION_USB_PERMISSION =
            "com.google.ar.core.codelabs.hellogeospatial.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeARCoreSession()
        initializeARComponents()

        // USB 권한 요청 및 장치 연결/분리 인텐트 필터 설정
        IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }.also { filter ->
            registerReceiver(usbPermissionReceiver, filter)
        }

        val switchLog = findViewById<Switch>(R.id.switch_log) as Switch
        switchLog.setOnClickListener {
            if (switchLog.isChecked) {
                Toast.makeText(this, "Logging Start", Toast.LENGTH_SHORT).show()
                setupUsbSerial()
                startLogging()
//                getGeospatialPoseData 를 백그라운드에서 1초에 1번씩 String으로 붙여서 갖고 있는 로직
            } else {
                Toast.makeText(this, "Logging Stop", Toast.LENGTH_SHORT).show()
                closeUsbSerial()
                stopLoggingAndSaveData()
//                getGeospatialPoseData 를 백그라운드에서 1초에 1번씩 String으로 붙인 문자열을 txt 파일로 download 경로에 저장하는 로직

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
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                // 권한이 부여되었을 때 수행할 작업
                                Log.d(TAG, "USB permission granted for device $device")
                                // USB 시리얼 설정 재시도 로직 추가
                                setupUsbSerial() // 권한이 부여된 후 USB 시리얼 설정을 재시도
                            }
                        } else {
                            // 권한이 거부되었을 때 사용자에게 알림
                            Toast.makeText(
                                context,
                                "USB permission denied for device $device",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "USB permission denied for device $device")
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.d(TAG, "USB device attached: $device")
                        requestUsbPermission(
                            (context.getSystemService(Context.USB_SERVICE) as UsbManager),
                            it
                        )
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        if (it == usbSerialPort?.driver?.device) {
                            Log.d(TAG, "USB device detached: $device")
                            closeUsbSerial()
                        }
                    }
                }
            }
        }
    }


    private fun startLogging() {
        isLogging = true
        startBackgroundThreadForUSBReading()
        startBackgroundThreadForGeospatialData()
    }

    private fun startBackgroundThreadForUSBReading() {
        backgroundThread = HandlerThread("USBReadThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        backgroundHandler.post(object : Runnable {
            override fun run() {
                if (!isLogging) return
                usbSerialPort?.let { port ->
                    try {
                        val buffer = ByteArray(4096)
                        val numBytesRead = port.read(buffer, 1000)
                        val readData:String? = String(buffer, 0, numBytesRead)
                        // readData가 null이 아니고, 빈 문자열이 아닌 경우에만 실행합니다.
                        if (!readData.isNullOrEmpty()) {
                            usbDataBuffer.append(readData) // 데이터를 StringBuilder에 추가합니다.

                            if (!readData.isNullOrEmpty() && readData.startsWith("\$GPGGA")) {
                                val gpggaParts = readData.split(',')
                                if (gpggaParts.size > 5) {
                                    val lat = gpggaParts[2] // 위도 값
                                    val latDirection = gpggaParts[3] // 위도 방향 (N or S)
                                    val lon = gpggaParts[4] // 경도 값
                                    val lonDirection = gpggaParts[5] // 경도 방향 (E or W)
                                    if (lat.isNotEmpty() && lon.isNotEmpty()) {
                                        val usbLatLon = "usb: ${parseNmeaToDecimal(lat, latDirection)}, ${parseNmeaToDecimal(lon, lonDirection)}"
                                        view.usbLatLon = usbLatLon // View의 usbLatLon 변수 업데이트

                                        val latitude = parseNmeaToDecimal(lat, latDirection)
                                        val longitude = parseNmeaToDecimal(lon, lonDirection)

                                        // HydroTrackARView에 마커 위치 업데이트 요청
                                        view.updateMarkerLocation(latitude, longitude)
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // 에러를 로그에 기록합니다.
                        Log.e(TAG, "Error reading from USB device: ${e.message}", e)
                        // 사용자에게 에러를 알립니다.
                        runOnUiThread {
                            Toast.makeText(
                                this@HydroTrackARActivity,
                                "Error reading USB data: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                if (isLogging) {
                    backgroundHandler.postDelayed(this, 100) // 다음 읽기 시도까지 지연시간을 줍니다.
                }
            }
        })
    }

    private fun startBackgroundThreadForGeospatialData() {
        backgroundThread = HandlerThread("GeospatialDataThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)

        geospatialDataBuffer.append("latitude,longitude,horizontalAccuracy,altitude,verticalAccuracy,heading,headingAccuracy,timestamp\n")

        val runnable = object : Runnable {
            override fun run() {
                if (!isLogging) return

                // Get the current geospatial pose from the renderer
                val currentTimeStamp = System.currentTimeMillis()
                val geospatialPose = renderer.getCurrentGeospatialPose()
                geospatialPose?.let {
                    val dataString = "${it.latitude},${it.longitude},${it.horizontalAccuracy},${it.altitude},${it.verticalAccuracy},${it.heading},${it.headingAccuracy},$currentTimeStamp\n"
                    geospatialDataBuffer.append(dataString)
                }

                // Post the next check one second later
                backgroundHandler.postDelayed(this, 1000)
            }
        }

        // Start the initial run
        backgroundHandler.post(runnable)
    }

    private fun stopLoggingAndSaveData() {
        isLogging = false
        if (backgroundHandler != null) {
            backgroundHandler.removeCallbacksAndMessages(null)
            backgroundThread.quitSafely()
        }

        val usbStringData = usbDataBuffer.toString()
        if (usbStringData.isNotEmpty()) {
            saveDataToDownloadFolder(usbStringData, "USB")
        }

        val usbGeospatialStringData = geospatialDataBuffer.toString()
        if (usbGeospatialStringData.isNotEmpty()) {
            saveDataToDownloadFolder(usbGeospatialStringData, "DEVICE")
        }

        usbDataBuffer.clear() // clear data
        geospatialDataBuffer.clear() // clear data
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
        connectToDevice(manager, driver)
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
        try {
            val connection =
                manager.openDevice(driver.device) ?: throw IOException("Device connection failed")
            usbSerialPort = driver.ports[0].apply {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Error opening device: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error opening device: ${e.message}", e)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Permission denied: ${e.message}", e)
        } catch (e: Exception) {
            Toast.makeText(this, "Unexpected error: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Unexpected error: ${e.message}", e)
        }
    }


    private fun closeUsbSerial() {
        try {
            usbSerialPort?.close()
        } catch (e: IOException) {
            Toast.makeText(this, "Error closing device: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error closing device: ${e.message}", e)
        } finally {
            usbSerialPort = null
        }
    }


    override fun onStart() {
        super.onStart()
        val filter = IntentFilter()
        filter.addAction(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbPermissionReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        // BroadcastReceiver 등록 해제
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver가 등록되지 않은 경우 예외 발생 가능
            Log.e(TAG, "Receiver was not registered", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 순서가 중요: 먼저 사용 중인 리소스를 해제
        usbSerialPort?.close()
        // Handler와 관련된 작업을 제거
        backgroundHandler.removeCallbacksAndMessages(null)
        // 마지막으로 스레드 종료
        backgroundThread.quitSafely()
        // BroadcastReceiver 해제
        unregisterReceiver(usbPermissionReceiver)
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
                Toast.makeText(this, "Error reading from device: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                Log.e(TAG, "Error reading from device: ${e.message}")
                return ""
            }
        }
        return ""
    }


    private fun saveDataToDownloadFolder(data: String, type: String) {
        val fileName =
            "${type}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
        val resolver = applicationContext.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        try {
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                ?: throw IOException("Failed to create new MediaStore record.")

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(data.toByteArray())
            } ?: throw IOException("Failed to open output stream.")

            Toast.makeText(this, "$fileName saved to Downloads.", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save data: ${e.message}", e)
            Toast.makeText(this, "Failed to save data: ${e.localizedMessage}", Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}", e)
            Toast.makeText(this, "Unexpected error: ${e.localizedMessage}", Toast.LENGTH_SHORT)
                .show()
        }
    }

    fun parseNmeaToDecimal(nmeaValue: String, direction: String): Double {
        val degrees: Double
        val minutes: Double

        if (direction == "N" || direction == "S") {
            // 위도: DDMM.MMMMM 형식
            degrees = nmeaValue.substring(0, 2).toDouble()
            minutes = nmeaValue.substring(2).toDouble()
        } else {
            // 경도: DDDMM.MMMMM 형식
            degrees = nmeaValue.substring(0, 3).toDouble()
            minutes = nmeaValue.substring(3).toDouble()
        }

        // 분을 도로 변환하고, 전체 값을 십진수 형태로 계산
        val decimal = degrees + (minutes / 60)

        // 방향에 따라 값 조정: 남위(S)와 서경(W)은 음수
        return if (direction == "S" || direction == "W") -decimal else decimal
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
