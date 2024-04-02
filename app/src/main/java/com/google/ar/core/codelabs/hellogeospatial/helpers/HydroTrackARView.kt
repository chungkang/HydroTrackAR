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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import com.google.ar.core.codelabs.hellogeospatial.HydroTrackARActivity
import com.google.ar.core.codelabs.hellogeospatial.R
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper

/** Contains UI elements */
class HydroTrackARView(val activity: HydroTrackARActivity) : DefaultLifecycleObserver {
    val root = View.inflate(activity, R.layout.activity_main, null)
    val surfaceView = root.findViewById<GLSurfaceView>(R.id.surfaceview)

    val session
        get() = activity.arCoreSessionHelper.session

    val snackbarHelper = SnackbarHelper()

    var mapView: MapView? = null
    val mapTouchWrapper: MapTouchWrapper =
        root.findViewById<MapTouchWrapper>(R.id.map_wrapper).apply {
            setup { screenLocation ->
                val latLng: LatLng =
                    mapView?.googleMap?.projection?.fromScreenLocation(screenLocation)
                        ?: return@setup
                activity.renderer.onMapClick(latLng)
            }
        }
    val mapFragment =
        (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
            it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
        }

    var usbLatLon: String? = null

    private var currentMarker: Marker? = null

    private val statusText: TextView = root.findViewById<TextView>(R.id.statusText)
    fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
        activity.runOnUiThread {
            val poseText = if (cameraGeospatialPose == null) "" else
                activity.getString(
                    R.string.geospatial_pose,
                    cameraGeospatialPose.latitude,
                    cameraGeospatialPose.longitude,
                    cameraGeospatialPose.horizontalAccuracy,
                    cameraGeospatialPose.altitude,
                    cameraGeospatialPose.verticalAccuracy,
                    cameraGeospatialPose.heading,
                    cameraGeospatialPose.headingAccuracy
                )
            val earthStateText = activity.resources.getString(
                R.string.earth_state,
                earth.earthState.toString(),
                earth.trackingState.toString(),
                poseText
            )

            val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            val usbDevicesText = if (deviceList.isEmpty()) {
                "No USB devices connected."
            } else {
                deviceList.values.joinToString(separator = "\n") { device ->
                    "Device: ${device.deviceName}, Vendor ID: ${device.vendorId}, Product ID: ${device.productId}"
                }
            }
            // Update status text with earth state, USB devices
//            statusText.text = "$earthStateText\n$usbDevicesText"
            // USB 위치 정보가 있으면 상태 텍스트에 추가
            val usbInfoText = usbLatLon?.let { "\n$it" } ?: ""
            statusText.text = "$earthStateText$usbInfoText"
        }

    }

    // 추가: mapView 위치 정보 업데이트 메서드
    fun updateLocation(latitude: Double, longitude: Double) {
        // mapView의 위치 정보를 업데이트하는 코드를 작성합니다.
        val newLatLng = LatLng(latitude, longitude)
        mapView?.googleMap?.moveCamera(CameraUpdateFactory.newLatLng(newLatLng))
    }

    override fun onResume(owner: LifecycleOwner) {
        surfaceView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        surfaceView.onPause()
    }

    fun updateMarkerLocation(latitude: Double, longitude: Double) {
        val newPosition = LatLng(latitude, longitude)
        activity.runOnUiThread {
            mapView?.googleMap?.let { googleMap ->
                // 기존에 추가된 마커가 있다면 제거
                currentMarker?.remove()

                // 새로운 마커 추가
                currentMarker = googleMap.addMarker(
                    MarkerOptions().position(newPosition).title("USB GNSS Position")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))) // 빨간색으로 설정
//                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(newPosition, 15f)) // 카메라를 마커 위치로 이동하고 줌 설정
            }
        }
    }

}
