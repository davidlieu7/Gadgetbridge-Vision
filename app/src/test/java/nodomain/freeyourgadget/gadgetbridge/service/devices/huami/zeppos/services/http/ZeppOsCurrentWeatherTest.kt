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
import com.google.gson.JsonParser
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
class ZeppOsCurrentWeatherTest {
    private val hourStart = 1_800_000_000

    private fun weather() = WeatherSpec().apply {
        timestamp = hourStart + 900
        currentTemp = 293
        feelsLikeTemp = 290
        currentConditionCode = 800
        currentHumidity = 67
        pressure = 1018.5f
        uvIndex = 4.2f
        visibility = 12000f
        windSpeed = 18f
        windDirection = 225
        dewPoint = 283
        precipProbability = 80
        sunRise = hourStart - 3600
        sunSet = hourStart + 3600
    }

    private fun forecast(timestamp: Int) = WeatherSpec.Hourly().apply {
        this.timestamp = timestamp
        temp = 303
        conditionCode = 500
        humidity = 40
        pressure = 1000f
        uvIndex = 1f
        windSpeed = 5f
        windDirection = 90
        dewPoint = 0
        precipProbability = 25
    }

    @After
    fun clearWeather() = Weather.setWeatherSpec(emptyList())

    @Test
    fun currentObservationsOverrideCurrentHourForecastInBothProtocols() {
        val spec = weather().apply {
            hourly.add(forecast(hourStart - 3600))
            hourly.add(forecast(hourStart))
            hourly.add(forecast(hourStart + 3600))
        }
        for (version in listOf(5, 6)) {
            val hours = hours(spec, version)
            assertEquals(2, hours.size())
            val current = hours[0].asJsonObject
            assertEquals(hourStart.toLong(), epoch(current))
            assertEquals("0", current["conditionCode"].asString)
            assertEquals(20f, current["temperature"].asFloat, 0f)
            assertEquals(0.67f, current["humidity"].asFloat, 0.0001f)
            assertEquals(1018.5f, current["pressure"].asFloat, 0f)
            assertEquals(4, current["uvIndex"].asInt)
            assertEquals(12000f, current["visibility"].asFloat, 0f)
            assertEquals(18f, current["windSpeed"].asFloat, 0f)
            assertEquals(225, current["windDirection"].asInt)
            assertEquals(spec.windSpeedAsBeaufort(), current["windScale"].asInt)
            if (version == 6) {
                assertEquals(17f, current["temperatureApparent"].asFloat, 0f)
                assertEquals(10f, current["temperatureDewPoint"].asFloat, 0f)
                assertEquals(0.25f, current["precipitationChance"].asFloat, 0f)
                assertTrue(current["daylight"].asBoolean)
            }
            val next = hours[1].asJsonObject
            assertEquals((hourStart + 3600).toLong(), epoch(next))
            assertEquals(30f, next["temperature"].asFloat, 0f)
            assertEquals(0.4f, next["humidity"].asFloat, 0.0001f)
            assertEquals(1000f, next["pressure"].asFloat, 0f)
            assertEquals(1, next["uvIndex"].asInt)
            assertEquals(5f, next["windSpeed"].asFloat, 0f)
            assertEquals(90, next["windDirection"].asInt)
            if (version == 6) {
                assertTrue(next["temperatureApparent"].isJsonNull)
                assertTrue(next["temperatureDewPoint"].isJsonNull)
                assertEquals(0.25f, next["precipitationChance"].asFloat, 0f)
            }
        }
    }

    @Test
    fun missingCurrentHourAndUnknownTemperaturesRemainSupported() {
        val spec = weather().apply {
            feelsLikeTemp = 0
            dewPoint = 0
            hourly.add(forecast(0))
            hourly.add(forecast(hourStart - 3600))
            hourly.add(forecast(hourStart + 3600))
        }
        for (version in listOf(5, 6)) {
            val hours = hours(spec, version)
            assertEquals(2, hours.size())
            assertEquals(hourStart.toLong(), epoch(hours[0].asJsonObject))
            if (version == 6) {
                assertTrue(hours[0].asJsonObject["temperatureApparent"].isJsonNull)
                assertTrue(hours[0].asJsonObject["temperatureDewPoint"].isJsonNull)
                assertEquals(0.8f, hours[0].asJsonObject["precipitationChance"].asFloat, 0f)
            }
        }
    }

    @Test
    fun forecastsStayLimitedTo72HoursWithOrWithoutCurrentHourEntry() {
        for (includeCurrent in listOf(false, true)) {
            val spec = weather().apply {
                for (offset in (if (includeCurrent) 0 else 1)..80) {
                    hourly.add(forecast(hourStart + offset * 3600))
                }
            }
            for (version in listOf(5, 6)) {
                val hours = hours(spec, version)
                assertEquals(72, hours.size())
                assertEquals(hourStart.toLong(), epoch(hours[0].asJsonObject))
                assertEquals((hourStart + 71 * 3600).toLong(), epoch(hours[71].asJsonObject))
            }
        }
    }

    private fun hours(spec: WeatherSpec, version: Int): JsonArray {
        Weather.setWeatherSpec(listOf(spec))
        val query = mutableMapOf("datasets" to "hourlyWeather")
        val response = if (version == 5) {
            JsonParser.parseString(ZeppOsWeatherHandlerV5.handleHttpRequest("/weather/v5/en/0/0/", query)!!)
        } else {
            val bytes = ZeppOsWeatherHandlerV6.handleHttpRequest("/weather/v6/en/0/0/", query)!!
            toJson(CborDecoder(ByteArrayInputStream(bytes)).decode().single())
        }
        return response.asJsonObject["hourlyWeather"].asJsonObject["hours"].asJsonArray
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
