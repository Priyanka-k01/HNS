package java.com.example.ins

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

// --- NEW Data Class for holding label information ---
data class TextLabel(
    val text: String,
    val position: Point
)

class PathfindingImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private var pathPoints: List<Point>? = null
    // --- NEW: A list to hold the labels to be drawn ---
    private var labels: List<TextLabel>? = null

    private val pathPaint = Paint().apply {
        color = Color.parseColor("#4285F4") // Google Blue, more standard
        strokeWidth = 10f // A good thickness
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        setShadowLayer(10f, 5f, 5f, Color.argb(128, 0, 0, 0))
    }

    private val pointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // --- NEW: A Paint object for drawing the text labels ---
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f // A readable text size
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        setShadowLayer(8f, 4f, 4f, Color.argb(100, 0, 0, 0))
    }

    /**
     * Call this to set both the path and the labels to be drawn.
     */
    fun setPathAndLabels(points: List<Point>?, newLabels: List<TextLabel>?) {
        this.pathPoints = points
        this.labels = newLabels
        invalidate() // Crucial: Trigger a redraw
    }

    /**
     * Deprecated but kept for compatibility. Clears labels.
     */
    fun setPath(points: List<Point>?) {
        setPathAndLabels(points, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentPath = pathPoints
        val currentLabels = labels

        // Nothing to do if we don't have a drawable
        if (drawable == null) {
            return
        }

        // --- Coordinate Scaling Logic (Your existing, correct logic) ---
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (drawableWidth <= 0 || drawableHeight <= 0) return

        val scale: Float
        val dx: Float
        val dy: Float

        if (drawableWidth / viewWidth > drawableHeight / viewHeight) {
            scale = viewWidth / drawableWidth
            dx = 0f
            dy = (viewHeight - drawableHeight * scale) * 0.5f
        } else {
            scale = viewHeight / drawableHeight
            dx = (viewWidth - drawableWidth * scale) * 0.5f
            dy = 0f
        }

        // --- 1. Path Drawing Logic ---
        if (!currentPath.isNullOrEmpty()) {
            val scaledPath = Path()
            currentPath.forEachIndexed { index, point ->
                val mappedX = point.x * scale + dx
                val mappedY = point.y * scale + dy

                if (index == 0) {
                    scaledPath.moveTo(mappedX, mappedY)
                } else {
                    scaledPath.lineTo(mappedX, mappedY)
                }
            }
            canvas.drawPath(scaledPath, pathPaint)

            // Draw circles at the start and end points for emphasis
            val startPoint = currentPath.first()
            val endPoint = currentPath.last()
            val startX = startPoint.x * scale + dx
            val startY = startPoint.y * scale + dy
            val endX = endPoint.x * scale + dx
            val endY = endPoint.y * scale + dy

            pointPaint.color = Color.GREEN
            canvas.drawCircle(startX, startY, 20f, pointPaint) // Green circle for start

            pointPaint.color = Color.RED
            canvas.drawCircle(endX, endY, 20f, pointPaint) // Red circle for end
        }

        // --- 2. NEW: Label Drawing Logic ---
        if (!currentLabels.isNullOrEmpty()) {
            currentLabels.forEach { label ->
                // Convert the label's original position to a scaled view position
                val mappedX = label.position.x * scale + dx
                val mappedY = label.position.y * scale + dy

                // Calculate background bounds for the text
                val textBounds = Rect()
                textPaint.getTextBounds(label.text, 0, label.text.length, textBounds)
                val backgroundRect = RectF(
                    mappedX - textBounds.width() / 2f - 20f,
                    mappedY - textBounds.height() - 30f, // Position text above the point
                    mappedX + textBounds.width() / 2f + 20f,
                    mappedY - 10f
                )

                // Draw white background, then black text on top
                canvas.drawRoundRect(backgroundRect, 15f, 15f, textBackgroundPaint)
                canvas.drawText(label.text, mappedX, mappedY - 25f, textPaint)
            }
        }
    }
}
