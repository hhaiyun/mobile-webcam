package com.example.webcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.OnFocusChangeListener
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var buttonStart: Button
    private lateinit var buttonStop: Button
    private lateinit var hostInput: EditText
    private lateinit var ipLabel: TextView

    // Executors
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var networkExecutor: ExecutorService

    // UDP
    private var udpSocket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 8000

    private var hasCameraPermission = false
    private var frameId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        hostInput = findViewById(R.id.hostInput)
        buttonStart = findViewById(R.id.buttonStart)
        buttonStop = findViewById(R.id.buttonStop)
        ipLabel = findViewById(R.id.ipLabel)

        hostInput.onFocusChangeListener = OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                hideKeyboard(v!!)
            }
        }

        // Request camera permission
        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                hasCameraPermission = granted
                if (!granted) {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            hasCameraPermission = true
        }

        // Start
        buttonStart.setOnClickListener {
            val host = hostInput.text.toString().trim()
            if (host.isNotEmpty()) {
                if (!hasCameraPermission) {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Toggle visibility
                buttonStart.visibility = View.GONE
                hostInput.visibility = View.GONE
                ipLabel.visibility = View.GONE

                buttonStop.visibility = View.VISIBLE

                try {
                    cameraExecutor = Executors.newSingleThreadExecutor()
                    networkExecutor = Executors.newSingleThreadExecutor()

                    serverAddress = InetAddress.getByName(host)
                    udpSocket = DatagramSocket()
                    serverPort = 8000
                    Log.i("Cam", "UDP socket created for $host:$serverPort")

                    runOnUiThread { startCamera() }
                } catch (e: Exception) {
                    Log.e("Cam", "UDP init error: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(this, "Connect failed: ${e.message}", Toast.LENGTH_LONG)
                            .show()
                        buttonStart.visibility = View.VISIBLE
                        hostInput.visibility = View.VISIBLE
                        ipLabel.visibility = View.VISIBLE

                        buttonStop.visibility = View.GONE
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a host IP", Toast.LENGTH_SHORT).show()
            }
        }

        // Stop
        buttonStop.setOnClickListener {
            resetConnection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resetConnection()
    }

    private fun resetConnection() {
        Thread {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }, ContextCompat.getMainExecutor(this))

                if (::cameraExecutor.isInitialized) cameraExecutor.shutdownNow()
                if (::networkExecutor.isInitialized) networkExecutor.shutdownNow()

                udpSocket?.close()
                udpSocket = null

                runOnUiThread {
                    buttonStart.visibility = View.VISIBLE
                    hostInput.visibility = View.VISIBLE
                    ipLabel.visibility = View.VISIBLE

                    buttonStop.visibility = View.GONE
                }

                Log.i("Cam", "Reset complete")
            } catch (e: Exception) {
                Log.e("Cam", "Reset error", e)
            }
        }.start()
    }

    fun hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // Camera setup
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        sendFrame(image)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Send frames over UDP
    private fun sendFrame(image: ImageProxy) {
        try {
            if (udpSocket == null || serverAddress == null) {
                image.close()
                return
            }

            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, baos)
            val jpegBytes = baos.toByteArray()

            val maxPacketSize = 1024
            val totalChunks = (jpegBytes.size + maxPacketSize - 1) / maxPacketSize
            val currentFrameId = frameId++

            for (chunkId in 0 until totalChunks) {
                val start = chunkId * maxPacketSize
                val end = minOf(start + maxPacketSize, jpegBytes.size)
                val chunk = jpegBytes.copyOfRange(start, end)

                // Header: [frameId(2B)][chunkId(2B)][totalChunks(2B)]
                val header = ByteArray(6)
                header[0] = ((currentFrameId shr 8) and 0xFF).toByte()
                header[1] = (currentFrameId and 0xFF).toByte()
                header[2] = ((chunkId shr 8) and 0xFF).toByte()
                header[3] = (chunkId and 0xFF).toByte()
                header[4] = ((totalChunks shr 8) and 0xFF).toByte()
                header[5] = (totalChunks and 0xFF).toByte()

                val packetData = header + chunk
                val packet = DatagramPacket(packetData, packetData.size, serverAddress, serverPort)

                networkExecutor.execute {
                    try {
                        udpSocket?.send(packet)
                    } catch (e: Exception) {
                        Log.e("Cam", "UDP send error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Cam", "Frame processing error: ${e.message}")
        } finally {
            image.close()
        }
    }
}