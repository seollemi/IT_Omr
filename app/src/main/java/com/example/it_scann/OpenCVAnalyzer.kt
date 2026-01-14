package com.example.it_scann

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

private const val DEBUG_DRAW = true

/* ====================== CAMERA ANALYZER ====================== */

class OpenCVAnalyzer(
    private val context: Context
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val src = image.toMat()

        try {
            val warped = detectAndWarpSheet(src) ?: return
            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val thresh = thresholdForOMR(context, warped)

            val answers = mutableListOf<String>()
            processAnswerSheetContours(context, thresh, warped, answers)

            answers.forEach { Log.d("OMR", it) }

            thresh.release()
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "Analyze failed", e)
        } finally {
            src.release()
            image.close()
        }
    }
}

/* ====================== FILE ANALYSIS ====================== */

fun analyzeImageFile(context: Context, imageUri: Uri) {
    context.contentResolver.openInputStream(imageUri)?.use {
        val bitmap = BitmapFactory.decodeStream(it) ?: return
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val warped = detectAndWarpSheet(mat) ?: return
        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)

        val answers = mutableListOf<String>()
        processAnswerSheetContours(context, thresh, warped, answers)

        answers.forEach { Log.d("OMR", it) }

        mat.release()
        warped.release()
        thresh.release()
    }
}

/* ====================== SHEET DETECTION ====================== */

fun detectAndWarpSheet(src: Mat): Mat? {
    val gray = Mat()
    val blur = Mat()
    val edges = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
    Imgproc.Canny(blur, edges, 75.0, 200.0)

    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(
        edges,
        contours,
        Mat(),
        Imgproc.RETR_EXTERNAL,
        Imgproc.CHAIN_APPROX_SIMPLE
    )

    contours.sortByDescending { Imgproc.contourArea(it) }

    val sheet = contours.firstNotNullOfOrNull { c ->
        val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
        if (approx.total() == 4L) approx else null
    } ?: return null

    val ordered = orderPoints(sheet.toArray())
    val dst = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(1200.0, 0.0),
        Point(1200.0, 1600.0),
        Point(0.0, 1600.0)
    )

    val matrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*ordered), dst)
    val warped = Mat()
    Imgproc.warpPerspective(src, warped, matrix, Size(1200.0, 1600.0))

    gray.release()
    blur.release()
    edges.release()

    return warped
}

fun orderPoints(pts: Array<Point>): Array<Point> {
    val rect = Array(4) { Point() }
    val sum = pts.map { it.x + it.y }
    val diff = pts.map { it.y - it.x }

    rect[0] = pts[sum.indexOf(sum.min())]
    rect[2] = pts[sum.indexOf(sum.max())]
    rect[1] = pts[diff.indexOf(diff.min())]
    rect[3] = pts[diff.indexOf(diff.max())]

    return rect
}

/* ====================== THRESHOLD ====================== */

fun thresholdForOMR(context: Context, src: Mat): Mat {
    val gray = Mat()
    val blur = Mat()
    val thresh = Mat()

    Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
    Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

    Imgproc.adaptiveThreshold(
        blur,
        thresh,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        31,
        3.0
    )

    if (DEBUG_DRAW) saveDebugMat(context, thresh, "02_thresh")

    gray.release()
    blur.release()
    return thresh
}

fun splitIntoElements(
    boxes: List<Rect>,
    imageWidth: Int,
    elementCount: Int = 4
): List<List<Rect>> {

    val elementWidth = imageWidth / elementCount
    val elements = List(elementCount) { mutableListOf<Rect>() }

    for (r in boxes) {
        val centerX = r.x + r.width / 2
        val index = (centerX / elementWidth)
            .coerceIn(0, elementCount - 1)
        elements[index].add(r)
    }

    return elements
}



/* ====================== OMR CORE ====================== */

fun processAnswerSheetContours(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    answers: MutableList<String>
) {
    val contours = mutableListOf<MatOfPoint>()
    Imgproc.findContours(
        thresh,
        contours,
        Mat(),
        Imgproc.RETR_LIST,
        Imgproc.CHAIN_APPROX_SIMPLE
    )

    Log.d("OMR", "Contours found: ${contours.size}")

    val boxes = contours.mapNotNull {
        val r = Imgproc.boundingRect(it)

        val minSize = 18
        val maxSize = 80

        if (
            r.width in minSize..maxSize &&
            r.height in minSize..maxSize
        ) r else null
    }

    Log.d("OMR", "Valid boxes: ${boxes.size}")
    if (boxes.size < 80) return   // 4 elements × 25 × 4 choices

    // 🔹 SPLIT INTO ELEMENTS (COLUMNS)
    val elements = splitIntoElements(
        boxes,
        debugMat.cols(),
        elementCount = 4
    )

    val choices = listOf("A", "B", "C", "D")

    elements.forEachIndexed { elementIndex, elementBoxes ->

        if (elementBoxes.isEmpty()) return@forEachIndexed

        // 🔹 SORT TOP → BOTTOM
        val sorted = elementBoxes.sortedBy { it.y }

        // 🔹 GROUP INTO QUESTIONS (4 bubbles each)
        val questions = sorted.chunked(4)

        questions.forEachIndexed { qIndex, row ->

            if (row.size != 4) return@forEachIndexed

            val fill = DoubleArray(4)

            for (i in 0 until 4) {
                val roi = thresh.submat(row[i])
                fill[i] = Core.countNonZero(roi).toDouble() / row[i].area()
                roi.release()
            }

            val selected = fill.indices.maxByOrNull { fill[it] } ?: -1
            val answer =
                if (selected >= 0 && fill[selected] > 0.12)
                    choices[selected]
                else "INVALID"

            answers.add(
                "Element ${elementIndex + 1} - Q${qIndex + 1}: $answer"
            )

            if (DEBUG_DRAW) {
                row.forEachIndexed { i, r ->
                    val color =
                        if (i == selected) Scalar(0.0, 0.0, 255.0)
                        else Scalar(255.0, 0.0, 0.0)
                    Imgproc.rectangle(debugMat, r, color, 2)
                }
            }
        }
    }

    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected_boxes")
}


    /* ====================== UTIL ====================== */

fun saveDebugMat(context: Context, mat: Mat, name: String) {
    val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(mat, bitmap)

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/OMR")
    }

    context.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        values
    )?.let { uri ->
        context.contentResolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }
}
