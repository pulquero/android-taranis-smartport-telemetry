package crazydude.com.telemetry.service

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDeviceConnection
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import com.hoho.android.usbserial.driver.UsbSerialPort
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.protocol.pollers.BluetoothDataPoller
import crazydude.com.telemetry.protocol.pollers.BluetoothLeDataPoller
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.pollers.DataPoller
import crazydude.com.telemetry.protocol.pollers.UsbDataPoller
import crazydude.com.telemetry.ui.MapsActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class DataService : Service(), DataDecoder.Listener {

    private var dataPoller: DataPoller? = null
    private var dataListener: DataDecoder.Listener? = null
    private val dataBinder = DataBinder()
    private var hasGPSFix = false
    private var isArmed = false
    private var satellites = 0
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var lastAltitude: Float = 0.0f
    private var lastSpeed: Float = 0.0f
    private var lastHeading: Float = 0.0f
    private lateinit var preferenceManager: PreferenceManager
    val points: ArrayList<LatLng> = ArrayList()

    override fun onCreate() {
        super.onCreate()

        preferenceManager = PreferenceManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel("bt_channel", "Bluetooth", importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }


        val notification = NotificationCompat.Builder(this, "bt_channel")
            .setContentText("Telemetry service is running")
            .setContentTitle("Telemetry service is running. To stop - disconnect and close the app")
            .setContentIntent(PendingIntent.getActivity(this, -1, Intent(this, MapsActivity::class.java), 0))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return Service.START_REDELIVER_INTENT
    }

    inner class DataBinder : Binder() {
        fun getService(): DataService = this@DataService
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun connect(device: BluetoothDevice) {
        try {
            dataPoller?.disconnect()

            var isBle = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                isBle = device.type == BluetoothDevice.DEVICE_TYPE_LE
            }

            val logFile = createLogFile()

            if (!isBle) {
                val socket =
                    device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                dataPoller =
                    BluetoothDataPoller(
                        socket,
                        this,
                        logFile
                    )
            } else {
                dataPoller =
                    BluetoothLeDataPoller(
                        this,
                        device,
                        this,
                        logFile
                    )
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to connect to bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private fun createLogFile() : FileOutputStream? {
        var fileOutputStream: FileOutputStream? = null
        if (preferenceManager.isLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val name = SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            dir.mkdirs()
            val file = File(dir, "$name.log")
            fileOutputStream = FileOutputStream(file)
        }

        return fileOutputStream
    }

    fun connect(serialPort: UsbSerialPort, connection: UsbDeviceConnection) {
        val logFile = createLogFile()
        dataPoller = UsbDataPoller(
            this,
            serialPort,
            connection,
            logFile
        )
    }

    fun setDataListener(dataListener: DataDecoder.Listener?) {
        this.dataListener = dataListener
        if (dataListener != null) {
            dataListener.onGPSState(satellites, hasGPSFix)
        } else {
            if (!isConnected()) {
                stopSelf()
            }
        }
    }

    fun isConnected(): Boolean {
        return dataPoller != null
    }

    override fun onBind(intent: Intent): IBinder? {
        return dataBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        dataPoller?.disconnect()
        dataPoller = null
    }

    override fun onConnectionFailed() {
        dataListener?.onConnectionFailed()
        dataPoller = null
    }

    override fun onFuelData(fuel: Int) {
        dataListener?.onFuelData(fuel)
    }

    override fun onConnected() {
        dataListener?.onConnected()
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        if (hasGPSFix) {
            points.add(LatLng(latitude, longitude))
        }
        lastLatitude = latitude
        lastLongitude = longitude
        dataListener?.onGPSData(latitude, longitude)
    }

    override fun onGPSData(list: List<LatLng>, addToEnd: Boolean) {

    }

    override fun onVBATData(voltage: Float) {
        dataListener?.onVBATData(voltage)
    }

    override fun onCellVoltageData(voltage: Float) {
        dataListener?.onCellVoltageData(voltage)
    }

    override fun onCurrentData(current: Float) {
        dataListener?.onCurrentData(current)
    }

    override fun onHeadingData(heading: Float) {
        lastHeading = heading
        dataListener?.onHeadingData(heading)
    }

    override fun onAirSpeed(speed: Float) {
        if (preferenceManager.usePitotTube()) {
            lastSpeed = speed
        }
        dataListener?.onAirSpeed(speed)
    }

    override fun onSuccessDecode() {
        dataListener?.onSuccessDecode()
    }

    override fun onRSSIData(rssi: Int) {
        dataListener?.onRSSIData(rssi)
    }

    override fun onDisconnected() {
        points.clear()
        dataListener?.onDisconnected()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        hasGPSFix = gpsFix
        dataListener?.onGPSState(satellites, gpsFix)
    }

    override fun onVSpeedData(vspeed: Float) {
        dataListener?.onVSpeedData(vspeed)
    }

    override fun onAltitudeData(altitude: Float) {
        lastAltitude = altitude
        dataListener?.onAltitudeData(altitude)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        dataListener?.onGPSAltitudeData(altitude)
    }

    override fun onDistanceData(distance: Int) {
        dataListener?.onDistanceData(distance)
    }

    override fun onRollData(rollAngle: Float) {
        dataListener?.onRollData(rollAngle)
    }

    override fun onPitchData(pitchAngle: Float) {
        dataListener?.onPitchData(pitchAngle)
    }

    override fun onGSpeedData(speed: Float) {
        if (!preferenceManager.usePitotTube()) {
            lastSpeed = speed
        }
        dataListener?.onGSpeedData(speed)
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        isArmed = armed
        dataListener?.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
    }

    fun disconnect() {
        points.clear()
        dataPoller?.disconnect()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
    }
}