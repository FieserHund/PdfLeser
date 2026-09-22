package de.pdfleser.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

object Prefs {
    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("books", Context.MODE_PRIVATE)
}

data class ReaderSettings(
    val fontSizeSp: Int = 19,
    val font: Int = 0,          // 0 Serif, 1 Sans, 2 Schmal
    val lineSpacing: Int = 1,   // 0 eng, 1 normal, 2 weit
    val margin: Int = 1,        // 0 schmal, 1 mittel, 2 breit
    val theme: Int = 3,         // 0 Hell, 1 Sepia, 2 Dunkel, 3 Nacht (weiß auf schwarz)
    val justify: Boolean = true,
    val fullscreen: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f, // -1 = Systemhelligkeit
    val autoVocab: Boolean = true,
    val showDefinition: Boolean = true,
    val ttsRate: Float = 1.0f
) {
    val isDark: Boolean get() = theme >= 2

    companion object {
        val SPACINGS = floatArrayOf(1.2f, 1.45f, 1.75f)
        val MARGINS_DP = intArrayOf(14, 26, 42)

        fun load(ctx: Context): ReaderSettings {
            val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val d = ReaderSettings()
            return ReaderSettings(
                fontSizeSp = p.getInt("fontSize", d.fontSizeSp),
                font = p.getInt("font", d.font),
                lineSpacing = p.getInt("spacing", d.lineSpacing),
                margin = p.getInt("margin", d.margin),
                theme = p.getInt("theme", d.theme),
                justify = p.getBoolean("justify", d.justify),
                fullscreen = p.getBoolean("fullscreen", d.fullscreen),
                volumeKeys = p.getBoolean("volumeKeys", d.volumeKeys),
                keepScreenOn = p.getBoolean("keepOn", d.keepScreenOn),
                brightness = p.getFloat("brightness", d.brightness),
                autoVocab = p.getBoolean("autoVocab", d.autoVocab),
                showDefinition = p.getBoolean("showDef", d.showDefinition),
                ttsRate = p.getFloat("ttsRate", d.ttsRate)
            )
        }

        fun save(ctx: Context, s: ReaderSettings) {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt("fontSize", s.fontSizeSp)
                .putInt("font", s.font)
                .putInt("spacing", s.lineSpacing)
                .putInt("margin", s.margin)
                .putInt("theme", s.theme)
                .putBoolean("justify", s.justify)
                .putBoolean("fullscreen", s.fullscreen)
                .putBoolean("volumeKeys", s.volumeKeys)
                .putBoolean("keepOn", s.keepScreenOn)
                .putFloat("brightness", s.brightness)
                .putBoolean("autoVocab", s.autoVocab)
                .putBoolean("showDef", s.showDefinition)
                .putFloat("ttsRate", s.ttsRate)
                .apply()
        }
    }
}

class ThemeColors(
    val bg: Int,
    val fg: Int,
    val footer: Int,
    val selection: Int,
    val placeholder: Int,
    val highlight: Int,
    val speaking: Int,
    val ribbon: Int,
    /** Filter für Abbildungen im Lesemodus (in dunklen Themes etwas gedimmt). */
    val imageFilter: ColorFilter?,
    /** Filter für ganze PDF-Seiten in der Originalansicht (Nacht = invertiert). */
    val pageFilter: ColorFilter?
) {
    companion object {
        fun of(theme: Int): ThemeColors = when (theme) {
            1 -> ThemeColors(
                0xFFF4ECD8.toInt(), 0xFF3B2F20.toInt(), 0xFF8A7A62.toInt(),
                0x55C28A2C, 0x22000000, 0x66F0B429, 0x33307ACF, 0xFFC0392B.toInt(),
                null, scale(0.96f, 0.93f, 0.85f)
            )
            2 -> ThemeColors(
                0xFF1E1E1E.toInt(), 0xFFD8D8D8.toInt(), 0xFF8C8C8C.toInt(),
                0x664F8FFF, 0x22FFFFFF, 0x55C99A2E, 0x444F8FFF, 0xFFC0473A.toInt(),
                scale(0.85f, 0.85f, 0.85f), invert(214f, 30f)
            )
            3 -> ThemeColors(
                0xFF000000.toInt(), 0xFFF0F0F0.toInt(), 0xFF8A8A8A.toInt(),
                0x664F8FFF, 0x22FFFFFF, 0x55A87F1F, 0x444F8FFF, 0xFFA83A2E.toInt(),
                scale(0.8f, 0.8f, 0.8f), invert(240f, 0f)
            )
            else -> ThemeColors(
                0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFF808080.toInt(),
                0x553F7FE0, 0x11000000, 0x66FFD54F, 0x3342A5F5, 0xFFD9453B.toInt(),
                null, null
            )
        }

        private fun scale(r: Float, g: Float, b: Float): ColorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        /** Schwarz → forBlack, Weiß → forWhite (Graustufen-Invertierung für Nachtansicht). */
        private fun invert(forBlack: Float, forWhite: Float): ColorFilter {
            val k = (forWhite - forBlack) / 255f
            return ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        k, 0f, 0f, 0f, forBlack,
                        0f, k, 0f, 0f, forBlack,
                        0f, 0f, k, 0f, forBlack,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    }
}
