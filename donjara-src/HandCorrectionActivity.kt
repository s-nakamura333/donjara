package com.example.donjara03

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Spinner
import android.widget.Button
import android.widget.ArrayAdapter
import android.content.Intent

class HandCorrectionActivity : AppCompatActivity() {

    private val spinnerIds = listOf(
        R.id.spinnerTile0, R.id.spinnerTile1, R.id.spinnerTile2,
        R.id.spinnerTile3, R.id.spinnerTile4, R.id.spinnerTile5,
        R.id.spinnerTile6, R.id.spinnerTile7, R.id.spinnerTile8
    )

    // 全牌名を受け取る
    private lateinit var allTileNames: List<String>
    // 認識結果(9枚)を受け取る
    private lateinit var recognizedHand: MutableList<String>

    private lateinit var spinners: List<Spinner>
    private lateinit var buttonConfirm: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hand_correction)

        // Intent から受け取る
        allTileNames = intent.getStringArrayListExtra("allTileNames")?.toList() ?: emptyList()
        recognizedHand = intent.getStringArrayListExtra("recognizedHand")?.toMutableList() ?: mutableListOf()

        // Spinnerをまとめて取得
        spinners = spinnerIds.map { findViewById<Spinner>(it) }

        // 確定ボタン
        buttonConfirm = findViewById(R.id.buttonConfirm)

        // Spinnerにアダプタを設定
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allTileNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // 初期選択の反映
        spinners.forEachIndexed { i, spinner ->
            spinner.adapter = adapter
            val selectedIndex = allTileNames.indexOf(recognizedHand.getOrNull(i))
            if (selectedIndex >= 0) {
                spinner.setSelection(selectedIndex)
            } else {
                spinner.setSelection(0)
            }
        }

        // 確定ボタン押下時の処理
        buttonConfirm.setOnClickListener {
            // Spinnerの選択結果を recognizedHand に反映
            spinners.forEachIndexed { i, spinner ->
                val selectedName = spinner.selectedItem as String
                recognizedHand[i] = selectedName
            }

            // 結果を DonjaraActivity (呼び出し元) に返す
            val resultIntent = Intent().apply {
                putStringArrayListExtra("correctedHand", ArrayList(recognizedHand))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }
}
