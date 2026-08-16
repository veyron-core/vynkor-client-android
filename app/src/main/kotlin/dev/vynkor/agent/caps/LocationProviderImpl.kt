package dev.vynkor.agent.caps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dev.vynkor.agent.Location
import dev.vynkor.agent.LocationProvider

/** Reads the last known location (fast, cache-only). Slow fixes arrive via
 * Agent.pushGeoUpdate from the caller. */
class LocationProviderImpl(context: Context) : LocationProvider {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fineGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    override fun lastKnown(): Location? {
        if (!fineGranted) return null
        for (provider in lm.getProviders(true)) {
            val fix = lm.getLastKnownLocation(provider) ?: continue
            return Location(
                lat = fix.latitude,
                lon = fix.longitude,
                accuracyM = fix.accuracy,
            )
        }
        return null
    }
}
