package com.example.it_scann

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.core.Scalar
import org.opencv.android.Utils
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.content.Context
import org.opencv.imgcodecs.Imgcodecs

class OpenCVAnalyzer : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {


        val src = image.toMat()
        val debugMat = src.clone()

        val gray = Mat()
        val blurred = Mat()
        val thresh = Mat()

        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        Imgproc.adaptiveThreshold(
            blurred,
            thresh,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            11,
            2.0
        )

        processAnswerSheet(thresh, debugMat)

        // 🔍 DEBUG: save one frame (optional)
        // Imgcodecs.imwrite("/sdcard/omr_debug.jpg", debugMat)

        src.release()
        debugMat.release()
        gray.release()
        blurred.release()
        thresh.release()
        image.close()
    }

}


fun loadTestImage(context: Context, drawableResId: Int): Mat {
    // Load Bitmap from drawable resource
    val bitmap = BitmapFactory.decodeResource(context.resources, drawableResId)
    val mat = Mat()
    Utils.bitmapToMat(bitmap, mat)
    return mat
}
fun testAnalyzeImage(testMat: Mat) {

    val src = testMat.clone()
    val debugMat = src.clone()

    val gray = Mat()
    val blurred = Mat()
    val thresh = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
    Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
    Imgproc.adaptiveThreshold(
        blurred,
        thresh,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        11,
        2.0
    )

    processAnswerSheet(thresh, debugMat)

    // Save debugMat to file to see results
    Imgcodecs.imwrite("/sdcard/Download/omr_test_output.jpg", debugMat);


    src.release()
    debugMat.release()
    gray.release()
    blurred.release()
    thresh.release()
}


fun safeSubmat(src: Mat, rect: Rect): Mat? {
    if (
        rect.x < 0 || rect.y < 0 ||
        rect.x + rect.width > src.cols() ||
        rect.y + rect.height > src.rows()
    ) {
        Log.e("OMR", "Rect out of bounds: $rect  Mat=${src.cols()}x${src.rows()}")
        return null
    }
    return src.submat(rect)
}

fun processAnswerSheet(thresh: Mat, debugMat: Mat) {

    Log.d("OMR", "Thresh size: ${thresh.cols()} x ${thresh.rows()}")

    val elemRects = listOf(
        Rect(100, 300, 900, 1200),
        Rect(1100, 300, 900, 1200),
        Rect(100, 1600, 900, 1200),
        Rect(1100, 1600, 900, 1200)
    )

    elemRects.forEachIndexed { index, rect ->

        if (DEBUG_DRAW) {
            drawRect(debugMat, rect, Scalar(0.0, 255.0, 0.0), 3) // 🟩 GREEN
        }

        val section = safeSubmat(thresh, rect)
        if (section != null) {
            scanSection(section, debugMat, rect, "Elem${index + 1}")
            section.release()
        }
    }
}



fun scanSection(
    section: Mat,
    debugMat: Mat,
    sectionRect: Rect,
    label: String
) {

    val questions = 25
    val choices = 4

    val rowHeight = section.rows() / questions
    val colWidth = section.cols() / choices

    if (rowHeight <= 20 || colWidth <= 40) {
        Log.e("OMR", "$label grid too small")
        return
    }

    for (q in 0 until questions) {
        var selected = -1
        var maxFilled = 0
        val bubbleRects = mutableListOf<Rect>()

        for (c in 0 until choices) {

            val x = c * colWidth
            val y = q * rowHeight

            val rect = Rect(
                x + 10,
                y + 10,
                colWidth - 20,
                rowHeight - 20
            )

            bubbleRects.add(rect)

            val bubble = safeSubmat(section, rect) ?: continue
            val filledPixels = Core.countNonZero(bubble)

            if (filledPixels > maxFilled) {
                maxFilled = filledPixels
                selected = c
            }

            bubble.release()
        }

        // 🎨 DRAW BUBBLES
        if (DEBUG_DRAW) {
            bubbleRects.forEachIndexed { index, r ->
                val absoluteRect = Rect(
                    sectionRect.x + r.x,
                    sectionRect.y + r.y,
                    r.width,
                    r.height
                )

                val color = if (index == selected)
                    Scalar(0.0, 0.0, 255.0)   // 🟥 RED = selected
                else
                    Scalar(255.0, 0.0, 0.0)   // 🟦 BLUE = others

                drawRect(debugMat, absoluteRect, color, 2)
            }
        }

        val answer = listOf("A", "B", "C", "D").getOrElse(selected) { "Blank" }
        Log.d("OMR", "$label Q${q + 1}: $answer")
    }

}
private const val DEBUG_DRAW = true

fun drawRect(mat: Mat, rect: Rect, color: Scalar, thickness: Int = 2) {
    Imgproc.rectangle(
        mat,
        rect.tl(),
        rect.br(),
        color,
        thickness
    )
}





