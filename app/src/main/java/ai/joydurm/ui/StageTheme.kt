package ai.joydurm.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.joydurm.core.Role
import kotlin.math.roundToInt

/** Native Android components shared by the stage and setup screens. */
class StageTheme(private val context: Context) {
    companion object {
        const val BG = 0xFF080E13.toInt()
        const val PANEL = 0xFF131D26.toInt()
        const val BORDER = 0xFF3C5061.toInt()
        const val BLUE = 0xFF1677FF.toInt()
        const val TEXT = 0xFFF4F7FB.toInt()
        const val MUTED = 0xFFA8BACB.toInt()
        const val GREEN = 0xFF6DED83.toInt()
        const val RED = 0xFFFF706A.toInt()
        const val YELLOW = 0xFFF8E660.toInt()
    }

    private val enabled = intArrayOf(android.R.attr.state_enabled)
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val focused = intArrayOf(android.R.attr.state_focused)
    private var controllerAtlas: Bitmap? = null
    private val controllerCells = mutableMapOf<Role, Bitmap>()

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    fun text(value: String, size: Int = 14, bold: Boolean = false, color: Int = TEXT): TextView =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
        }

    fun column(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun primary(label: String, action: () -> Unit): Button = button(label, true, action)

    fun secondary(label: String, action: () -> Unit): Button = button(label, false, action)

    private fun button(label: String, primary: Boolean, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setTextColor(ColorStateList(arrayOf(disabled, enabled, intArrayOf()), intArrayOf(MUTED, TEXT, TEXT)))
        background = interactiveBackground(if (primary) BLUE else PANEL, 12)
        backgroundTintList = null
        elevation = 0f
        stateListAnimator = null
        isFocusable = true
        setOnClickListener { action() }
    }

    fun field(hint: String, value: String): EditText = EditText(context).apply {
        this.hint = hint
        setText(value)
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        minHeight = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = interactiveBackground(BG, 12)
        backgroundTintList = null
        // Let the editor grow at accessibility font sizes instead of truncating its text.
        setSingleLine(false)
    }

    fun card(): LinearLayout = column().apply {
        background = shape(PANEL, BORDER, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun icon(name: String, description: String, action: () -> Unit): ImageButton = ImageButton(context).apply {
        val drawable = when (name) {
            "back" -> ai.joydurm.R.drawable.ui_back
            "settings" -> ai.joydurm.R.drawable.ui_settings
            "devices" -> ai.joydurm.R.drawable.ui_devices
            "kit" -> ai.joydurm.R.drawable.ui_kit
            "check" -> ai.joydurm.R.drawable.ui_check
            "close" -> ai.joydurm.R.drawable.ui_close
            "play" -> ai.joydurm.R.drawable.ui_play
            "pause" -> ai.joydurm.R.drawable.ui_pause
            "refresh" -> ai.joydurm.R.drawable.ui_refresh
            "bluetooth" -> ai.joydurm.R.drawable.ui_bluetooth
            "volume" -> ai.joydurm.R.drawable.ui_volume
            "tune" -> ai.joydurm.R.drawable.ui_tune
            "help" -> ai.joydurm.R.drawable.ui_help
            "chevron" -> ai.joydurm.R.drawable.ui_chevron
            "record" -> ai.joydurm.R.drawable.ui_record
            else -> android.R.drawable.ic_menu_info_details
        }
        setImageResource(drawable)
        imageTintList = ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(MUTED, TEXT))
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = ViewGroup.LayoutParams(dp(48), dp(48))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        background = interactiveBackground(PANEL, 12)
        backgroundTintList = null
        isFocusable = true
        setOnClickListener { action() }
    }

    /** Decode once, then crop actual transparent bitmap cells and center the visible controllers. */
    fun roleArt(role: Role): ImageView = ImageView(context).apply {
        if (controllerAtlas == null) {
            controllerAtlas = runCatching {
                context.assets.open("ui/controllers.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        val atlas = controllerAtlas
        if (atlas != null && atlas.width >= 4) {
            val cell = controllerCells.getOrPut(role) {
                val index = when (role) {
                    Role.LEFT_HAND -> 0
                    Role.RIGHT_HAND -> 1
                    Role.LEFT_FOOT -> 2
                    Role.RIGHT_FOOT -> 3
                }
                val start = atlas.width * index / 4
                val end = atlas.width * (index + 1) / 4
                cropVisibleCell(atlas, start, end - start)
            }
            setImageBitmap(cell)
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        contentDescription = "${role.label}手柄"
        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun cropVisibleCell(atlas: Bitmap, start: Int, width: Int): Bitmap {
        val pixels = IntArray(width * atlas.height)
        atlas.getPixels(pixels, 0, width, start, 0, width, atlas.height)
        var left = width
        var right = -1
        var top = atlas.height
        var bottom = -1
        for (y in 0 until atlas.height) {
            for (x in 0 until width) {
                if (Color.alpha(pixels[y * width + x]) >= 8) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        if (right < left) return Bitmap.createBitmap(atlas, start, 0, width, atlas.height)
        // Keep a little original transparent padding so the soft edges are never clipped.
        left = maxOf(0, left - 8)
        right = minOf(width - 1, right + 8)
        top = maxOf(0, top - 8)
        bottom = minOf(atlas.height - 1, bottom + 8)
        return Bitmap.createBitmap(atlas, start + left, top, right - left + 1, bottom - top + 1)
    }

    private fun shape(fill: Int, border: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), border)
    }

    private fun interactiveBackground(fill: Int, radius: Int): RippleDrawable {
        val states = StateListDrawable().apply {
            addState(disabled, shape(PANEL, BORDER, radius))
            addState(focused, shape(fill, TEXT, radius))
            addState(intArrayOf(), shape(fill, if (fill == BLUE) BLUE else BORDER, radius))
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), states, shape(Color.WHITE, Color.WHITE, radius))
    }
}
