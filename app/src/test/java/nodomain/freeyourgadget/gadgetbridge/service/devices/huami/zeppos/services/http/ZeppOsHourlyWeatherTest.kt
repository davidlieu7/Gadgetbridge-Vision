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
class ZeppOsHourlyWeatherTest {
    private val hourStart = 1_800_000_000

    @After
    fun clearWeather() = Weather.setWeatherSpec(emptyList())

    @Test
    fun hourlyForecastsUseTheirOwnFeelsLikeIncludingZeroAndNegativeCelsius() {
        val spec = weather().apply {
            hourly.add(forecast(hourStart, 273))
            hourly.add(forecast(hourStart + 3600, 268))
            hourly.add(forecast(hourStart + 7200))
        }
        val response = hours(spec)
        assertEquals(3, response.size())
        val currentHour = response[0].asJsonObject
        // An existing current-hour forecast retains its original precedence over observations.
        assertEquals(30f, currentHour["temperature"].asFloat, 0f)
        assertEquals(0.4f, currentHour["humidity"].asFloat, 0.0001f)
        assertEquals(0f, currentHour["temperatureApparent"].asFloat, 0f)
        assertEquals(-5f, response[1].asJsonObject["temperatureApparent"].asFloat, 0f)
        assertTrue(response[2].asJsonObject["temperatureApparent"].isJsonNull)
    }

    @Test
    fun missingHourlyFeelsLikeDoesNotReuseTheCurrentObservation() {
        val spec = weather().apply {
            hourly.add(forecast(hourStart))
            hourly.add(forecast(hourStart + 3600))
        }
        assertTrue(hours(spec).all { it.asJsonObject["temperatureApparent"].isJsonNull })
    }

    @Test
    fun missingCurrentHourKeepsTheExistingObservationFallback() {
        val spec = weather().apply {
            hourly.add(forecast(0))
            hourly.add(forecast(hourStart - 3600))
            hourly.add(forecast(hourStart + 3600, 278))
        }
        val response = hours(spec)
        assertEquals(2, response.size())
        assertEquals(hourStart.toLong(), epoch(response[0].asJsonObject))
        assertEquals(20f, response[0].asJsonObject["temperature"].asFloat, 0f)
        assertEquals(17f, response[0].asJsonObject["temperatureApparent"].asFloat, 0f)
        assertEquals(5f, response[1].asJsonObject["temperatureApparent"].asFloat, 0f)
        spec.feelsLikeTemp = 0
        assertTrue(hours(spec)[0].asJsonObject["temperatureApparent"].isJsonNull)
    }

    @Test
    fun advancingObservationHourKeepsTheForecastFeelsLikeAvailable() {
        val spec = weather().apply {
            hourly.add(forecast(hourStart + 3600, 278))
            hourly.add(forecast(hourStart + 7200, 281))
        }
        assertEquals(5f, hours(spec)[1].asJsonObject["temperatureApparent"].asFloat, 0f)
        spec.timestamp += 3600
        spec.feelsLikeTemp = 280
        val response = hours(spec)
        assertEquals(2, response.size())
        assertEquals((hourStart + 3600).toLong(), epoch(response[0].asJsonObject))
        assertEquals(5f, response[0].asJsonObject["temperatureApparent"].asFloat, 0f)
        assertEquals(8f, response[1].asJsonObject["temperatureApparent"].asFloat, 0f)
    }

    @Test
    fun forecastLimitStaysAt72HoursWithOrWithoutCurrentHourForecast() {
        for (includeCurrent in listOf(false, true)) {
            val spec = weather().apply {
                for (offset in (if (includeCurrent) 0 else 1)..80) {
                    hourly.add(forecast(hourStart + offset * 3600, 278))
                }
            }
            val response = hours(spec)
            assertEquals(72, response.size())
            assertEquals(hourStart.toLong(), epoch(response[0].asJsonObject))
            assertEquals((hourStart + 71 * 3600).toLong(), epoch(response[71].asJsonObject))
        }
    }

    private fun weather() = WeatherSpec().apply {
        timestamp = hourStart + 900
        currentTemp = 293
        feelsLikeTemp = 290
        currentConditionCode = 800
        currentHumidity = 67
    }

    private fun forecast(timestamp: Int, feelsLike: Int = 0) = WeatherSpec.Hourly().apply {
        this.timestamp = timestamp
        temp = 303
        conditionCode = 500
        humidity = 40
        feelsLikeTemp = feelsLike
    }

    private fun hours(spec: WeatherSpec): JsonArray {
        Weather.setWeatherSpec(listOf(spec))
        val bytes = ZeppOsWeatherHandlerV6.handleHttpRequest(
            "/weather/v6/en/0/0/", mapOf("datasets" to "hourlyWeather")
        )!!
        return toJson(CborDecoder(ByteArrayInputStream(bytes)).decode().single())
            .asJsonObject["hourlyWeather"].asJsonObject["hours"].asJsonArray
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
