package kr.ac.kangwon.hai.vibefactory

data class RuntimeErrorAnalysis(
    val summary: String,
    val actualError: String?,
    val hasActualError: Boolean,
    val friendlyExplanation: String
)

object RuntimeErrorAnalyzer {
    private val structuredReportPrefixes = listOf(
        "simulated_error_test=",
        "phase=",
        "triggered_at=",
        "package_name=",
        "message="
    )

    fun analyze(
        stackTrace: String,
        errorMessage: String? = null,
        reportKind: String? = null
    ): RuntimeErrorAnalysis {
        val normalizedStack = stackTrace.trim()
        val normalizedErrorMessage = errorMessage?.trim().orEmpty()
        val actualError = sequenceOf(
            normalizedErrorMessage,
            *normalizedStack.lineSequence().toList().toTypedArray()
        )
            .map { normalizeCandidate(it) }
            .firstOrNull(::isLikelyActualError)

        if (!actualError.isNullOrBlank()) {
            return RuntimeErrorAnalysis(
                summary = actualError.take(180),
                actualError = actualError,
                hasActualError = true,
                friendlyExplanation = buildFriendlyExplanation(actualError)
            )
        }

        val isManualLikeReport = reportKind == "manual_report" || looksLikeStructuredManualReport(normalizedStack)
        return RuntimeErrorAnalysis(
            summary = if (isManualLikeReport) {
                "실제 예외 정보가 포함되지 않은 수동 오류 보고"
            } else {
                "실제 예외 정보를 추출하지 못한 런타임 오류"
            },
            actualError = null,
            hasActualError = false,
            friendlyExplanation = if (isManualLikeReport) {
                "이 보고는 실제 크래시 예외가 아니라 앱이 수동으로 보낸 테스트 또는 상태 보고예요."
            } else {
                "앱 실행 중 오류가 발생했지만 예외 문장을 바로 식별하지 못했어요. 원본 오류를 확인해 주세요."
            }
        )
    }

    private fun normalizeCandidate(value: String): String {
        return value
            .trim()
            .removePrefix("Unhandled Exception:")
            .trim()
    }

    private fun isLikelyActualError(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.startsWith("#")) return false
        if (value.startsWith("at ")) return false
        if (value.startsWith("library:")) return false
        if (value.startsWith("context:")) return false
        if (structuredReportPrefixes.any { value.startsWith(it) }) return false

        val lowercase = value.lowercase()
        return lowercase.contains("exception") ||
            lowercase.contains("error") ||
            lowercase.contains("assert") ||
            lowercase.contains("overflowed") ||
            lowercase.contains("null check operator used on a null value") ||
            lowercase.contains("setstate() or markneedsbuild() called during build")
    }

    private fun looksLikeStructuredManualReport(stackTrace: String): Boolean {
        if (stackTrace.isBlank()) return false
        val lines = stackTrace.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        return lines.any { line -> structuredReportPrefixes.any { prefix -> line.startsWith(prefix) } }
    }

    private fun buildFriendlyExplanation(actualError: String): String {
        val lowercase = actualError.lowercase()
        return when {
            "null check operator used on a null value" in lowercase ->
                "값이 아직 준비되지 않았는데 바로 사용하려 해서 발생한 문제예요."
            "lateinitializationerror" in lowercase ->
                "나중에 채우기로 한 값이 준비되기 전에 먼저 사용돼서 발생한 문제예요."
            "setstate() or markneedsbuild() called during build" in lowercase ->
                "화면이 아직 그려지는 중인데 상태를 바꾸려 해서 발생한 문제예요."
            "renderflex overflowed" in lowercase ->
                "화면에 들어갈 공간보다 내용이 커서 레이아웃이 넘친 문제예요."
            "nosuchmethoderror" in lowercase ->
                "없는 기능이나 잘못된 대상을 호출해서 발생한 문제예요."
            "missingpluginexception" in lowercase ->
                "앱이 필요로 하는 네이티브 기능 연결이 준비되지 않아 발생한 문제예요."
            "socketexception" in lowercase || "failed host lookup" in lowercase ->
                "네트워크 연결이나 서버 주소 문제로 통신에 실패한 상황이에요."
            "formatexception" in lowercase ->
                "문자열이나 데이터 형식이 기대한 모양과 달라서 처리하지 못한 문제예요."
            "rangeerror" in lowercase || "index out of range" in lowercase ->
                "리스트나 문자열의 범위를 벗어난 위치에 접근해서 발생한 문제예요."
            "assertion" in lowercase || "failed assertion" in lowercase ->
                "코드가 가정한 조건이 실제 실행 중에 깨져서 발생한 문제예요."
            "platformexception" in lowercase ->
                "Flutter와 안드로이드 네이티브 기능 사이 호출에서 문제가 생겼어요."
            "type '" in actualError && " is not a subtype of type " in actualError ->
                "데이터 타입이 기대한 형태와 달라서 처리하지 못한 문제예요."
            else ->
                "앱 실행 중 예외가 발생했어요. 원본 오류를 함께 확인해 주세요."
        }
    }
}
