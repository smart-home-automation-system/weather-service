package cloud.cholewa.weather.station;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.http.ShellyTimeouts;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.SensorsRead;
import cloud.cholewa.weather.model.Station;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
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

        sut.load(station).map(SensorsRead::sensors).as(StepVerifier::create)
            .assertNext(sensors -> {
                assertThat(sensors.sensors()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    200, Metric.BATTERY, 205, Metric.TEMPERATURE, 209, Metric.WIND, 210, Metric.GUST));
                assertThat(sensors.others()).containsExactlyInAnyOrder(211, 300);
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

        sut.load(station).map(SensorsRead::sensors).as(StepVerifier::create)
            .assertNext(sensors -> {
                assertThat(sensors.sensors()).containsExactlyEntriesOf(Map.of(205, Metric.TEMPERATURE));
                assertThat(sensors.deviceComponent()).isNull();
            })
            .verifyComplete();
    }

    //what each sensor of the station holds now - a value that never changes arrives only this way;
    //a sensor never heard (value null, time 0) and one of another device bring nothing
    @Test
    void should_read_what_the_sensors_of_the_station_hold_now() {
        gateway.enqueue(page(0, 4, 27,
            "{\"key\":\"bthomesensor:200\",\"status\":{\"id\":200,\"value\":100,\"last_updated_ts\":1792135287},"
                + "\"config\":{\"id\":200,\"addr\":\"" + STATION + "\",\"obj_id\":1,\"idx\":0}}",
            "{\"key\":\"bthomesensor:208\",\"status\":{\"id\":208,\"value\":false,\"last_updated_ts\":1792135278},"
                + "\"config\":{\"id\":208,\"addr\":\"" + STATION + "\",\"obj_id\":32,\"idx\":0}}",
            sensor(205, STATION, 0x45, 0),
            "{\"key\":\"bthomesensor:300\",\"status\":{\"id\":300,\"value\":21.5,\"last_updated_ts\":1792135287},"
                + "\"config\":{\"id\":300,\"addr\":\"aa:bb:cc:dd:ee:ff\",\"obj_id\":69,\"idx\":0}}"));

        sut.load(station).as(StepVerifier::create)
            .assertNext(read -> assertThat(read.readings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                Metric.BATTERY, Reading.number(new BigDecimal("100"), Instant.ofEpochSecond(1792135287)),
                Metric.RAIN, Reading.flag(false, Instant.ofEpochSecond(1792135278)))))
            .verifyComplete();
    }

    //one odd component must not cost the whole map: a status that is not the model is skipped
    @Test
    void should_skip_a_status_that_is_not_the_model() {
        gateway.enqueue(page(0, 2, 27,
            "{\"key\":\"bthomesensor:200\",\"status\":{\"id\":200,\"value\":100,\"last_updated_ts\":\"soon\"},"
                + "\"config\":{\"id\":200,\"addr\":\"" + STATION + "\",\"obj_id\":1,\"idx\":0}}",
            sensor(205, STATION, 0x45, 0)));

        sut.load(station).as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.sensors().sensors()).containsOnlyKeys(200, 205);
                assertThat(read.readings()).isEmpty();
            })
            .verifyComplete();
    }

    //a re-pairing that left the old component: two sensors of one metric give the newer reading
    @Test
    void should_take_the_newer_of_two_sensors_of_one_metric() {
        gateway.enqueue(page(0, 2, 27,
            "{\"key\":\"bthomesensor:215\",\"status\":{\"id\":215,\"value\":4.4,\"last_updated_ts\":1792135288},"
                + "\"config\":{\"id\":215,\"addr\":\"" + STATION + "\",\"obj_id\":69,\"idx\":0}}",
            "{\"key\":\"bthomesensor:205\",\"status\":{\"id\":205,\"value\":12.0,\"last_updated_ts\":1792000000},"
                + "\"config\":{\"id\":205,\"addr\":\"" + STATION + "\",\"obj_id\":69,\"idx\":0}}"));

        sut.load(station).as(StepVerifier::create)
            .assertNext(read -> assertThat(read.readings()).containsExactlyEntriesOf(Map.of(
                Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), Instant.ofEpochSecond(1792135288)))))
            .verifyComplete();
    }

    @Test
    void should_give_the_status_of_the_device_of_the_station() {
        gateway.enqueue(page(0, 1, 27, device(200, STATION)));

        sut.load(station).as(StepVerifier::create)
            .assertNext(read -> assertThat(read.device().getRssi()).isEqualTo(-73))
            .verifyComplete();
    }

    //a component sent with "config": null is left out, not a failed map
    @Test
    void should_skip_a_component_without_its_config() {
        gateway.enqueue(page(0, 2, 27,
            "{\"key\":\"bthomesensor:206\",\"status\":{\"id\":206},\"config\":null}",
            sensor(205, STATION, 0x45, 0)));

        sut.load(station).map(SensorsRead::sensors).as(StepVerifier::create)
            .assertNext(sensors -> assertThat(sensors.sensors()).containsExactlyEntriesOf(Map.of(205, Metric.TEMPERATURE)))
            .verifyComplete();
    }

    //half a map would make the rest of the metrics unknown
    @Test
    void should_fail_the_whole_read_when_one_page_fails() {
        gateway.enqueue(page(0, 6, 27, sensor(205, STATION, 0x45, 0)));
        gateway.enqueue(new MockResponse.Builder().code(500).build());

        sut.load(station).as(StepVerifier::create).expectError().verify();
    }

    //the gateway is read every five minutes: a WARN about the same component once, again only after a
    //read without it
    @Test
    void should_say_a_sensor_without_a_metric_once() {
        final ListAppender<ILoggingEvent> loaderLog = new ListAppender<>();
        loaderLog.start();
        ((Logger) LoggerFactory.getLogger(SensorMapLoader.class)).addAppender(loaderLog);
        try {
            gateway.enqueue(page(0, 1, 27, sensor(211, STATION, 0x99, 0)));
            gateway.enqueue(page(0, 1, 27, sensor(211, STATION, 0x99, 0)));
            gateway.enqueue(page(0, 1, 27, sensor(205, STATION, 0x45, 0)));
            gateway.enqueue(page(0, 1, 27, sensor(211, STATION, 0x99, 0)));

            sut.load(station).block();
            sut.load(station).block();
            sut.load(station).block();
            sut.load(station).block();
        } finally {
            ((Logger) LoggerFactory.getLogger(SensorMapLoader.class)).detachAppender(loaderLog);
        }

        assertThat(loaderLog.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(2)
            .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("bthomesensor:211"));
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
