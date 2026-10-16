package nodomain.freeyourgadget.gadgetbridge.externalevents

import android.app.Application
import android.content.Intent
import nodomain.freeyourgadget.gadgetbridge.model.weather.Weather
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GenericWeatherReceiverAirQualityTest {
    @After
    fun clearWeather() = Weather.setWeatherSpec(emptyList())

    @Test
    fun importsUsEpaAqiSeparatelyFromPlumeForCurrentAndDailyWeather() {
        val intent = Intent(GenericWeatherReceiver.ACTION_GENERIC_WEATHER).putExtra(
            GenericWeatherReceiver.EXTRA_WEATHER_JSON,
            """{"airQuality":{"aqiUsEpa":31.0},
                "forecasts":[{"airQuality":{"aqiUsEpa":42}},{"airQuality":{"aqi":15}}]}"""
        )
        GenericWeatherReceiver().onReceive(RuntimeEnvironment.getApplication(), intent)
        val spec = requireNotNull(Weather.getWeatherSpec())
        assertEquals(31, spec.airQuality!!.aqiUsEpa)
        assertEquals(-1, spec.airQuality!!.aqi)
        assertEquals(42, spec.forecasts[0].airQuality!!.aqiUsEpa)
        assertEquals(15, spec.forecasts[1].airQuality!!.aqi)
        assertEquals(-1, spec.forecasts[1].airQuality!!.aqiUsEpa)
    }
}
