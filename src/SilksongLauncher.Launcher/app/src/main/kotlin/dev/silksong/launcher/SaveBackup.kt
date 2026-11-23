package dev.silksong.launcher

import android.content.Context
import java.io.File

/**
 * A copy of the saves, outside the app's own storage.
 *
 * The game writes to `<persistentDataPath>/default/userN.dat`, and on Android
 * that is the app's *external files* directory -- so an uninstall takes the
 * saves with it, silently, and `pm uninstall` does not even ask (the
 * `hasFragileUserData` prompt only appears for an uninstall from the UI). One
 * hour of play has already been lost that way on this project.
 *
 * So: before every launch, copy whatever is there to a directory beside the
 * user's depot -- the one place we know is the user's own and survives the app
 * being replaced -- keeping the last few copies. Cheap (a save is tens of KB)
 * and it runs while the launcher is waiting for the game to start anyway.
 */
object SaveBackup {

    /** Beside the depot, not inside it: the depot is the game's, this is ours. */
    fun dirFor(depot: File?): File? =
        depot?.parentFile?.let { File(it, "silksong-saves-backup") }

    /**
     * Copies the current saves into a timestamped folder. Returns that folder,
     * or null when there was nothing to copy or nowhere to put it.
     *
     * Never throws: a backup is a safety net, and a safety net that can stop
     * the game from starting is worse than none.
     */
    fun backup(context: Context, depot: File?, keep: Int = 20): File? {
        return try {
        val target = dirFor(depot) ?: return null
        val saves = SaveDir.of(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("user") && it.length() > 0 }
            ?: return null
        if (saves.isEmpty()) return null

        // Nothing changed since the last copy (a second launch to change a
        // setting, say): do not spend a slot on it. Twenty identical folders
        // is how a backup directory stops being read.
        val newest = latest(depot)
        if (newest != null && identical(newest, saves)) {
            LauncherLog.log("saves: unchanged since ${newest.name}, no new snapshot")
            return null
        }

        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val out = File(target, stamp)
        out.mkdirs()
        var copied = 0
        for (f in saves) {
            if (!f.isFile || f.length() <= 0) continue
            f.copyTo(File(out, f.name), overwrite = true)
            copied++
        }
        if (copied == 0) {
            out.delete()
            return null
        }
        prune(target, keep)
        LauncherLog.log("saves: backed up $copied file(s) to $out")
        out
        } catch (t: Throwable) {
            LauncherLog.log("saves: backup failed", t)
            null
        }
    }

    /** Newest first; drops everything past [keep]. */
    private fun prune(target: File, keep: Int) {
        val dirs = target.listFiles { f -> f.isDirectory } ?: return
        // Only folders this class makes are ours to delete. "rescued-…" is a
        // copy a person put there on purpose (after the 11-21 uninstall scare),
        // and a name-sorted prune would happily spend one of the five slots on
        // it and then throw it away, which is the opposite of the point.
        val stamps = dirs.filter { STAMP.matches(it.name) }.sortedByDescending { it.name }
        for (old in stamps.drop(keep)) old.deleteRecursively()
    }

    private val STAMP = Regex("""\d{8}-\d{6}""")

    /** Same names, same bytes: the current saves are already backed up. */
    private fun identical(folder: File, saves: List<File>): Boolean {
        return try {
            val there = folder.listFiles()?.filter { it.isFile } ?: return false
            if (there.size != saves.size) return false
            saves.all { f ->
                val copy = File(folder, f.name)
                copy.isFile && copy.length() == f.length() &&
                    copy.readBytes().contentEquals(f.readBytes())
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** The most recent backup, for a restore. */
    fun latest(depot: File?): File? =
        dirFor(depot)?.listFiles { f -> f.isDirectory && STAMP.matches(f.name) }?.maxByOrNull { it.name }
}
