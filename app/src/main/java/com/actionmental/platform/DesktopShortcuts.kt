package com.actionmental.platform

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.actionmental.core.action.Action
import com.actionmental.core.shortcut.DesktopName
import com.actionmental.core.shortcut.Shortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面按钮：把一条快捷键钉到桌面上，轻点即执行，不必按键、也不必打开本应用。
 *
 * 钉住的只是快捷键的 id，不是动作本身 —— 改了动作，桌面上那一个照样跑新的；
 * 名称与图标的变化由 [sync] 推给桌面，删了快捷键则把按钮置灰并说明原因（系统不允许应用替用户移除它）。
 */
class DesktopShortcuts(
    private val context: Context,
    private val translate: (String) -> String,
) {

    private val _pinned = MutableStateFlow<Set<String>>(emptySet())

    /** 已经钉在桌面上的快捷键 id。桌面上的移除系统不通知，界面回到前台时 [refresh] 重查。 */
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    /** 桌面（启动器）支不支持应用请求钉快捷方式。不支持时界面如实说明，不给一个点了没反应的按钮。 */
    val supported: Boolean
        get() = runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    fun refresh() {
        _pinned.value = pinnedInfos().mapNotNull { shortcutIdOf(it.id) }.toSet()
    }

    /** 发出钉住请求；是否真的钉上由用户在桌面的确认框里决定，结果回到前台时由 [refresh] 读到。 */
    fun requestPin(shortcut: Shortcut): Boolean {
        if (!supported) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, infoOf(shortcut), null) }
            .getOrDefault(false)
    }

    /**
     * 把快捷键表的当前样子推给桌面上已有的按钮。
     *
     * 只碰已钉住的：没钉的不发布动态快捷方式，免得长按本应用图标时冒出一长串。
     */
    fun sync(shortcuts: List<Shortcut>) {
        val infos = pinnedInfos()
        if (infos.isEmpty()) return
        val byId = shortcuts.associateBy { it.id }
        val pinnedIds = infos.mapNotNull { info -> shortcutIdOf(info.id)?.let { info.id to it } }

        val alive = pinnedIds.mapNotNull { (_, id) -> byId[id] }
        if (alive.isNotEmpty()) {
            runCatching {
                ShortcutManagerCompat.enableShortcuts(context, alive.map(::infoOf))
                ShortcutManagerCompat.updateShortcuts(context, alive.map(::infoOf))
            }
        }
        val gone = pinnedIds.filter { (_, id) -> id !in byId }.map { it.first }
        if (gone.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.disableShortcuts(context, gone, translate("这条快捷键已删除")) }
        }
        _pinned.value = alive.map { it.id }.toSet()
    }

    private fun pinnedInfos(): List<ShortcutInfoCompat> = runCatching {
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
    }.getOrDefault(emptyList())

    private fun infoOf(shortcut: Shortcut): ShortcutInfoCompat {
        val name = DesktopName.of(shortcut, translate)
        val intent = Intent(context, ShortcutLaunchActivity::class.java)
            .setAction(ACTION_RUN)
            .putExtra(EXTRA_SHORTCUT_ID, shortcut.id)
        return ShortcutInfoCompat.Builder(context, PIN_PREFIX + shortcut.id)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(DesktopIcon.bitmap(context, shortcut.action, name)))
            .setIntent(intent)
            .build()
    }

    companion object {
        const val ACTION_RUN = "com.actionmental.action.RUN_SHORTCUT"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
        private const val PIN_PREFIX = "run:"

        private fun shortcutIdOf(pinId: String): String? =
            pinId.takeIf { it.startsWith(PIN_PREFIX) }?.removePrefix(PIN_PREFIX)
    }
}

/**
 * 桌面按钮的图标。
 *
 * 启动应用的就用那个应用自己的图标：认图标比读名字快。其余的画成本应用的设计语言 ——
 * 纸色底上一颗键帽，键帽上是名字的第一个字，底边一道朱红的定位凸条（F / J 键上那一道），
 * 一眼看得出「这是 Actionmental 的按钮」，又能靠那一个字分清是哪一个。
 * 编辑页的预览与桌面上的是同一张位图。
 */
object DesktopIcon {

    /** 自适应图标的画布是 108 个单位，中间直径 72 的圆之外随时可能被桌面裁掉。 */
    private const val UNITS = 108f

    fun bitmap(context: Context, action: Action, name: String): Bitmap {
        val size = (UNITS * context.resources.displayMetrics.density).toInt().coerceIn(108, 432)
        val launch = action as? Action.LaunchApp
        val appIcon = launch?.let {
            runCatching { context.packageManager.getApplicationIcon(it.packageName) }.getOrNull()
        }
        return appIcon?.let { appBitmap(it, size) } ?: keycapBitmap(DesktopName.glyphOf(name), size)
    }

    /** 自适应图标按层画满 108 画布；老式图标缩进安全区，四周留纸色。 */
    private fun appBitmap(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            listOfNotNull(drawable.background, drawable.foreground).forEach { layer ->
                layer.setBounds(0, 0, size, size)
                layer.draw(canvas)
            }
        } else {
            canvas.drawColor(PAPER)
            val inset = (size * 22f / UNITS).toInt()
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun keycapBitmap(glyph: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val u = size / UNITS
        canvas.drawColor(PAPER)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = 10f * u
        // 硬阴影：键帽下沿多出来的那一截，和应用里的 KeyCap 是同一种「按得下去」的暗示
        paint.color = KEYCAP_SHADOW
        canvas.drawRoundRect(RectF(31f * u, 34f * u, 77f * u, 78f * u), radius, radius, paint)
        val face = RectF(31f * u, 30f * u, 77f * u, 74f * u)
        paint.color = KEYCAP_FACE
        canvas.drawRoundRect(face, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * u
        paint.color = KEYCAP_LINE
        canvas.drawRoundRect(face, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = ACCENT
        canvas.drawRoundRect(RectF(48f * u, 66f * u, 60f * u, 68.4f * u), 1.2f * u, 1.2f * u, paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 22f * u
        }
        // 宽字（emoji、全角）缩到键帽里，不顶边
        val maxWidth = 30f * u
        val measured = text.measureText(glyph)
        if (measured > maxWidth) text.textSize *= maxWidth / measured
        val baseline = 51f * u - (text.descent() + text.ascent()) / 2f
        canvas.drawText(glyph, 54f * u, baseline, text)
        return bitmap
    }

    // 与 Theme.kt 的浅色令牌一致：桌面不跟本应用的深浅色走，固定用纸色最稳
    private val PAPER = Color.parseColor("#EFEBE2")
    private val KEYCAP_FACE = Color.parseColor("#FFFDF8")
    private val KEYCAP_LINE = Color.parseColor("#D8D2C6")
    private val KEYCAP_SHADOW = Color.parseColor("#C9C2B4")
    private val INK = Color.parseColor("#1B1917")
    private val ACCENT = Color.parseColor("#C8452B")
}
