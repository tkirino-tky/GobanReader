package com.github.tkirino.gobanreader.utility

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugUtility {
    fun saveDebugImage(context: Context, mat: Mat, label: String) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "debug_${label}_$timeStamp.png"

        // 写真フォルダ(DCIM/Pictures)を指定
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val file = File(dir, fileName)

        Log.d("DebugBoard", "saveDebugImage: 保存先 -> ${file.absolutePath}")

        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)

        try {
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("DebugBoard", "saveDebugImage: 保存完了")
        } catch (e: Exception) {
            Log.e("DebugBoard", "保存失敗: ${e.message}")
        }
    }
}
