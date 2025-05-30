package com.example.donjara03

import android.util.Log

/**
 * 牌情報を表すデータクラス
 */
data class Tile(
    val name: String,
    val attribute: String,
    val era: String,
    val category: String,
    val color: String
)

/**
 * 計算結果を表すデータクラス
 */
data class BonusRoleDetail(
    val roleName: String,
    val bonusScore: Int,
    val targetTiles: List<String>
) {
    companion object {
        fun createEmpty(): BonusRoleDetail {
            return BonusRoleDetail("", 0, emptyList())
        }
    }
}

data class DonjaraResult(
    val basicRoleName: String?,
    val basicRoleScore: Int,
    val bonusRoles: List<String>, // 既存の加点役名
    val bonusScore: Int,
    val finalScore: Int,
    val hand: List<String>, // 手牌一覧
    val bonusRoleDetail: List<BonusRoleDetail> // 各加点役の詳細情報（対象牌など）
)

/**
 * 基本役の条件を定義
 */
data class BasicRoleCondition(
    val name: String,
    val allowed: List<String>?, // nullならすべて許可
    val ng: List<String>,
    val score: Int,
    val priority: Int,
    val special: Boolean = false // ゴジラファイナルウォーズセット判定用フラグ
)

/**
 * 加点役の条件を定義
 */
data class BonusRole(
    val name: String,
    val condition: String,
    val requiredCount: Int,
    val targets: List<String>,
    val bonusScore: Int
)

/**
 * 基本役条件のリスト(既存)
 */
val basicRoleConditions = listOf(
    // ゴジラファイナルウォーズセット
    BasicRoleCondition(
        "ゴジラファイナルウォーズセット",
        null,
        emptyList(),
        500000,
        10,
        special = true
    ),
    BasicRoleCondition(
        "昭和ゴジラセット",
        allowed = listOf("昭和ゴジラ"),
        ng = listOf("平成ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ", "昭和怪獣", "平成怪獣", "ミレニアム怪獣", "東宝メカ"),
        score = 360000,
        priority = 9
    ),
    BasicRoleCondition(
        "ミレニアムゴジラＶＳ怪獣セット",
        allowed = listOf("ミレニアムゴジラ", "ミレニアム怪獣"),
        ng = listOf("昭和ゴジラ", "平成ゴジラ", "シン・ゴジラ", "昭和怪獣", "平成怪獣", "東宝メカ"),
        score = 240000,
        priority = 8
    ),
    BasicRoleCondition(
        "平成ゴジラＶＳ怪獣セット",
        allowed = listOf("平成ゴジラ", "平成怪獣"),
        ng = listOf("昭和ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ", "昭和怪獣", "ミレニアム怪獣", "東宝メカ"),
        score = 240000,
        priority = 7
    ),
    BasicRoleCondition(
        "昭和ゴジラ対怪獣セット",
        allowed = listOf("昭和ゴジラ", "昭和怪獣"),
        ng = listOf("平成ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ", "平成怪獣", "ミレニアム怪獣", "東宝メカ"),
        score = 180000,
        priority = 6
    ),
    BasicRoleCondition(
        "ゴジラ一色セット",
        allowed = listOf("昭和ゴジラ", "平成ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ"),
        ng = listOf("昭和怪獣", "平成怪獣", "ミレニアム怪獣", "東宝メカ"),
        score = 180000,
        priority = 5
    ),
    BasicRoleCondition(
        "ゴジラVS東宝メカセット",
        allowed = listOf("昭和ゴジラ", "平成ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ", "東宝メカ"),
        ng = listOf("昭和怪獣", "平成怪獣", "ミレニアム怪獣"),
        score = 120000,
        priority = 4
    ),
    BasicRoleCondition(
        "怪獣セット",
        allowed = listOf("昭和怪獣", "平成怪獣", "ミレニアム怪獣"),
        ng = listOf("昭和ゴジラ", "平成ゴジラ", "ミレニアムゴジラ", "シン・ゴジラ", "東宝メカ"),
        score = 120000,
        priority = 3
    ),
    // 基本セット
    BasicRoleCondition(
        "基本セット",
        null,
        emptyList(),
        60000,
        2
    )
)

/**
 * 基本役判定（ゴジラファイナルウォーズは別途チェック）
 */
fun checkBasicRole(setsList: List<String>): Pair<String?, Int> {
    // GFWセットは special=true なのでここではスキップ
    for (role in basicRoleConditions.sortedByDescending { it.priority }) {
        if (role.special) continue
        // allowed が指定されていれば全セットが allowed の中にあるか
        if (role.allowed != null && !setsList.all { it in role.allowed }) continue
        // NGセットが一つでも含まれていたら不成立
        if (setsList.any { it in role.ng }) continue
        return Pair(role.name, role.score)
    }
    // すべて当てはまらなければ基本セット扱い
    return Pair("基本セット", 60000)
}

class DonjaraCalculator(
    private val tiles: List<Tile>,
    private val bonusRoles: List<BonusRole>
) {

    /**
     * (既存) オールマイティ牌をユーザーに問い合わせて変換するメソッド
     * 実際のUIは DonjaraActivity で実装している。
     */
    private fun handleAlmightyTiles(originalHand: List<String>): List<String> {
        val almightyName = "オキシジェン・デストロイヤー"
        var convertedHand = originalHand.toMutableList()

        while (convertedHand.contains(almightyName)) {
            val idx = convertedHand.indexOf(almightyName)
            if (idx < 0) break

            val currentHandNames = convertedHand.toSet()
            val candidateTiles = tiles.filter { it.name !in currentHandNames }
            val candidateNames = candidateTiles.map { it.name }

            // ユーザーが選ぶという前提。ここでは先頭を仮選択
            val userChoice = candidateNames.firstOrNull() ?: almightyName

            Log.d("DonjaraCalculator", "オールマイティ置き換え: $almightyName -> $userChoice")

            convertedHand[idx] = userChoice
        }

        return convertedHand
    }

    /**
     * (既存) 得点計算のメイン処理
     */
    suspend fun calculateScore(hand: List<String>): DonjaraResult {
        Log.d("DonjaraCalculator", "🀄手牌入力: $hand")

        // 1) オールマイティ変換
        val userDefinedHand = handleAlmightyTiles(hand)
        Log.d("DonjaraCalculator", "🃏オールマイティ変換後: $userDefinedHand")

        // 2) Tile変換
        val handTiles = userDefinedHand.mapNotNull { name ->
            tiles.find { it.name == name }
        }

        Log.d("DonjaraCalculator", "🧱手牌Tile情報:")
        handTiles.forEach {
            Log.d("DonjaraCalculator", "- ${it.name} (${it.attribute})")
        }

        // 3) GFWセット判定
        val finalWarsSetTiles = setOf(
            "ゴジラ(04)", "カマキラス(04)", "クモンガ(04)", "キングシーサー(04)",
            "ジラ", "モスラ(04)", "ガイガン(04)", "改造ガイガン", "モンスターＸ", "カイザーギドラ", "新・轟天号"
        )
        val countGfw = handTiles.count { it.name in finalWarsSetTiles }
        Log.d("DonjaraCalculator", "🛡️GFW該当枚数: $countGfw")
        if (countGfw >= 9) {
            Log.d("DonjaraCalculator", "✅ゴジラファイナルウォーズセット成立: 500000点")
            return DonjaraResult(
                basicRoleName = "ゴジラファイナルウォーズセット",
                basicRoleScore = 500000,
                bonusRoles = emptyList(),
                bonusScore = 0,
                finalScore = 500000,
                hand = hand,
                bonusRoleDetail = emptyList()
            )
        }

        // 4) 属性カウント → セット構成
        val groupedByAttr = handTiles.groupBy { it.attribute }
        Log.d("DonjaraCalculator", "📊属性ごとの枚数:")
        groupedByAttr.forEach { (attr, list) ->
            Log.d("DonjaraCalculator", "- $attr: ${list.size}枚 → ${list.size / 3}セット")
        }

        val setsList = groupedByAttr
            .mapValues { it.value.size / 3 }
            .filter { it.value > 0 }
            .flatMap { (attr, count) -> List(count) { attr } }

        Log.d("DonjaraCalculator", "📦形成されたセット一覧（属性）: $setsList")

        // 5) 3セット未満 → 基本役不成立
        if (setsList.size < 3) {
            Log.d("DonjaraCalculator", "❌基本役不成立（セット数 ${setsList.size}）")
            return DonjaraResult(
                basicRoleName = null,
                basicRoleScore = 0,
                bonusRoles = emptyList(),
                bonusScore = 0,
                finalScore = 0,
                hand = hand,
                bonusRoleDetail = emptyList()
            )
        }

        // 6) 基本役判定
        val (basicRoleName, basicRoleScore) = checkBasicRole(setsList)
        Log.d("DonjaraCalculator", "🎯基本役判定: $basicRoleName ($basicRoleScore 点)")

        if (basicRoleName == null || basicRoleScore == 0) {
            Log.d("DonjaraCalculator", "❌基本役不成立（条件不一致）")
            return DonjaraResult(
                basicRoleName = null,
                basicRoleScore = 0,
                bonusRoles = emptyList(),
                bonusScore = 0,
                finalScore = 0,
                hand = hand,
                bonusRoleDetail = emptyList()
            )
        }

        // 7) 加点役判定
        val (bonusDetailList, bonusScore) = calculateBonusScoreWithDetails(handTiles)
        val bonusNames = bonusDetailList.map { it.roleName }
        Log.d("DonjaraCalculator", "✨加点役成立: ${bonusNames.joinToString()}（合計 $bonusScore 点）")

        // 8) 最終得点
        val finalScore = basicRoleScore + bonusScore
        Log.d("DonjaraCalculator", "🏁最終得点: $finalScore 点")

        return DonjaraResult(
            basicRoleName = basicRoleName,
            basicRoleScore = basicRoleScore,
            bonusRoles = bonusNames,
            bonusScore = bonusScore,
            finalScore = finalScore,
            hand = hand,
            bonusRoleDetail = bonusDetailList
        )
    }


    /**
     * 加点役の詳細判定
     */
    private fun calculateBonusScoreWithDetails(handTiles: List<Tile>): Pair<List<BonusRoleDetail>, Int> {
        val detailList = mutableListOf<BonusRoleDetail>()
        var totalScore = 0
        for (bonus in bonusRoles) {
            val count = handTiles.count { it.name in bonus.targets }
            if (count >= bonus.requiredCount) {
                val matchedTiles = handTiles.filter { it.name in bonus.targets }.map { it.name }
                detailList.add(
                    BonusRoleDetail(
                        roleName = bonus.name,
                        bonusScore = bonus.bonusScore,
                        targetTiles = matchedTiles
                    )
                )
                totalScore += bonus.bonusScore
            }
        }
        return detailList to totalScore
    }

    /**
     * (既存) 加点役の判定
     */
    private fun calculateBonusScore(handTiles: List<Tile>): Pair<List<String>, Int> {
        val matchedBonuses = mutableListOf<String>()
        var totalBonusScore = 0
        for (bonus in bonusRoles) {
            val count = handTiles.count { it.name in bonus.targets }
            if (count >= bonus.requiredCount) {
                matchedBonuses.add(bonus.name)
                totalBonusScore += bonus.bonusScore
            }
        }
        return matchedBonuses to totalBonusScore
    }
}
