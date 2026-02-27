package java.com.example.ins

import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.firebase.firestore.FirebaseFirestore

class IndoorNavigationActivity : AppCompatActivity() {

    private lateinit var floorMapView: PathfindingImageView
    private lateinit var fromDropdown: AutoCompleteTextView
    private lateinit var toDropdown: AutoCompleteTextView
    private lateinit var btnSearchPath: Button

    private lateinit var db: FirebaseFirestore
    private var locationId: String? = null

    // Store the full OCR data, including bounding boxes
    private var ocrDataMap: Map<String, Map<String, Any>> = emptyMap()
    private var walkableAreas: List<Rect> = listOf()

    companion object {
        private const val TAG = "IndoorNavActivity"
        const val EXTRA_LOCATION_DOC_ID = "location_document_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_indoor_navigation)

        db = FirebaseFirestore.getInstance()

        floorMapView = findViewById(R.id.floorMapView)
        fromDropdown = findViewById(R.id.from_dropdown)
        toDropdown = findViewById(R.id.to_dropdown)
        btnSearchPath = findViewById(R.id.btnSearchPath)

        locationId = intent.getStringExtra(EXTRA_LOCATION_DOC_ID)
        val mapImageUrl = intent.getStringExtra("map_image_url")
        val destinationName = intent.getStringExtra("destination_name")
        supportActionBar?.title = destinationName ?: "Indoor Map"

        if (!mapImageUrl.isNullOrEmpty()) {
            loadMapImageAndSetupPathfinding(mapImageUrl)
        } else {
            Toast.makeText(this, "Floor map image URL not available.", Toast.LENGTH_LONG).show()
        }

        if (locationId == null) {
            Toast.makeText(this, "Error: Location ID not found.", Toast.LENGTH_LONG).show()
        }
    }

    // --- THIS IS THE FINAL, CORRECTED findAndDrawPath FUNCTION ---
    private fun findAndDrawPath() {
        val fromText = fromDropdown.text.toString()
        val toText = toDropdown.text.toString()

        if (fromText.isEmpty() || toText.isEmpty() || fromText == toText) {
            Toast.makeText(this, "Please select a valid start and end point.", Toast.LENGTH_SHORT).show()
            return
        }

        val fromData = ocrDataMap[fromText]
        val toData = ocrDataMap[toText]
        val drawable = floorMapView.drawable

        if (fromData == null || toData == null || drawable == null) {
            Toast.makeText(this, "Map data or image is not ready.", Toast.LENGTH_SHORT).show()
            return
        }

        if (walkableAreas.isEmpty()) {
            Toast.makeText(this, "Error: Walkable areas not loaded. Pathfinding unavailable.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "findAndDrawPath called but walkableAreas list is empty.")
            return
        }

        val mapWidth = drawable.intrinsicWidth
        val mapHeight = drawable.intrinsicHeight

        val pathfinder = AStarPathfinder(mapWidth, mapHeight, walkableAreas, gridSize = 20)

        val startRoomCenter = getCenterPointFromData(fromData)
        val endRoomCenter = getCenterPointFromData(toData)

        if (startRoomCenter == null || endRoomCenter == null) {
            Toast.makeText(this, "Could not find coordinates for start or end point.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Calculating path...", Toast.LENGTH_SHORT).show()

        val calculatedPath = pathfinder.findPath(startRoomCenter, endRoomCenter)

        if (calculatedPath != null && calculatedPath.isNotEmpty()) {
            Log.d(TAG, "Path found with ${calculatedPath.size} points.")

            // --- THIS IS THE FIX: Use the room names for the labels ---
            val labels = listOf(
                TextLabel(fromText, startRoomCenter),
                TextLabel(toText, endRoomCenter)
            )

            // Use the new method to set both the path and the labels
            floorMapView.setPathAndLabels(calculatedPath, labels)
            // --- END OF FIX ---

        } else {
            Log.w(TAG, "No path found between $fromText and $toText.")
            Toast.makeText(this, "Could not find a path.", Toast.LENGTH_LONG).show()
            // Clear both the path and labels if no path is found
            floorMapView.setPathAndLabels(null, null)
        }
    }

    // Helper to extract a Rect from our data map
    private fun getRectFromData(data: Map<String, Any>): Rect? {
        val box = data["boundingBox"] as? Map<String, Long> ?: return null
        return Rect(
            (box["left"] ?: 0).toInt(),
            (box["top"] ?: 0).toInt(),
            (box["right"] ?: 0).toInt(),
            (box["bottom"] ?: 0).toInt()
        )
    }

    // Helper to extract the center Point from our data map
    private fun getCenterPointFromData(data: Map<String, Any>): Point? {
        val rect = getRectFromData(data) ?: return null
        return Point(rect.centerX(), rect.centerY())
    }

    private fun fetchDropdownDataAndSetupSearch(docId: String) {
        db.collection("uploadedFloorMaps").document(docId).get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val ocrDataList = document.get("ocrData") as? List<Map<String, Any>>
                    if (ocrDataList.isNullOrEmpty()) {
                        Toast.makeText(this, "No rooms found for this map.", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }
                    ocrDataMap = ocrDataList.associateBy { it["text"] as? String ?: "" }

                    val rawWalkableAreas = document.get("walkableAreas") as? List<Map<String, Long>> ?: listOf()
                    walkableAreas = rawWalkableAreas.mapNotNull { rectMap ->
                        val left = rectMap["left"]?.toInt()
                        val top = rectMap["top"]?.toInt()
                        val right = rectMap["right"]?.toInt()
                        val bottom = rectMap["bottom"]?.toInt()
                        if (left != null && top != null && right != null && bottom != null) {
                            Rect(left, top, right, bottom)
                        } else {
                            null
                        }
                    }
                    Log.d(TAG, "Loaded ${walkableAreas.size} walkable areas from Firestore.")

                    val roomNames = ocrDataMap.keys.toList().sorted()
                    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, roomNames)
                    fromDropdown.setAdapter(adapter)
                    toDropdown.setAdapter(adapter)

                    btnSearchPath.isEnabled = true
                    btnSearchPath.setOnClickListener { findAndDrawPath() }

                    Toast.makeText(this, "${roomNames.size} locations loaded.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Map data document not found.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error fetching map data", exception)
                Toast.makeText(this, "Failed to load map data.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadMapImageAndSetupPathfinding(imageUrl: String) {
        btnSearchPath.isEnabled = false // Disable search until map and data are ready

        Glide.with(this)
            .load(imageUrl)
            .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    Toast.makeText(this@IndoorNavigationActivity, "Failed to load map.", Toast.LENGTH_LONG).show()
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>, dataSource: DataSource, isFirstResource: Boolean): Boolean {
                    Log.i(TAG, "Map image loaded successfully. Its dimensions are: ${resource.intrinsicWidth}x${resource.intrinsicHeight}")

                    if (locationId != null) {
                        fetchDropdownDataAndSetupSearch(locationId!!)
                    } else {
                        Toast.makeText(this@IndoorNavigationActivity, "Location ID is missing, cannot load rooms.", Toast.LENGTH_LONG).show()
                        Log.e(TAG, "locationId is null in onResourceReady, cannot proceed.")
                    }
                    return false
                }
            })
            .into(floorMapView)
    }
}
