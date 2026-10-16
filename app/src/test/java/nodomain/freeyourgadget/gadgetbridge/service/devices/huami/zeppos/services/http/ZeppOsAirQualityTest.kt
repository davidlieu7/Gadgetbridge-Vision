package nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services.http

import android.app.Application
import co.nstant.`in`.cbor.CborDecoder
import co.nstant.`in`.cbor.model.DataItem
import co.nstant.`in`.cbor.model.DoublePrecisionFloat
import co.nstant.`in`.cbor.model.SimpleValue
import co.nstant.`in`.cbor.model.UnicodeString
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import co.nstant.`in`.cbor.model.Array as CborArray
import co.nstant.`in`.cbor.model.Map as CborMap
import co.nstant.`in`.cbor.model.Number as CborNumber

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ZeppOsAirQualityTest {
    private val hourStart = 1_800_000_000

    private fun weather() = WeatherSpec().apply {
        timestamp = hourStart + 900
    }

    @After
    fun clearWeather() = Weather.setWeatherSpec(emptyList())

    @Test
    fun usEpaIndexUsesItsOwnStandardCategoriesAndWeatherObservationHour() {
        val values = listOf(0, 31, 50, 51, 100, 101, 150, 151, 200, 201, 300, 301, 500, 999)
        val expected = listOf(0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5)
        values.zip(expected).forEach { (value, category) ->
            val spec = weather().apply {
                airQuality = WeatherSpec.AirQuality().apply {
                    aqi = 15 // must not override the explicitly supplied US index
                    aqiUsEpa = value
                }
            }
            val dataset = airQuality(spec)["hourlyAirQuality"].asJsonObject
            assertEquals("q_us-epa", dataset["code"].asString)
            assertEquals("AQI (US EPA)", dataset["standard"].asJsonObject["displayName"].asString)
            val hour = dataset["hours"].asJsonArray.single().asJsonObject
            assertEquals(value, hour["aqi"].asInt)
            assertEquals(category, hour["categoryIndex"].asInt)
            assertEquals(hourStart.toLong(), epoch(hour))
            assertTrue(hour["cursorPosition"].asInt in 0..100)
            if (value == 31) {
                assertEquals("Good", hour["category"].asString)
                assertEquals(0x00e400, hour["color"].asInt)
            }
        }
    }

    @Test
    fun legacyPlumeAndMissingAirQualityAreUnchanged() {
        val spec = weather().apply { airQuality = WeatherSpec.AirQuality().apply { aqi = 31 } }
        val dataset = airQuality(spec)["hourlyAirQuality"].asJsonObject
        assertEquals("a_glb-plume", dataset["code"].asString)
        val hour = dataset["hours"].asJsonArray.single().asJsonObject
        assertEquals("Fair", hour["category"].asString)
        assertEquals(hourStart.toLong(), epoch(hour))
        spec.airQuality = WeatherSpec.AirQuality()
        assertFalse(airQuality(spec).has("hourlyAirQuality"))
    }

    @Test
    fun dailyAirQualityDoesNotMixUsAndPlumeIndexes() {
        val spec = weather().apply {
            airQuality = WeatherSpec.AirQuality().apply { aqiUsEpa = 31 }
            forecasts.add(WeatherSpec.Daily().apply {
                airQuality = WeatherSpec.AirQuality().apply { aqiUsEpa = 42 }
            })
            forecasts.add(WeatherSpec.Daily().apply {
                airQuality = WeatherSpec.AirQuality().apply { aqi = 15 }
            })
        }
        val days = airQuality(spec)["dailyAirQuality"].asJsonObject["days"].asJsonArray
        assertEquals(listOf("31", "42"), days.map { it.asJsonObject["aqi"].asString })
    }

    private fun airQuality(spec: WeatherSpec): JsonObject {
        Weather.setWeatherSpec(listOf(spec))
        val bytes = ZeppOsWeatherHandlerV6.handleHttpRequest(
            "/weather/v6/en/0/0/", mapOf("datasets" to "hourlyAirQuality,dailyAirQuality")
        )!!
        return toJson(CborDecoder(ByteArrayInputStream(bytes)).decode().single()).asJsonObject
    }

    private fun epoch(hour: JsonObject) = OffsetDateTime.parse(hour["forecastStart"].asString).toEpochSecond()

    private fun toJson(item: DataItem): JsonElement = when (item) {
        is CborMap -> JsonObject().apply { item.keys.forEach { add((it as UnicodeString).string, toJson(item[it])) } }
        is CborArray -> JsonArray().apply { item.dataItems.forEach { add(toJson(it)) } }
        is UnicodeString -> JsonPrimitive(item.string)
        is CborNumber -> JsonPrimitive(item.value)
        is DoublePrecisionFloat -> JsonPrimitive(item.value)
        SimpleValue.TRUE -> JsonPrimitive(true)
        SimpleValue.FALSE -> JsonPrimitive(false)
        SimpleValue.NULL -> JsonNull.INSTANCE
        else -> error("Unexpected CBOR item: $item")
    }
}
