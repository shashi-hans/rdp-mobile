package rdpmobile.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Shows the remote desktop and turns touches into mouse input, trackpad style:
 * one finger moves the cursor, tap = left click, two-finger tap = right click,
 * two-finger drag = scroll, pinch = zoom, long press then drag = hold left button.
 */
@SuppressLint("ViewConstructor")
class RemoteView(context: Context, private val session: RdpSession) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.5f, 13f); lineTo(8f, 20f)
        lineTo(10.5f, 19f); lineTo(7f, 12f); lineTo(12.5f, 12f); close()
    }

    private val drawMatrix = Matrix()
    private var fitScale = 1f
    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /** Cursor position in remote desktop pixels. */
    private var cx = 0f
    private var cy = 0f

    private var dragging = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var centeredFor: Bitmap? = null
    private var scrollAccum = 0f
    private var twoFingerStart = 0L
    private var twoFingerMoved = false
    private var lastTwoFingerY = 0f

    private val frameCallback: () -> Unit = { postInvalidateOnAnimation() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        session.onFrame = frameCallback
    }

    private val scale = ScaleGestureDetector(context, object :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 4f)
            twoFingerMoved = true
            followCursor()
            invalidate()
            return true
        }
    }).apply {
        // Tap-then-slide must move the pointer, not zoom.
        isQuickScaleEnabled = false
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            click(LibFreeRDP.PTR_FLAGS_BUTTON1)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            dragging = true
            dragLastX = e.x
            dragLastY = e.y
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            send(LibFreeRDP.PTR_FLAGS_BUTTON1 or LibFreeRDP.PTR_FLAGS_DOWN)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount != 1) return false
            return movePointer(-dx, -dy)
        }
    }).apply {
        // Each tap of a double tap must reach onSingleTapUp as its own click;
        // with a double-tap listener the second tap would be swallowed.
        setOnDoubleTapListener(null)
    }

    /** Moves the remote pointer by a finger delta in view pixels. */
    private fun movePointer(fdx: Float, fdy: Float): Boolean {
        val bmp = session.bitmap ?: return false
        // Finger distance maps to remote pixels at the current zoom, with a
        // 1.5x boost so one thumb sweep crosses the screen.
        val k = 1.5f / (fitScale * zoom)
        cx = (cx + fdx * k).coerceIn(0f, bmp.width - 1f)
        cy = (cy + fdy * k).coerceIn(0f, bmp.height - 1f)
        send(LibFreeRDP.PTR_FLAGS_MOVE)
        followCursor()
        invalidate()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFingerStart = e.eventTime
                twoFingerMoved = false
                lastTwoFingerY = (e.getY(0) + e.getY(1)) / 2
                scrollAccum = 0f
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 2 && !scale.isInProgress) {
                val y = (e.getY(0) + e.getY(1)) / 2
                scrollAccum += y - lastTwoFingerY
                lastTwoFingerY = y
                if (abs(scrollAccum) > SCROLL_STEP_PX) {
                    twoFingerMoved = true
                    val up = scrollAccum > 0
                    send(wheel(if (up) LibFreeRDP.WHEEL_DELTA else -LibFreeRDP.WHEEL_DELTA))
                    scrollAccum = 0f
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2 && !twoFingerMoved &&
                e.eventTime - twoFingerStart < TWO_FINGER_TAP_MS
            ) {
                click(LibFreeRDP.PTR_FLAGS_BUTTON2)
                twoFingerMoved = true
            }
            // GestureDetector stops calling onScroll after a long press, so
            // drag moves are tracked here.
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1) {
                movePointer(e.x - dragLastX, e.y - dragLastY)
                dragLastX = e.x
                dragLastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                send(LibFreeRDP.PTR_FLAGS_BUTTON1)
            }
        }
        // GestureDetector cancels tap and long press when a second finger lands,
        // and onScroll ignores multi-finger moves, so it sees every event.
        gestures.onTouchEvent(e)
        return true
    }

    private fun wheel(amount: Int): Int {
        var flags = LibFreeRDP.PTR_FLAGS_WHEEL
        if (amount < 0) flags = flags or LibFreeRDP.PTR_FLAGS_WHEEL_NEGATIVE
        return flags or (amount and 0xFF)
    }

    private fun click(button: Int) {
        send(button or LibFreeRDP.PTR_FLAGS_DOWN)
        send(button)
    }

    private fun send(flags: Int) = session.pointer(cx.roundToInt(), cy.roundToInt(), flags)

    /** Pans the zoomed view so the cursor stays at least 15% inside each edge. */
    private fun followCursor() {
        val bmp = session.bitmap ?: return
        val s = fitScale * zoom
        val viewW = width / s
        val viewH = height / s
        val mx = viewW * 0.15f
        val my = viewH * 0.15f
        if (cx < offsetX + mx) offsetX = cx - mx
        if (cx > offsetX + viewW - mx) offsetX = cx - viewW + mx
        if (cy < offsetY + my) offsetY = cy - my
        if (cy > offsetY + viewH - my) offsetY = cy - viewH + my
        offsetX = offsetX.coerceIn(0f, (bmp.width - viewW).coerceAtLeast(0f))
        offsetY = offsetY.coerceIn(0f, (bmp.height - viewH).coerceAtLeast(0f))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = session.bitmap ?: return
        fitScale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        if (centeredFor !== bmp) {
            // New or resized desktop: start the pointer in the middle.
            centeredFor = bmp
            cx = bmp.width / 2f
            cy = bmp.height / 2f
        }
        val s = fitScale * zoom
        // Center the desktop when it is smaller than the view.
        val padX = ((width - bmp.width * s) / 2).coerceAtLeast(0f)
        val padY = ((height - bmp.height * s) / 2).coerceAtLeast(0f)
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(padX - offsetX * s, padY - offsetY * s)
        canvas.drawBitmap(bmp, drawMatrix, paint)

        val px = padX + (cx - offsetX) * s
        val py = padY + (cy - offsetY) * s
        val ptr = session.pointer.value
        when {
            ptr == null -> {
                canvas.save()
                canvas.translate(px, py)
                canvas.scale(density, density)
                canvas.drawPath(arrow, cursorFill)
                canvas.drawPath(arrow, cursorStroke)
                canvas.restore()
            }
            ptr.bitmap != null -> {
                canvas.save()
                canvas.translate(px - ptr.hotX * s, py - ptr.hotY * s)
                canvas.scale(s, s)
                canvas.drawBitmap(ptr.bitmap, 0f, 0f, paint)
                canvas.restore()
            }
        }
    }

    private val density get() = resources.displayMetrics.density.coerceAtLeast(1f)

    // ---- keyboard ----

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password type turns off suggestions and autocorrect, so each
        // key reaches the laptop as typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            // Swipe and voice keyboards first show a word as "composing" text
            // and then commit it. Only committed text is sent, once.
            private var composing = ""

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) session.text(composing)
                composing = ""
                return true
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                session.text(text.toString())
                return true
            }

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { session.tapKey(VK.BACK) }
                repeat(after) { session.tapKey(VK.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean = handleKey(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = handleKey(event) || super.onKeyUp(keyCode, event)

    private fun handleKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // With Ctrl, Alt or Meta held, Android gives letters no unicode
        // character, so Ctrl+C would be lost; send letters and digits as keys.
        val shortcut = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        val vk = androidToVk(event.keyCode) ?: if (shortcut) letterOrDigitVk(event.keyCode) else null
        if (vk != null) {
            session.key(vk, down)
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0 && down) {
            session.text(String(Character.toChars(ch)))
            return true
        }
        return false
    }

    private fun androidToVk(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_DEL -> VK.BACK
        KeyEvent.KEYCODE_FORWARD_DEL -> VK.DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VK.RETURN
        KeyEvent.KEYCODE_TAB -> VK.TAB
        KeyEvent.KEYCODE_ESCAPE -> VK.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> VK.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> VK.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> VK.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> VK.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> VK.HOME
        KeyEvent.KEYCODE_MOVE_END -> VK.END
        KeyEvent.KEYCODE_PAGE_UP -> VK.PRIOR
        KeyEvent.KEYCODE_PAGE_DOWN -> VK.NEXT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> VK.LCONTROL
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> VK.LMENU
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> VK.LSHIFT
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> VK.LWIN
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> VK.F1 + (code - KeyEvent.KEYCODE_F1)
        else -> null
    }

    private fun letterOrDigitVk(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41 + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30 + (code - KeyEvent.KEYCODE_0)
        else -> null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        followCursor()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Hardware keyboard keys reach the laptop without opening the soft keyboard first.
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        // A newer view may own the callback by now; only clear our own.
        if (session.onFrame === frameCallback) session.onFrame = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SCROLL_STEP_PX = 40f
        const val TWO_FINGER_TAP_MS = 250L
    }
}
