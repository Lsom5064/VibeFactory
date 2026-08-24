package kr.ac.kangwon.hai.vibefactory

object KoreanPhoneNumberFormatter {
    fun format(rawValue: String): String {
        val digits = rawValue.filter(Char::isDigit).take(MAX_PHONE_DIGITS)
        return if (digits.startsWith(SEOUL_AREA_CODE)) {
            formatSeoulNumber(digits)
        } else {
            formatGeneralNumber(digits)
        }
    }

    private fun formatSeoulNumber(digits: String): String = when {
        digits.length <= 2 -> digits
        digits.length <= 5 -> "${digits.substring(0, 2)}-${digits.substring(2)}"
        digits.length <= 9 -> {
            "${digits.substring(0, 2)}-${digits.substring(2, digits.length - 4)}-${digits.takeLast(4)}"
        }
        else -> "${digits.substring(0, 2)}-${digits.substring(2, 6)}-${digits.substring(6, 10)}"
    }

    private fun formatGeneralNumber(digits: String): String = when {
        digits.length <= 3 -> digits
        digits.length <= 7 -> "${digits.substring(0, 3)}-${digits.substring(3)}"
        else -> "${digits.substring(0, 3)}-${digits.substring(3, 7)}-${digits.substring(7)}"
    }

    private const val MAX_PHONE_DIGITS = 11
    private const val SEOUL_AREA_CODE = "02"
}
