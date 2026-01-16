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
import androidx.core.graphics.createBitmap

private const val DEBUG_DRAW = true

/* ====================== CAMERA ANALYZER ====================== */

class OpenCVAnalyzer(
    private val context: Context
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val raw = image.toMat()
        val src = rotateMatIfNeeded(raw, image.imageInfo.rotationDegrees)
        raw.release()

        try {
            val warped = detectAndWarpSheet(src) ?: return
            if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

            val thresh = thresholdForOMR(context, warped)

            val answers = mutableListOf<String>()
            processAnswerSheetGrid(context, thresh, warped, answers)

            answers.forEach { Log.d("OMR", it) }

            thresh.release()
            warped.release()

        } catch (e: Exception) {
            Log.e("OMR", "OMR analyze failed", e)
        } finally {
            src.release()
            image.close()
        }
    }
}


/* ====================== FILE ANALYSIS ====================== */

fun analyzeImageFile(context: Context, imageUri: Uri) {
    context.contentResolver.openInputStream(imageUri)?.use { input ->
        val bitmap = BitmapFactory.decodeStream(input) ?: return

        val raw = Mat()
        Utils.bitmapToMat(bitmap, raw)

        val rotated = rotateBitmapIfNeeded(context, imageUri, raw)
        raw.release()

        val warped = detectAndWarpSheet(rotated) ?: return
        if (DEBUG_DRAW) saveDebugMat(context, warped, "01_warped")

        val thresh = thresholdForOMR(context, warped)

        val answers = mutableListOf<String>()
        processAnswerSheetGrid(context, thresh, warped, answers)

        answers.forEach { Log.d("OMR", it) }

        thresh.release()
        warped.release()
        rotated.release()
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

fun processAnswerSheetGrid(
    context: Context,
    thresh: Mat,
    debugMat: Mat,
    answers: MutableList<String>
) {
    val questions = 25
    val choices = 4
    val labels = listOf("A", "B", "C", "D")

    data class Column(val name: String, val start: Double, val width: Double)

    val columns = listOf(
        Column("Elem 1", 0.05, 0.20),
        Column("Elem 2", 0.27, 0.20),
        Column("Elem 3", 0.49, 0.20),
        Column("Elem 4", 0.71, 0.20)
    )

    for (col in columns) {

        val xStart = (thresh.cols() * col.start).toInt()
        val xEnd = (xStart + thresh.cols() * col.width).toInt()

        val colMat = thresh.submat(0, thresh.rows(), xStart, xEnd)

        val qHeight = colMat.rows() / questions
        val cWidth = colMat.cols() / choices

        for (q in 0 until questions) {

            val fill = DoubleArray(choices)

            for (c in 0 until choices) {
                val roi = colMat.submat(
                    q * qHeight,
                    (q + 1) * qHeight,
                    c * cWidth,
                    (c + 1) * cWidth
                )

                fill[c] = Core.countNonZero(roi).toDouble() / roi.total()
                roi.release()
            }

            val ranked = fill.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
            val best = ranked[0]
            val second = ranked[1]

            val answer = when {
                best.second < 0.15 -> "INVALID"
                best.second / second.second < 1.4 -> "MULTIPLE"
                else -> labels[best.first]
            }

            answers.add("${col.name} - Q${q + 1}: $answer")

            if (answer in labels) {
                val cx = xStart + best.first * cWidth + cWidth / 2
                val cy = q * qHeight + qHeight / 2
                Imgproc.circle(debugMat, Point(cx.toDouble(), cy.toDouble()), 18, Scalar(0.0, 0.0, 255.0), 3)
            }
        }

        colMat.release()
    }

    if (DEBUG_DRAW) saveDebugMat(context, debugMat, "04_detected")
}



    /* ====================== UTIL ====================== */

fun saveDebugMat(context: Context, mat: Mat, name: String) {
    val bitmap = createBitmap(mat.cols(), mat.rows())
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

fun rotateBitmapIfNeeded(context: Context, uri: Uri, mat: Mat): Mat {
    val input = context.contentResolver.openInputStream(uri) ?: return mat
    val exif = androidx.exifinterface.media.ExifInterface(input)
    input.close()

    val orientation = exif.getAttributeInt(
        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    )

    val rotated = Mat()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ->
            Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 ->
            Core.rotate(mat, rotated, Core.ROTATE_180)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ->
            Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> mat.copyTo(rotated)
    }
    return rotated
}

