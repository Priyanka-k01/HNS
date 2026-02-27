package java.com.example.ins

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.UUID

data class OcrTextData(
    var text: String,
    val boundingBox: Rect?,
    var isUserAdded: Boolean = false,
    val id: String = UUID.randomUUID().toString()
)

class AddDetailsActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var tvLocationName: TextView
    private lateinit var tvLocationCoords: TextView
    private lateinit var btnSelectFloorMap: Button
    private lateinit var ivFloorMapPreview: HighlightingSubsamplingScaleImageView
    private lateinit var btnProcessMap: Button
    private lateinit var tvExtractedDetailsHeader: TextView
    private lateinit var rvOcrResults: RecyclerView
    private lateinit var ocrResultsAdapter: OcrResultsAdapter
    private lateinit var btnAddCustomPoint: Button
    private lateinit var layoutActionButtons: LinearLayout
    private lateinit var btnSaveMapDetails: Button
    private lateinit var btnUpdateMapDetails: Button
    private lateinit var btnCancelMapUpload: Button
    // --- MERGED: Walkable Area Button ---
    private lateinit var btnDrawWalkableArea: Button

    // --- Data ---
    private var selectedImageUri: Uri? = null
    private var locationNameExtra: String? = null
    private var locationLatitudeExtra: Double = 0.0
    private var locationLongitudeExtra: Double = 0.0
    private var locationTypeExtra: String? = null
    private var docIdToUpdate: String? = null
    private var currentOcrData: MutableList<OcrTextData> = mutableListOf()

    // --- Firebase ---
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    // --- State ---
    private var selectedItemForHighlight: OcrTextData? = null
    private var isAddingCustomPointMode: Boolean = false
    // --- MERGED: Walkable Area State ---
    private var isDrawingWalkableMode: Boolean = false
    private var customPointStartS: PointF? = null

    companion object {
        const val EXTRA_LOCATION_NAME = "location_name"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_LOCATION_TYPE = "location_type"
        const val EXTRA_DOC_ID = "doc_id"
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                ivFloorMapPreview.setImage(ImageSource.uri(uri))
                ivFloorMapPreview.visibility = View.VISIBLE
                btnProcessMap.visibility = View.VISIBLE
                btnProcessMap.isEnabled = true
                resetOcrEditingUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_details)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        // Find all views
        tvLocationName = findViewById(R.id.tvLocationName)
        tvLocationCoords = findViewById(R.id.tvLocationCoords)
        btnSelectFloorMap = findViewById(R.id.btnSelectFloorMap)
        ivFloorMapPreview = findViewById(R.id.ivFloorMapPreview)
        btnProcessMap = findViewById(R.id.btnProcessMap)
        tvExtractedDetailsHeader = findViewById(R.id.tvExtractedDetailsHeader)
        rvOcrResults = findViewById(R.id.rvOcrResults)
        btnAddCustomPoint = findViewById(R.id.btnAddCustomPoint)
        layoutActionButtons = findViewById(R.id.layoutActionButtons)
        btnSaveMapDetails = findViewById(R.id.btnSaveMapDetails)
        btnUpdateMapDetails = findViewById(R.id.btnUpdateMapDetails)
        btnCancelMapUpload = findViewById(R.id.btnCancelMapUpload)
        // --- MERGED: Find Walkable Area Button ---
        btnDrawWalkableArea = findViewById(R.id.btnDrawWalkableArea)

        locationNameExtra = intent.getStringExtra(EXTRA_LOCATION_NAME)
        locationLatitudeExtra = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
        locationLongitudeExtra = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
        locationTypeExtra = intent.getStringExtra(EXTRA_LOCATION_TYPE)
        docIdToUpdate = intent.getStringExtra(EXTRA_DOC_ID)

        if (locationNameExtra != null) {
            tvLocationName.text = locationNameExtra
            tvLocationCoords.text = "Lat: $locationLatitudeExtra, Lon: $locationLongitudeExtra, Type: ${locationTypeExtra ?: "Generic"}"
            if (docIdToUpdate != null) {
                btnSaveMapDetails.visibility = View.GONE
                btnUpdateMapDetails.visibility = View.VISIBLE
            } else {
                btnSaveMapDetails.visibility = View.VISIBLE
                btnUpdateMapDetails.visibility = View.GONE
            }
        } else {
            tvLocationName.text = "Location details not found"
            tvLocationCoords.text = ""
            btnSelectFloorMap.isEnabled = false
            btnProcessMap.isEnabled = false
        }

        setupOcrRecyclerView()
        setupMapPreviewTouchListener()
        resetOcrEditingUI()

        btnSelectFloorMap.setOnClickListener { openImageChooser() }
        btnProcessMap.setOnClickListener {
            selectedImageUri?.let { uri -> processSelectedImageWithOcr(uri) }
                ?: Toast.makeText(this, "Please select a map image first.", Toast.LENGTH_SHORT).show()
        }

        btnAddCustomPoint.setOnClickListener {
            isAddingCustomPointMode = true
            isDrawingWalkableMode = false // MERGED: Ensure other mode is off
            selectedItemForHighlight = null
            ivFloorMapPreview.setHighlightRect(null)
            ivFloorMapPreview.setTemporaryRect(null)
            Toast.makeText(this, "Mode: Add Label. Tap and drag on the map.", Toast.LENGTH_LONG).show()
        }

        // --- MERGED: Add listener for the new button ---
        btnDrawWalkableArea.setOnClickListener {
            isDrawingWalkableMode = true
            isAddingCustomPointMode = false
            selectedItemForHighlight = null
            ivFloorMapPreview.setHighlightRect(null)
            ivFloorMapPreview.setTemporaryRect(null)
            Toast.makeText(this, "Mode: Draw Walkable Area. Tap and drag on the map.", Toast.LENGTH_LONG).show()
        }

        val saveAction = {
            if (selectedImageUri == null) {
                Toast.makeText(this, "No map image selected.", Toast.LENGTH_SHORT).show()
            } else {
                saveMapAndDetails()
            }
        }
        btnSaveMapDetails.setOnClickListener { saveAction() }
        btnUpdateMapDetails.setOnClickListener { saveAction() }
        btnCancelMapUpload.setOnClickListener { finish() }
    }

    private fun openImageChooser() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun processSelectedImageWithOcr(uri: Uri) {
        try {
            val inputImage = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(inputImage).addOnSuccessListener { visionText ->
                val keywords = getKeywordsForLocationType(locationTypeExtra)
                val roomNumberPattern = Regex("""\b([A-Za-z]{0,3}\s?\d{1,4}[A-Za-z]{0,2})\b""")
                val tempOcrList = visionText.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line ->
                        val lineText = line.text.trim().replace("\n", " ")
                        val lineTextUpper = lineText.uppercase(Locale.ROOT)
                        val isImportant = lineText.length <= 50 &&
                                (roomNumberPattern.containsMatchIn(lineText) || keywords.any { keyword -> lineTextUpper.contains(keyword) })
                        if (isImportant) OcrTextData(lineText, line.boundingBox) else null
                    }
                }
                currentOcrData.clear()
                currentOcrData.addAll(tempOcrList.distinctBy { it.text.uppercase(Locale.ROOT) + (it.boundingBox?.toShortString() ?: "") })
                ocrResultsAdapter.updateData(currentOcrData)
                updateOcrHeader()
                tvExtractedDetailsHeader.visibility = View.VISIBLE
                rvOcrResults.visibility = View.VISIBLE
                btnAddCustomPoint.visibility = View.VISIBLE
                btnDrawWalkableArea.visibility = View.VISIBLE // MERGED: Show new button
                layoutActionButtons.visibility = View.VISIBLE
                Toast.makeText(this, "Found ${currentOcrData.size} relevant labels.", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener { e ->
                Log.e("AddDetailsActivity", "OCR Failed", e)
                Toast.makeText(this, "OCR processing failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("AddDetailsActivity", "Image processing error", e)
            Toast.makeText(this, "Failed to load image for OCR.", Toast.LENGTH_SHORT).show()
        }
    }

    // THIS IS YOUR WORKING, CORRECT SETUP FUNCTION
    private fun setupOcrRecyclerView() {
        ocrResultsAdapter = OcrResultsAdapter(
            onItemClick = { item ->
                selectedItemForHighlight = item
                ivFloorMapPreview.setHighlightRect(item.boundingBox)
                ocrResultsAdapter.notifyDataSetChanged()
            },
            onItemTextChange = { position, newText ->
                if (position in currentOcrData.indices) {
                    currentOcrData[position].text = newText
                }
            },
            onItemRemove = { position ->
                if (position in currentOcrData.indices) {
                    val removedItem = currentOcrData.removeAt(position)
                    if (removedItem.id == selectedItemForHighlight?.id) {
                        selectedItemForHighlight = null
                        ivFloorMapPreview.setHighlightRect(null)
                    }
                    ocrResultsAdapter.updateData(currentOcrData)
                    updateOcrHeader()
                }
            }
        )
        rvOcrResults.layoutManager = LinearLayoutManager(this)
        rvOcrResults.adapter = ocrResultsAdapter
        ocrResultsAdapter.updateData(currentOcrData)
    }

    // --- MERGED: Touch listener now handles BOTH modes ---
    private fun setupMapPreviewTouchListener() {
        ivFloorMapPreview.setOnTouchListener { _, event ->
            if (!ivFloorMapPreview.isReady || (!isAddingCustomPointMode && !isDrawingWalkableMode)) {
                return@setOnTouchListener false
            }
            val sourceTouchPoint: PointF? = ivFloorMapPreview.viewToSourceCoord(event.x, event.y)
            if (sourceTouchPoint == null) {
                if (event.action == MotionEvent.ACTION_UP && customPointStartS != null) {
                    val edgeX = event.x.coerceIn(0f, ivFloorMapPreview.width.toFloat())
                    val edgeY = event.y.coerceIn(0f, ivFloorMapPreview.height.toFloat())
                    ivFloorMapPreview.viewToSourceCoord(edgeX, edgeY)?.let {
                        finalizeCustomRectDrawing(it.x, it.y)
                    }
                }
                return@setOnTouchListener true
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    customPointStartS = sourceTouchPoint
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    customPointStartS?.let { start ->
                        val normalizedRect = Rect(
                            minOf(start.x.toInt(), sourceTouchPoint.x.toInt()),
                            minOf(start.y.toInt(), sourceTouchPoint.y.toInt()),
                            maxOf(start.x.toInt(), sourceTouchPoint.x.toInt()),
                            maxOf(start.y.toInt(), sourceTouchPoint.y.toInt())
                        )
                        ivFloorMapPreview.setTemporaryRect(normalizedRect)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    finalizeCustomRectDrawing(sourceTouchPoint.x, sourceTouchPoint.y)
                    true
                }
                else -> false
            }
        }
    }

    // --- MERGED: Finalize drawing now handles BOTH modes ---
    private fun finalizeCustomRectDrawing(endX: Float, endY: Float) {
        customPointStartS?.let { start ->
            val finalRect = Rect(minOf(start.x.toInt(), endX.toInt()), minOf(start.y.toInt(), endY.toInt()), maxOf(start.x.toInt(), endX.toInt()), maxOf(start.y.toInt(), endY.toInt()))
            if (finalRect.width() > 10 && finalRect.height() > 10) {
                if (isDrawingWalkableMode) {
                    ivFloorMapPreview.addWalkableRect(finalRect)
                    ivFloorMapPreview.setTemporaryRect(null)
                    Toast.makeText(this, "Walkable area added.", Toast.LENGTH_SHORT).show()
                } else {
                    showAddCustomPointDialog(finalRect)
                }
            } else {
                Toast.makeText(this, "Area drawn is too small. Please try again.", Toast.LENGTH_SHORT).show()
                ivFloorMapPreview.setTemporaryRect(null)
            }
        }
        isAddingCustomPointMode = false
        isDrawingWalkableMode = false
        customPointStartS = null
    }

    private fun getKeywordsForLocationType(type: String?): Set<String> {
        return when (type?.uppercase(Locale.ROOT) ?: "GENERIC") {
            "BUS_STATION" -> setOf("GATE", "PLATFORM", "BAY", "TERMINAL", "BUS STOP", "TICKET", "INFORMATION", "INFO", "WAITING AREA", "RESTROOM", "WC", "EXIT", "ENTRANCE", "ARRIVAL", "DEPARTURE", "ROUTE", "LINE", "STAND", "KIOSK", "SHOP", "CAFE", "SECURITY", "LUGGAGE")
            "AIRPORT" -> setOf("GATE", "TERMINAL", "CHECK-IN", "BAGGAGE", "SECURITY", "CUSTOMS", "IMMIGRATION", "DEPARTURE", "ARRIVAL", "LOUNGE", "DUTY FREE", "SHOP", "RESTAURANT", "CAFE", "INFO", "FLIGHT", "BOARDING", "WC", "RESTROOM", "EXIT", "ENTRANCE", "TAXI", "BUS", "TRAIN", "PASSPORT")
            "SHOPPING_MALL", "MALL" -> setOf("SHOP", "STORE", "ENTRANCE", "EXIT", "FOOD COURT", "RESTROOM", "WC", "INFORMATION", "INFO DESK", "ATM", "ESCALATOR", "LIFT", "ELEVATOR", "STAIR", "PARKING", "CINEMA", "KIOSK", "MANAGEMENT", "CUSTOMER SERVICE")
            "HOSPITAL" -> setOf("WARD", "ROOM", "ER", "ICU", "OR", "OPERATION", "RECEPTION", "ADMISSION", "PHARMACY", "LAB", "X-RAY", "RADIOLOGY", "PATHOLOGY", "CAFETERIA", "CHAPEL", "CONSULTATION", "DOCTOR", "NURSE", "WAITING", "EXIT", "ENTRANCE", "LIFT", "STAIR", "WC", "EMERGENCY", "SURGERY", "CLINIC")
            "OFFICE", "RESIDENTIAL", "BUILDING", "GENERIC" -> setOf("ROOM", "OFFICE", "STE", "SUITE", "LOBBY", "STAIR", "ELEVATOR", "EXIT", "LIFT", "ENTRANCE", "HALL", "DEPT", "LAB", "CLASS", "CONF", "WC", "RESTROOM", "INFO", "GATE", "BEDROOM", "DINING", "TOILET", "BATH", "BATHROOM", "KITCHEN", "TERRACE", "BALCONY", "STORE", "UTILITY", "PANTRY", "LIVING", "STUDY", "CLOSET", "WIC", "GARAGE", "PARKING", "RECEPTION", "MEETING", "SERVER", "BALC", "KIT", "DR", "BR", "LR", "SHOWER", "OPEN TO SKY", "OPEN", "VOID", "DUCT", "SHAFT", "FOYER", "CORRIDOR", "CABIN")
            else -> setOf("ROOM", "OFFICE", "STORE", "SHOP", "TOILET", "WC", "LOBBY", "INFO", "EXIT", "ENTRANCE", "STAIR", "LIFT", "ELEVATOR", "GATE", "HALL")
        }
    }

    private fun updateOcrHeader() {
        tvExtractedDetailsHeader.text = "Editable Extracted Details (${currentOcrData.size} items):"
    }

    private fun resetOcrEditingUI() {
        tvExtractedDetailsHeader.visibility = View.GONE
        rvOcrResults.visibility = View.GONE
        btnAddCustomPoint.visibility = View.GONE
        btnDrawWalkableArea.visibility = View.GONE // MERGED
        layoutActionButtons.visibility = View.GONE
        currentOcrData.clear()
        if (::ocrResultsAdapter.isInitialized) ocrResultsAdapter.updateData(emptyList())
        if (::ivFloorMapPreview.isInitialized) ivFloorMapPreview.clearAllRects() // MERGED
        selectedItemForHighlight = null
        isAddingCustomPointMode = false
        isDrawingWalkableMode = false // MERGED
        customPointStartS = null
    }

    private fun showAddCustomPointDialog(drawnBox: Rect?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_ocr_point, null)
        val etCustomText: EditText = dialogView.findViewById(R.id.etCustomOcrText)
        AlertDialog.Builder(this).setTitle("Add Custom Label").setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val text = etCustomText.text.toString().trim()
                if (text.isNotEmpty() && drawnBox != null) {
                    val newOcrItem = OcrTextData(text, drawnBox, isUserAdded = true)
                    currentOcrData.add(newOcrItem)
                    ocrResultsAdapter.addItem(newOcrItem)
                    updateOcrHeader()
                    ivFloorMapPreview.setTemporaryRect(null)
                } else {
                    Toast.makeText(this, "Label cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> ivFloorMapPreview.setTemporaryRect(null) }
            .setOnDismissListener { ivFloorMapPreview.setTemporaryRect(null) }
            .show()
    }

    // --- MERGED: Save function now includes walkable areas ---
    private fun saveMapAndDetails() {
        val finalOcrDataToSave = ocrResultsAdapter.getItems()
        val finalWalkableAreas = ivFloorMapPreview.getWalkableRects()
        val currentUser = auth.currentUser
        if (selectedImageUri == null || currentUser == null) {
            Toast.makeText(this, "Missing Image or User.", Toast.LENGTH_SHORT).show(); return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()

        val fileName = "${locationNameExtra?.replace(" ", "_")}_${System.currentTimeMillis()}.jpg"
        val storageRef = storage.reference.child("floor_maps/${currentUser.uid}/$fileName")
        storageRef.putFile(selectedImageUri!!).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                saveDetailsToFirestore(
                    currentUser.uid, locationNameExtra!!, locationLatitudeExtra, locationLongitudeExtra,
                    downloadUri.toString(), fileName, locationTypeExtra ?: "GENERIC",
                    finalOcrDataToSave.map { ocrItem ->
                        mapOf("text" to ocrItem.text, "boundingBox" to ocrItem.boundingBox, "isUserAdded" to ocrItem.isUserAdded)
                    },
                    finalWalkableAreas
                )
                progressDialog.dismiss()
            }.addOnFailureListener { e ->
                progressDialog.dismiss()
                Toast.makeText(this, "Failed to get download URL: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener { e ->
            progressDialog.dismiss()
            Toast.makeText(this, "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- MERGED: Firestore function now accepts walkable areas ---
    private fun saveDetailsToFirestore(
        ownerId: String, locName: String, lat: Double, lon: Double,
        imageUrl: String, imageName: String, locationType: String,
        ocrDataForFirestore: List<Map<String, Any?>>,
        walkableAreasForFirestore: List<Rect>
    ) {
        val mapDocumentData = hashMapOf(
            "ownerId" to ownerId, "locationName" to locName, "latitude" to lat, "longitude" to lon,
            "locationType" to locationType, "mapImageUrl" to imageUrl, "mapImageName" to imageName,
            "ocrData" to ocrDataForFirestore, "uploadedAt" to com.google.firebase.Timestamp.now(),
            "walkableAreas" to walkableAreasForFirestore // The new data field
        )
        val docRef = firestore.collection("uploadedFloorMaps").document(docIdToUpdate ?: firestore.collection("uploadedFloorMaps").document().id)
        docRef.set(mapDocumentData, SetOptions.merge()).addOnSuccessListener {
            Toast.makeText(this, "Map details saved successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Failed to save details: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // THIS IS YOUR WORKING, CORRECT ADAPTER
    private inner class OcrResultsAdapter(
        private val onItemClick: (OcrTextData) -> Unit,
        private val onItemTextChange: (Int, String) -> Unit,
        private val onItemRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<OcrResultsAdapter.ViewHolder>() {

        private var ocrItems: MutableList<OcrTextData> = mutableListOf()

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ocrText: EditText = view.findViewById(R.id.etOcrText)
            val removeButton: Button = view.findViewById(R.id.btnRemoveOcrItem)
            val itemLayout: LinearLayout = view.findViewById(R.id.ocr_item_layout)

            init {
                itemLayout.setOnClickListener {
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        onItemClick(ocrItems[adapterPosition])
                    }
                }
                ocrText.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus && adapterPosition != RecyclerView.NO_POSITION) {
                        val newText = ocrText.text.toString()
                        if (newText != ocrItems[adapterPosition].text) {
                            onItemTextChange(adapterPosition, newText)
                        }
                    }
                }
                removeButton.setOnClickListener {
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        onItemRemove(adapterPosition)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_ocr_result, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount() = ocrItems.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = ocrItems[position]
            holder.ocrText.setText(item.text)

            holder.itemLayout.setBackgroundColor(
                if (item.id == selectedItemForHighlight?.id)
                    ContextCompat.getColor(holder.itemView.context, R.color.highlight_color)
                else
                    Color.TRANSPARENT
            )
        }

        fun updateData(newOcrItems: List<OcrTextData>) {
            this.ocrItems = newOcrItems.toMutableList()
            notifyDataSetChanged()
        }

        fun addItem(item: OcrTextData) {
            this.ocrItems.add(item)
            notifyItemInserted(this.ocrItems.size - 1)
        }

        fun getItems(): List<OcrTextData> {
            return ocrItems.toList()
        }
    }
}
