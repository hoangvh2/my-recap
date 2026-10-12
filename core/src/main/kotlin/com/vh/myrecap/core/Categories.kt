package com.vh.myrecap.core

/** Picks an expense category for a typed title without asking the AI. */
object Categories {
    /** Keywords per category, on folded text; checked in order so "vé phim" lands on entertainment
     * and "mua điện thoại" on shopping, not bills. */
    private val KEYWORDS = listOf(
        "Giải trí" to listOf("phim", "netflix", "spotify", "karaoke", "game", "du lich"),
        "Ăn uống" to listOf("ca phe", "cafe", "coffee", "an", "com", "pho", "bun", "banh", "tra sua", "tra da", "nuoc uong", "bia", "nhau", "lau", "nuong", "an sang", "an trua", "an toi"),
        "Di chuyển" to listOf("grab", "be bike", "be car", "xanh sm", "xang", "taxi", "gui xe", "ve xe", "ve may bay", "ve tau", "do xe", "sua xe"),
        "Mua sắm" to listOf("shopee", "lazada", "tiki", "mua", "quan ao", "giay", "sieu thi"),
        "Hoá đơn" to listOf("dien", "nuoc", "internet", "wifi", "tien nha", "thue nha", "dien thoai", "4g", "truyen hinh"),
        "Sức khoẻ" to listOf("thuoc", "kham", "benh vien", "nha khoa", "gym", "bao hiem"),
        "Giáo dục" to listOf("sach", "hoc", "khoa hoc", "hoc phi"),
        "Gia đình" to listOf("bo me", "sinh nhat", "dam cuoi", "dam gio", "mung cuoi"),
    )

    /**
     * The category the user gave the latest expense with the same title, else a keyword guess,
     * else "Khác". History wins so a user's own correction sticks.
     */
    fun guess(title: String, history: List<Item>): String {
        val key = TextSearch.fold(title)
        history.asSequence()
            .filter { it.type == ItemType.EXPENSE && !it.category.isNullOrBlank() && TextSearch.fold(it.title) == key }
            .maxByOrNull { it.createdAt }
            ?.let { return it.category!! }
        for ((category, words) in KEYWORDS) {
            if (words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(key) }) return category
        }
        return SpendingStats.OTHER
    }
}
