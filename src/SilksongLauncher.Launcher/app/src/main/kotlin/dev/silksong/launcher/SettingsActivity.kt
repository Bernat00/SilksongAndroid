// SettingsActivity — a tiny standalone Activity for the launcher's
// user-tweakable behaviour flags. Currently:
//
//   Auto-pull before game launch — when enabled AND a Steam token is
//     on file, tapping Launch runs a pull FIRST and only then starts
//     the game, so the player always boots on their freshest cloud
//     save — the same model Steam uses. analyzePull downloads files
//     where cloud is strictly newer; if a local save is newer than the
//     cloud, the conflict dialog asks the user to keep local or keep
//     remote (or cancel, which aborts the launch).
//
//   Auto-push on game exit — when enabled AND a Steam token is on
//     file, the launcher runs a `safe push` the next time it gains
//     focus after the user returned from playing the game. A conflict
//     (cloud newer than local) raises the same keep-local / keep-remote
//     dialog as the manual Push button; Cancel aborts, nothing uploads.
//
//   Show perf overlay — toggles the in-game OnGUI HUD.
//
// All toggles default to OFF so the launcher behaves identically to
// the manual-button-only baseline until the user opts in. Persisted
// via SettingsStore (SharedPreferences).

package dev.silksong.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch

class SettingsActivity : Activity() {

    private lateinit var settings: SettingsStore
    private lateinit var swAutoPull: Switch
    private lateinit var swAutoPush: Switch
    private lateinit var swPerfOverlay: Switch
    private lateinit var swPerfLog: Switch
    private lateinit var swTouchOverlay: Switch
    private lateinit var swGogPull: Switch
    private lateinit var swGogPush: Switch
    private lateinit var btnGogAuth: Button
    private lateinit var tvGogStatus: android.widget.TextView
    private lateinit var swKeepScreenOn: Switch
    private lateinit var swSkipIntro: Switch
    private lateinit var swDualScreen: Switch
    private lateinit var swShowTopHud: Switch
    private lateinit var swWideAspect: Switch
    private lateinit var btnClearBuild: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsStore(this)

        swAutoPull = findViewById(R.id.sw_auto_pull)
        swAutoPush = findViewById(R.id.sw_auto_push)
        swPerfOverlay = findViewById(R.id.sw_perf_overlay)
        swPerfLog = findViewById(R.id.sw_perf_log)
        swTouchOverlay = findViewById(R.id.sw_touch_overlay)
        swGogPull = findViewById(R.id.sw_gog_pull)
        swGogPush = findViewById(R.id.sw_gog_push)
        btnGogAuth = findViewById(R.id.btn_gog_auth)
        tvGogStatus = findViewById(R.id.tv_gog_status)
        swKeepScreenOn = findViewById(R.id.sw_keep_screen_on)
        swSkipIntro = findViewById(R.id.sw_skip_intro)
        swDualScreen = findViewById(R.id.sw_dual_screen)
        swShowTopHud = findViewById(R.id.sw_show_top_hud)
        swWideAspect = findViewById(R.id.sw_wide_aspect)

        val btnBack: Button = findViewById(R.id.btn_settings_back)

        swAutoPull.isChecked = settings.autoPull
        swAutoPush.isChecked = settings.autoPush
        swPerfOverlay.isChecked = settings.perfOverlay
        swPerfLog.isChecked = settings.perfLog
        swTouchOverlay.isChecked = settings.touchOverlay
        swGogPull.isChecked = settings.gogAutoPull && GogAuth.isSignedIn(this)
        swGogPush.isChecked = settings.gogAutoPush && GogAuth.isSignedIn(this)
        swKeepScreenOn.isChecked = settings.keepScreenOn
        swSkipIntro.isChecked = settings.skipIntro
        swDualScreen.isChecked = settings.dualScreen
        swShowTopHud.isChecked = settings.showTopHud
        swShowTopHud.isEnabled = swDualScreen.isChecked
        swWideAspect.isChecked = settings.wideAspect

        // Persist on every toggle — no separate Save button; the
        // settings screen is tiny enough that "click to toggle" is
        // its own commit. setOnCheckedChangeListener fires for the
        // initial isChecked = ... assignment too, so set listeners
        // AFTER seeding the initial state to avoid logging spurious
        // writes.
        swAutoPull.setOnCheckedChangeListener { _, checked ->
            settings.autoPull = checked
            LauncherLog.log("Settings: auto-pull → $checked")
        }
        swAutoPush.setOnCheckedChangeListener { _, checked ->
            settings.autoPush = checked
            LauncherLog.log("Settings: auto-push → $checked")
        }
        swPerfOverlay.setOnCheckedChangeListener { _, checked ->
            settings.perfOverlay = checked
            LauncherLog.log("Settings: perf overlay → $checked (takes effect on next game launch)")
        }
        swPerfLog.setOnCheckedChangeListener { _, checked ->
            settings.perfLog = checked
            LauncherLog.log("Settings: perf CSV log → $checked (takes effect on next game launch)")
        }
        swTouchOverlay.setOnCheckedChangeListener { _, checked ->
            settings.touchOverlay = checked
            LauncherLog.log("Settings: on-screen controls → $checked (takes effect on next game launch)")
        }
        swGogPull.setOnCheckedChangeListener { _, checked ->
            if (!GogAuth.isSignedIn(this)) {
                swGogPull.isChecked = false
                LauncherLog.log("GOG: not signed in; sign in first")
                return@setOnCheckedChangeListener
            }
            settings.gogAutoPull = checked
            LauncherLog.log("Settings: GOG auto-pull → $checked")
        }
        swGogPush.setOnCheckedChangeListener { _, checked ->
            if (!GogAuth.isSignedIn(this)) {
                swGogPush.isChecked = false
                LauncherLog.log("GOG: not signed in; sign in first")
                return@setOnCheckedChangeListener
            }
            settings.gogAutoPush = checked
            LauncherLog.log("Settings: GOG auto-push → $checked")
        }
        btnGogAuth.setOnClickListener { gogAuthClicked() }
        swKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
            LauncherLog.log("Settings: keep screen on while playing → $checked (next game launch)")
        }
        findViewById<Button>(R.id.btn_last_benchmark).setOnClickListener { showLastBenchmark() }
        swSkipIntro.setOnCheckedChangeListener { _, checked ->
            settings.skipIntro = checked
            LauncherLog.log("Settings: skip intro → $checked (takes effect on next game launch)")
        }
        swDualScreen.setOnCheckedChangeListener { _, checked ->
            settings.dualScreen = checked
            swShowTopHud.isEnabled = checked
            LauncherLog.log("Settings: dual screen → $checked (next game launch; requires DualScreen build)")
        }
        swShowTopHud.setOnCheckedChangeListener { _, checked ->
            settings.showTopHud = checked
            LauncherLog.log("Settings: show health HUD on top screen → $checked (takes effect on next game launch)")
        }
        swWideAspect.setOnCheckedChangeListener { _, checked ->
            settings.wideAspect = checked
            LauncherLog.log("Settings: unlock aspect ratio → $checked (takes effect on next game launch)")
        }

        btnBack.setOnClickListener { finish() }

        btnClearBuild = findViewById(R.id.btn_clear_build)
        btnClearBuild.setOnClickListener { confirmClearBuild() }
        // Offered only when there is a build to clear. A destructive-looking
        // button that does nothing is worse than no button.
        btnClearBuild.isEnabled = BuildReset.hasBuild(this)
        btnClearBuild.alpha = if (btnClearBuild.isEnabled) 1f else 0.4f

        // The same screen the porting flow offers, because the person who
        // needs a log is as likely to have reached this screen as that one.
        findViewById<Button>(R.id.btn_settings_logs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    /**
     * The last run benchmark, from the game's own CSV log.
     *
     * PerfOverlay appends one row per second while a session runs and flushes
     * a self-describing "# SUMMARY" line when the game is backgrounded: rows,
     * average and minimum FPS, average watts, energy per frame, the share of
     * seconds that hit the frame cap, and the p99 frametime average and worst
     * second. This shows that line beside the config stamps the CSV opens
     * with, exactly as the file recorded it -- a settings screen that
     * paraphrases a benchmark invites the question of what it changed.
     *
     * The newest file only. The perf directory is the launcher's own external
     * files dir, which this process can read freely.
     */
    private fun showLastBenchmark() {
        val perfDir = getExternalFilesDir(null)?.let { java.io.File(it, "perf") }
        val csv = perfDir?.listFiles()
            ?.filter { it.isFile && it.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }

        val message: String = if (csv == null) {
            "No benchmark has been recorded yet.\n\nEnable \"Log performance to CSV\", play a session, " +
                "then close the game — the summary is written when you background it."
        } else {
            val text = runCatching { csv.readText() }.getOrDefault("")
            val lines = text.lineSequence().toList()
            val stamp = lines.firstOrNull { it.startsWith("# Silksong perf log") }?.removePrefix("# ")
            val device = lines.firstOrNull { it.startsWith("# device=") }?.removePrefix("# ")
            val gfx = lines.firstOrNull { it.startsWith("# gfx=") }?.removePrefix("# ")
            val target = lines.firstOrNull { it.startsWith("# targetFps=") }?.removePrefix("# ")
            val summary = lines.lastOrNull { it.startsWith("# SUMMARY") }?.removePrefix("# ")
            listOfNotNull(
                csv.name,
                stamp,
                device?.let { "device: $it" },
                gfx?.let { "gfx: $it" },
                target?.let { "target: $it" },
                summary?.let { "\n$it" } ?: "(no summary — the game was still running when this file was written)",
            ).joinToString("\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Last run benchmark")
            .setMessage(message.ifEmpty { "(empty)" })
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Asks first, and says what will and will not survive.
     *
     * The scary half of the sentence is what is kept, not what is deleted:
     * "clear data" on Android means the saves go too, and this is the same
     * words for a very different thing.
     */
    private fun confirmClearBuild() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_build_title)
            .setMessage(R.string.settings_clear_build_message)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_clear_build_confirm) { _, _ -> clearBuild() }
            .show()
    }

    private fun clearBuild() {
        // Gigabytes across thousands of small files, so not on the main
        // thread -- and not cancellable either, because a half-deleted build
        // is the state this exists to get out of.
        val progress = AlertDialog.Builder(this)
            .setMessage(getString(R.string.settings_clear_build_working))
            .setCancelable(false)
            .show()

        Thread({
            var error: Throwable? = null
            try {
                BuildReset.clear(this)
            } catch (t: Throwable) {
                error = t
                LauncherLog.log("could not clear the build", t)
            }
            runOnUiThread {
                runCatching { progress.dismiss() }
                val failure = error
                if (failure != null) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.settings_clear_build_failed,
                                              failure.message ?: failure.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@runOnUiThread
                }
                startPorting()
            }
        }, "clear-build").start()
    }

    /**
     * Back to the porting screen, with nothing stale behind it.
     *
     * SetupActivity is the app's entry point and works out what to show from
     * what is on disk, which is now "a game that needs building"; its onResume
     * re-reads that, so a reused instance is as correct as a new one.
     *
     * CLEAR_TOP finishes everything above it in the task -- this screen and the
     * launcher behind it, both of which are describing a game that no longer
     * exists -- and SINGLE_TOP delivers to the existing instance rather than
     * stacking a second. finishAffinity is deliberately not used: it would
     * close the task this has just started an activity into.
     */
    private fun startPorting() {
        try {
            startActivity(
                Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        } catch (t: Throwable) {
            LauncherLog.log("could not open the porting screen: $t")
            finish()
        }
    }

    /**
     * Sign in / sign out of GOG cloud saves. A sign-in only counts when the
     * selected depot is a GOG build: the game id and the cloud folder come
     * from the depot's own goggame file, so a Steam depot never sees this
     * button do anything.
     */
    private fun gogAuthClicked() {
        val depot = DepotLocation.resolve(this)
        if (!GogCloud.isGogDepot(depot)) {
            LauncherLog.log("GOG: the selected depot is not a GOG build; no cloud saves to sync")
            return
        }
        if (GogAuth.isSignedIn(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.settings_gog_sign_out)
                .setMessage("Cloud saves stay in GOG storage; only this device signs out.")
                .setPositiveButton("Sign out") { _, _ ->
                    GogAuth.signOut(this)
                    refreshGogUi()
                    LauncherLog.log("GOG: signed out")
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val gogId = GogCloud.gogId(depot)
        if (gogId.isEmpty()) {
            LauncherLog.log("GOG: depot has no goggame id; cannot resolve the cloud bucket")
            return
        }
        startActivityForResult(
            Intent(this, GogLoginActivity::class.java)
                .putExtra(GogLoginActivity.EXTRA_GOG_ID, gogId),
            7123)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7123) {
            val ok = resultCode == GogLoginActivity.RESULT_SIGNED_IN
            LauncherLog.log("GOG sign-in: " + if (ok) "succeeded" else "did not finish")
            refreshGogUi()
        }
    }

    /** Signed-in state and button label are one call apart, everywhere. */
    private fun refreshGogUi() {
        val signed = GogAuth.isSignedIn(this)
        tvGogStatus.text = getString(
            if (signed) R.string.settings_gog_signed_in else R.string.settings_gog_signed_out)
        btnGogAuth.text = getString(
            if (signed) R.string.settings_gog_sign_out else R.string.settings_gog_sign_in)
        if (!signed) {
            swGogPull.isChecked = false
            swGogPush.isChecked = false
        }
    }
}
