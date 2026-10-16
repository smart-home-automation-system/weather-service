package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.StationLink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The current state of the station, in memory: per metric the last value with the time the station
//measured it. Updated by every status of a sensor of the station - the gateway publishes a sensor only
//when its value changes, so the state is made of the changes and a metric never received is absent.
//A reading older than the one held is a message that came late (a redelivery) and is dropped; the same
//reading again (a repeated status) changes nothing. Times are instants, so the night the clocks go
//back changes nothing either.
//The snapshot of WeatherSnapshot takes the state as it is and whether anything changed since the last
//one; the state restored from the database at the start does not count as a change.
//The listener runs on several threads (prefetch 3), hence one lock for the map - a reading of one
//metric and a snapshot of all of them must not interleave
@Slf4j
@Component
public class CurrentWeather {

    private final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
    private boolean changedSinceSnapshot;
    private final AtomicReference<StationLink> link = new AtomicReference<>();

    //true when the reading became the state
    public boolean observe(final Metric metric, final Reading reading) {
        synchronized (readings) {
            final Reading held = readings.get(metric);
            if (reading.sameAs(held)) {
                return false;
            }
            if (held != null && reading.measuredAt().isBefore(held.measuredAt())) {
                log.debug("Reading of {} measured at {} is older than the one held ({}) and is dropped",
                    metric, reading.measuredAt(), held.measuredAt());
                return false;
            }
            readings.put(metric, reading);
            changedSinceSnapshot = true;
            return true;
        }
    }

    //the state the service starts from: fills only what no message has brought since the start
    public void restore(final Map<Metric, Reading> stored) {
        synchronized (readings) {
            stored.forEach((metric, reading) -> {
                final Reading held = readings.get(metric);
                if (held == null || held.measuredAt().isBefore(reading.measuredAt())) {
                    readings.put(metric, reading);
                }
            });
        }
    }

    //another station or gateway: what the last one measured is not a reading of this one
    public void clear() {
        synchronized (readings) {
            readings.clear();
            changedSinceSnapshot = false;
        }
        link.set(null);
    }

    public void link(final StationLink current) {
        link.set(current);
    }

    public Map<Metric, Reading> readings() {
        synchronized (readings) {
            return Map.copyOf(readings);
        }
    }

    public Optional<StationLink> link() {
        return Optional.ofNullable(link.get());
    }

    //the state for a row of the database, or nothing when nothing changed since the last one
    public Optional<Snapshot> takeSnapshot() {
        synchronized (readings) {
            if (!changedSinceSnapshot || readings.isEmpty()) {
                return Optional.empty();
            }
            changedSinceSnapshot = false;
            final Instant newest = readings.values().stream()
                .map(Reading::measuredAt)
                .max(Instant::compareTo)
                .orElseThrow();
            return Optional.of(new Snapshot(newest, Map.copyOf(readings)));
        }
    }

    //a snapshot that could not be stored: the next pass tries again with what is held then
    public void snapshotFailed() {
        synchronized (readings) {
            changedSinceSnapshot = true;
        }
    }

    public record Snapshot(Instant measuredAt, Map<Metric, Reading> readings) {
    }
}
