import org.junit.Assert.assertEquals
import org.junit.Test

class StsLocalAdbCommandTest {
    @Test
    fun explicitTargetIsForwardedBeforeTheAction() {
        assertEquals(
            listOf("python3", "-m", "scripts.tools.local_adb", "--expected-serial", "device-B", "adb", "--", "shell", "am", "force-stop", "io.test"),
            localAdbPrefix("python3", "device-B") + listOf("adb", "--", "shell", "am", "force-stop", "io.test")
        )
    }

    @Test
    fun unspecifiedTargetKeepsTheConfiguredNonInstallPolicy() {
        assertEquals(
            listOf("python3", "-m", "scripts.tools.local_adb", "ensure"),
            localAdbPrefix("python3", "") + "ensure"
        )
    }
}
