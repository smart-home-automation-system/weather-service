package cloud.cholewa.weather.station;

import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.http.ShellyTimeouts;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Station;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//against a stub of the gateway with the real ShellyRpcClient, so the calls are the ones a device
//answers; the pages have the shape the gateway TREADMILL answered (HAS-234, 11 components a page)
class SensorMapLoaderTest {

    private static final String STATION = "08:b9:5f:d1:e6:71";

    private MockWebServer gateway;
    private SensorMapLoader sut;
    private Station station;

    @BeforeEach
    void setUp() throws IOException {
        gateway = new MockWebServer();
        gateway.start();
        sut = new SensorMapLoader(new ShellyRpcClient(WebClient.builder(),
            new ShellyTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2))), JsonMapper.builder().build());
        station = new Station(55, "Weather station", STATION, 54, "shellyplugsg3-28372f2f0460",
            "localhost:" + gateway.getPort());
    }

    @AfterEach
    void tearDown() {
        gateway.close();
    }

    //the address in either case; 0x99 is no metric; 300 and the device 201 are another device's
    @Test
    void should_map_the_sensors_of_the_station_by_their_objects() throws InterruptedException {
        gateway.enqueue(page(0, 8, 27,
            device(201, "aa:bb:cc:dd:ee:ff"),
            device(200, STATION.toUpperCase()),
            sensor(200, STATION, 1, 0),
            sensor(205, STATION, 0x45, 0),
            sensor(209, STATION, 0x44, 0)));
        gateway.enqueue(page(5, 8, 27,
            sensor(210, STATION, 0x44, 1),
            sensor(211, STATION, 0x99, 0),
            sensor(300, "aa:bb:cc:dd:ee:ff", 0x45, 0)));

        StepVerifier.create(sut.load(station))
            .assertNext(sensors -> {
                assertThat(sensors.sensors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    200, Metric.BATTERY, 205, Metric.TEMPERATURE, 209, Metric.WIND, 210, Metric.GUST));
                assertThat(sensors.deviceComponent()).isEqualTo(200);
            })
            .verifyComplete();

        final RecordedRequest first = gateway.takeRequest();
        assertThat(first.getUrl().encodedPath()).isEqualTo("/rpc/Shelly.GetComponents");
        assertThat(first.getBody().utf8()).contains("\"dynamic_only\":true");
    }

    @Test
    void should_know_no_device_component_without_one_of_the_station() {
        gateway.enqueue(page(0, 2, 27, device(201, "aa:bb:cc:dd:ee:ff"), sensor(205, STATION, 0x45, 0)));

        StepVerifier.create(sut.load(station))
            .assertNext(sensors -> {
                assertThat(sensors.sensors()).containsExactlyEntriesOf(Map.of(205, Metric.TEMPERATURE));
                assertThat(sensors.deviceComponent()).isNull();
            })
            .verifyComplete();
    }

    //half a map would make the rest of the metrics unknown
    @Test
    void should_fail_the_whole_read_when_one_page_fails() {
        gateway.enqueue(page(0, 6, 27, sensor(205, STATION, 0x45, 0)));
        gateway.enqueue(new MockResponse.Builder().code(500).build());

        StepVerifier.create(sut.load(station)).expectError().verify();
    }

    private static MockResponse page(final int offset, final int total, final int revision, final String... components) {
        return new MockResponse.Builder()
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("""
                {"components":[%s],"cfg_rev":%d,"offset":%d,"total":%d}
                """.formatted(String.join(",", components), revision, offset, total))
            .build();
    }

    private static String device(final int id, final String address) {
        return """
            {"key":"bthomedevice:%d","status":{"id":%d,"rssi":-73},"config":{"id":%d,"addr":"%s","name":null,"meta":null},"attrs":{"model_id":11}}
            """.formatted(id, id, id, address).strip();
    }

    private static String sensor(final int id, final String address, final int objectId, final int index) {
        return """
            {"key":"bthomesensor:%d","status":{"id":%d,"value":null,"last_updated_ts":0},"config":{"id":%d,"addr":"%s","name":null,"obj_id":%d,"idx":%d,"meta":null}}
            """.formatted(id, id, id, address, objectId, index).strip();
    }
}
