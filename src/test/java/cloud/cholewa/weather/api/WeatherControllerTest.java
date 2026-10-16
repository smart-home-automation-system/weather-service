package cloud.cholewa.weather.api;

import cloud.cholewa.weather.api.model.CurrentMetric;
import cloud.cholewa.weather.api.model.StationStatus;
import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.config.ExceptionHandlerConfig;
import cloud.cholewa.weather.current.CurrentWeatherQuery;
import cloud.cholewa.weather.error.WeatherException;
import cloud.cholewa.weather.history.WeatherHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

//The JSON is the contract of the dashboard, the blinds schedules and the overview, so the bodies are
//written out whole
@WebFluxTest(WeatherController.class)
@Import(ExceptionHandlerConfig.class)
class WeatherControllerTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 12, 43, 35);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 8, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 9, 0, 0);
    private static final String HISTORY = "/history?metric={metric}&from={from}&to={to}";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CurrentWeatherQuery currentWeatherQuery;

    @MockitoBean
    private WeatherHistoryService weatherHistoryService;

    @Test
    void should_answer_the_current_weather() {
        final Map<String, CurrentMetric> current = new LinkedHashMap<>();
        current.put("battery", new CurrentMetric(new BigDecimal("100"), AT.minusHours(3), true));
        current.put("pressure", new CurrentMetric(new BigDecimal("993.30"), AT, false));
        current.put("raining", new CurrentMetric(false, AT, false));
        when(currentWeatherQuery.current()).thenReturn(current);

        webTestClient.get().uri("/current")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "battery": {"value": 100, "measuredAt": "2026-10-16T09:43:35", "stale": true},
                      "pressure": {"value": 993.30, "measuredAt": "2026-10-16T12:43:35", "stale": false},
                      "raining": {"value": false, "measuredAt": "2026-10-16T12:43:35", "stale": false}
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    //nothing received yet: an empty object, never nulls or zeros
    @Test
    void should_answer_an_empty_object_before_the_first_reading() {
        when(currentWeatherQuery.current()).thenReturn(Map.of());

        webTestClient.get().uri("/current")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("{}", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_status_of_the_station() {
        when(currentWeatherQuery.status()).thenReturn(new StationStatus(
            new StationStatus.Gateway("shellyplugsg3-28372f2f0460", true, AT), AT.minusSeconds(20), false));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "gateway": {"deviceId": "shellyplugsg3-28372f2f0460", "reachable": true, "checkedAt": "2026-10-16T12:43:35"},
                      "lastReadingAt": "2026-10-16T12:43:15",
                      "silent": false
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    //missing is never off: what is not known is left out, not sent as null or false
    @Test
    void should_leave_out_what_is_not_known_in_the_status() {
        when(currentWeatherQuery.status()).thenReturn(new StationStatus(
            new StationStatus.Gateway("shellyplugsg3-28372f2f0460", null, null), null, true));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {"gateway": {"deviceId": "shellyplugsg3-28372f2f0460"}, "silent": true}
                    """, JsonCompareMode.STRICT
            );
    }

    @Test
    void should_leave_out_the_gateway_without_a_station() {
        when(currentWeatherQuery.status()).thenReturn(new StationStatus(null, null, true));

        webTestClient.get().uri("/status")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json("{\"silent\": true}", JsonCompareMode.STRICT);
    }

    @Test
    void should_answer_the_history_of_a_metric() {
        when(weatherHistoryService.queryHistory("gust", FROM, TO)).thenReturn(Mono.just(new WeatherHistoryReply(
            "gust", FROM, TO, 300, List.of(
            new WeatherHistoryReply.Point(FROM, new BigDecimal("1.43"), new BigDecimal("0.40"), new BigDecimal("3.10")),
            new WeatherHistoryReply.Point(FROM.plusMinutes(10), new BigDecimal("2.00"), new BigDecimal("2.00"), new BigDecimal("2.00"))
        ))));

        webTestClient.get().uri(HISTORY, "gust", "2026-10-08T00:00:00", "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody().json(
                """
                    {
                      "metric": "gust",
                      "from": "2026-10-08T00:00:00",
                      "to": "2026-10-09T00:00:00",
                      "bucketSeconds": 300,
                      "points": [
                        {"at": "2026-10-08T00:00:00", "avg": 1.43, "min": 0.40, "max": 3.10},
                        {"at": "2026-10-08T00:10:00", "avg": 2.00, "min": 2.00, "max": 2.00}
                      ]
                    }
                    """, JsonCompareMode.STRICT
            );
    }

    @Test
    void should_answer_an_empty_history_as_an_empty_list() {
        when(weatherHistoryService.queryHistory("wind", FROM, TO))
            .thenReturn(Mono.just(new WeatherHistoryReply("wind", FROM, TO, 300, List.of())));

        webTestClient.get().uri(HISTORY, "wind", "2026-10-08T00:00:00", "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.points").isArray()
            .jsonPath("$.points.length()").isEqualTo(0);
    }

    //the code is what the dashboard branches on
    @Test
    void should_answer_400_with_a_code_for_an_unknown_metric() {
        when(weatherHistoryService.queryHistory("rain", FROM, TO)).thenReturn(Mono.error(WeatherException.unknownMetric()));

        webTestClient.get().uri(HISTORY, "rain", "2026-10-08T00:00:00", "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors.length()").isEqualTo(1)
            .jsonPath("$.errors[0].code").isEqualTo("UNKNOWN_METRIC")
            .jsonPath("$.errors[0].message").isEqualTo("Unknown metric")
            .jsonPath("$.errors[0].details").isEqualTo("metric must be one of: battery, pressure, illuminance, dewPoint, "
                + "voltage, raining, humidity, wind, gust, temperature, uv, windDirection, precipitation");
    }

    @Test
    void should_answer_400_without_a_code_for_a_range_the_service_refuses() {
        when(weatherHistoryService.queryHistory("wind", TO, FROM)).thenReturn(Mono.error(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to")));

        webTestClient.get().uri(HISTORY, "wind", "2026-10-09T00:00:00", "2026-10-08T00:00:00")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors.length()").isEqualTo(1)
            .jsonPath("$.errors[0].message").value(String.class, message ->
                assertThat(message).contains("from must be before to"))
            .jsonPath("$.errors[0].code").doesNotExist();
    }

    //read with the offset dropped, 22:00Z would be answered as 22:00 of the house
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-08T00:00:00Z", "2026-10-08T00:00:00+02:00", "2026-10-08", "yesterday", ""})
    void should_answer_400_for_a_bound_that_is_not_a_local_date_time(final String bound) {
        webTestClient.get().uri(HISTORY, "wind", bound, "2026-10-09T00:00:00")
            .exchange()
            .expectStatus().isBadRequest();

        webTestClient.get().uri(HISTORY, "wind", "2026-10-07T00:00:00", bound)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(weatherHistoryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/history",
        "/history?from=2026-10-08T00:00:00&to=2026-10-09T00:00:00",
        "/history?metric=wind&from=2026-10-08T00:00:00",
        "/history?metric=wind&to=2026-10-09T00:00:00"
    })
    void should_answer_400_without_every_parameter(final String uri) {
        webTestClient.get().uri(uri)
            .exchange()
            .expectStatus().isBadRequest();

        verifyNoInteractions(weatherHistoryService);
    }

    //reads only: nothing else is answered under the paths
    @Test
    void should_refuse_a_write() {
        webTestClient.post().uri("/current")
            .exchange()
            .expectStatus().value(status -> assertThat(status).isIn(404, 405));

        verifyNoInteractions(currentWeatherQuery);
    }
}
