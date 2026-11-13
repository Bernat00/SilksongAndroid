// SilksongApp — custom Application class for the Silksong APK.
//
// Runs ONCE PER PROCESS at process startup, before any Activity is created.
// That is the only place either of the things below can go: both have to be in
// effect before anything else in the process runs.

package dev.silksong.launcher

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

class SilksongApp : Application() {

    // Before any class in this app is loaded, which rules out onCreate.
    //
    // The APK links against com.unity3d.player.* and ships none of it: the
    // player classes are dexed on the device out of the module the app
    // downloads. GameActivity's superclass is one of the types that resolves
    // from there, so the dex has to be in the class loader before the
    // framework instantiates the activity. attachBaseContext is the first
    // point in the process where that is possible.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        UnityDex.inject(this)
    }

    override fun onCreate() {
        super.onCreate()

        // First, so that everything below is recorded too. The log is written
        // to a file from here on, which is what makes it survivable enough to
        // be worth asking a user for. It also drains whatever
        // attachBaseContext logged above, which had nowhere to go yet.
        LauncherLog.attach(this)

        // Which process this is. The log is shared by all of them and every
        // session in it opens with the same two lines, so until now a header
        // could not be told from :launcher, :builder or the game -- and
        // "the game's process started and then nothing" was not a readable
        // fact. It is the first thing worth knowing about a launch that
        // failed. (Issue #24.)
        LauncherLog.log("process ${processTag()} started")

        // And why the previous ones are not still here.
        logPreviousExits()

        // Before the engine's own handler, which cannot be installed until
        // GameActivity exists -- see installCrashHandler.
        installCrashHandler()

        // Before anything else, and in every process: JavaSteam's crypto
        // registers itself the first time it is touched, and on Android it
        // registers the wrong thing unless this has run first. See SteamCrypto.
        SteamCrypto.install()
    }

    /**
     * Uncaught exceptions, in every process, recorded before the process goes.
     *
     * GameActivity installs one of these too and it is not enough: that one
     * runs from the activity's onCreate, so it cannot see a failure that
     * happens BEFORE the activity exists. The framework instantiating
     * GameActivity is exactly such a moment -- its superclass comes from the
     * dex injected in attachBaseContext, so a missing or unloadable dex dies
     * here, with a ClassNotFoundException naming the class it could not find.
     *
     * That was invisible. Everything the game records -- game.log, errors.log,
     * the session.running marker -- is written from inside onCreate, so a
     * launch that never reached onCreate wrote nothing at all, and the report
     * said only that the game "did not start". See issue #24.
     *
     * Chained, never swallowed: the previous handler is what actually ends the
     * process, and an app that logs a crash and then carries on is worse than
     * one that crashes.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Through LauncherLog because it appends and closes per line, so
            // the line is on disk before the process is taken away.
            runCatching {
                val stack = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(stack))
                LauncherLog.log(
                    "FATAL in ${processTag()} on thread ${thread.name}\n" +
                        stack.toString().take(MAX_STACK_CHARS),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The process, named where the platform will say and numbered always. */
    private fun processTag(): String {
        val pid = android.os.Process.myPid()
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { getProcessName() }.getOrNull()
        } else {
            null
        }
        return if (name != null) "$name (pid $pid)" else "pid $pid"
    }

    /**
     * Why this app's previous processes are no longer running, and it is the
     * launcher's line that matters.
     *
     * The build reports a builder's death itself -- MonoRuntime asks the
     * platform what happened and puts the answer in the log. But :launcher
     * is the process doing the asking, and when the system takes :launcher
     * there is nothing left to ask or answer: #47's log ran from a
     * conversion starting with 2.5 GB free to a fresh launch twelve minutes
     * later without a word between, and the cause died with the processes.
     *
     * The platform keeps the answer. getHistoricalProcessExitReasons
     * describes every process of this package that has died recently, and
     * the records survive the death of every process that could have read
     * them -- so whoever comes up next, in whatever process, can ask.
     *
     * A launcher death is always worth a line, because it is never routine.
     * The builders' normal end IS routine -- each one kills itself when the
     * run it hosted is finished -- so a builder's record earns a line only
     * when the reason is anything else: reclaimed for memory, crashed, ANR.
     * A healthy build therefore logs nothing extra beyond one line per
     * launch, and an unhealthy one logs the one line a bug report is about.
     * One line per process name, newest first, a recent window only: the
     * interesting death is by definition the one just before this process.
     */
    private fun logPreviousExits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = getSystemService(ActivityManager::class.java) ?: return
        val records = runCatching {
            am.getHistoricalProcessExitReasons(packageName, 0, EXIT_RECORDS_MAX)
        }.getOrNull().orEmpty()
        if (records.isEmpty()) return

        val now = System.currentTimeMillis()
        val named = HashSet<String>()
        for (record in records.sortedByDescending { it.timestamp }) {
            val age = now - record.timestamp
            if (age < 0 || age > EXIT_RECORD_WINDOW_MS) break
            val name = runCatching { record.processName }.getOrNull() ?: continue
            if (!named.add(name)) continue
            // The app's own way of stopping a finished run: not news, and
            // in a builder the commonest record there is. The :launcher is
            // excluded because its death is never this app's doing.
            val selfStop = record.reason == android.app.ApplicationExitInfo.REASON_EXIT_SELF ||
                (record.reason == android.app.ApplicationExitInfo.REASON_SIGNALED && record.status == 9)
            if (selfStop && !name.endsWith(":launcher")) continue
            if (!firstReport(record.pid, record.timestamp)) continue
            val reason = runCatching {
                MonoRuntime.describeExit(record, "the process")
            }.getOrDefault("ended for an unnamed reason (${record.reason})")
            LauncherLog.log(
                "previous exit: ${record.processName} (pid ${record.pid}), " +
                    "${ageText(age)} ago: $reason",
            )
            if (named.size >= EXIT_LINES_MAX) break
        }
    }

    /**
     * An age, short enough to read beside a timestamp.
     */
    private fun ageText(ms: Long): String = when {
        ms >= 86_400_000L -> "${ms / 3_600_000L / 24}d"
        ms >= 3_600_000L -> "${ms / 3_600_000L}h"
        ms >= 60_000L -> "${ms / 60_000L}m"
        else -> "${ms / 1000L}s"
    }

    /**
     * Whether a death is being reported here for the first time.
     *
     * Builders come up every few minutes during a build, and each one reads
     * the same recent exit records this process already logged -- #47's
     * rerun showed the launcher's death five times over in the minute after
     * every restart, burying the heartbeat lines underneath it. Memory is
     * per process, so a process that itself dies reports the same records
     * again once, which is the correct answer: a reader cannot tell which
     * earlier process logged what, only that the file says it once since.
     */
    private fun firstReport(pid: Int, timestamp: Long): Boolean {
        val key = "$pid:$timestamp"
        return synchronized(reportedExits) { reportedExits.add(key) }
    }

    private companion object {
        /**
         * Deaths this process has already reported, by pid and timestamp.
         * See [firstReport].
         */
        val reportedExits = HashSet<String>()

        /**
         * Enough for the frames that name the failure, not so much that one
         * crash fills the file a user is being asked to send.
         */
        const val MAX_STACK_CHARS = 4000

        /** How far back the platform is asked about, and how far back is worth reporting. */
        const val EXIT_RECORDS_MAX = 32
        const val EXIT_RECORD_WINDOW_MS = 60L * 60L * 1000L
        const val EXIT_LINES_MAX = 3
    }
}
