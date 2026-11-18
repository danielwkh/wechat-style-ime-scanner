package com.danielwkh.weime

/**
 * 純 Kotlin、零依賴的一維條碼編碼器（EAN-13／EAN-8／Code 128／Code 39／ITF-25）。
 * 供列印預覽與 LPAPI 輸出共用；[Encoded.bits] 為 '1'（黑條）與 '0'（空白）的字串。
 */
object BarcodeRenderer {
    const val TYPE_AUTO = "auto"
    const val TYPE_EAN13 = "ean13"
    const val TYPE_EAN8 = "ean8"
    const val TYPE_CODE128 = "code128"
    const val TYPE_CODE39 = "code39"
    const val TYPE_ITF = "itf25"

    /** 供 UI 下拉選單使用：代碼與顯示名稱。 */
    val types = listOf(
        TYPE_AUTO to "自動判斷",
        TYPE_EAN13 to "EAN-13",
        TYPE_EAN8 to "EAN-8",
        TYPE_CODE128 to "Code 128",
        TYPE_CODE39 to "Code 39",
        TYPE_ITF to "ITF-25",
    )

    data class Encoded(val bits: String, val display: String, val type: String)

    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011"
    )
    private val G = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111"
    )
    private val R = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100"
    )
    private val EAN13_PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    )
    private val CODE128_B = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    )

    /** Code 39：9 個元素（條、空交錯），'1' 為寬元素。 */
    private val CODE39 = mapOf(
        '0' to "000110100", '1' to "100100001", '2' to "001100001", '3' to "101100000",
        '4' to "000110001", '5' to "100110000", '6' to "001110000", '7' to "000100101",
        '8' to "100100100", '9' to "001100100",
        'A' to "100001001", 'B' to "001001001", 'C' to "101001000", 'D' to "000011001",
        'E' to "100011000", 'F' to "001011000", 'G' to "000001101", 'H' to "100001100",
        'I' to "001001100", 'J' to "000011100", 'K' to "100000011", 'L' to "001000011",
        'M' to "101000010", 'N' to "000010011", 'O' to "100010010", 'P' to "001010010",
        'Q' to "000000111", 'R' to "100000110", 'S' to "001000110", 'T' to "000010110",
        'U' to "110000001", 'V' to "011000001", 'W' to "111000000", 'X' to "010010001",
        'Y' to "110010000", 'Z' to "011010000",
        '-' to "010000101", '.' to "110000100", ' ' to "011000100", '$' to "010101000",
        '/' to "010100010", '+' to "010001010", '%' to "000101010", '*' to "010010100"
    )

    /** ITF-25：每個數字 5 個元素，其中 2 個為寬元素。 */
    private val ITF = arrayOf(
        "00110", "10001", "01001", "11000", "00101",
        "10100", "01100", "00011", "10010", "01010"
    )

    private const val NARROW = 1
    private const val WIDE = 3

    /**
     * 依 [type] 編碼；[TYPE_AUTO] 時依長度判斷（8 碼 → EAN-8、12/13 碼 → EAN-13、其餘 → Code 128）。
     * 指定類型但內容不符合時回傳 null，讓 UI 顯示「條碼格式無效」而不是印出錯誤的條碼。
     */
    fun encode(raw: String, type: String = TYPE_AUTO): Encoded? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val digits = text.filter { it.isDigit() }
        return when (type) {
            TYPE_EAN13 -> encodeEan13(normalizeEan13(digits), text)
            TYPE_EAN8 -> encodeEan8(digits, text)
            TYPE_CODE128 -> encodeCode128(text, text)
            TYPE_CODE39 -> encodeCode39(text)
            TYPE_ITF -> encodeItf(digits, text)
            else -> when (digits.length) {
                8 -> encodeEan8(digits, text) ?: encodeCode128(text, text)
                12 -> encodeEan13(normalizeEan13(digits), text) ?: encodeCode128(text, text)
                13 -> encodeEan13(digits, text) ?: encodeCode128(text, text)
                else -> encodeCode128(text, text)
            }
        }
    }

    /** 12 碼補檢查碼；13 碼原樣（由 encodeEan13 驗證檢查碼）。 */
    private fun normalizeEan13(digits: String): String? = when (digits.length) {
        12 -> digits + checksum(digits)
        13 -> digits
        else -> null
    }

    private fun encodeCode128(value: String, display: String): Encoded? {
        if (value.isEmpty() || value.any { it.code !in 32..126 }) return null
        val values = value.map { it.code - 32 }
        var checksumValue = 104
        values.forEachIndexed { index, item -> checksumValue += item * (index + 1) }
        val bits = buildString {
            append(CODE128_B[104])
            values.forEach { append(CODE128_B[it]) }
            append(CODE128_B[checksumValue % 103])
            append(CODE128_B[106])
        }
        return Encoded(expandWidths(bits, quiet = 10), display, "Code 128")
    }

    /**
     * Code 128 的樣式表是「每個元素的模組寬度」（1–4），直接串成 '1'/'0' 位元字串。
     * 為了與其他條碼共用繪製程式，這裡把寬度展開成 0/1。
     */
    private fun expandWidths(pattern: String, quiet: Int): String {
        val sb = StringBuilder()
        sb.append("0".repeat(quiet))
        var bar = true
        for (ch in pattern) {
            val width = ch - '0'
            sb.append((if (bar) '1' else '0').toString().repeat(width))
            bar = !bar
        }
        sb.append("0".repeat(quiet))
        return sb.toString()
    }

    private fun encodeEan13(full: String?, display: String): Encoded? {
        if (full == null || full.length != 13) return null
        val body = full.substring(0, 12)
        if (full[12] - '0' != checksum(body)) return null
        val parity = EAN13_PARITY[full[0] - '0']
        val left = buildString {
            for (i in 0 until 6) append(if (parity[i] == 'L') L else G, body[i + 1] - '0')
        }
        val right = buildString {
            for (i in 6 until 12) append(R, body[i] - '0')
        }
        return Encoded("0".repeat(9) + "101" + left + "01010" + right + "101" + "0".repeat(7), display, "EAN-13")
    }

    private fun encodeEan8(digits: String, display: String): Encoded? {
        if (digits.length != 8 || checksum(digits.substring(0, 7)) != digits[7] - '0') return null
        val left = buildString { for (i in 0 until 4) append(L, digits[i] - '0') }
        val right = buildString { for (i in 4 until 8) append(R, digits[i] - '0') }
        return Encoded("0".repeat(7) + "101" + left + "01010" + right + "101" + "0".repeat(7), display, "EAN-8")
    }

    private fun encodeCode39(value: String): Encoded? {
        val text = value.uppercase()
        if (text.any { it !in CODE39 }) return null
        val sb = StringBuilder()
        sb.append("0".repeat(10))
        for (ch in "*$text*") {
            val pattern = CODE39.getValue(ch)
            for (i in pattern.indices) {
                val wide = pattern[i] == '1'
                val width = if (wide) WIDE else NARROW
                sb.append((if (i % 2 == 0) '1' else '0').toString().repeat(width))
            }
            // 字元間固定一個窄空白
            sb.append('0')
        }
        sb.append("0".repeat(10))
        return Encoded(sb.toString(), value, "Code 39")
    }

    private fun encodeItf(digits: String, display: String): Encoded? {
        if (digits.isEmpty() || digits.length > 40) return null
        // ITF 必須是偶數位，奇數時補前導 0
        val body = if (digits.length % 2 == 0) digits else "0$digits"
        val sb = StringBuilder()
        sb.append("0".repeat(10))
        // 起始碼：4 個窄元素（條空條空）
        repeat(4) { i -> sb.append((if (i % 2 == 0) '1' else '0').toString().repeat(NARROW)) }
        var i = 0
        while (i < body.length) {
            val a = ITF[body[i] - '0']
            val b = ITF[body[i + 1] - '0']
            // 兩數字交錯：第 1 個數字的元素為條，第 2 個為空
            for (k in 0 until 5) {
                sb.append("1".repeat(if (a[k] == '1') WIDE else NARROW))
                sb.append("0".repeat(if (b[k] == '1') WIDE else NARROW))
            }
            i += 2
        }
        // 終止碼：寬條、窄空、窄條
        sb.append("1".repeat(WIDE)).append("0".repeat(NARROW)).append("1".repeat(NARROW))
        sb.append("0".repeat(10))
        return Encoded(sb.toString(), display, "ITF-25")
    }

    private fun checksum(body: String): Int {
        var sum = 0
        for (i in body.indices.reversed()) {
            val weight = if ((body.lastIndex - i) % 2 == 0) 3 else 1
            sum += (body[i] - '0') * weight
        }
        return (10 - sum % 10) % 10
    }

    private fun StringBuilder.append(patterns: Array<String>, index: Int) {
        append(patterns[index])
    }
}
