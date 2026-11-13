package cloud.cholewa.weather.air;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

//The reads of the air sensor: GET http://<address>/api/air/state (HAS-427) and, once an hour, GET /api/air/runtime -
//the hours the laser has run (HAS-437). Reads, nothing else. No command is ever sent: /s/kick (a measurement at
//once) shortens the life of the laser.
//Every failure is an AirSensorCallException with its kind - the sensor away, a timeout, an error status,
//a body that is not the model ({} included); AirQuality logs one WARN an hour per kind. The WebClient is
//the airSensorWebClient of WebClientConfig: built from the injected builder (traced by Logbook), with a
//response timeout of 5 s and no base url - the address comes from the registry
@Component
public class AirSensorClient {

    private final WebClient airSensorWebClient;

    public AirSensorClient(@Qualifier("airSensorWebClient") final WebClient airSensorWebClient) {
        this.airSensorWebClient = airSensorWebClient;
    }

    public Mono<AirStateResponse> state(final String address) {
        return read(address, "state", AirStateResponse.class)
            .filter(state -> state.air() != null)
            .switchIfEmpty(Mono.error(() -> new AirSensorCallException(AirSensorCallException.Kind.INVALID_BODY,
                "the air sensor answered without the state of the air")))
            .onErrorMap(error -> !(error instanceof AirSensorCallException), AirSensorClient::mapped);
    }

    //the hours the laser has run - a whole number from 0 up; anything else ({}, a fraction, a negative, a text) is a
    //failed read of the kind INVALID_BODY
    public Mono<Long> runtimeHours(final String address) {
        return read(address, "runtime", AirRuntimeResponse.class)
            .flatMap(runtime -> Mono.justOrEmpty(runtime.hours()))
            .switchIfEmpty(Mono.error(() -> new AirSensorCallException(AirSensorCallException.Kind.INVALID_BODY,
                "the air sensor answered without the whole hours of its laser")))
            .onErrorMap(error -> !(error instanceof AirSensorCallException), AirSensorClient::mapped);
    }

    private <T> Mono<T> read(final String address, final String resource, final Class<T> type) {
        return airSensorWebClient
            .get()
            .uri(UriComponentsBuilder.fromUriString("http://" + address).pathSegment("api", "air", resource).build().toUri())
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> response.releaseBody()
                .then(Mono.error(() -> new AirSensorCallException(AirSensorCallException.Kind.STATUS,
                    "the air sensor answered " + response.statusCode().value()))))
            .bodyToMono(type);
    }

    //the cause decides the kind; the text of a body is never quoted
    private static AirSensorCallException mapped(final Throwable error) {
        final Throwable cause = error instanceof WebClientRequestException request && request.getCause() != null
            ? request.getCause()
            : error;
        if (cause instanceof ReadTimeoutException || cause instanceof TimeoutException
            || cause instanceof ConnectTimeoutException) {
            return new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time");
        }
        if (error instanceof WebClientRequestException || cause instanceof ConnectException) {
            return new AirSensorCallException(AirSensorCallException.Kind.CONNECTION,
                "the air sensor could not be reached: " + cause.getClass().getSimpleName());
        }
        return new AirSensorCallException(AirSensorCallException.Kind.INVALID_BODY,
            "the air sensor answered with a body that is not its state: " + error.getClass().getSimpleName());
    }
}
