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
class WeatherSpecHourlyTest {
    @Test
    fun fullWeatherParcelPreservesDifferentHourlyFeelsLikeValues() {
        val spec = WeatherSpec().apply {
            hourly.add(hour(273)) // A valid 0 degrees Celsius, not missing
            hourly.add(hour(268))
            hourly.add(hour(0))
        }
        val parcel = Parcel.obtain()
        try {
            spec.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = WeatherSpec.CREATOR.createFromParcel(parcel)
            assertEquals(spec, restored)
            assertEquals(listOf(273, 268, 0), restored.hourly.map { it.feelsLikeTemp })
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun olderHourlyParcelsLeaveFeelsLikeUnknownWithoutReadingNextObject() {
        for (version in listOf(1, 2)) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(version)
                parcel.writeInt(1_800_000_000) // timestamp
                parcel.writeInt(293) // temp
                parcel.writeInt(800) // conditionCode
                parcel.writeInt(65) // humidity
                parcel.writeFloat(12f) // windSpeed
                parcel.writeInt(180) // windDirection
                parcel.writeFloat(3f) // uvIndex
                parcel.writeInt(20) // precipProbability
                if (version >= 2) {
                    parcel.writeInt(283) // dewPoint
                    parcel.writeFloat(1018f) // pressure
                    parcel.writeInt(45) // cloudCover
                }
                parcel.writeInt(0x12345678) // the following parcelled value
                parcel.setDataPosition(0)
                val restored = WeatherSpec.Hourly.CREATOR.createFromParcel(parcel)
                assertEquals(0, restored.feelsLikeTemp)
                assertEquals(293, restored.temp)
                assertEquals(if (version == 2) 283 else 0, restored.dewPoint)
                assertEquals(if (version == 2) 1018f else 0f, restored.pressure, 0f)
                assertEquals(0x12345678, parcel.readInt())
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun jsonCacheRoundTripPreservesFeelsLikeAndAcceptsOlderEntries() {
        val gson = Gson()
        val spec = WeatherSpec().apply { hourly.add(hour(268)) }
        val restored = gson.fromJson(gson.toJson(spec), WeatherSpec::class.java)
        assertEquals(268, restored.hourly.single().feelsLikeTemp)
        val old = gson.fromJson("""{"hourly":[{"temp":293,"dewPoint":283}]}""", WeatherSpec::class.java)
        assertEquals(0, old.hourly.single().feelsLikeTemp)
        assertEquals(283, old.hourly.single().dewPoint)
    }

    @Test
    fun hourlyEqualityIncludesFeelsLike() {
        assertEquals(hour(273), hour(273))
        assertEquals(hour(273).hashCode(), hour(273).hashCode())
        assertNotEquals(hour(273), hour(268))
        assertNotEquals(hour(273).hashCode(), hour(268).hashCode())
    }

    private fun hour(feelsLike: Int) = WeatherSpec.Hourly().apply {
        timestamp = 1_800_000_000
        temp = 293
        feelsLikeTemp = feelsLike
        dewPoint = 283
        pressure = 1018f
        cloudCover = 45
    }
}
