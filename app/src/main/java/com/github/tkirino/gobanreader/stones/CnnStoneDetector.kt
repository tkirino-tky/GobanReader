package com.github.tkirino.gobanreader.stones

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.github.tkirino.gobanreader.model.StoneColor
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream

class CnnStoneDetector(context: Context) {
    private var torchModule: Module? = null

    init {
        val modelPath = assetFilePath(context, "goban_model.pt")
        torchModule = Module.load(modelPath)
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        context.assets.open(assetName).use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
        return file.absolutePath
    }

    fun predictPatch(colorMat: Mat, edgeMat: Mat): StoneColor {
        val module = torchModule ?: return StoneColor.EMPTY

        // OpenCVのBGR形式を、Python（PIL）と同じRGB形式に変換する
        val rgbMat = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(colorMat, rgbMat, org.opencv.imgproc.Imgproc.COLOR_BGR2RGB)

        try {
            val colorBmp = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, colorBmp)
            rgbMat.release() // 不要になった一時Matの解放

            val colorTensor = TensorImageUtils.bitmapToFloat32Tensor(
                colorBmp,
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(1f, 1f, 1f)
            )

            val floatArray = FloatArray(40 * 40)
            val buff = ByteArray(40 * 40)
            edgeMat.get(0, 0, buff)
            for (i in 0 until 40 * 40) {
                floatArray[i] = (buff[i].toInt() and 0xFF) / 255.0f
            }
            val edgeTensor = Tensor.fromBlob(floatArray, longArrayOf(1, 1, 40, 40))

            val outputTensor = module.forward(IValue.from(colorTensor), IValue.from(edgeTensor)).toTensor()
            val scores = outputTensor.dataAsFloatArray

            val maxIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            return when (maxIndex) {
                1 -> StoneColor.BLACK
                2 -> StoneColor.WHITE
                else -> StoneColor.EMPTY
            }
        } catch (e: Exception) {
            Log.e("CnnStoneDetector", "推論実行エラー", e)
            rgbMat.release()
            return StoneColor.EMPTY
        }
    }
}
