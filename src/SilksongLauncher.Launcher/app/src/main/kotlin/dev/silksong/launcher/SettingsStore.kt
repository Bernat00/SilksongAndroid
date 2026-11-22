// SettingsStore — thin SharedPreferences wrapper for the launcher's
// user-tweakable behaviour flags. Kept deliberately tiny: one prefs
// file, raw key/value access, no observer plumbing — Settings UI
// reads/writes directly and the consumers (LauncherActivity) re-read
// on every relevant lifecycle event.
//
// Default for both flags is OFF so the launcher behaves identically
// to its current manual-only operation until the user opts in. We
// store them under a dedicated `launcher_settings` prefs file rather
// than mixing into TokenStore's `launcher_tokens` to keep secrets
// and behaviour flags on separate disk-level lifecycles (clearing
// the token shouldn't wipe sync preferences, and vice-versa).

package dev.silksong.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * The value [SettingsStore.il2cppBudget] stores when the device decides the
 * conversion tier. File-level rather than in [SettingsStore]'s companion,
 * because that object is private (see its keys) and both the port screen and
 * [MonoRuntime] have to name this one value.
 */
const val BUDGET_AUTO = "auto"

class SettingsStore(context: Context) {

    // Held so every write can re-derive the exported file immediately:
    // the game reads ONLY that file (cross-process), so an export that
    // waits for the next launch is an export that lies.
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The one settings pathway: write the preference, then re-export in the
     * same breath. Every property setter routes through this — nothing that
     * changes a setting can forget to publish it to the game side, which is
     * exactly the bug class the old launch-time-only export produced (the
     * on-screen controls toggle "did nothing" until relaunch).
     */
    private fun changed(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        exportForGame(appContext)
    }

    var autoPull: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PULL, false)
        set(value) { changed(KEY_AUTO_PULL, value) }

    var autoPush: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PUSH, false)
        set(value) { changed(KEY_AUTO_PUSH, value) }

    /**
     * When enabled, an OnGUI overlay rendered in the Unity game shows
     * live FPS / CPU / GPU / battery stats in the top-left corner.
     * Read from the launcher process here for the UI; the actual
     * overlay lives in PerfOverlay.cs (Unity side) which reads the
     * SAME SharedPreferences key via JNI at startup. We share the
     * underlying prefs file because the launcher process
     * (`:launcher`) and the game process (default) share package
     * data even though they run in separate processes.
     */
    var perfOverlay: Boolean
        get() = prefs.getBoolean(KEY_PERF_OVERLAY, false)
        set(value) { changed(KEY_PERF_OVERLAY, value) }

    /**
     * When enabled, skip Silksong's startup intro (studio logos +
     * opening quote) and go straight to loading the main menu. Read
     * via JNI from the game process at boot from this SAME prefs file;
     * the actual skip lives in IntroSkipper.cs (Unity side) which
     * short-circuits StartManager's intro animator to the loading
     * state. The unavoidable loading/save-icon screen still shows
     * briefly while the menu finishes loading. Default OFF (vanilla
     * intro). Takes effect on next game launch.
     */
    var skipIntro: Boolean
        get() = prefs.getBoolean(KEY_SKIP_INTRO, false)
        set(value) { changed(KEY_SKIP_INTRO, value) }

    /**
     * Dual-screen support. When enabled, the Unity side (DualScreenV2, one of
     * the patches compiled on the device) activates a secondary display and
     * draws its own inventory / crest / tasks / journal / map screens on it,
     * built from the game's own data and driven by touch. Read via JNI from the
     * game process at boot from this SAME prefs file. Defaults ON, so the
     * switch is for turning it OFF; it is inert on a device with no second
     * display.
     */
    var dualScreen: Boolean
        get() = prefs.getBoolean(KEY_DUAL_SCREEN, true)
        set(value) { changed(KEY_DUAL_SCREEN, value) }

    /**
     * Also keep the original health / crest / silk / equipped-tool HUD on the main screen
     * while dual-screen rendering is active. Defaults OFF and is exported
     * for the next game launch. Has no effect without a working second screen.
     */
    var showTopHud: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TOP_HUD, false)
        set(value) { changed(KEY_SHOW_TOP_HUD, value) }

    /**
     * Unlock the aspect ratio.
     *
     * Silksong clamps the shape it renders into to between 1.6 : 1 and
     * 2.3916667 : 1 and letterboxes the rest, which never shows on a 16:9
     * monitor and always shows on the screens Android actually has -- a
     * foldable's inner screen is about 1.16 : 1, its cover screen about
     * 2.46 : 1, and a 4:3 handheld 1.33 : 1. When this is on, the build's
     * woven gate widens that range so the game renders at the screen's own
     * proportions and the bars go away.
     *
     * Default OFF, and deliberately so: the same clamp drives the camera, so
     * opening it also pulls the camera back and shows more of the world than
     * the art was framed for. On a foldable that is a full screen; it can also
     * look wrong. Only the player can make that trade.
     *
     * Costs a relaunch, not a rebuild -- the weave is always in the build and
     * this only decides whether the gate is opened. Inert on an ordinary
     * phone, whose aspect is already inside the game's own range.
     */
    var wideAspect: Boolean
        get() = prefs.getBoolean(KEY_WIDE_ASPECT, false)
        set(value) { changed(KEY_WIDE_ASPECT, value) }

    /**
     * Keep the screen on while the game runs. Read by KeepScreenOn.cs via
     * the exported settings file; defaults ON, because the alternative is
     * the screen dimming out from under a controller-held session on the
     * floor next to the phone.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) { changed(KEY_KEEP_SCREEN_ON, value) }

    /**
     * Append the one-second perf rows to persistentDataPath/perf/, and write
     * the session summary when the game is backgrounded. Without the perf
     * overlay there is no on-screen cost. Default OFF.
     */
    var perfLog: Boolean
        get() = prefs.getBoolean(KEY_PERF_LOG, false)
        set(value) { changed(KEY_PERF_LOG, value) }

    /**
     * On-screen touch controls. Read by VirtualPad.cs through the exported
     * settings file; defaults ON so the pad is there the first time a phone
     * user starts the game without a controller. The pad steps out of the way
     * on its own while a physical controller is being pressed.
     */
    var touchOverlay: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_OVERLAY, true)
        set(value) { changed(KEY_TOUCH_OVERLAY, value) }

    /**
     * GOG cloud saves - pull the cloud before launching and push after the
     * game exits. GOG-only: the sign-in is offered in Settings when the
     * selected depot is a GOG build, and both default OFF so a GOG depot
     * behaves exactly like today until saves are opted into the cloud.
     */
    var gogAutoPull: Boolean
        get() = prefs.getBoolean(KEY_GOG_AUTO_PULL, false)
        set(value) { changed(KEY_GOG_AUTO_PULL, value) }

    var gogAutoPush: Boolean
        get() = prefs.getBoolean(KEY_GOG_AUTO_PUSH, false)
        set(value) { changed(KEY_GOG_AUTO_PUSH, value) }

    /**
     * Which share of the device the IL2CPP conversion is allowed to take, as
     * "cores:heapMb", or "auto" to let [MonoRuntime.il2cppBudget] choose from
     * what is actually free at the moment the button is pressed.
     *
     * Launcher-only and deliberately not exported to the game: nothing on the
     * Unity side runs a conversion, and a setting the game cannot use is a
     * setting somebody will eventually try to make it use (see exportForGame).
     *
     * A string rather than an enum because this is a budget, not a mode: a
     * value a later version cannot parse has to fall back to automatic rather
     * than fail to load the screen it is read on.
     */
    /**
     * The tier whose run was last reclaimed by the memory killer, as "cores:heap".
     *
     * Kept on disk rather than in memory because the launcher process dies too:
     * on 2026-11-22 the conversion was reclaimed at 926 files, the retry
     * correctly dropped to 1 core, and then the *launcher* was killed as well --
     * so the ladder died with it and the next attempt restarted at the stored
     * 8 cores/2560 MB, the tier that had just failed. Clearing it on success is
     * what stops that from outliving the pressure it describes.
     */
    var il2cppReclaimed: String
        get() = prefs.getString(KEY_IL2CPP_RECLAIMED, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_IL2CPP_RECLAIMED, value).apply()
        }

    var il2cppBudget: String
        get() = prefs.getString(KEY_IL2CPP_BUDGET, BUDGET_AUTO) ?: BUDGET_AUTO
        set(value) {
            prefs.edit().putString(KEY_IL2CPP_BUDGET, value).apply()
        }

    /**
     * Hands the game the settings it needs, as a file it can read.
     *
     * The launcher runs in its own process, so the game cannot read these
     * preferences directly -- cross-process SharedPreferences means
     * MODE_MULTI_PROCESS, which is deprecated because it does not reliably
     * work. Written to the app's external files directory because that is
     * what Unity reports as Application.persistentDataPath on this platform,
     * so the patch side needs no package name and no path convention.
     *
     * Only what the GAME acts on. The cloud-save toggles are the launcher's
     * own business and are deliberately not here: a setting the game cannot
     * use is a setting somebody will eventually try to make it use.
     *
     * Written whole on every settings change (see [changed]), plus once
     * before launch as a belt-and-braces ensure — so it cannot drift from
     * what the user last chose.
     */
    fun exportForGame(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val text = buildString {
            append(KEY_PERF_OVERLAY).append('=').append(perfOverlay).append('\n')
            append(KEY_SKIP_INTRO).append('=').append(skipIntro).append('\n')
            append(KEY_DUAL_SCREEN).append('=').append(dualScreen).append('\n')
            append(KEY_SHOW_TOP_HUD).append('=').append(showTopHud).append('\n')
            append(KEY_WIDE_ASPECT).append('=').append(wideAspect).append('\n')
            append(KEY_KEEP_SCREEN_ON).append('=').append(keepScreenOn).append('\n')
            append(KEY_PERF_LOG).append('=').append(perfLog).append('\n')
            append(KEY_TOUCH_OVERLAY).append('=').append(touchOverlay).append('\n')
        }
        try {
            val out = File(dir, "game-settings.txt")
            val tmp = File(dir, "game-settings.txt.part")
            tmp.writeText(text)
            if (!tmp.renameTo(out)) {
                tmp.delete()
                throw java.io.IOException("rename to $out")
            }
            LauncherLog.log("settings for the game: ${text.replace('\n', ' ').trim()}")
        } catch (t: Throwable) {
            // Not fatal: the patches fall back to defaults, which is the
            // behaviour the game had before any of this existed.
            LauncherLog.log("could not write the game's settings", t)
        }
    }

    private companion object {
        const val PREFS_NAME = "launcher_settings"
        const val KEY_AUTO_PULL = "auto_pull"
        const val KEY_AUTO_PUSH = "auto_push"
        const val KEY_PERF_OVERLAY = "perf_overlay"
        const val KEY_SKIP_INTRO = "skip_intro"
        const val KEY_DUAL_SCREEN = "dualscreen_enabled"
        const val KEY_SHOW_TOP_HUD = "dualscreen_show_top_hud"
        const val KEY_WIDE_ASPECT = "wide_aspect"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_PERF_LOG = "perf_log"
        const val KEY_TOUCH_OVERLAY = "touch_overlay"
        const val KEY_GOG_AUTO_PULL = "gog_auto_pull"
        const val KEY_GOG_AUTO_PUSH = "gog_auto_push"
        const val KEY_IL2CPP_BUDGET = "il2cpp_budget"
        const val KEY_IL2CPP_RECLAIMED = "il2cpp_reclaimed"
    }
}
