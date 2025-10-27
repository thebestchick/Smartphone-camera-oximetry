package com.example.spo2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.spo2.ui.theme.SpO2Theme
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import java.io.File
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var btnTorch: Button
    private lateinit var btnRecord: Button
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var isTorchOn = false
    private var isRecording = false
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var imageReader: ImageReader
    private lateinit var videoFile: File

    private val REQUEST_CAMERA_PERMISSION = 1
    private val CAMERA_ID = "0" // 默认使用后置摄像头

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpO2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        textureView = findViewById(R.id.textureView)
        btnTorch = findViewById(R.id.btnTorch)
        btnRecord = findViewById(R.id.btnRecord)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        btnTorch.setOnClickListener { toggleTorch() }
        btnRecord.setOnClickListener { toggleRecording() }

        // 请求权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            openCamera()
        }
    }

    private fun openCamera() {
        try {
            val characteristics = cameraManager!!.getCameraCharacteristics(CAMERA_ID)
            val streamConfigurationMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSize = Size(176, 144)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }

            cameraManager!!.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview(previewSize)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, null)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPreview(size: Size) {
        val texture = textureView.surfaceTexture!!
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer: ByteBuffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            // 🌈 在这里对RGB通道做简单增益模拟（实际为YUV，这里示意）
            for (i in data.indices step 3) {
                val r = data[i].toInt() and 0xFF
                val g = ((data.getOrNull(i + 1)?.toInt() ?: 0) and 0xFF) * 3
                val b = ((data.getOrNull(i + 2)?.toInt() ?: 0) and 0xFF) * 18
                data[i] = r.coerceAtMost(255).toByte()
                data.getOrNull(i + 1)?.let { data[i + 1] = g.coerceAtMost(255).toByte() }
                data.getOrNull(i + 2)?.let { data[i + 2] = b.coerceAtMost(255).toByte() }
            }
            image.close()
        }, null)

        val previewRequestBuilder =
            cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(surface)

        cameraDevice!!.createCaptureSession(
            listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    previewRequestBuilder.set(
                        CaptureRequest.CONTROL_MODE,
                        CameraMetadata.CONTROL_MODE_AUTO
                    )
                    session.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            null
        )
    }

    private fun toggleTorch() {
        try {
            isTorchOn = !isTorchOn
            cameraManager!!.setTorchMode(CAMERA_ID, isTorchOn)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        btnRecord.text = "停止录制"

        videoFile = File(getExternalFilesDir(null), "video_${System.currentTimeMillis()}.mp4")
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(videoFile.absolutePath)
            setVideoEncodingBitRate(1000000)
            setVideoFrameRate(30)
            setVideoSize(176, 144)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
        mediaRecorder.start()
        Log.d("Camera", "Recording started: ${videoFile.absolutePath}")
    }

    private fun stopRecording() {
        try {
            isRecording = false
            btnRecord.text = "开始录制"
            mediaRecorder.stop()
            mediaRecorder.release()
            Log.d("Camera", "Recording stopped and saved.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SpO2Theme {
        Greeting("Android")
    }
}