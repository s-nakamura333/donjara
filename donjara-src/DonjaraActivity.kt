package com.example.donjara03

import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.donjara03.utils.CsvUtils
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.graphics.Color


class DonjaraActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_IMAGE_CAPTURE = 1001
        private const val REQUEST_HAND_CORRECTION = 2002
    }

    private lateinit var editTextHand: EditText
    private lateinit var buttonCalculate: Button
    private lateinit var textViewResult: TextView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // カメラ起動
            launchCameraApp()
        } else {
            // 拒否された場合の対応
            Toast.makeText(this, "カメラの使用が許可されていません。", Toast.LENGTH_SHORT).show()
        }
    }

    // カメラ起動用ボタン
    private lateinit var buttonCamera: Button

    private lateinit var calculator: DonjaraCalculator
    // 全牌一覧を保持（CSV読み込み時に設定）
    private lateinit var allTiles: List<Tile>

    // 撮影画像を一時保存するファイル（キャッシュディレクトリ内）
    private var capturedImageFile: File? = null

    // 画像認識で予測した手牌リスト(9枚想定)
    private var recognizedHand: MutableList<String> = mutableListOf()

    // 「再計算」ボタン（ユーザーが再入力を終えたあと押す想定）
    private lateinit var buttonStartCalculation: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donjara)

        editTextHand = findViewById(R.id.editTextHand)
        buttonCalculate = findViewById(R.id.buttonCalculate)
        textViewResult = findViewById(R.id.textViewResult)

        // カメラ起動ボタン
        buttonCamera = findViewById(R.id.buttonCamera)
        // パーミッションを確認してからカメラ起動する
        buttonCamera.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 「再計算」ボタン
        buttonStartCalculation = findViewById(R.id.buttonStartCalculation)
        buttonStartCalculation.isEnabled = false

        // CSVから牌データと加点役データをロード
        CoroutineScope(Dispatchers.IO).launch {
            try {
                allTiles = loadTiles() // 全牌一覧を取得
                val bonusRoles: List<BonusRole> = loadBonusRoles()
                withContext(Dispatchers.Main) {
                    calculator = DonjaraCalculator(allTiles, bonusRoles)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DonjaraActivity, "データ読み込み失敗: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 「計算実行」ボタン押下時の処理
        buttonStartCalculation.setOnClickListener {
            val finalHand = editTextHand.text.toString().trim()
            if (finalHand.isEmpty()) {
                textViewResult.text = "手牌が入力されていません。"
                return@setOnClickListener
            }
            val nameList = finalHand.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (nameList.size != 9) {
                textViewResult.text = "手牌は9枚で入力してください。"
                return@setOnClickListener
            }

            // 計算実行
            CoroutineScope(Dispatchers.IO).launch {
                val replacedHand = promptAlmightyReplacement(nameList)
                val result = calculator.calculateScore(replacedHand)
                withContext(Dispatchers.Main) {
                    textViewResult.setText(buildStyledResultText(result), TextView.BufferType.SPANNABLE)
                    deleteCapturedImageFile()
                }

            }
        }

        // 既存の「計算」ボタン押下時の処理
        buttonCalculate.setOnClickListener {
            val inputText = editTextHand.text.toString().trim()
            if (inputText.isEmpty()) {
                textViewResult.text = "手牌が入力されていません。"
                return@setOnClickListener
            }

            val nameList = inputText.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            CoroutineScope(Dispatchers.IO).launch {
                val result = calculator.calculateScore(nameList)
                val styledText = buildStyledResultText(result)  // 1度だけ生成
                withContext(Dispatchers.Main) {
                    textViewResult.text = styledText
                    Log.d("Debug", "Styled Text: ${styledText.toString()}")
                }
            }

        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // カメラ撮影成功時の処理をここに書く
            CoroutineScope(Dispatchers.IO).launch {
                val recognizedTiles = performOcrAndMatch(capturedImageFile!!)
                recognizedHand.clear()
                recognizedHand.addAll(recognizedTiles)

                withContext(Dispatchers.Main) {
                    showRecognizedHandDialog(recognizedHand)
                }
            }
        }
    }

    /**
     * CSVファイルから牌データをロード
     */
    private suspend fun loadTiles(): List<Tile> {
        return withContext(Dispatchers.IO) {
            CsvUtils.parseCsvFromAssets(this@DonjaraActivity, "hai_list.csv").mapNotNull { row ->
                val name = row["牌名"] ?: return@mapNotNull null
                val attribute = row["属性"] ?: ""
                val era = row["時代"] ?: ""
                val cat = row["分類"] ?: ""
                val color = row["時代色"] ?: ""
                Tile(name, attribute, era, cat, color)
            }
        }
    }

    /**
     * CSVファイルから加点役データをロード
     */
    private suspend fun loadBonusRoles(): List<BonusRole> {
        return withContext(Dispatchers.IO) {
            CsvUtils.parseCsvFromAssets(this@DonjaraActivity, "bonus_roles.csv").mapNotNull { row ->
                val name = row["役名"] ?: return@mapNotNull null
                val condition = row["条件"] ?: ""
                val requiredCount = row["必要個数"]?.toIntOrNull() ?: 0
                val targets = row["対象"]?.split("、")?.map { it.trim() } ?: emptyList()
                val bonusScore = row["得点"]?.toIntOrNull() ?: 0
                BonusRole(name, condition, requiredCount, targets, bonusScore)
            }
        }
    }

    /**
     * カメラアプリを起動し写真撮影を行う
     */
    private fun launchCameraApp() {
        val cacheDir = externalCacheDir ?: cacheDir
        val fileName = "capture_${System.currentTimeMillis()}.jpg"
        capturedImageFile = File(cacheDir, fileName)

        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            capturedImageFile!!
        )

        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }

        cameraLauncher.launch(takePictureIntent)
    }

    /**
     * ML Kit を使ったOCR解析 + 牌一覧との照合を行い、9枚の牌名リストを返す
     */
    private suspend fun performOcrAndMatch(imageFile: File): List<String> = withContext(Dispatchers.IO) {
        try {
            val inputImage = InputImage.fromFilePath(this@DonjaraActivity, Uri.fromFile(imageFile))
            val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

            val visionText = suspendCoroutine<Text> { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { textResult ->
                        continuation.resume(textResult)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWith(Result.failure(e))
                    }
            }
            val fullText = visionText.text
            val rawList = fullText
                .split("\n", " ", "\t")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val matchedList = rawList.map { recognizedName ->
                matchTileName(recognizedName)
            }.toMutableList()

            // 9枚より少ない場合は不明で埋め、9枚超なら切り捨て
            while (matchedList.size < 9) {
                matchedList.add("不明")
            }
            if (matchedList.size > 9) {
                matchedList.subList(9, matchedList.size).clear()
            }

            matchedList

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext List(9) { "不明" }
        }
    }

    private fun matchTileName(recognizedName: String): String {
        // 完全一致
        allTiles.find { it.name == recognizedName }?.let {
            return it.name
        }
        // 部分一致(簡易)
        val lowered = recognizedName.lowercase()
        var bestMatch: String? = null
        var bestScore = 0
        allTiles.forEach { tile ->
            val tileName = tile.name.lowercase()
            val score = commonCharsCount(lowered, tileName)
            if (score > bestScore) {
                bestScore = score
                bestMatch = tile.name
            }
        }
        val finalMatch = bestMatch
        return if (bestScore > 3 && finalMatch != null) {
            finalMatch
        } else {
            "不明"
        }
    }

    private fun commonCharsCount(a: String, b: String): Int {
        val setA = a.toSet()
        val setB = b.toSet()
        return setA.intersect(setB).size
    }

    /**
     * ユーザーに予測手牌を表示し、「はい/いいえ」を確認する
     */

    private val handCorrectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val correctedHand = result.data?.getStringArrayListExtra("correctedHand")?.toList() ?: return@registerForActivityResult
            recognizedHand.clear()
            recognizedHand.addAll(correctedHand)
            showRecognizedHandDialog(recognizedHand)
        }
    }


    private fun showRecognizedHandDialog(handList: List<String>) {
        val handWithAttributes: List<Pair<String, String>> = handList.map { tileName ->
            val attribute = allTiles.find { it.name == tileName }?.attribute ?: "不明"
            tileName to attribute
        }

        val sortedHand = handWithAttributes.sortedBy { it.second }
        recognizedHand.clear()
        recognizedHand.addAll(sortedHand.map { it.first })

        val message = buildString {
            append("画像認識結果:\n")
            sortedHand.forEachIndexed { index, pair ->
                append("${index + 1}枚目: ${pair.first} (属性: ${pair.second})\n")
            }
            append("\nこの手牌でよろしいですか？")
        }

        AlertDialog.Builder(this)
            .setTitle("手牌の再確認")
            .setMessage(message)
            .setPositiveButton("はい") { dialog, _ ->
                dialog.dismiss()

                if (recognizedHand.size < 9) {
                    Toast.makeText(this, "手牌が9枚ありません。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 手牌確定処理をここで行う（内部的に手牌確定！）
                editTextHand.setText(recognizedHand.joinToString(","))
                buttonStartCalculation.isEnabled = true
                Toast.makeText(this, "手牌が確定しました。「計算実行!!」ボタンを押してください。", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("いいえ") { dialog, _ ->
                dialog.dismiss()

                val intent = Intent(this, HandCorrectionActivity::class.java).apply {
                    putStringArrayListExtra("recognizedHand", ArrayList(recognizedHand))
                    putStringArrayListExtra("allTileNames", ArrayList(allTiles.map { it.name }))
                }
                handCorrectionLauncher.launch(intent)
            }
            .show()
    }



    /**
     * オールマイティ置き換え処理
     */
    private suspend fun promptAlmightyReplacement(originalHand: List<String>): List<String> =
        suspendCoroutine { continuation ->
            val almightyName = "オキシジェン・デストロイヤー"
            val handList = originalHand.toMutableList()
            val candidateNames = allTiles.map { it.name }.toMutableList()

            // オールマイティが存在しない場合はそのまま返す
            if (!handList.contains(almightyName)) {
                continuation.resume(handList)
                return@suspendCoroutine
            }

            fun replaceNext(indexList: List<Int>, currentIdx: Int) {
                if (currentIdx >= indexList.size) {
                    continuation.resume(handList)
                    return
                }

                val almightyIndex = indexList[currentIdx]
                val currentHandNames = handList.toSet()
                val filteredCandidates = candidateNames.filter { it !in currentHandNames }

                if (filteredCandidates.isEmpty()) {
                    continuation.resume(handList)
                    return
                }

                val options = filteredCandidates.toTypedArray()

                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("オールマイティ${currentIdx + 1}枚目の置き換え先を選択してください")
                        .setItems(options) { _, which ->
                            val selected = options[which]
                            handList[almightyIndex] = selected
                            candidateNames.remove(selected) // 重複防止
                            replaceNext(indexList, currentIdx + 1)
                        }
                        .setCancelable(false)
                        .show()
                }
            }

            // すべてのオールマイティの位置を記録
            val almightyIndexes = handList.mapIndexedNotNull { idx, name ->
                if (name == almightyName) idx else null
            }

            replaceNext(almightyIndexes, 0)
        }


    // 補助メソッド：牌名から属性情報を取得する関数
    private fun tentativeHandWithAttributes(hand: List<String>): List<Pair<String, String>> {
        return hand.map { tileName ->
            val attribute = allTiles.find { it.name == tileName }?.attribute ?: "不明"
            tileName to attribute
        }.sortedBy { it.second }
    }


    /**
     * 計算結果を整形して表示用テキストを作成
     */
    private fun buildResultText(result: DonjaraResult): String {
        return buildString {
            // ① 最終得点
            append("【最終得点】 ${result.finalScore}点\n\n")

            // ② 基本役
            if (result.basicRoleName != null) {
                append("【基本役】${result.basicRoleName}（${result.basicRoleScore}点）\n\n")
            } else {
                append("【基本役】成立せず\n\n")
            }

            // ③ 加点役とその対象牌
            if (result.bonusRoleDetail.isNotEmpty()) {
                append("【加点役】\n")
                result.bonusRoleDetail.forEach { detail ->
                    append("・${detail.roleName}（${detail.bonusScore}点）\n")
                    // 対象牌を一枚ずつ改行して表示
                    detail.targetTiles.forEach { tile ->
                        append("　　- $tile\n")
                    }
                    append("\n")
                }
                append("合計加点: ${result.bonusScore}点\n\n")
            } else {
                append("【加点役】なし\n\n")
            }

            // ④ 手牌一覧を一枚ずつ改行して表示
            append("【手牌一覧】\n")
            result.hand.forEach { tile ->
                append("$tile\n")
            }
        }
    }

    private fun buildStyledResultText(result: DonjaraResult): SpannableString {
        // SpannableStringBuilderを使うと、文字列を追加しつつスパンを設定しやすい
        val builder = SpannableStringBuilder()

        // 最終得点
        val score = result.finalScore

// 得点に応じてサイズと色を決定
        val (colorHex, fontSize) = when {
            score <= 100000 -> "#FFFFFF" to 24  // 白
            score <= 200000 -> "#448AFF" to 30  // 青
            score <= 300000 -> "#4CAF50" to 40  // 緑
            else -> "#FF5252" to 48             // 赤
        }

// 「【最終得点】」ラベル
        val finalScoreLabelStart = builder.length
        builder.append("【最終得点】\n")
        val finalScoreLabelEnd = builder.length

// 得点部分（🔥など追加して派手に！）
        val finalScoreValueStart = builder.length
        builder.append("🔥${score}点🔥\n\n")
        val finalScoreValueEnd = builder.length

// ラベル部分（固定で白・24sp太字）
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            finalScoreLabelStart,
            finalScoreLabelEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            ForegroundColorSpan(Color.WHITE),
            finalScoreLabelStart,
            finalScoreLabelEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            AbsoluteSizeSpan(24, true),
            finalScoreLabelStart,
            finalScoreLabelEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

// 得点部分（動的に決定された色とサイズを使用）
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            finalScoreValueStart,
            finalScoreValueEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor(colorHex)), // 動的カラー
            finalScoreValueStart,
            finalScoreValueEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            AbsoluteSizeSpan(fontSize, true), // 動的サイズ
            finalScoreValueStart,
            finalScoreValueEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )


        // --- (2) 基本役 ---
        if (result.basicRoleName != null) {
            // タイトル（基本役）の強調表示
            val basicRoleLabelStart = builder.length
            builder.append("【基本役】\n")
            val basicRoleLabelEnd = builder.length

            // 役名部分の強調表示
            val basicRoleNameStart = builder.length
            builder.append("・${result.basicRoleName}\n")
            val basicRoleNameEnd = builder.length

            // 得点部分の強調表示
            val basicRoleScoreStart = builder.length
            builder.append("+${result.basicRoleScore}点\n\n")
            val basicRoleScoreEnd = builder.length

            // タイトルのスタイル（太字＆大きめ）
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                basicRoleLabelStart,
                basicRoleLabelEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                AbsoluteSizeSpan(24, true),
                basicRoleLabelStart,
                basicRoleLabelEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 役名のスタイル（さらに大きめ）
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                basicRoleNameStart,
                basicRoleNameEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                AbsoluteSizeSpan(24, true),
                basicRoleNameStart,
                basicRoleNameEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // 得点のスタイル（やや大きめ、カラーを変えるなども可能）
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                basicRoleScoreStart,
                basicRoleScoreEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                AbsoluteSizeSpan(24, true),
                basicRoleScoreStart,
                basicRoleScoreEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

        } else {
            builder.append("【基本役】成立せず\n\n")
        }


        // --- (3) 加点役 見出し ---
        val bonusTitleStart = builder.length
        builder.append("【加点役】\n")
        val bonusTitleEnd = builder.indexOf('\n', bonusTitleStart).takeIf { it >= 0 } ?: builder.length
        builder.setSpan(StyleSpan(Typeface.BOLD), bonusTitleStart, bonusTitleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(AbsoluteSizeSpan(24, true), bonusTitleStart, bonusTitleEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // --- (3.1) 各加点役の行 ---
        if (result.bonusRoleDetail.isNotEmpty()) {
            result.bonusRoleDetail.forEach { detail ->
                // 「・」＋役名を追加し、役名の範囲取得
                val roleNameStart = builder.length
                builder.append("・${detail.roleName}\n")
                val roleNameEnd = builder.length

                // 得点部分を追加し、得点の範囲取得
                val bonusScoreStart = builder.length
                builder.append("+${detail.bonusScore}点\n")
                val bonusScoreEnd = builder.length

                // 対象牌を追加（改行で区切り見やすくする）
                val targetTilesStart = builder.length
                detail.targetTiles.forEach { tile ->
                    builder.append("・$tile\n")
                }
                builder.append("\n")

                // 役名のスタイル（太字＆大きめ）
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    roleNameStart,
                    roleNameEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    AbsoluteSizeSpan(24, true),
                    roleNameStart,
                    roleNameEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // 得点のスタイル（太字＆大きめ・少し役名より小さめにしてメリハリを）
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    bonusScoreStart,
                    bonusScoreEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    AbsoluteSizeSpan(22, true),
                    bonusScoreStart,
                    bonusScoreEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            builder.append("\n")
        }


// --- (4) 手牌一覧 ---
// 「【手牌一覧】」タイトルの範囲取得とスタイル指定
        val handTitleStart = builder.length
        builder.append("【手牌一覧】\n")
        val handTitleEnd = builder.length

        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            handTitleStart,
            handTitleEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            AbsoluteSizeSpan(24, true),
            handTitleStart,
            handTitleEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

// 各牌名の先頭に「・」を追加
        result.hand.forEach { tile ->
            builder.append("・$tile\n")
        }


        return SpannableString(builder)
    }


    /**
     * 計算終了時等にキャッシュファイルを削除する処理例
     */
    private fun deleteCapturedImageFile() {
        try {
            capturedImageFile?.let {
                if (it.exists()) {
                    it.delete()
                    Log.d("DonjaraActivity", "一時保存した画像を削除しました: ${it.absolutePath}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            capturedImageFile = null
        }
    }
}
