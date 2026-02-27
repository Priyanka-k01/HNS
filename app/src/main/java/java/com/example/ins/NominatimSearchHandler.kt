// NominatimSearchHandler.kt
package java.com.example.ins // Or your chosen package

import android.content.Context
import android.location.Address
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.util.Locale

// Data class to represent a search result
data class NominatimSearchResult(
    val geoPoint: GeoPoint,
    val displayName: String
)

class NominatimSearchHandler(private val userAgent: String) {

    private val geocoderNominatim = GeocoderNominatim(Locale.getDefault(), userAgent)

    /**
     * Searches for a location by name using Nominatim.
     * This function should be called from a coroutine (e.g., viewModelScope or lifecycleScope)
     * as it performs network operations.
     *
     * @param query The location name to search for.
     * @return NominatimSearchResult if found, null otherwise.
     */
    suspend fun searchLocationByName(query: String): NominatimSearchResult? {
        if (query.isBlank()) {
            return null
        }
        return withContext(Dispatchers.IO) { // Perform network operation on IO dispatcher
            try {
                // Get at most 1 result for a direct button search
                val addresses: List<Address>? = geocoderNominatim.getFromLocationName(query, 1)

                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    if (address.hasLatitude() && address.hasLongitude()) {
                        Log.d("NominatimSearchHandler", "Found: ${address.getAddressLine(0)}, Lat: ${address.latitude}, Lon: ${address.longitude}")
                        NominatimSearchResult(
                            GeoPoint(address.latitude, address.longitude),
                            address.getAddressLine(0) ?: query // Use the first address line or fallback to query
                        )
                    } else {
                        Log.w("NominatimSearchHandler", "Address found for '$query' but no lat/lon.")
                        null
                    }
                } else {
                    Log.d("NominatimSearchHandler", "No location found for query: $query")
                    null
                }
            } catch (e: IOException) {
                Log.e("NominatimSearchHandler", "Geocoding failed for query: $query", e)
                null // Network error or other IO issue
            } catch (e: Exception) {
                Log.e("NominatimSearchHandler", "An unexpected error occurred during geocoding for query: $query", e)
                null // Other unexpected errors
            }
        }
    }
}


