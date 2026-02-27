package java.com.example.ins

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView // Keep this if you have it in your XML
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback // <-- IMPORT THIS
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.widget.EditText

// Data classes for drawer content (ensure these are defined as before)
data class RegisteredLocationInfo(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val id: String
)

data class UploadedFloorMapInfo(
    val placeName: String,
    val latitude: Double,
    val longitude: Double,
    val mapImageUrl: String,
    val id: String
)

// This data class is used by your NominatimSearchHandler
// Ensure it's defined (here or preferably in NominatimSearchHandler.kt and imported if needed)
// and that NominatimSearchHandler.kt does not also declare it to avoid "Redeclaration".
// If it's declared in NominatimSearchHandler.kt, you can remove this one from Owner.kt.
// For this solution, I'll assume it needs to be accessible here.


class Owner : AppCompatActivity() {
    private var mapView: MapView? = null
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    private lateinit var autocompleteTextView: AutoCompleteTextView
    private lateinit var nominatimSearchHandler: NominatimSearchHandler
    private lateinit var btnSearchLocation: Button
    private lateinit var locationSpecificButton: Button

    private val osmUserAgent = "INS/1.0"
    private var fusedLocationProvider: FusedLocationProviderClient? = null
    private var lastSearchedGeoPoint: GeoPoint? = null
    private var lastSearchedDisplayName: String? = null

    private lateinit var drawerLayout: DrawerLayout

    private lateinit var navPlacesAddedLayout: LinearLayout
    private lateinit var navFloorMapsAddedLayout: LinearLayout

    private val ownerRegisteredLocationsList = mutableListOf<RegisteredLocationInfo>()
    private val ownerUploadedFloorMapsList = mutableListOf<UploadedFloorMapInfo>()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "OwnerActivity" // Added for logging consistency
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = osmUserAgent

        setContentView(R.layout.activity_simple_loc)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        mapView = findViewById(R.id.mapViewUser)
        autocompleteTextView = findViewById(R.id.o_autocomplete)
        btnSearchLocation = findViewById(R.id.btn_search_loc)
        locationSpecificButton = findViewById(R.id.location_specific_button)

        drawerLayout = findViewById(R.id.owner_drawer_layout)
        val hamburgerIcon: ImageButton = findViewById(R.id.owner_hamburger_icon)
        hamburgerIcon.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        val navView: com.google.android.material.navigation.NavigationView = findViewById(R.id.owner_nav_view)
        // Ensure your nav_linear_layout_content exists directly under navView or adjust findViewById target.
        // If nav_linear_layout_content is inside a header, you'd do:
        // val headerView = navView.getHeaderView(0)
        // val navContentLayout: LinearLayout? = headerView.findViewById(R.id.nav_linear_layout_content)
        val navContentLayout: LinearLayout? = navView.findViewById(R.id.nav_linear_layout_content)

        if (navContentLayout != null) {
            navPlacesAddedLayout = navContentLayout.findViewById(R.id.nav_ll_places_added_list)
            navFloorMapsAddedLayout = navContentLayout.findViewById(R.id.nav_ll_floor_maps_added_list)
        } else {
            Log.e(TAG, "Critical: nav_linear_layout_content or its children not found. Drawer content will be empty.")
            // Initialize with dummy layouts to prevent crashes, but this is a layout issue.
            navPlacesAddedLayout = LinearLayout(this)
            navFloorMapsAddedLayout = LinearLayout(this)
        }


        mapView?.setMultiTouchControls(true)
        mapView?.controller?.setZoom(6.0)
        mapView?.controller?.setCenter(GeoPoint(20.5937, 78.9629))

        nominatimSearchHandler = NominatimSearchHandler(osmUserAgent)
        fusedLocationProvider = LocationServices.getFusedLocationProviderClient(this)
        requestLocationPermissionsAndGetCurrentLocation()

        val onBackPressedCallback = object : OnBackPressedCallback(true /* enabled by default */) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    if (isEnabled) {
                        isEnabled = false
                        this@Owner.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this , onBackPressedCallback)


        btnSearchLocation.setOnClickListener {
            val query = autocompleteTextView.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard()
                searchInDatabase(query) { lat, lon, docId -> // docId not directly used for button logic here
                    if (lat != null && lon != null) {
                        val point = GeoPoint(lat, lon)
                        updateMapWithLocation(point, query) // Use query as displayName for DB results
                        Toast.makeText(this, "'$query' found in your database!", Toast.LENGTH_SHORT).show()
                        locationSpecificButton.text = "Manage '$query'" // Or "Add Map for '$query'"
                        locationSpecificButton.visibility = View.VISIBLE
                        lastSearchedGeoPoint = point
                        lastSearchedDisplayName = query
                    } else {
                        // Not found in database, so search Nominatim
                        searchLocationWithNominatim(query)
                    }
                }
            } else {
                Toast.makeText(this, "Please enter a location", Toast.LENGTH_SHORT).show()
                locationSpecificButton.visibility = View.GONE
                lastSearchedGeoPoint = null
                lastSearchedDisplayName = null
            }
        }

        locationSpecificButton.setOnClickListener {
            if (lastSearchedGeoPoint != null && lastSearchedDisplayName != null) {
                // This is your existing logic, which now also needs to handle locationType
                // For simplicity, I'll keep the direct launch but you'll need to adapt AddDetailsActivity
                // or add the location type selection dialog here as discussed previously.

                val locationTypes = arrayOf(
                    "OFFICE", "RESIDENTIAL", "BUS_STATION", "AIRPORT",
                    "SHOPPING_MALL", "MALL", "HOSPITAL", "BUILDING", "GENERIC"
                )
                var selectedLocationType = "GENERIC" // Default

                AlertDialog.Builder(this@Owner)
                    .setTitle("Select Location Type")
                    .setItems(locationTypes) { dialog, which ->
                        selectedLocationType = locationTypes[which]
                        launchAddDetailsActivityInternal(selectedLocationType)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel") { dialog, _ ->
                        Toast.makeText(this@Owner, "Location type selection cancelled.", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    .setNeutralButton("Use Generic") { dialog, _ ->
                        launchAddDetailsActivityInternal("GENERIC")
                        dialog.dismiss()
                    }
                    .show()
            } else {
                Toast.makeText(this, "No location selected for action.", Toast.LENGTH_SHORT).show()
            }
        }


        if (auth.currentUser != null) {
            fetchAndDisplayOwnerWorkInDrawer()
        } else {
            updateDrawerPlacesAddedUI(emptyList())
            updateDrawerFloorMapsAddedUI(emptyList())
            Toast.makeText(this, "Please login to manage locations.", Toast.LENGTH_LONG).show()
        }
    }

    // Renamed to avoid confusion with any AddDetailsActivity methods.
    private fun launchAddDetailsActivityInternal(locationType: String) {
        if (lastSearchedGeoPoint != null && lastSearchedDisplayName != null) {
            val intent = Intent(this, AddDetailsActivity::class.java).apply {
                putExtra(AddDetailsActivity.EXTRA_LOCATION_NAME, lastSearchedDisplayName)
                putExtra(AddDetailsActivity.EXTRA_LATITUDE, lastSearchedGeoPoint!!.latitude)
                putExtra(AddDetailsActivity.EXTRA_LONGITUDE, lastSearchedGeoPoint!!.longitude)
                putExtra(AddDetailsActivity.EXTRA_LOCATION_TYPE, locationType) // Make sure AddDetailsActivity handles this
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Cannot proceed: Location data is missing for AddDetailsActivity.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun requestLocationPermissionsAndGetCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            getCurrentLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission denied.", Toast.LENGTH_LONG).show()
                mapView?.controller?.setZoom(6.0)
                mapView?.controller?.setCenter(GeoPoint(20.5937, 78.9629))
            }
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationProvider?.lastLocation?.addOnSuccessListener { location: Location? ->
            if (location != null) {
                val currentLocationPoint = GeoPoint(location.latitude, location.longitude)
                mapView?.controller?.setZoom(15.0)
                mapView?.controller?.animateTo(currentLocationPoint)
                // Remove previous "You are here" markers specifically
                mapView?.overlays?.removeAll { it is Marker && it.title == "You are here" }
                val currentMarker = Marker(mapView).apply {
                    position = currentLocationPoint
                    title = "You are here" // Specific title for current location marker
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView?.overlays?.add(currentMarker)
                mapView?.invalidate()
            } else {
                Log.w(TAG, "Current location not found (fusedLocationProvider returned null).")
                // Toast.makeText(this@Owner, "Current location not found.", Toast.LENGTH_SHORT).show()
            }
        }?.addOnFailureListener { e->
            Log.e(TAG, "Failed to get current location.", e)
            // Toast.makeText(this@Owner, "Failed to get current location.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Ensure autocompleteTextView is not null and has a window token
        autocompleteTextView.windowToken?.let { token ->
            imm.hideSoftInputFromWindow(token, 0)
        }
        autocompleteTextView.clearFocus() // Also clear focus
    }

    private fun searchInDatabase(query: String, onResult: (Double?, Double?, String?) -> Unit) {
        val ownerId = auth.currentUser?.uid
        if (ownerId == null) {
            Log.d(TAG, "User not logged in. Proceeding to Nominatim search.")
            onResult(null, null, null) // Indicate not found in DB to trigger Nominatim
            return
        }
        Log.d(TAG, "Searching Firebase for '$query' by owner '$ownerId'")
        db.collection("locations")
            .whereEqualTo("ownerId", ownerId)
            .whereEqualTo("name", query) // Case-sensitive exact match
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val doc = documents.documents[0]
                    Log.i(TAG, "'$query' found in Firebase for owner '$ownerId'.")
                    onResult(doc.getDouble("latitude"), doc.getDouble("longitude"), doc.id)
                } else {
                    Log.i(TAG, "'$query' NOT found in Firebase for owner '$ownerId'.")
                    onResult(null, null, null) // Not found in DB
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error searching Firebase for '$query'", e)
                onResult(null, null, null) // Error, so effectively not found in DB
            }
    }

    private fun searchLocationWithNominatim(query: String) {
        Log.d(TAG, "Attempting Nominatim search for: '$query'")
        lifecycleScope.launch {
            try {
                // This calls your NominatimSearchHandler's method
                val result: NominatimSearchResult? = nominatimSearchHandler.searchLocationByName(query)

                // --- THIS IS THE KEY CHANGE ---
                // If result is null, it means NominatimSearchHandler determined no valid location was found.
                if (result != null) {
                    // Successfully found by Nominatim
                    Log.i(TAG, "Nominatim found: '${result.displayName}' at ${result.geoPoint}")
                    updateMapWithLocation(result.geoPoint, result.displayName)
                    Toast.makeText(this@Owner, "'${result.displayName}' found online.", Toast.LENGTH_SHORT).show()
                    locationSpecificButton.text = "Add details for '${result.displayName.take(20)}...'"
                    locationSpecificButton.visibility = View.VISIBLE
                    lastSearchedGeoPoint = result.geoPoint
                    lastSearchedDisplayName = result.displayName
                } else {
                    // Not found by Nominatim (handler returned null)
                    Log.w(TAG, "Nominatim found NO results for '$query'. Triggering custom add dialog.")
                    Toast.makeText(this@Owner, "'$query' not found online. Please add manually.", Toast.LENGTH_LONG).show()
                    locationSpecificButton.visibility = View.GONE // Hide button as no valid location is "selected"
                    lastSearchedGeoPoint = null // Clear any previous search point
                    lastSearchedDisplayName = query // Keep original query for dialog pre-fill
                    showAddCustomLocationDialog(query) // Your existing dialog function
                }
            } catch (e: Exception) {
                // Catch any unexpected exceptions during the Nominatim call or processing
                Log.e(TAG, "Unexpected error during Nominatim search for '$query'", e)
                Toast.makeText(this@Owner, "Error during public search. Please add manually.", Toast.LENGTH_LONG).show()
                locationSpecificButton.visibility = View.GONE
                lastSearchedGeoPoint = null
                lastSearchedDisplayName = query
                showAddCustomLocationDialog(query)
            }
        }
    }


    private fun updateMapWithLocation(geoPoint: GeoPoint, displayName: String) {
        // Remove previous search markers, but keep "You are here" marker if it exists
        mapView?.overlays?.removeAll { it is Marker && it.title != "You are here" }

        val marker = Marker(mapView).apply {
            position = geoPoint
            title = displayName // This title will be shown on marker tap
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // Optionally set a custom icon:
            // icon = resources.getDrawable(R.drawable.your_custom_marker_icon, theme)
        }
        mapView?.overlays?.add(marker)
        mapView?.controller?.animateTo(geoPoint)
        mapView?.controller?.setZoom(17.0) // Zoom in closer for specific locations
        mapView?.invalidate()
    }

    // Your existing dialog function - uses R.layout.dialog_add_location
    private fun showAddCustomLocationDialog(originalQuery: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_location, null)
        val editTextName = dialogView.findViewById<EditText>(R.id.editTextLocationName)
        val editTextLatitude = dialogView.findViewById<EditText>(R.id.editTextLatitude)
        val editTextLongitude = dialogView.findViewById<EditText>(R.id.editTextLongitude)

        editTextName.setText(originalQuery) // Pre-fill name with the search query

        // Pre-fill lat/lon only if lastSearchedGeoPoint was set from a *previous successful search*
        // If Nominatim failed (result == null), lastSearchedGeoPoint should have been cleared above.
        // However, your original logic kept it, so I'll maintain that for now.
        // For a clean "not found" scenario, you might want to ensure lastSearchedGeoPoint is null here.
        lastSearchedGeoPoint?.let {
            editTextLatitude.setText(it.latitude.toString())
            editTextLongitude.setText(it.longitude.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Location Not Found or Add New") // Your title
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editTextName.text.toString().trim()
                val latStr = editTextLatitude.text.toString().trim()
                val lonStr = editTextLongitude.text.toString().trim()

                if (validateCustomLocationInput(name, latStr, lonStr)) { // Your validation function
                    val latitude = latStr.toDouble()
                    val longitude = lonStr.toDouble()
                    val currentOwner = auth.currentUser
                    if (currentOwner == null) {
                        Toast.makeText(this, "You must be logged in to save locations.", Toast.LENGTH_LONG).show()
                        return@setPositiveButton // Keep dialog open if user not logged in
                    }
                    val ownerId = currentOwner.uid
                    val locationDataMap = hashMapOf(
                        "name" to name,
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "ownerId" to ownerId
                        // "nameLowercase" to name.lowercase() // Consider for case-insensitive search
                    )
                    db.collection("locations").add(locationDataMap)
                        .addOnSuccessListener { documentReference ->
                            Log.d(TAG, "Custom location '$name' saved successfully with ID: ${documentReference.id}")
                            Toast.makeText(this, "'$name' saved successfully!", Toast.LENGTH_SHORT).show()
                            val newPoint = GeoPoint(latitude, longitude)
                            updateMapWithLocation(newPoint, name) // Update map with newly saved location
                            locationSpecificButton.text = "Add Map for '$name'" // Update button text
                            locationSpecificButton.visibility = View.VISIBLE
                            lastSearchedGeoPoint = newPoint // Update last searched for consistency
                            lastSearchedDisplayName = name
                            fetchAndDisplayOwnerWorkInDrawer() // Refresh drawer with new location
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to save custom location '$name'", e)
                            Toast.makeText(this, "Failed to save '$name': ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                // Dialog dismisses automatically on positive button click if not prevented by return@setPositiveButton
            }
            .setNegativeButton("Cancel", null) // null listener means just dismiss
            .create() // Good practice to call create() before show()
            .show()
    }

    // Your validation function (ensure it's robust)
    private fun validateCustomLocationInput(name: String, latStr: String, lonStr: String): Boolean {
        if (name.isBlank()) {
            Toast.makeText(this, "Location name cannot be empty.", Toast.LENGTH_SHORT).show(); return false
        }
        if (latStr.isBlank() || lonStr.isBlank()){
            Toast.makeText(this, "Latitude and Longitude cannot be empty.", Toast.LENGTH_SHORT).show(); return false
        }
        try {
            val lat = latStr.toDouble()
            val lon = lonStr.toDouble()
            if (lat < -90.0 || lat > 90.0) { // Standard latitude range
                Toast.makeText(this, "Latitude must be between -90 and 90.", Toast.LENGTH_LONG).show(); return false
            }
            if (lon < -180.0 || lon > 180.0) { // Standard longitude range
                Toast.makeText(this, "Longitude must be between -180 and 180.", Toast.LENGTH_LONG).show(); return false
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(this, "Latitude/Longitude must be valid numbers.", Toast.LENGTH_SHORT).show(); return false
        }
        return true
    }

    private fun fetchAndDisplayOwnerWorkInDrawer() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            updateDrawerPlacesAddedUI(emptyList())
            updateDrawerFloorMapsAddedUI(emptyList())
            Log.d(TAG, "User not logged in, clearing drawer.")
            return
        }
        Log.d(TAG, "Fetching drawer content for user: $userId")

        ownerRegisteredLocationsList.clear() // Clear before fetching
        db.collection("locations").whereEqualTo("ownerId", userId).orderBy("name").get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Fetched ${documents.size()} places for drawer.")
                documents.forEach { doc ->
                    ownerRegisteredLocationsList.add(RegisteredLocationInfo(
                        doc.getString("name") ?: "N/A",
                        doc.getDouble("latitude") ?: 0.0, // Default to 0.0 if null
                        doc.getDouble("longitude") ?: 0.0, // Default to 0.0 if null
                        doc.id))
                }
                updateDrawerPlacesAddedUI(ownerRegisteredLocationsList)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching places for drawer", e)
                updateDrawerPlacesAddedUI(emptyList()) // Show empty on error
            }

        ownerUploadedFloorMapsList.clear() // Clear before fetching
        db.collection("uploadedFloorMaps").whereEqualTo("ownerId", userId).orderBy("locationName").get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Fetched ${documents.size()} floor maps for drawer.")
                documents.forEach { doc ->
                    ownerUploadedFloorMapsList.add(UploadedFloorMapInfo(
                        doc.getString("locationName") ?: "N/A",
                        doc.getDouble("latitude") ?: 0.0,
                        doc.getDouble("longitude") ?: 0.0,
                        doc.getString("mapImageUrl") ?: "", // Default to empty string if null
                        doc.id))
                }
                updateDrawerFloorMapsAddedUI(ownerUploadedFloorMapsList)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching floor maps for drawer", e)
                updateDrawerFloorMapsAddedUI(emptyList()) // Show empty on error
            }
    }

    private fun updateDrawerPlacesAddedUI(places: List<RegisteredLocationInfo>) {
        navPlacesAddedLayout.removeAllViews()
        if (places.isEmpty()) {
            navPlacesAddedLayout.addView(TextView(this).apply { text = "No places registered." })
            return
        }
        places.forEach { place ->
            navPlacesAddedLayout.addView(TextView(this).apply {
                text = "📍 ${place.name}\n   Lat: ${String.format("%.4f", place.latitude)}, Lon: ${String.format("%.4f", place.longitude)}"
                textSize = 15f; setPadding(16, 12, 16, 12) // Added some horizontal padding
                isClickable = true
                isFocusable = true
                // Example: Add a simple background selector for touch feedback
                // setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    mapView?.controller?.animateTo(GeoPoint(place.latitude, place.longitude))
                    mapView?.controller?.setZoom(17.0)
                    updateMapWithLocation(GeoPoint(place.latitude, place.longitude), place.name) // Ensure marker is updated
                    drawerLayout.closeDrawer(GravityCompat.START)
                    lastSearchedGeoPoint = GeoPoint(place.latitude, place.longitude)
                    lastSearchedDisplayName = place.name
                    locationSpecificButton.text = "Manage '${place.name.take(20)}...'" // Updated button text
                    locationSpecificButton.visibility = View.VISIBLE
                }
            })
        }
    }

    private fun updateDrawerFloorMapsAddedUI(floorMaps: List<UploadedFloorMapInfo>) {
        navFloorMapsAddedLayout.removeAllViews()
        if (floorMaps.isEmpty()) {
            navFloorMapsAddedLayout.addView(TextView(this).apply { text = "No floor maps uploaded." })
            return
        }
        floorMaps.forEach { floorMap ->
            navFloorMapsAddedLayout.addView(TextView(this).apply {
                text = "🗺️ Map for: ${floorMap.placeName}"
                textSize = 15f; setPadding(16, 12, 16, 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val intent = Intent(this@Owner, AddDetailsActivity::class.java).apply {
                        putExtra(AddDetailsActivity.EXTRA_LOCATION_NAME, floorMap.placeName)
                        putExtra(AddDetailsActivity.EXTRA_LATITUDE, floorMap.latitude)
                        putExtra(AddDetailsActivity.EXTRA_LONGITUDE, floorMap.longitude)
                        // If you want to pass more data for existing maps:
                        // putExtra(AddDetailsActivity.EXTRA_LOCATION_TYPE, "EXISTING_MAP_TYPE_IF_KNOWN")
                        // putExtra("existing_map_document_id", floorMap.id) // Example
                        // putExtra("existing_map_image_url", floorMap.mapImageUrl) // Example
                    }
                    startActivity(intent)
                    drawerLayout.closeDrawer(GravityCompat.START)
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume() // Resume osmdroid map
        if (auth.currentUser != null) {
            fetchAndDisplayOwnerWorkInDrawer() // Refresh drawer content
        }
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause() // Pause osmdroid map
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView?.onDetach() // Detach osmdroid map to prevent memory leaks
    }
}
