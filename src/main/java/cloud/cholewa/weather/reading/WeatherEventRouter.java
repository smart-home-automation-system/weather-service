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

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

//Which events of the device queue are readings of the station, and what they say. The queue carries
//every event of every device: the routing key alone decides first - the status of a bthomesensor or
//the bthomedevice component of the gateway of the station - so the body of any other event is never
//read. The id of the gateway is matched whatever its case.
//- <gatewayId>.status.bthomesensor:<id>: {id, value, last_updated_ts}, one sensor, published when its
//  value changes. A sensor of another device on the gateway is ignored without a word, one the map
//  does not have at all is said at WARN once (unknown()); one without a
//  value or a time (0 until the gateway has heard the object once) is no reading
//- <gatewayId>.status.bthomedevice:<id>: the signal, the battery and the packet id of the station,
//  and when the gateway heard it last.
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

    //the sensors already said at WARN, for the map they were not on
    private final AtomicReference<StationSensors> warnedFor = new AtomicReference<>();
    private final Set<Integer> warned = ConcurrentHashMap.newKeySet();

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
            unknown(topic, sensors);
            return Mono.empty();
        }
        return Mono.fromCallable(() -> shellyMqttDecoder.decode(topic, body))
            .flatMap(Mono::justOrEmpty)
            .cast(ShellyEvent.Status.class)
            .flatMap(status -> Mono.justOrEmpty(status.as(BthomeSensorStatus.class)))
            .flatMap(status -> Mono.justOrEmpty(SensorReadings.of(metric, status)))
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
            .doOnNext(status -> {
                currentWeather.link(new StationLink(
                    status.getRssi(), status.getBattery(), status.getPacketId(), LocalDateTime.now(clock)));
                SensorReadings.heardAt(status).ifPresent(currentWeather::heard);
            })
            .then();
    }

    //A sensor of another Bluetooth device on the gateway is known and expected - there may be many,
    //and every change of each arrives here - so nothing is said. One the gateway did not have when the
    //map was read is said at WARN once per map; the map is read again with the next refresh anyway
    //(every five minutes): a re-pairing gives the sensors of the station other ids
    private void unknown(final ShellyTopic topic, final StationSensors sensors) {
        if (sensors.others().contains(topic.index())) {
            return;
        }
        //by content: every read of the gateway (every five minutes) builds the map anew
        if (!sensors.equals(warnedFor.get())) {
            warnedFor.set(sensors);
            warned.clear();
        }
        if (warned.add(topic.index())) {
            log.warn("Sensor bthomesensor:{} of the gateway {} is not on its map and is ignored until the map is read again",
                topic.index(), topic.deviceId());
        }
    }
}
