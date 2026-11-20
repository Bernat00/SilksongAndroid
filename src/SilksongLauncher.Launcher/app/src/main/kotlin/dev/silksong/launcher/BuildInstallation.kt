package dev.silksong.launcher

import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Readiness belongs to the whole installation, not to an individual cached build step. */
object BuildInstallation {
    private fun marker(pkg: File) = File(pkg, ".built")

    fun signature(assets: AssetManager): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun walk(path: String) {
            val names = assets.list(path).orEmpty().sorted()
            if (names.isEmpty()) {
                AssetDigest.update(md, assets, path)
            } else {
                for (name in names) walk("$path/$name")
            }
        }
        walk("ondevice")
        // Earlier markers could survive an interrupted same-APK mod rebuild.
        return "2|" + md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun haveOutputs(pkg: File): Boolean {
        val data = File(pkg, "data.apk")
        val engine = File(pkg, "lib/arm64/libil2cpp.so")
        return data.isFile && data.length() > 0 && engine.isFile && engine.length() > 0
    }

    /** Whether a previous build left the engine and player image behind. */
    fun hasOutputs(pkg: File): Boolean = haveOutputs(pkg)

    /** The signature the installed build was made with, or null when there is no marker. */
    fun builtSignature(pkg: File): String? =
        marker(pkg).takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }

    /**
     * A finished build whose inputs this APK no longer matches.
     *
     * Separate from [isReady] because the answer is not "no game": outputs and
     * a marker are both there, and the only thing at stake is whether the
     * patches shipped in this APK are in the game on disk. That difference is
     * the one the setup screen can offer a choice about -- play the build that
     * exists, or rebuild to take the new patches -- instead of treating an app
     * update as a machine with no game on it.
     */
    fun isStale(pkg: File, signature: String): Boolean {
        if (!haveOutputs(pkg)) return false
        val built = builtSignature(pkg) ?: return false
        return built != signature
    }

    fun isReady(pkg: File, signature: String): Boolean {
        val marker = marker(pkg)
        if (!haveOutputs(pkg) || !marker.isFile) return false
        return try {
            marker.readText() == signature
        } catch (e: IOException) {
            LauncherLog.log("Could not read the installed build marker", e)
            false
        }
    }

    /** Must succeed before a build can change any installed artifact. */
    fun invalidate(pkg: File) {
        val marker = marker(pkg)
        if (marker.exists() && !marker.delete()) {
            throw IOException("Could not invalidate the installed build marker")
        }
    }

    /** Called after the engine, image, content and installed mod records all succeed. */
    fun complete(pkg: File, signature: String) {
        if (!haveOutputs(pkg)) throw IOException("The installed engine or player image is missing")
        writeAtomic(marker(pkg), signature)
    }

    internal fun writeAtomic(file: File, text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create $parent")
        }
        val tmp = File(parent, "${file.name}.part")
        FileOutputStream(tmp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
