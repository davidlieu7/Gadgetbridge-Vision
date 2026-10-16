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
class GenericWeatherReceiverTest {
    @After
    fun clearWeather() = Weather.setWeatherSpec(emptyList())

    @Test
    fun importsHourlyFeelsLikeNumbersAndKeepsUnavailableValuesUnknown() {
        val intent = Intent(GenericWeatherReceiver.ACTION_GENERIC_WEATHER).putExtra(
            GenericWeatherReceiver.EXTRA_WEATHER_JSON,
            """{
              "currentTemp":293,"feelsLikeTemp":290,
              "hourly":[
                {"timestamp":1800000000,"temp":293,"feelsLikeTemp":273,"dewPoint":283},
                {"timestamp":1800003600,"temp":294,"feelsLikeTemp":268.0},
                {"timestamp":1800007200,"temp":295},
                {"timestamp":1800010800,"temp":296,"feelsLikeTemp":null},
                {"timestamp":1800014400,"temp":297,"feelsLikeTemp":"unavailable"}
              ]
            }"""
        )
        GenericWeatherReceiver().onReceive(RuntimeEnvironment.getApplication(), intent)
        val spec = requireNotNull(Weather.getWeatherSpec())
        assertEquals(290, spec.feelsLikeTemp)
        assertEquals(listOf(273, 268, 0, 0, 0), spec.hourly.map { it.feelsLikeTemp })
        assertEquals(283, spec.hourly.first().dewPoint)
    }
}
