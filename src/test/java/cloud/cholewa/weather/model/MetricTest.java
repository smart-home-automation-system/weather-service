package cloud.cholewa.weather.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MetricTest {

    //the 13 objects the WS90 sends, as the gateway TREADMILL listed them (HAS-234)
    @ParameterizedTest
    @CsvSource({
        "1, 0, BATTERY", "4, 0, PRESSURE", "5, 0, ILLUMINANCE", "8, 0, DEW_POINT", "12, 0, VOLTAGE",
        "32, 0, RAIN", "46, 0, HUMIDITY", "68, 0, WIND", "68, 1, GUST", "69, 0, TEMPERATURE",
        "70, 0, UV", "94, 0, WIND_DIRECTION", "95, 0, PRECIPITATION"
    })
    void should_know_every_object_of_the_station(final int objectId, final int index, final Metric metric) {
        assertThat(Metric.of(objectId, index)).contains(metric);
    }

    @Test
    void should_read_a_missing_index_as_the_first() {
        assertThat(Metric.of(0x44, null)).contains(Metric.WIND);
    }

    @Test
    void should_know_no_other_object() {
        assertThat(Metric.of(0x02, 0)).isEmpty();
        assertThat(Metric.of(0x44, 2)).isEmpty();
        assertThat(Metric.of(null, 0)).isEmpty();
    }

    @Test
    void should_have_rain_as_the_only_flag() {
        assertThat(Metric.values()).filteredOn(Metric::isFlag).containsExactly(Metric.RAIN);
    }

    //the names of the read API are wire contract: the dashboard and the blinds schedules ask by them
    @Test
    void should_keep_the_names_of_the_read_api() {
        assertThat(Metric.values()).extracting(Metric::apiName).containsExactly(
            "battery", "pressure", "illuminance", "dewPoint", "voltage", "raining", "humidity", "wind", "gust",
            "temperature", "uv", "windDirection", "precipitation");
    }

    //the history reads the column of each metric: every one has its own
    @Test
    void should_name_a_column_of_weather_reading_for_every_metric() {
        assertThat(Metric.values()).extracting(Metric::column).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(Metric.values()).filteredOn(Metric::isAngle).containsExactly(Metric.WIND_DIRECTION);
    }

    @ParameterizedTest
    @CsvSource({"dewPoint, DEW_POINT", "raining, RAIN", "windDirection, WIND_DIRECTION"})
    void should_find_a_metric_by_its_api_name(final String name, final Metric metric) {
        assertThat(Metric.ofApiName(name)).contains(metric);
    }

    @ParameterizedTest
    @CsvSource({"DEW_POINT", "dewpoint", "rain", "' temperature'", "''"})
    void should_find_no_metric_by_another_name(final String name) {
        assertThat(Metric.ofApiName(name)).isEmpty();
    }
}
