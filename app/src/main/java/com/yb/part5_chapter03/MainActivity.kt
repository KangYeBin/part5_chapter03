package com.yb.part5_chapter03

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.impl.ImageOutputConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.yb.part5_chapter03.ImageListActivity.Companion.IMAGE_LIST_REQUEST_CODE
import com.yb.part5_chapter03.databinding.ActivityMainBinding
import com.yb.part5_chapter03.extensions.clear
import com.yb.part5_chapter03.extensions.loadCenterCrop
import com.yb.part5_chapter03.util.PathUtil
import java.io.File
import java.io.FileNotFoundException
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private val cameraMainExecutor by lazy { ContextCompat.getMainExecutor(this) }

    // 카메라 얻어오면 이후 실행 리스너 등록
    private val cameraProviderFuture by lazy { ProcessCameraProvider.getInstance(this) }

    private lateinit var imageCapture: ImageCapture

    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private var displayId: Int = -1

    private var camera: Camera? = null

    private var root: View? = null

    private var isCapturing: Boolean = false

    private var isFlashEnabled: Boolean = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            // 화면이 회전되었을때 대응
            if (this@MainActivity.displayId == displayId) {
                if (::imageCapture.isInitialized && root != null) {
                    imageCapture.targetRotation =
                        root?.display?.rotation ?: ImageOutputConfig.INVALID_ROTATION
                }

            }
        }
    }

    private var uriList = mutableListOf<Uri>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        root = binding.root
        setContentView(binding.root)

        if (allPermissionGranted()) {
            startCamera(binding.viewFinder)
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun allPermissionGranted() = REQUIRED_PERMISSIONS.all {
        ActivityCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera(viewFinder: PreviewView) {
        displayManager.registerDisplayListener(displayListener, null)
        cameraExecutor = Executors.newSingleThreadExecutor()
        viewFinder.postDelayed({
            displayId = viewFinder.display.displayId
            bindCameraUseCase()
        }, 10)

    }

    private fun bindCameraUseCase() = with(binding) {
        val rotation = viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(LENS_FACING)     // 카메라 설정 (후면)
            .build()

        cameraProviderFuture.addListener(
            {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().apply {
                    setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    setTargetRotation(rotation)
                }.build()

                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .setFlashMode(ImageCapture.FLASH_MODE_AUTO)

                imageCapture = imageCaptureBuilder.build()

                try {
                    cameraProvider.unbindAll() // 기존에 바인딩 되어있는 카메라는 해제
                    camera = cameraProvider.bindToLifecycle(
                        this@MainActivity,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    preview.setSurfaceProvider(viewFinder.surfaceProvider)
                    bindCaptureListener()
                    bindZoomListener()
                    initFlashAndAddListener()
                    bindPreviewImageViewClickListener()

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, cameraMainExecutor
        )
    }

    private fun bindCaptureListener() = with(binding) {
        captureButton.setOnClickListener {
            if (!isCapturing) {
                isCapturing = true
                captureCamera()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindZoomListener() = with(binding) {
        val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)

                return true
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(this@MainActivity, listener)
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            return@setOnTouchListener true
        }
    }

    private fun initFlashAndAddListener() = with(binding) {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        flashSwitch.isVisible = hasFlash

        if (hasFlash) {
            flashSwitch.setOnCheckedChangeListener { _, isChecked ->
                isFlashEnabled = isChecked
            }
        } else {
            isFlashEnabled = false
            flashSwitch.setOnCheckedChangeListener(null)
        }
    }

    private var contentUri: Uri? = null

    private fun captureCamera() {
        if (::imageCapture.isInitialized.not()) return

        val photoFile = File(
            PathUtil.getOutputDirectory(this),
            SimpleDateFormat(FILENAME_FORMAT,
                Locale.KOREA).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        if (isFlashEnabled) {
            flashLight(true)
        }

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    contentUri = savedUri
                    updateSavedImageContent()
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    isCapturing = false
                    flashLight(false)
                }

            }
        )
    }

    private fun flashLight(light: Boolean) {
        val hasFlash = camera?.cameraInfo?.hasFlashUnit() ?: false
        if (hasFlash) {
            camera?.cameraControl?.enableTorch(light)
        }
    }

    private fun updateSavedImageContent() {
        contentUri?.let {
            isCapturing = try {
                val file = File(PathUtil.getPath(this, it) ?: throw FileNotFoundException())
                MediaScannerConnection.scanFile(this,
                    arrayOf(file.path),
                    arrayOf("image/jpeg"),
                    null)

                Handler(Looper.getMainLooper()).post {
                    binding.previewImageView.loadCenterCrop(it.toString(), 4f)
                }
                uriList.add(it)
                flashLight(false)
                false
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "파일이 존재하지 않습니다", Toast.LENGTH_SHORT).show()
                flashLight(false)
                false
            }
        }
    }

    private fun bindPreviewImageViewClickListener() = with(binding) {
        previewImageView.setOnClickListener {
            startActivityForResult(
                ImageListActivity.newIntent(this@MainActivity, uriList), IMAGE_LIST_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionGranted()) {
                startCamera(binding.viewFinder)
            } else {
                Toast.makeText(this, "카메라 권한이 없습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_LIST_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            uriList = data?.getParcelableArrayListExtra(ImageListActivity.URI_LIST_KEY) ?: uriList
            if (uriList.isNotEmpty()) {
                binding.previewImageView.loadCenterCrop(url = uriList.first().toString(), corner = 4f)
            } else {
                binding.previewImageView.clear()
            }
        }
    }


    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private val LENS_FACING = CameraSelector.LENS_FACING_BACK
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
}