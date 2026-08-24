package kr.ac.kangwon.hai.vibefactory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportSenderPolicyTest {
    @Test
    fun `accepts matching sender when platform provides sender identity`() {
        assertTrue(
            CrashReportSenderPolicy.accepts(
                reportedPackageName = "kr.ac.kangwon.hai.generated.sample",
                senderPackageName = "kr.ac.kangwon.hai.generated.sample",
                senderIdentityAvailable = true,
            )
        )
    }

    @Test
    fun `rejects missing or mismatched sender when identity is available`() {
        assertFalse(
            CrashReportSenderPolicy.accepts(
                reportedPackageName = "kr.ac.kangwon.hai.generated.sample",
                senderPackageName = null,
                senderIdentityAvailable = true,
            )
        )
        assertFalse(
            CrashReportSenderPolicy.accepts(
                reportedPackageName = "kr.ac.kangwon.hai.generated.sample",
                senderPackageName = "com.example.other",
                senderIdentityAvailable = true,
            )
        )
    }

    @Test
    fun `keeps legacy delivery compatible when sender identity is unavailable`() {
        assertTrue(
            CrashReportSenderPolicy.accepts(
                reportedPackageName = "kr.ac.kangwon.hai.generated.sample",
                senderPackageName = null,
                senderIdentityAvailable = false,
            )
        )
    }
}
