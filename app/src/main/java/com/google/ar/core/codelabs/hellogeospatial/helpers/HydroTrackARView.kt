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
package com.google.ar.core.codelabs.hellogeospatial.helpers

import android.content.Context
import android.hardware.usb.UsbManager
import android.opengl.GLSurfaceView
import android.view.View
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.codelabs.hellogeospatial.HydroTrackARActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern


/** Contains UI elements for Hello Geo. */
class HydroTrackARView(val activity: HydroTrackARActivity) : DefaultLifecycleObserver {
  val root = View.inflate(activity, R.layout.activity_main, null)
  val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

    // Method to setup USB serial communication
    // Improved method for USB serial setup and reading data
    private fun setupUsbSerial(): String {
        val manager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            return "Error: No USB drivers available"
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device) ?: return "Error: Connection is null"

        val port = driver.ports[0]
        return try {
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            val buffer = ByteArray(1024)
            val numBytesRead = port.read(buffer, 1000)
            String(buffer, 0, numBytesRead, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            "Error reading from device: ${e.message}"
        } finally {
            port.close()
        }
    }

    // Convert NMEA latitude/longitude to decimal degrees
    private fun convertToDecimalDegrees(coordinate: String, direction: String): Double? {
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
    private fun parseNmeaData(nmeaData: String): Pair<Double?, Double?> {
        val pattern = Pattern.compile("""\$\GPGGA,[^,]*,([0-9.]+),(N|S),([0-9.]+),(E|W),""")
        val matcher: Matcher = pattern.matcher(nmeaData)
        if (matcher.find()) {
            val latitude = convertToDecimalDegrees(matcher.group(1), matcher.group(2))
            val longitude = convertToDecimalDegrees(matcher.group(3), matcher.group(4))
            return Pair(latitude, longitude)
        }
        return Pair(null, null)
    }

    val session
    get() = activity.arCoreSessionHelper.session

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapTouchWrapper: MapTouchWrapper = root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
    setup { screenLocation ->
      val latLng: LatLng =
        mapView?.googleMap?.projection?.fromScreenLocation(screenLocation) ?: return@setup
      activity.renderer.onMapClick(latLng)
    }
  }
  val mapFragment =
    (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
    }

  private val statusText: TextView = root.findViewById<TextView>(R.id.statusText)
    fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
        activity.runOnUiThread {
            val poseText = if (cameraGeospatialPose == null) "" else
                activity.getString(R.string.geospatial_pose,
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy)
            val earthStateText = activity.resources.getString(R.string.earth_state,
                earth.earthState.toString(),
                earth.trackingState.toString(),
                poseText)

            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            val usbDevicesText = if (deviceList.isEmpty()) {
                "No USB devices connected."
            } else {
                val nmeaData = setupUsbSerial()
                deviceList.values.joinToString(separator = "\n") { device ->
                    "Device: ${device.deviceName}, Vendor ID: ${device.vendorId}, Product ID: ${device.productId}"
//                    "Device: ${device.deviceName}, Vendor ID: ${device.vendorId}, Product ID: ${device.productId}, NMEA: ${nmeaData}"
                }
            }

            // Update status text with earth state, USB devices, and coordinates
            statusText.text = "$earthStateText\n$usbDevicesText"
                }
            }


  override fun onResume(owner: LifecycleOwner) {
    surfaceView.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    surfaceView.onPause()
  }
}
