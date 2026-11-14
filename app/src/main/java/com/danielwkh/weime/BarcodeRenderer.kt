package com.danielwkh.weime

/** 純 Kotlin 的零依賴 EAN-13／EAN-8 條碼編碼器，供列印預覽與後續 LPAPI 輸出共用。 */
object BarcodeRenderer {
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

    fun encode(raw: String): Encoded? {
        val digits = raw.filter { it.isDigit() }
        val display = raw.trim()
        return when (digits.length) {
            8 -> encodeEan8(digits, display) ?: encodeCode128(digits, display)
            12 -> encodeEan13("0$digits", display) ?: encodeCode128(digits, display)
            13 -> encodeEan13(digits, display) ?: encodeCode128(digits, display)
            else -> encodeCode128(digits, display)
        }
    }

    private fun encodeCode128(value: String, display: String = value): Encoded? {
        if (value.isEmpty() || value.any { it.code !in 32..126 }) return null
        val values = value.map { it.code - 32 }
        var checksum = 104
        values.forEachIndexed { index, item -> checksum += item * (index + 1) }
        val bits = buildString {
            append(CODE128_B[104])
            values.forEach { append(CODE128_B[it]) }
            append(CODE128_B[checksum % 103])
            append(CODE128_B[106])
        }
        return Encoded(bits, display, "Code 128")
    }

    private fun encodeEan13(full: String, display: String): Encoded? {
        if (full.length != 13) return null
        val body = full.substring(0, 12)
        val expected = checksum(body)
        if (full[12] - '0' != expected) return null
        val parity = EAN13_PARITY[full[0] - '0']
        val left = buildString {
            for (i in 0 until 6) append(if (parity[i] == 'L') L else G, body[i + 1] - '0')
        }
        val right = buildString {
            for (i in 6 until 12) append(R, body[i] - '0')
        }
        return Encoded("101$left" + "01010" + right + "101", display, "EAN-13")
    }

    private fun encodeEan8(digits: String, display: String): Encoded? {
        if (digits.length != 8 || checksum(digits.substring(0, 7)) != digits[7] - '0') return null
        val left = buildString { for (i in 0 until 4) append(L, digits[i] - '0') }
        val right = buildString { for (i in 4 until 8) append(R, digits[i] - '0') }
        return Encoded("101$left" + "01010" + right + "101", display, "EAN-8")
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
