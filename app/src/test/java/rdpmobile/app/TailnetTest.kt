package rdpmobile.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetTest {
    @Test
    fun acceptsTailscaleRange() {
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.254", " 100.64.0.36 ").forEach {
            assertTrue(it, Tailnet.isTailnetLiteral(it))
        }
    }

    @Test
    fun rejectsOutsideRangeAndNonLiterals() {
        listOf(
            "100.63.255.255", "100.128.0.1", "192.168.1.10", "10.0.0.1", "::1",
            "100.64.0", "100.64.0.1.5", "100.64.0.256", "laptop.ts.net", "", "100.64.0.-1",
        ).forEach {
            assertFalse(it, Tailnet.isTailnetLiteral(it))
        }
    }
}
