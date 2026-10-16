package nodomain.freeyourgadget.gadgetbridge.model

import android.app.Application
import android.os.Parcel
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WeatherSpecAirQualityTest {
    @Test
    fun parcelRoundTripPreservesBothIndexes() {
        val spec = WeatherSpec().apply {
            airQuality = WeatherSpec.AirQuality().apply {
                aqi = 15
                aqiUsEpa = 31
            }
            forecasts.add(WeatherSpec.Daily().apply {
                airQuality = WeatherSpec.AirQuality().apply { aqiUsEpa = 42 }
            })
        }
        val parcel = Parcel.obtain()
        try {
            spec.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = WeatherSpec.CREATOR.createFromParcel(parcel)
            assertEquals(spec, restored)
            assertEquals(spec.hashCode(), restored.hashCode())
            assertEquals(31, restored.airQuality!!.aqiUsEpa)
            assertEquals(42, restored.forecasts.single().airQuality!!.aqiUsEpa)
        } finally { parcel.recycle() }
    }

    @Test
    fun readsLegacyParcelWithoutConsumingFollowingValue() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(1)
            parcel.writeInt(15)
            repeat(6) { parcel.writeFloat(-1f) }
            repeat(6) { parcel.writeInt(-1) }
            parcel.writeInt(0x12345678)
            parcel.setDataPosition(0)
            val aqi = WeatherSpec.AirQuality.CREATOR.createFromParcel(parcel)
            assertEquals(15, aqi.aqi)
            assertEquals(-1, aqi.aqiUsEpa)
            assertEquals(0x12345678, parcel.readInt())
        } finally { parcel.recycle() }
    }

    @Test
    fun jsonCachePreservesNewFieldsAndKeepsLegacyIndexSeparate() {
        val gson = Gson()
        val aqi = WeatherSpec.AirQuality().apply { aqiUsEpa = 31 }
        val restored = gson.fromJson(gson.toJson(aqi), WeatherSpec.AirQuality::class.java)
        assertEquals(aqi, restored)
        val old = gson.fromJson("""{"aqi":15}""", WeatherSpec.AirQuality::class.java)
        assertEquals(15, old.aqi)
        assertEquals(-1, old.aqiUsEpa)
        assertNotEquals(old, aqi)
    }
}
