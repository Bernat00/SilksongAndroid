package dev.silksong.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The on-screen pad's layout, as the game reads it.
 *
 * The pad itself is one of the C# patches, compiled into the player image, so
 * it cannot be changed from the launcher by editing code -- changing a patch
 * costs a full conversion on the phone. This file is the seam: the game reads
 * `pad-layout.json` from its own files directory once at startup, and the
 * editor below writes it. Moving a button is then a write and a relaunch
 * instead of fifteen minutes of compilation.
 *
 * Units are the game's own: [Key.x] a fraction of the width, [Key.y] a fraction
 * of the height measured from the top, and [Key.w]/[Key.h] fractions of vmin so
 * a circle stays circular at any aspect ratio.
 */
class PadLayout(
    val keys: MutableList<Key> = mutableListOf(),
    var stick: Stick = Stick(),
) {

    data class Key(
        var id: String,
        var target: String,
        var x: Float,
        var y: Float,
        var w: Float,
        var h: Float,
        var shape: String = "circle",
        var scale: Float = 1f,
        var label: String = "",
        /** Supplies "up" while held: the game only throws a tool while aiming. */
        var aimUp: Boolean = false,
    )

    data class Stick(
        var mode: String = "floating",
        var cx: Float = 0.165f,
        var cy: Float = 0.70f,
        var r: Float = 0.125f,
        var x0: Float = 0f,
        var y0: Float = 0.30f,
        var x1: Float = 0.40f,
        var y1: Float = 1f,
    )

    fun copy(): PadLayout = PadLayout(
        keys.map { it.copy() }.toMutableList(),
        stick.copy(),
    )

    fun toJson(): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("stick", JSONObject().apply {
            put("mode", stick.mode)
            put("cx", stick.cx.toDouble())
            put("cy", stick.cy.toDouble())
            put("r", stick.r.toDouble())
            put("zoneX0", stick.x0.toDouble())
            put("zoneY0", stick.y0.toDouble())
            put("zoneX1", stick.x1.toDouble())
            put("zoneY1", stick.y1.toDouble())
        })
        put("keys", JSONArray().apply {
            for (k in keys) put(JSONObject().apply {
                put("id", k.id)
                put("target", k.target)
                put("x", k.x.toDouble())
                put("y", k.y.toDouble())
                put("w", k.w.toDouble())
                put("h", k.h.toDouble())
                put("shape", k.shape)
                put("scale", k.scale.toDouble())
                put("label", k.label)
                put("aimUp", k.aimUp)
            })
        })
    }.toString(2)

    companion object {
        /** Also the name the game reads. Keep in step with PadLayoutFile.FileName. */
        const val FILE = "pad-layout.json"
        const val SCHEMA = 1
        const val SLOTS = 9

        /**
         * The controls the game actually binds.
         *
         * A key can only emit one of these; anything else would be a button
         * that silently does nothing, because the pad does not decide what an
         * action means -- the game's own ControllerMapping does.
         */
        val TARGETS = listOf(
            "Action1" to "Jump",
            "Action2" to "Bind / Heal",
            "Action3" to "Attack",
            "Action4" to "Dream Nail",
            "LeftTrigger" to "Super Dash",
            "RightTrigger" to "Dash / Sprint",
            "LeftBumper" to "Map",
            "RightBumper" to "Skill / Tool",
            "Select" to "Inventory",
            "Start" to "Pause",
            "RightStickButton" to "Taunt",
            "Back" to "Back",
        )

        fun file(context: Context): File = File(context.getExternalFilesDir(null), FILE)

        // Slot order, mirroring OnScreenDevice: the game's array is indexed the
        // same way and the JSON is written in that order.
        const val SLOT_JUMP = 0
        const val SLOT_CAST = 1
        const val SLOT_ATTACK = 2
        const val SLOT_HEAL = 3
        const val SLOT_DASH = 4
        const val SLOT_MAP = 5
        const val SLOT_INVENTORY = 6
        const val SLOT_THROW = 7
        const val SLOT_PAUSE = 8

        /** The four face keys: the diamond, as one shape. */
        val DIAMOND = intArrayOf(SLOT_JUMP, SLOT_ATTACK, SLOT_HEAL, SLOT_DASH)

        fun load(context: Context, width: Int, height: Int): PadLayout {
            val f = file(context)
            if (!f.isFile) return defaults(width, height)
            return try {
                parse(f.readText(), width, height) ?: defaults(width, height)
            } catch (t: Throwable) {
                LauncherLog.log("pad layout: could not read $f: $t")
                defaults(width, height)
            }
        }

        fun parse(text: String, width: Int, height: Int): PadLayout? {
            // Anything that is not JSON is "no layout", not an exception: the
            // caller's fallback is the built-in one, and a half-typed file
            // must not take the settings screen down with it.
            val o = try {
                JSONObject(text)
            } catch (t: Throwable) {
                return null
            }
            if (o.optInt("schema", 0) != SCHEMA) return null
            val array = o.optJSONArray("keys") ?: return null
            if (array.length() != SLOTS) return null
            val keys = mutableListOf<Key>()
            for (i in 0 until array.length()) {
                val k = array.getJSONObject(i)
                keys += Key(
                    id = k.optString("id"),
                    target = k.optString("target", "Action1"),
                    x = k.optDouble("x", 0.5).toFloat(),
                    y = k.optDouble("y", 0.5).toFloat(),
                    w = k.optDouble("w", 0.16).toFloat(),
                    h = k.optDouble("h", 0.16).toFloat(),
                    shape = k.optString("shape", "circle"),
                    scale = k.optDouble("scale", 1.0).toFloat(),
                    label = k.optString("label", ""),
                    aimUp = k.optBoolean("aimUp", false),
                )
            }
            val s = o.optJSONObject("stick")
            val stick = Stick(
                mode = s?.optString("mode", "floating") ?: "floating",
                cx = s?.optDouble("cx", 0.165)?.toFloat() ?: 0.165f,
                cy = s?.optDouble("cy", 0.70)?.toFloat() ?: 0.70f,
                r = s?.optDouble("r", 0.125)?.toFloat() ?: 0.125f,
                x0 = s?.optDouble("zoneX0", 0.0)?.toFloat() ?: 0f,
                y0 = s?.optDouble("zoneY0", 0.30)?.toFloat() ?: 0.30f,
                x1 = s?.optDouble("zoneX1", 0.40)?.toFloat() ?: 0.40f,
                y1 = s?.optDouble("zoneY1", 1.0)?.toFloat() ?: 1f,
            )
            val layout = PadLayout(keys, stick)
            layout.sanitise()
            return layout
        }

        /** Same order as OnScreenDevice's slots: Jump, Cast, Attack, Heal, Dash, Map, Inventory, Throw, Pause. */
        fun defaults(width: Int, height: Int): PadLayout {
            // A view that has not been laid out yet reports 0, and 0/0 here
            // used to put the face diamond at infinity: text still drew (its
            // position is the rect's centre) while every shape vanished. The
            // guard keeps the arithmetic finite; the caller loads again once
            // it has a size.
            val vmin = minOf(width, height).toFloat().coerceAtLeast(1f)
            val wf = (width / vmin).coerceAtLeast(0.01f)   // one vmin in width fractions
            val hf = (height / vmin).coerceAtLeast(0.01f)  // one vmin in height fractions
            val off = 0.155f
            val face = 0.16f
            fun key(id: String, target: String, x: Float, y: Float, w: Float, h: Float,
                    shape: String, label: String, aimUp: Boolean = false) =
                Key(id, target, x, y, w, h, shape, 1f, label, aimUp)

            return PadLayout(
                mutableListOf(
                    key("jump", "Action1", 0.845f, 0.60f + off / hf, face, face, "circle", "Jump"),
                    key("cast", "RightBumper", 0.906f, 0.27f, 0.110f * wf, 0.090f * hf, "pill", "Skill"),
                    key("attack", "Action3", 0.845f - off / wf, 0.60f, face, face, "circle", "Attack"),
                    key("heal", "Action2", 0.845f + off / wf, 0.60f, face, face, "circle", "Bind"),
                    key("dash", "RightTrigger", 0.845f, 0.60f - off / hf, face, face, "circle", "Dash"),
                    key("map", "LeftBumper", 0.100f, 0.26f, 0.130f * wf, 0.075f * hf, "pill", "Map"),
                    key("inventory", "Select", 0.100f, 0.18f, 0.130f * wf, 0.075f * hf, "pill", "Inventory"),
                    key("throw", "RightBumper", 0.784f, 0.27f, 0.110f * wf, 0.090f * hf, "pill", "Tool", aimUp = true),
                    key("pause", "Start", 0.940f, 0.10f, 0.055f * wf, 0.075f * hf, "pill", "II"),
                ),
                Stick(),
            )
        }

        /** Atomic write: the game may read this while the editor is done with it. */
        fun save(context: Context, layout: PadLayout) {
            val f = file(context) ?: return
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, "$FILE.part")
            part.writeText(layout.toJson())
            if (!part.renameTo(f)) {
                part.delete()
                throw java.io.IOException("rename to $f")
            }
            LauncherLog.log("pad layout: wrote ${layout.keys.size} keys to $f")
        }

        fun delete(context: Context) {
            val f = file(context) ?: return
            if (f.isFile && !f.delete()) throw java.io.IOException("could not delete $f")
        }
    }

    /**
     * The first pair of keys that are genuinely on top of each other, or null.
     *
     * Plain arithmetic rather than RectF so this can be tested off-device, and
     * a tolerance rather than a strict test because the built-in diamond is
     * designed to touch: its centres are 0.155 vmin apart and each radius is
     * 0.080, so the four face keys overlap by 0.005 vmin on purpose. A strict
     * test refuses the layout the game ships with -- which is exactly what
     * happened the first time this screen was used.
     */
    fun firstOverlap(width: Int, height: Int, toleranceVmin: Float = 0.02f): String? {
        if (width <= 0 || height <= 0) return null
        val vmin = minOf(width, height).toFloat()
        val tol = toleranceVmin * vmin
        for (i in keys.indices) {
            for (j in i + 1 until keys.size) {
                val a = keys[i]
                val b = keys[j]
                val dx = kotlin.math.abs(a.x - b.x) * width
                val dy = kotlin.math.abs(a.y - b.y) * height
                val clash = if (a.shape == "circle" && b.shape == "circle") {
                    val reach = (a.w * a.scale + b.w * b.scale) * vmin / 2f
                    dx * dx + dy * dy < (reach - tol) * (reach - tol)
                } else {
                    val halfW = (a.w * a.scale + b.w * b.scale) * vmin / 2f
                    val halfH = (a.h * a.scale + b.h * b.scale) * vmin / 2f
                    dx < halfW - tol && dy < halfH - tol
                }
                if (clash) return "${a.label} and ${b.label}"
            }
        }
        return null
    }

    /** Values into a range the game can survive; mirrors PadLayoutFile.Clamp. */
    fun sanitise() {
        stick.mode = if (stick.mode == "pinned") "pinned" else "floating"
        stick.cx = stick.cx.coerceIn(0f, 1f)
        stick.cy = stick.cy.coerceIn(0f, 1f)
        stick.r = stick.r.coerceIn(0.05f, 0.30f)
        stick.x0 = stick.x0.coerceIn(0f, 0.95f)
        stick.y0 = stick.y0.coerceIn(0f, 0.95f)
        stick.x1 = stick.x1.coerceIn(stick.x0 + 0.05f, 1f)
        stick.y1 = stick.y1.coerceIn(stick.y0 + 0.05f, 1f)
        for (k in keys) {
            k.x = k.x.coerceIn(-0.05f, 1.05f)
            k.y = k.y.coerceIn(-0.05f, 1.05f)
            k.w = k.w.coerceIn(0.02f, 0.80f)
            k.h = k.h.coerceIn(0.02f, 0.80f)
            k.scale = k.scale.coerceIn(0.5f, 2f)
            if (k.shape != "pill") k.shape = "circle"
        }
    }
}
