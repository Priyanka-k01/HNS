package java.com.example.ins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class HighlightingSubsamplingScaleImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SubsamplingScaleImageView(context, attrs) {

    // --- Paint Objects for different rectangle types ---
    // For the currently selected OCR item (Red)
    private val highlightPaint = Paint().apply {
        color = Color.argb(100, 255, 0, 0) // Semi-transparent red
        style = Paint.Style.FILL
    }
    private val highlightStrokePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // For a rectangle being actively drawn by the user (Green)
    private val temporaryPaint = Paint().apply {
        color = Color.argb(100, 0, 255, 0) // Semi-transparent green
        style = Paint.Style.FILL
    }
    private val temporaryStrokePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    // For walkable areas (Blue)
    private val walkablePaint = Paint().apply {
        color = Color.argb(100, 30, 144, 255) // Semi-transparent blue
        style = Paint.Style.FILL
    }
    private val walkableStrokePaint = Paint().apply {
        color = Color.rgb(30, 144, 255) // Solid blue for the border
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // --- Data Lists to hold rectangles ---
    private var highlightedRectS: Rect? = null
    private var temporaryRectS: Rect? = null
    private var walkableRectsS: MutableList<Rect> = mutableListOf()

    init {
        // This makes sure the onDraw method is called
        setWillNotDraw(false)
    }

    // --- PUBLIC METHODS (These are what you were calling from AddDetailsActivity) ---

    fun setHighlightRect(rect: Rect?) {
        this.highlightedRectS = rect
        invalidate() // Tell the view to redraw itself
    }

    fun setTemporaryRect(rect: Rect?) {
        this.temporaryRectS = rect
        invalidate() // Tell the view to redraw itself
    }

    fun addWalkableRect(rect: Rect) {
        walkableRectsS.add(rect)
        invalidate() // Tell the view to redraw itself
    }

    fun getWalkableRects(): List<Rect> {
        return walkableRectsS.toList() // Return a copy for safety
    }

    // A function to clear everything
    fun clearAllRects() {
        highlightedRectS = null
        temporaryRectS = null
        walkableRectsS.clear()
        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isReady) {
            return
        }

        // 1. Draw the highlighted (selected) rectangle in red
        highlightedRectS?.let { rect ->
            sourceToViewCoord(rect.left.toFloat(), rect.top.toFloat())?.let { topLeft ->
                sourceToViewCoord(rect.right.toFloat(), rect.bottom.toFloat())?.let { bottomRight ->
                    val viewRect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                    canvas.drawRect(viewRect, highlightPaint)
                    canvas.drawRect(viewRect, highlightStrokePaint)
                }
            }
        }

        // 2. Draw all the walkable areas in blue
        walkableRectsS.forEach { rect ->
            sourceToViewCoord(rect.left.toFloat(), rect.top.toFloat())?.let { topLeft ->
                sourceToViewCoord(rect.right.toFloat(), rect.bottom.toFloat())?.let { bottomRight ->
                    val viewRect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                    canvas.drawRect(viewRect, walkablePaint)
                    canvas.drawRect(viewRect, walkableStrokePaint)
                }
            }
        }

        // 3. Draw the temporary (currently being drawn) rectangle in green on top of everything
        temporaryRectS?.let { rect ->
            sourceToViewCoord(rect.left.toFloat(), rect.top.toFloat())?.let { topLeft ->
                sourceToViewCoord(rect.right.toFloat(), rect.bottom.toFloat())?.let { bottomRight ->
                    val viewRect = RectF(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                    canvas.drawRect(viewRect, temporaryPaint)
                    canvas.drawRect(viewRect, temporaryStrokePaint)
                }
            }
        }
    }
}
