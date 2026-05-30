package sa.allstak.android.timber

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import timber.log.Timber

/**
 * The auto-installer is what makes Timber logging zero-config: the core SDK
 * invokes it reflectively during init. These checks confirm it plants exactly
 * one tree and is a safe no-op when already planted.
 */
class AllStakTimberInstallerTest {

    @AfterEach
    fun tearDown() {
        Timber.uprootAll()
        // Reset the one-shot guard so each test starts clean.
        val field = AllStakTimberInstaller::class.java.getDeclaredField("planted")
        field.isAccessible = true
        (field.get(AllStakTimberInstaller) as java.util.concurrent.atomic.AtomicBoolean).set(false)
    }

    @Test
    fun `install plants exactly one AllStakTree`() {
        AllStakTimberInstaller.install()
        assertEquals(1, Timber.forest().count { it is AllStakTree })
    }

    @Test
    fun `install is idempotent within a process`() {
        AllStakTimberInstaller.install()
        AllStakTimberInstaller.install()
        assertEquals(1, Timber.forest().count { it is AllStakTree })
    }

    @Test
    fun `install skips when an AllStakTree is already planted by the host`() {
        Timber.plant(AllStakTree())
        AllStakTimberInstaller.install()
        // Still just the one the host planted — no duplicate.
        assertEquals(1, Timber.forest().count { it is AllStakTree })
    }
}
