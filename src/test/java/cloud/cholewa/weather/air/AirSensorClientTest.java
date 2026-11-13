package cloud.cholewa.weather.air;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AirSensorClientTest {

    private static final String STATE = """
        {"air":{"airQualityLevel":1,"sensors":[{"type":"pm1","value":7,"trend":3,"state":0,"qualityLevel":-1,"elapsedTimeS":374},
        {"type":"pm2.5","value":12,"trend":3,"state":0,"qualityLevel":1,"elapsedTimeS":374},
        {"type":"pm10","value":12,"trend":3,"state":0,"qualityLevel":1,"elapsedTimeS":374}]}}
        """;

    private MockWebServer server;
    private AirSensorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        final HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(1));
        client = new AirSensorClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String address() {
        return "localhost:" + server.getPort();
    }

    //the answer of the sensor of the house, read on 2026-11-12
    @Test
    void should_read_the_state_of_the_air_at_the_address_of_the_sensor() throws InterruptedException {
        server.enqueue(json(STATE));

        client.state(address()).as(StepVerifier::create)
            .assertNext(state -> {
                assertThat(state.air().airQualityLevel()).isEqualTo(1);
                assertThat(state.air().sensors()).extracting(AirStateResponse.Sensor::type).containsExactly("pm1", "pm2.5", "pm10");
                assertThat(state.air().sensors().get(1).value()).isEqualTo(12);
                assertThat(state.air().sensors().get(1).elapsedTimeS()).isEqualTo(374L);
            })
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/api/air/state");
    }

    //{} decodes into nulls: no state, a failed read - never an air without readings
    @Test
    void should_fail_on_an_answer_without_the_state_of_the_air() {
        server.enqueue(json("{}"));

        client.state(address()).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.INVALID_BODY));
    }

    @Test
    void should_fail_on_a_body_that_is_not_the_model() {
        server.enqueue(json("[1, 2]"));

        client.state(address()).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.INVALID_BODY));
    }

    @Test
    void should_fail_on_an_error_status() {
        server.enqueue(new MockResponse.Builder().code(500).body("busy").build());

        client.state(address()).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .hasMessage("the air sensor answered 500")
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.STATUS));
    }

    @Test
    void should_fail_on_a_sensor_that_does_not_answer_in_time() {
        server.enqueue(json(STATE).newBuilder().headersDelay(3, TimeUnit.SECONDS).build());

        client.state(address()).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.TIMEOUT))
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void should_fail_on_a_socket_closed_before_the_answer() {
        server.enqueue(json(STATE).newBuilder().onResponseBody(new SocketEffect.CloseSocket()).build());

        client.state(address()).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class))
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void should_fail_on_a_sensor_away() {
        server.close();

        client.state(address()).as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.CONNECTION))
            .verify(Duration.ofSeconds(10));
    }

    //HAS-437: the counter of the sensor of the house, read on 2026-11-12
    @Test
    void should_read_the_hours_the_laser_has_run() throws InterruptedException {
        server.enqueue(json("{\"runtime\":{\"timeH\":1908}}"));

        client.runtimeHours(address()).as(StepVerifier::create)
            .expectNext(1908L)
            .verifyComplete();

        final RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getUrl().encodedPath()).isEqualTo("/api/air/runtime");
    }

    @Test
    void should_read_a_new_laser_as_none_run() {
        server.enqueue(json("{\"runtime\":{\"timeH\":0}}"));

        client.runtimeHours(address()).as(StepVerifier::create)
            .expectNext(0L)
            .verifyComplete();
    }

    //{} decodes into nulls; a negative, a fraction or a text is no count of hours - never cut, never 0
    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"runtime\":{}}",
        "{\"runtime\":{\"timeH\":-1}}",
        "{\"runtime\":{\"timeH\":1908.5}}",
        "{\"runtime\":{\"timeH\":\"many\"}}",
        "{\"runtime\":{\"timeH\":99999999999999999999}}",
        "[1, 2]"
    })
    void should_fail_on_an_answer_without_the_whole_hours_of_the_laser(final String body) {
        server.enqueue(json(body));

        client.runtimeHours(address()).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.INVALID_BODY));
    }

    @Test
    void should_fail_on_an_error_status_of_the_counter() {
        server.enqueue(new MockResponse.Builder().code(404).build());

        client.runtimeHours(address()).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(AirSensorCallException.class)
                .hasMessage("the air sensor answered 404")
                .extracting("kind").isEqualTo(AirSensorCallException.Kind.STATUS));
    }

    private static MockResponse json(final String body) {
        return new MockResponse.Builder().code(200)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body).build();
    }
}
