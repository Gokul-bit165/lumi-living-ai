package com.livingai.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin CameraX wrapper. Captures a single frame only when the user explicitly asks — this app
 * never continuously analyzes the camera feed, only the live preview is running while the
 * screen is open.
 */
class CameraCaptureController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    suspend fun bind(lifecycleOwner: LifecycleOwner, preview: Preview): Unit = suspendCancellableCoroutine { cont ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val capture = ImageCapture.Builder().build()
                imageCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cont.resume(Unit)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun captureBitmap(): Result<Bitmap> {
        val capture = imageCapture ?: return Result.failure(IllegalStateException("Camera not bound"))
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = image.toBitmap()
                        image.close()
                        if (bitmap != null) {
                            cont.resume(Result.success(bitmap))
                        } else {
                            cont.resume(Result.failure(IllegalStateException("Failed to decode captured frame")))
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(Result.failure(exception))
                    }
                }
            )
        }
    }

    fun unbind() {
        cameraProvider?.unbindAll()
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (imageInfo.rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray =
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                stream.toByteArray()
            }
    }
}
