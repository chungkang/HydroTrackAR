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

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

class HydroTrackARActivity : AppCompatActivity() {
  // 백그라운드 핸들러와 스레드
  private lateinit var backgroundHandler: Handler
  private lateinit var backgroundThread: HandlerThread
  private var usbSerialPort: UsbSerialPort? = null
  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: HydroTrackARView
  lateinit var renderer: HydroTrackARRenderer

  companion object {
    private const val TAG = "HydroTrackARActivity"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initializeARCoreSession()
    initializeBackgroundThread()
    setupUsbSerial()
    initializeARComponents()
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

  private fun initializeBackgroundThread() {
    backgroundThread = HandlerThread("USBBackgroundThread").apply { start() }
    backgroundHandler = Handler(backgroundThread.looper)
    backgroundHandler.postDelayed({ readAndProcessNmeaData() }, 1000)
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
    Log.e(TAG, "ARCore threw an exception", exception)
    view.snackbarHelper.showError(this, message)
  }

  private fun readAndProcessNmeaData() {
    if (usbSerialPort != null) {
      val nmeaData = readNmeaData()
      if (nmeaData.isNotEmpty()) {
        getLocationFromNmeaData(nmeaData)
      }
    }
    backgroundHandler.postDelayed({ readAndProcessNmeaData() }, 1000)
  }

  //   USB 위치 정보 업데이트 메서드 (백그라운드 스레드에서 실행)
  private fun getLocationFromNmeaData(nmeaData: String) {
    if (nmeaData.startsWith("\$GPGGA")) {
      val (latitude, longitude) = parseNmeaData(nmeaData)
      if (latitude != null && longitude != null) {
//        renderer.updateUsbGeospatialPose(latitude, longitude)
      }
    }
  }
  override fun onDestroy() {
    super.onDestroy()
    usbSerialPort?.close()
    backgroundThread.quitSafely()
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
      Toast.makeText(this, "Camera and location permissions are needed to run this application", Toast.LENGTH_LONG)
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


  // Method to setup USB serial communication
  // Improved method for USB serial setup and reading data
  // USB 연결 설정 메서드
  private fun setupUsbSerial() {
    if (usbSerialPort != null) {
      return // 포트가 이미 설정된 경우, 다시 설정하지 않음
    }

    val manager = getSystemService(Context.USB_SERVICE) as UsbManager
    val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    if (availableDrivers.isEmpty()) {
      Log.e(TAG, "Error: No USB drivers available")
      return
    }

    val driver = availableDrivers[0]
    val connection = manager.openDevice(driver.device)
    if (connection == null) {
      Log.e(TAG, "Error: Connection is null")
      return
    }

    val port = driver.ports[0]
    try {
      usbSerialPort = port
      port.open(connection)
      port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
    } catch (e: IOException) {
      Log.e(TAG, "Error setting up device: ${e.message}")
      usbSerialPort = null
      port.close()
    }
  }

  // USB 장치로부터 데이터를 읽는 메서드
  private fun readNmeaData(): String {
    usbSerialPort?.let { port ->
      try {
        val buffer = ByteArray(1024)
        val numBytesRead = port.read(buffer, 1000)
        return String(buffer, 0, numBytesRead, StandardCharsets.UTF_8)
      } catch (e: IOException) {
        Log.e(TAG, "Error reading from device: ${e.message}")
        return ""
      }
    }
    return ""
  }

  // Convert NMEA latitude/longitude to decimal degrees
  fun convertToDecimalDegrees(coordinate: String, direction: String): Double? {
    return try {
      val degrees = coordinate.substring(0, 2).toDouble()
      val minutes = coordinate.substring(2).toDouble()
      val decimalDegrees = degrees + minutes / 60.0
      if (direction == "S" || direction == "W") -decimalDegrees else decimalDegrees
    } catch (e: NumberFormatException) {
      null
    }
  }

  // Improved NMEA data parsing method
  fun parseNmeaData(nmeaData: String): Pair<Double?, Double?> {
    val pattern = Pattern.compile("""\$\GPGGA,[^,]*,([0-9.]+),(N|S),([0-9.]+),(E|W),""")
    val matcher: Matcher = pattern.matcher(nmeaData)
    if (matcher.find()) {
      val latitude = convertToDecimalDegrees(matcher.group(1), matcher.group(2))
      val longitude = convertToDecimalDegrees(matcher.group(3), matcher.group(4))
      return Pair(latitude, longitude)
    }
    return Pair(null, null)
  }
}
