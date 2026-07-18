package com.github.tkirino.gobanreader

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.tkirino.gobanreader.export.SgfParser
import com.github.tkirino.gobanreader.export.SgfWriter
import com.github.tkirino.gobanreader.model.GameRecord
import com.github.tkirino.gobanreader.model.ReaderUiState
import com.github.tkirino.gobanreader.model.StoneColor
import com.github.tkirino.gobanreader.utility.GeometryUtils
import com.github.tkirino.gobanreader.vision.BoardCornerDetector
import com.github.tkirino.gobanreader.vision.BoardRectifier
import com.github.tkirino.gobanreader.vision.GridLineDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    var toastMessage by mutableStateOf<String?>(null)
    var adjustmentBitmap: android.graphics.Bitmap? = null

    // 状態管理用の変数
    var initialCorners by mutableStateOf<List<Point>>(emptyList()) // フィット後（赤）
    var rawCorners by mutableStateOf<List<Point>>(emptyList())     // 生データ（青）

    private var lastSourceMat: Mat? = null

    fun loadPhotoForAdjustment(file: File) {
        lastSourceMat?.release()
        val src = Imgcodecs.imread(file.absolutePath)
        lastSourceMat = src

        val detector = BoardCornerDetector()
        val result = detector.detect(getApplication(), src)

        // 生データを保持
        val detectedCorners = if (result.found) {
            result.corners
        } else {
            val guideRect = GeometryUtils.calculateGuideRect(src.cols().toDouble(), src.rows().toDouble())
            getFallbackCorners(Rect(guideRect.x.toInt(), guideRect.y.toInt(), guideRect.width.toInt(), guideRect.height.toInt()))
        }

        // ここで Raw と Fitted を分けるロジックを適用
        this.rawCorners = detectedCorners
        this.initialCorners = detectedCorners // 最初は同じものが入る

        val bitmap = android.graphics.Bitmap.createBitmap(src.cols(), src.rows(), android.graphics.Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(src, bitmap)
        this.adjustmentBitmap = bitmap
    }

    fun processWithCorners(corners: List<Point>) {
        val src = lastSourceMat ?: return

        // ユーザーが調整した結果を保持
        this.initialCorners = corners

        // 1. ユーザーの指定した4隅を使って補正
        val rectifiedMat = BoardRectifier.rectify(src, corners)

        // 2. 罫線検出を実行
        val gridDetector = GridLineDetector()
        val gray = Mat()
        Imgproc.cvtColor(rectifiedMat, gray, Imgproc.COLOR_BGR2GRAY)

        val horizontal = gridDetector.detectGridLines(gray, GridLineDetector.Axis.HORIZONTAL)
        val vertical = gridDetector.detectGridLines(gray, GridLineDetector.Axis.VERTICAL)

        if (horizontal != null && vertical != null) {
            Log.d("MainViewModel", "罫線検出成功: H=${horizontal.spacing}, V=${vertical.spacing}")
        }

        gray.release()
        rectifiedMat.release()
    }

    private fun getFallbackCorners(guideRect: Rect): List<Point> {
        val paddingX = guideRect.width * 0.07
        val paddingY = guideRect.height * 0.07
        return listOf(
            Point(guideRect.x + paddingX, guideRect.y + paddingY),
            Point(guideRect.x + guideRect.width - paddingX, guideRect.y + paddingY),
            Point(guideRect.x + guideRect.width - paddingX, guideRect.y + guideRect.height - paddingY),
            Point(guideRect.x + paddingX, guideRect.y + guideRect.height - paddingY)
        )
    }

    fun processCapturedPhoto(file: File) {
        loadPhotoForAdjustment(file)
        processWithCorners(this.initialCorners)
    }

    // --- 既存のユーティリティ（変更なし） ---
    fun loadDummySgf() { /* 省略（元のコードを維持） */ }
    fun rotateRight() { /* 省略（元のコードを維持） */ }
    fun rotateLeft() { /* 省略（元のコードを維持） */ }
    fun updateBlackPlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(blackPlayer = name)) } }
    fun updateWhitePlayer(name: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(whitePlayer = name)) } }
    fun updateNextPlayer(nextPlayer: String) { _uiState.update { it.copy(gameRecord = it.gameRecord.copy(nextPlayer = nextPlayer)) } }
    fun exportSgf(context: android.content.Context, gameRecord: GameRecord) { /* 省略（元のコードを維持） */ }

    override fun onCleared() {
        super.onCleared()
        lastSourceMat?.release()
    }
}
