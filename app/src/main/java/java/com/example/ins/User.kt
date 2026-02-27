package java.com.example.ins

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*
import com.google.firebase.Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
//import com.google.firebase.firestore.ktx.firestore
//import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

class User : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mapView: MapView
    private lateinit var autocompleteTextView: AutoCompleteTextView
    private lateinit var btnSearchLocation: Button
    private lateinit var btnStartNavigation: Button
    private lateinit var btnIndoorNavigation: Button
    private lateinit var tvDistanceToDestination: TextView

    private lateinit var firestore: FirebaseFirestore

    private var currentLocation: GeoPoint? = null
    private val fusedLocationProviderClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private var currentRoad: Road? = null
    private var currentStepIndex = 0
    private var tts: TextToSpeech? = null

    private var roadOverlayCasing: Polyline? = null
    private var roadOverlayTop: Polyline? = null

    private var currentSearchedDestinationName: String? = null
    private var currentSearchedDestinationGeoPoint: GeoPoint? = null
    private var currentIndoorMapImageUrl: String? = null // For storing retrieved indoor map URL
    private var currentIndoorMapLocationId: String? = null // <-- ADD THIS LINE
    private var autocompleteJob: Job? = null
    private val debouncePeriod = 500L

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val TAG = "UserActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_user)

        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        // It's good practice to set a user agent for network requests, osmdroid requires it.
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        autocompleteTextView = findViewById(R.id.autocomplete)
        btnSearchLocation = findViewById(R.id.btn_s_loc)
        btnStartNavigation = findViewById(R.id.btn_start_navigation)
        btnIndoorNavigation = findViewById(R.id.btn_indoor_navigation)
        mapView = findViewById(R.id.U_mapViewUser)
        tvDistanceToDestination = findViewById(R.id.tv_distance_to_destination)

        firestore = Firebase.firestore

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0) // Default zoom

        // Initially hide navigation-related buttons
        btnStartNavigation.visibility = View.GONE
        btnIndoorNavigation.visibility = View.GONE
        tvDistanceToDestination.visibility = View.GONE

        tts = TextToSpeech(this, this)

        requestLocationPermissionsAndGetCurrentLocation()
        setupAutoCompleteSearchWithNameFieldStrategy() // Using exact name strategy

        btnSearchLocation.setOnClickListener {
            val destinationQuery = autocompleteTextView.text.toString().trim()
            if (destinationQuery.isNotEmpty()) {
                hideKeyboard()
                clearMapOverlaysForNewSearch()
                searchFirestoreFirstByExactName(destinationQuery) // Main search entry point
            } else {
                Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartNavigation.setOnClickListener {
            if (btnStartNavigation.text.toString().equals("Stop Navigation", ignoreCase = true)) {
                stopOutdoorNavigation()
            } else if (currentRoad != null && currentLocation != null && currentSearchedDestinationGeoPoint != null) {
                currentStepIndex = 0
                requestLocationUpdatesForOutdoorNavigation()
                Toast.makeText(this, "Outdoor navigation started", Toast.LENGTH_SHORT).show()
                btnStartNavigation.text = "Stop Navigation"
                btnIndoorNavigation.visibility = View.GONE // Hide indoor button during outdoor navigation
                currentRoad?.mLength?.let { initialDistance ->
                    tvDistanceToDestination.text = String.format(Locale.getDefault(), "Distance: %.2f km", initialDistance)
                    tvDistanceToDestination.visibility = View.VISIBLE
                }
            } else {
                Toast.makeText(this, "Route or location not available to start navigation.", Toast.LENGTH_SHORT).show()
            }
        }

        btnIndoorNavigation.setOnClickListener {
            if (currentSearchedDestinationName != null &&
                currentSearchedDestinationGeoPoint != null &&
                currentIndoorMapImageUrl != null) { // Check if URL was successfully retrieved

                val intent = Intent(this, IndoorNavigationActivity::class.java).apply {
                    putExtra("destination_name", currentSearchedDestinationName)
                    putExtra("latitude", currentSearchedDestinationGeoPoint!!.latitude)
                    putExtra("longitude", currentSearchedDestinationGeoPoint!!.longitude)
                    putExtra("map_image_url", currentIndoorMapImageUrl) // Pass the specific map image URL
                    putExtra(IndoorNavigationActivity.EXTRA_LOCATION_DOC_ID, currentIndoorMapLocationId)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Indoor map data is not fully available to start.", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Indoor Nav Button Click: Data incomplete. Name: $currentSearchedDestinationName, GeoPoint: $currentSearchedDestinationGeoPoint, ImageUrl: $currentIndoorMapImageUrl")
            }
        }
    }

    private fun setupAutoCompleteSearchWithNameFieldStrategy() {
        autocompleteTextView.threshold = 2 // Start suggesting after 2 characters

        autocompleteTextView.setOnItemClickListener { parent, _, position, _ ->
            val selectedPlace = parent.getItemAtPosition(position).toString()
            autocompleteTextView.setText(selectedPlace, false) // false to not trigger text changed listener again
            hideKeyboard()
            clearMapOverlaysForNewSearch()
            searchFirestoreFirstByExactName(selectedPlace) // Use the exact name search
        }

        autocompleteTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                autocompleteJob?.cancel() // Cancel previous job if user is still typing
                val query = s.toString().trim()

                if (query.length < autocompleteTextView.threshold) {
                    runOnUiThread { autocompleteTextView.setAdapter(null) } // Clear suggestions
                    return
                }

                autocompleteJob = CoroutineScope(Dispatchers.Main).launch {
                    delay(debouncePeriod) // Wait for user to stop typing
                    if (isActive) { // Check if job wasn't cancelled by newer input
                        Log.d(TAG, "Fetching combined suggestions (name field strategy) for: $query")
                        fetchCombinedSuggestionsUsingNameFieldStrategy(query)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // Autocomplete strategy using "name" field (case-sensitive "starts-with")
    private suspend fun fetchCombinedSuggestionsUsingNameFieldStrategy(query: String) {
        val firestoreSuggestions = mutableListOf<String>()
        val geocoderSuggestions = mutableListOf<String>()

        // 1. Fetch from Firestore using "name" (case-sensitive "starts-with")
        try {
            Log.d(TAG, "Querying Firestore suggestions for '$query' on 'name' field")
            // Requires an ASCENDING index on the "name" field in "locations" collection.
            val firestoreQuerySnapshot = firestore.collection("locations")
                .orderBy("name") // Order by the 'name' field
                .startAt(query)  // Case-sensitive starts-with
                .endAt(query + '\uf8ff') // Standard way to do "starts-with" in Firestore
                .limit(3) // Limit Firestore results for autocomplete
                .get()
                .await() // Using KTX await for coroutines

            for (document in firestoreQuerySnapshot.documents) {
                document.getString("name")?.let { firestoreSuggestions.add(it) }
            }
            Log.d(TAG, "Firestore suggestions (name field) for '$query': ${firestoreSuggestions.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Firestore suggestions (name field) for '$query'", e)
        }

        // 2. Fetch from Geocoder
        try {
            val geocoder = Geocoder(this@User, Locale.getDefault())
            val addresses: List<Address>? = withContext(Dispatchers.IO) {
                try {
                    geocoder.getFromLocationName(query, 3) // Limit Geocoder results
                } catch (ioe: IOException) {
                    Log.e(TAG, "Geocoder IO Exception in suggestions for '$query'", ioe)
                    null
                } catch (iae: IllegalArgumentException) {
                    Log.e(TAG, "Geocoder Illegal Argument in suggestions for '$query'", iae)
                    null
                }
            }
            addresses?.mapNotNullTo(geocoderSuggestions) { it.getAddressLine(0) }
            Log.d(TAG, "Geocoder suggestions for '$query': ${geocoderSuggestions.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Geocoder suggestions for '$query'", e)
        }

        // 3. Combine and display suggestions
        val combinedSuggestions = (firestoreSuggestions + geocoderSuggestions)
            .distinctBy { it.lowercase() } // Make distinct based on lowercase to avoid "Place" and "place"
            .take(5) // Show top 5 distinct results

        Log.d(TAG, "Combined suggestions (name field strategy) for '$query': $combinedSuggestions")

        if (coroutineContext.isActive) { // Check if the coroutine is still active
            runOnUiThread {
                if (autocompleteTextView.text.toString().trim() == query) { // Ensure query hasn't changed
                    if (combinedSuggestions.isNotEmpty()) {
                        val adapter = ArrayAdapter(
                            this@User,
                            android.R.layout.simple_dropdown_item_1line,
                            combinedSuggestions
                        )
                        autocompleteTextView.setAdapter(adapter)
                        if (autocompleteTextView.isFocused) { // Only show dropdown if view has focus
                            autocompleteTextView.showDropDown()
                        }
                    } else {
                        autocompleteTextView.setAdapter(null) // Clear suggestions if none found
                    }
                } else {
                    Log.d(TAG, "Query changed (name field strategy), not updating adapter for old query '$query'")
                }
            }
        }
    }

    private fun requestLocationPermissionsAndGetCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            fetchLastKnownLocation()
        }
    }

    private fun fetchLastKnownLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return // Permissions not granted
        }
        fusedLocationProviderClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLocation = GeoPoint(location.latitude, location.longitude)
                mapView.controller.setCenter(currentLocation)
                mapView.controller.setZoom(16.0)
                addOrUpdateMarker(currentLocation!!, "You are here", true) // Default marker
                mapView.invalidate()
            } else {
                Toast.makeText(this, "Unable to get current location. Enable location services.", Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Failed to get current location: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Main search function - queries "name" field case-sensitively
    private fun searchFirestoreFirstByExactName(query: String) {
        Log.d(TAG, "Attempting Firestore search (EXACT NAME) for destination: '$query'")
        currentIndoorMapImageUrl = null // Reset indoor map URL for new search
        btnIndoorNavigation.visibility = View.GONE // Hide button initially

        firestore.collection("locations") // YOUR "locations" COLLECTION
            .whereEqualTo("name", query) // CASE-SENSITIVE search on "name" field
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Firestore (Exact Name) search for '$query' returned ${documents.size()} documents.")
                if (!documents.isEmpty) {
                    val doc = documents.first()
                    Log.d(TAG, "Document found (Exact Name): ID=${doc.id}, Data=${doc.data}")

                    val name = doc.getString("name")
                    val lat = doc.getDouble("latitude")
                    val lon = doc.getDouble("longitude")

                    if (name != null && lat != null && lon != null) {
                        Log.i(TAG, "SUCCESS (Exact Name): Valid data found for '$name' in Firestore at $lat, $lon.")
                        val destGeoPoint = GeoPoint(lat, lon)
                        currentSearchedDestinationGeoPoint = destGeoPoint
                        currentSearchedDestinationName = name

                        addOrUpdateMarker(destGeoPoint, "Destination: $name", false) // Default marker
                        checkIfIndoorMapAvailable(name, destGeoPoint) // Check for associated indoor map

                        if (currentLocation != null) {
                            calculateAndDisplayRoute(currentLocation!!, destGeoPoint)
                        } else {
                            Toast.makeText(this, "Current location not available to calculate route.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.w(TAG, "Firestore doc (Exact Name) for '$query' found, BUT data incomplete (name, lat, or lon is null). Falling back to Geocoder.")
                        findRouteToDestinationWithGeocoder(query)
                    }
                } else {
                    Log.d(TAG, "INFO (Exact Name): '$query' NOT found in Firestore 'locations'. Falling back to Geocoder.")
                    findRouteToDestinationWithGeocoder(query)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore (Exact Name) search for '$query' FAILED.", e)
                Toast.makeText(this, "Error searching your saved places. Trying public search.", Toast.LENGTH_SHORT).show()
                findRouteToDestinationWithGeocoder(query)
            }
    }

    private fun findRouteToDestinationWithGeocoder(destinationQuery: String) {
        Log.d(TAG, "Fallback: Searching Geocoder for destination: '$destinationQuery'")
        currentIndoorMapImageUrl = null // Reset indoor map URL for Geocoder search
        btnIndoorNavigation.visibility = View.GONE // Hide button

        if (currentLocation == null) {
            Toast.makeText(this, "Waiting for current location to search Geocoder...", Toast.LENGTH_SHORT).show()
            fetchLastKnownLocation() // Attempt to get location again
            return
        }

        Thread {
            val geocoder = Geocoder(this, Locale.getDefault())
            try {
                val addresses: List<Address>? = geocoder.getFromLocationName(destinationQuery, 1)
                if (addresses != null && addresses.isNotEmpty()) {
                    val destinationAddress = addresses[0]
                    val destGeoPoint = GeoPoint(destinationAddress.latitude, destinationAddress.longitude)
                    val destName = destinationAddress.getAddressLine(0) ?: destinationQuery

                    currentSearchedDestinationGeoPoint = destGeoPoint
                    currentSearchedDestinationName = destName

                    runOnUiThread {
                        Log.i(TAG, "Geocoder found '$destName'.")
                        addOrUpdateMarker(destGeoPoint, "Destination: $destName", false) // Default marker
                        calculateAndDisplayRoute(currentLocation!!, destGeoPoint)
                        // Check for indoor map even if found by Geocoder,
                        // if the name matches one in your "uploadedFloorMaps"
                        checkIfIndoorMapAvailable(destName, destGeoPoint)
                    }
                } else {
                    runOnUiThread {
                        Log.w(TAG, "Destination '$destinationQuery' not found by Geocoder.")
                        Toast.makeText(this, "Destination '$destinationQuery' not found by public search.", Toast.LENGTH_LONG).show()
                        btnStartNavigation.visibility = View.GONE
                        // btnIndoorNavigation is already GONE
                        tvDistanceToDestination.visibility = View.GONE
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Geocoder findRoute IOException for '$destinationQuery'", e)
                runOnUiThread { Toast.makeText(this, "Public search error (Geocoder). Check network.", Toast.LENGTH_SHORT).show() }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Geocoder findRoute IllegalArgumentException for '$destinationQuery'", e)
                runOnUiThread { Toast.makeText(this, "Invalid location name for public search.", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun calculateAndDisplayRoute(startPoint: GeoPoint, endPoint: GeoPoint) {
        Log.d(TAG, "Calculating route from $startPoint to $endPoint")
        Thread {
            val roadManager = OSRMRoadManager(this, BuildConfig.APPLICATION_ID)
            roadManager.setMean(OSRMRoadManager.MEAN_BY_FOOT) // For walking directions
            val waypoints = arrayListOf(startPoint, endPoint)
            val road = roadManager.getRoad(waypoints)

            runOnUiThread {
                // Clear previous route lines before adding new ones
                if (roadOverlayCasing != null) mapView.overlays.remove(roadOverlayCasing)
                if (roadOverlayTop != null) mapView.overlays.remove(roadOverlayTop)
                roadOverlayCasing = null
                roadOverlayTop = null

                if (road.mStatus == Road.STATUS_OK && road.mNodes.isNotEmpty()) {
                    currentRoad = road
                    Log.i(TAG, "Route calculated successfully by OSRM. Length: ${road.mLength} km, Nodes: ${road.mNodes.size}")

                    // Create casing for the polyline (wider, lighter color for border effect)
                    roadOverlayCasing = Polyline().apply {
                        setPoints(road.mNodes.map { it.mLocation })
                        outlinePaint.color = Color.parseColor("#8076D7EA") // Light blue, semi-transparent
                        outlinePaint.strokeWidth = 22f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    mapView.overlays.add(roadOverlayCasing)

                    // Create the main polyline (narrower, solid color)
                    roadOverlayTop = Polyline().apply {
                        setPoints(road.mNodes.map { it.mLocation })
                        outlinePaint.color = Color.parseColor("#FF007BFF") // Solid blue
                        outlinePaint.strokeWidth = 15f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    mapView.overlays.add(roadOverlayTop)

                    mapView.invalidate()
                    // Zoom to fit the route
                    if (road.mBoundingBox != null) {
                        mapView.zoomToBoundingBox(road.mBoundingBox, true, 150) // Animate with padding
                    } else if (road.mNodes.isNotEmpty()){
                        mapView.controller.animateTo(road.mNodes.first().mLocation) // Fallback to start of route
                    }

                    val distance = road.mLength // OSRM returns distance in km
                    tvDistanceToDestination.text = String.format(Locale.getDefault(), "Distance: %.2f km", distance)
                    tvDistanceToDestination.visibility = View.VISIBLE
                    btnStartNavigation.visibility = View.VISIBLE
                    btnStartNavigation.text = "Start Navigation"
                } else {
                    Log.e(TAG, "Error finding route: OSRM status ${road.mStatus}")
                    Toast.makeText(this, "Error finding route (OSRM Status: ${road.mStatus})", Toast.LENGTH_SHORT).show()
                    tvDistanceToDestination.visibility = View.GONE
                    btnStartNavigation.visibility = View.GONE
                }
            }
        }.start()
    }

    // Uses default osmdroid marker as marker.icon is not set
    private fun addOrUpdateMarker(geoPoint: GeoPoint, titleForType: String, isUserLocation: Boolean) {
        val markerIdentifier = if (isUserLocation) "You are here" else "Destination"

        // Remove previous marker of the same identifier to prevent duplicates
        mapView.overlays.removeAll { anOverlay ->
            anOverlay is Marker && anOverlay.title == markerIdentifier
        }

        val marker = Marker(mapView).apply {
            position = geoPoint
            this.title = markerIdentifier // Used for identification and removal
            subDescription = titleForType // Actual display name for info window or accessibility
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            // No marker.icon = ... line, so osmdroid default is used
        }
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    // Checks for indoor map using exact "locationName" (case-sensitive)
    private fun checkIfIndoorMapAvailable(destinationName: String?, destinationGeoPoint: GeoPoint) {
        val nameToCheck = destinationName ?: run {
            Log.w(TAG, "Destination name is null for indoor check. Hiding button.")
            btnIndoorNavigation.visibility = View.GONE
            currentIndoorMapImageUrl = null // Reset
            return
        }
        Log.d(TAG, "Checking for indoor map for (exact name): '$nameToCheck'")
        currentIndoorMapImageUrl = null // Reset before new check

        firestore.collection("uploadedFloorMaps") // YOUR "uploadedFloorMaps" COLLECTION
            .whereEqualTo("locationName", nameToCheck) // CASE-SENSITIVE match on "locationName"
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Indoor map check for '$nameToCheck' found ${documents.size()} documents.")
                // Inside checkIfIndoorMapAvailable -> addOnSuccessListener
                if (!documents.isEmpty) {
                    val mapDoc = documents.first()
                    val imageUrl = mapDoc.getString("mapImageUrl")
                    if (!imageUrl.isNullOrEmpty()) {
                        Log.i(TAG, "Indoor map FOUND for '$nameToCheck'. Image URL: $imageUrl. Storing doc ID: ${mapDoc.id}")

                        // --- THIS IS THE FIX ---
                        currentIndoorMapImageUrl = imageUrl
                        currentIndoorMapLocationId = mapDoc.id // <-- STORE THE DOCUMENT ID
                        // -----------------------

                        btnIndoorNavigation.visibility = View.VISIBLE
                    } else {
                        Log.w(TAG, "Indoor map document found for '$nameToCheck', but 'mapImageUrl' is missing or empty.")
                        btnIndoorNavigation.visibility = View.GONE
                        currentIndoorMapLocationId = null // Reset on failure
                    }
                } else {
                    Log.d(TAG, "No indoor maps found in 'uploadedFloorMaps' for '$nameToCheck'. Hiding button.")
                    btnIndoorNavigation.visibility = View.GONE
                    currentIndoorMapLocationId = null // Reset on failure
                }

            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore query FAILED for indoor map check on '$nameToCheck'", e)
                btnIndoorNavigation.visibility = View.GONE
            }
    }

    private fun requestLocationUpdatesForOutdoorNavigation() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000) // Update every 3 seconds
            .setMinUpdateIntervalMillis(1500) // Fastest update 1.5 seconds
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required for navigation.", Toast.LENGTH_SHORT).show()
            return
        }
        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        Log.d(TAG, "Requested location updates for outdoor navigation.")
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                currentLocation = GeoPoint(location.latitude, location.longitude)
                addOrUpdateMarker(currentLocation!!, "You are here", true) // Default marker

                currentSearchedDestinationGeoPoint?.let { destPoint ->
                    val distanceToDest = currentLocation!!.distanceToAsDouble(destPoint) / 1000.0 // in km
                    tvDistanceToDestination.text = String.format(Locale.getDefault(), "Distance: %.2f km", distanceToDest)
                    if (tvDistanceToDestination.visibility == View.GONE) tvDistanceToDestination.visibility = View.VISIBLE

                    // Check for arrival
                    if (distanceToDest * 1000 < 30) { // Within 30 meters
                        Toast.makeText(this@User, "You have arrived at your destination.", Toast.LENGTH_LONG).show()
                        speakInstruction("You have arrived at your destination.")
                        stopOutdoorNavigation()
                        tvDistanceToDestination.text = "Arrived at destination"
                        // Re-check for indoor map as user has arrived
                        currentSearchedDestinationName?.let { name -> checkIfIndoorMapAvailable(name, destPoint) }
                        return@let
                    }
                }

                // Update route based on current location (for next step instructions)
                currentRoad?.let { road ->
                    if (currentStepIndex < road.mNodes.size) {
                        val nextStepNode = road.mNodes[currentStepIndex]
                        val distanceToNextStep = currentLocation!!.distanceToAsDouble(nextStepNode.mLocation) // in meters
                        if (distanceToNextStep < 25) { // Threshold for next instruction (e.g., 25 meters)
                            val instruction = nextStepNode.mInstructions
                            if (!instruction.isNullOrEmpty()) {
                                speakInstruction(instruction)
                            }
                            currentStepIndex++
                            updateRouteOverlayAfterStep() // Redraw remaining route
                        }
                    }
                }
            }
        }
    }

    private fun stopOutdoorNavigation() {
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        btnStartNavigation.text = "Start Navigation" // Reset button text
        Log.d(TAG, "Outdoor navigation stopped. Location updates removed.")
        // Optionally, re-check for indoor map availability when navigation stops
        if (currentSearchedDestinationName != null && currentSearchedDestinationGeoPoint != null) {
            checkIfIndoorMapAvailable(currentSearchedDestinationName!!, currentSearchedDestinationGeoPoint!!)
        }
    }

    private fun updateRouteOverlayAfterStep() {
        currentRoad?.let { road ->
            // Clear previous route segments
            if (roadOverlayCasing != null) mapView.overlays.remove(roadOverlayCasing)
            if (roadOverlayTop != null) mapView.overlays.remove(roadOverlayTop)
            roadOverlayCasing = null
            roadOverlayTop = null

            // Get remaining nodes from current step onwards
            val remainingNodes = road.mNodes.drop(currentStepIndex)
            val points = mutableListOf<GeoPoint>()
            currentLocation?.let { points.add(it) } // Start updated route from current location
            points.addAll(remainingNodes.map { node -> node.mLocation })

            if (points.size > 1) { // Need at least two points to draw a polyline
                // Re-add casing
                roadOverlayCasing = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = Color.parseColor("#8076D7EA")
                    outlinePaint.strokeWidth = 22f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
                mapView.overlays.add(roadOverlayCasing)
                // Re-add top line
                roadOverlayTop = Polyline().apply {
                    setPoints(points)
                    outlinePaint.color = Color.parseColor("#FF007BFF")
                    outlinePaint.strokeWidth = 15f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
                mapView.overlays.add(roadOverlayTop)
            }
            mapView.invalidate()
        }
    }

    private fun speakInstruction(instruction: String) {
        Toast.makeText(this, instruction, Toast.LENGTH_SHORT).show() // Show instruction as Toast
        tts?.speak(instruction, TextToSpeech.QUEUE_ADD, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) // Set language for TTS
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS: Language specified is not supported!")
            } else {
                Log.i(TAG, "TTS initialized successfully.")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed! Status: $status")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Location permission granted after request.")
                fetchLastKnownLocation()
            } else {
                Log.w(TAG, "Location permission denied after request.")
                Toast.makeText(this, "Location permission is required for map features.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus ?: View(this) // If no view currently has focus, create a new one
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        autocompleteTextView.clearFocus() // Important to remove focus from AutoCompleteTextView
    }

    private fun clearMapOverlaysForNewSearch() {
        Log.d(TAG, "Clearing map overlays for new search.")
        currentRoad = null // Clear current route data
        currentIndoorMapImageUrl = null // Reset indoor map URL

        // Remove route polylines
        if (roadOverlayCasing != null) mapView.overlays.remove(roadOverlayCasing)
        if (roadOverlayTop != null) mapView.overlays.remove(roadOverlayTop)
        roadOverlayCasing = null
        roadOverlayTop = null

        // Remove destination markers (user marker is updated by addOrUpdateMarker)
        mapView.overlays.removeAll { anOverlay ->
            anOverlay is Marker && anOverlay.title != null && anOverlay.title.startsWith("Destination")
        }
        mapView.invalidate() // Refresh the map display

        // Hide navigation buttons
        btnStartNavigation.visibility = View.GONE
        btnIndoorNavigation.visibility = View.GONE
        tvDistanceToDestination.visibility = View.GONE

        // If outdoor navigation was active, stop it
        if (btnStartNavigation.text.toString().equals("Stop Navigation", ignoreCase = true)) {
            stopOutdoorNavigation()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume() // Resume osmdroid map
        // If navigation was ongoing and app was paused, optionally resume location updates
        if (btnStartNavigation.text.toString().equals("Stop Navigation", ignoreCase = true) && currentLocation != null) {
            Log.d(TAG, "Resuming navigation location updates on onResume.")
            requestLocationUpdatesForOutdoorNavigation()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause() // Pause osmdroid map
        // Always pause location updates when the activity is not visible to save battery
        fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates paused in onPause.")
    }

    override fun onDestroy() {
        // Release TTS resources
        tts?.stop()
        tts?.shutdown()
        // Detach osmdroid map to prevent memory leaks
        mapView.onDetach()
        // Cancel any pending autocomplete jobs
        autocompleteJob?.cancel()
        Log.d(TAG, "UserActivity onDestroy called, resources released.")
        super.onDestroy()
    }
}
