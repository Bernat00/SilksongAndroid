package dev.silksong.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The budget string the port screen stores, and the two pure rules behind the
 * "patches are newer than the game" state.
 *
 * Both are small and both decide whether an hour of somebody's evening is
 * spent well, which is exactly the kind of code that should be checkable
 * without a phone.
 */
class BudgetTest {

    @Test
    fun readsTheFormTheScreenStores() {
        assertEquals(MonoRuntime.Budget(6, 1792), MonoRuntime.parseBudget("6:1792"))
        assertEquals(MonoRuntime.Budget(1, 448), MonoRuntime.parseBudget(" 1 : 448 "))
    }

    @Test
    fun refusesAnythingItCannotUse() {
        // "auto" is a sentinel the caller checks first, never a budget.
        assertNull(MonoRuntime.parseBudget("auto"))
        assertNull(MonoRuntime.parseBudget(""))
        assertNull(MonoRuntime.parseBudget("6"))
        assertNull(MonoRuntime.parseBudget("6:"))
        assertNull(MonoRuntime.parseBudget("six:1792"))
        // Below the runtime's own floor: a run at that setting would have
        // nothing left to give up the moment it is reclaimed.
        assertNull(MonoRuntime.parseBudget("6:100"))
        assertNull(MonoRuntime.parseBudget("0:1792"))
    }

    @Test
    fun everyChoiceOnTheScreenRoundTrips() {
        for (budget in MonoRuntime.CHOICES) {
            assertEquals(budget, MonoRuntime.parseBudget(budget.setting()))
        }
    }
}

class StaleBuildTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A package with both installed artefacts and no marker. */
    private fun installed(): File = temp.newFolder("pkg").apply {
        File(this, "lib/arm64").mkdirs()
        File(this, "lib/arm64/libil2cpp.so").writeText("engine")
        File(this, "data.apk").writeText("image")
    }

    @Test
    fun aBuildThatNeverFinishedIsNotStale() {
        // Nothing was ever marked complete: that is the port screen's job, not
        // this state's, and reporting it here would offer to keep playing a
        // game that does not exist.
        assertFalse(BuildInstallation.isStale(installed(), "2|new"))
    }

    @Test
    fun differentInputsAreStaleAndTheSameOnesAreNot() {
        val pkg = installed()
        BuildInstallation.complete(pkg, "2|old")
        assertEquals("2|old", BuildInstallation.builtSignature(pkg))
        assertTrue(BuildInstallation.isStale(pkg, "2|new"))
        assertFalse(BuildInstallation.isStale(pkg, "2|old"))
    }

    @Test
    fun outputsWithoutAMarkerAreNotStale() {
        val pkg = temp.newFolder("empty")
        assertNull(BuildInstallation.builtSignature(pkg))
        assertFalse(BuildInstallation.isStale(pkg, "2|new"))
    }
}
