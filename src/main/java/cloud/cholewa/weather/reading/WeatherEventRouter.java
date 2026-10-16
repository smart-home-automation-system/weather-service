package cloud.cholewa.weather.reading;

import cloud.cholewa.shelly.codec.ShellyEvent;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.shelly.codec.ShellyTopic;
import cloud.cholewa.shelly.model.BthomeDeviceStatus;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationLink;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.station.WeatherStation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

//Which events of the device queue are readings of the station, and what they say. The queue carries
//every event of every device: the routing key alone decides first - the status of a bthomesensor or
//the bthomedevice component of the gateway of the station - so the body of any other event is never
//read. The id of the gateway is matched whatever its case.
//- <gatewayId>.status.bthomesensor:<id>: {id, value, last_updated_ts}, one sensor, published when its
//  value changes. A sensor that is not on the map of the station is said at WARN and ignored; one
//  without a value or a time (0 until the gateway has heard the object once) is no reading
//- <gatewayId>.status.bthomedevice:<id>: the signal, the battery and the packet id of the station.
//Until the station and its map are known every event is ignored - the first refresh comes at once
//after the start. A body that is not what its topic carries is an error for the consumer to log
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherEventRouter {

    private static final String SENSOR = "bthomesensor";
    private static final String DEVICE = "bthomedevice";

    private final WeatherStation weatherStation;
    private final CurrentWeather currentWeather;
    private final ShellyMqttDecoder shellyMqttDecoder;
    private final Clock clock;

    public Mono<Void> route(final String routingKey, final byte[] body) {
        return Mono.justOrEmpty(ShellyTopic.parse(routingKey))
            .filter(topic -> topic.kind() == ShellyTopic.Kind.STATUS && topic.index() != null
                && (SENSOR.equals(topic.component()) || DEVICE.equals(topic.component())))
            .filter(topic -> weatherStation.station()
                .map(Station::gatewayDeviceId)
                .filter(topic.deviceId()::equalsIgnoreCase)
                .isPresent())
            .flatMap(topic -> Mono.justOrEmpty(weatherStation.sensors())
                .flatMap(sensors -> SENSOR.equals(topic.component())
                    ? sensor(topic, body, sensors)
                    : device(topic, body, sensors)));
    }

    private Mono<Void> sensor(final ShellyTopic topic, final byte[] body, final StationSensors sensors) {
        final Metric metric = sensors.sensors().get(topic.index());
        if (metric == null) {
            log.warn("Sensor bthomesensor:{} of the gateway {} is not one of the weather station and is ignored",
                topic.index(), topic.deviceId());
            return Mono.empty();
        }
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(status.as(BthomeSensorStatus.class)))
            .flatMap(status -> Mono.justOrEmpty(reading(metric, status)))
            .doOnNext(reading -> currentWeather.observe(metric, reading))
            .then();
    }

    private Mono<Void> device(final ShellyTopic topic, final byte[] body, final StationSensors sensors) {
        if (!topic.index().equals(sensors.deviceComponent())) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(status.as(BthomeDeviceStatus.class)))
            .doOnNext(status -> currentWeather.link(new StationLink(
                status.getRssi(), status.getBattery(), status.getPacketId(), LocalDateTime.now(clock))))
            .then();
    }

    //the value as the metric reads it: a flag for RAIN, a number for every other one. A value of the
    //wrong type is a sensor the map got wrong - said at WARN, never stored as something else
    private Optional<Reading> reading(final Metric metric, final BthomeSensorStatus status) {
        final Object value = status.getValue();
        final Long timestamp = status.getLastUpdatedTs();
        if (value == null || timestamp == null || timestamp <= 0) {
            return Optional.empty();
        }
        final LocalDateTime measuredAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), clock.getZone());
        if (metric.isFlag() && value instanceof Boolean flag) {
            return Optional.of(Reading.flag(flag, measuredAt));
        }
        if (!metric.isFlag() && value instanceof Number number) {
            return Optional.of(Reading.number(new BigDecimal(number.toString()), measuredAt));
        }
        log.warn("Sensor bthomesensor:{} ({}) sent the value {}, which is not what that metric is",
            status.getId(), metric, value);
        return Optional.empty();
    }
}
