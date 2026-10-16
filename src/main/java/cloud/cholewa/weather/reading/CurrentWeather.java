package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.StationLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
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
//Apart from the state, the newest time the gateway heard the station (heardAt): every reading counts,
//taken or not - the same value again at a later time is no change, but it is the station heard - and
//so does the last_updated_ts of its bthomedevice. The watch over the station (StationWatch) judges
//silence by it; the time is the station's, so a reading that arrives late is not a sign of life now.
//A time ahead of the clock of the pod by more than a minute is not taken: the gateway with a clock gone
//wrong once would otherwise hold heardAt in the future, and a dead station would never be reported.
//Per metric, the newest time the held value was confirmed (confirmed): a status of the same value at a
//later time is no new reading - no row for it - but it says the value still holds then. The read API
//shows a value with that time and judges by it whether the value is stale (HAS-238): a value that never
//changes, the battery or the rain for days, is as fresh as the last packet that carried it.
//The listener runs on several threads (prefetch 3), hence one lock for the map - a reading of one
//metric and a snapshot of all of them must not interleave
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentWeather {

    static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(1);

    private final Clock clock;

    private final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
    private final Map<Metric, Instant> confirmed = new EnumMap<>(Metric.class);
    private boolean changedSinceSnapshot;
    private final AtomicReference<StationLink> link = new AtomicReference<>();
    private final AtomicReference<Instant> heardAt = new AtomicReference<>();

    //true when the reading became the state
    public boolean observe(final Metric metric, final Reading reading) {
        heard(reading.measuredAt());
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
            take(metric, reading);
            return true;
        }
    }

    //what a sensor holds now, read from the gateway with the map: a metric not held yet, or a value
    //other than the held one measured strictly later. It never replaces a held reading of the same
    //second - the map read may have been answered before an event of that second, and the event is
    //the later word; and the same value at a later time is no change - no row for it (the gateway may
    //move the time of a sensor without publishing it)
    public boolean seed(final Metric metric, final Reading reading) {
        heard(reading.measuredAt());
        synchronized (readings) {
            final Reading held = readings.get(metric);
            final boolean take = held == null
                || reading.measuredAt().isAfter(held.measuredAt()) && !sameValue(held, reading);
            if (take) {
                take(metric, reading);
            } else if (sameValue(held, reading)) {
                confirm(metric, reading.measuredAt());
            }
            return take;
        }
    }

    //under the lock of the map
    private void take(final Metric metric, final Reading reading) {
        readings.put(metric, reading);
        confirmed.put(metric, reading.measuredAt());
        changedSinceSnapshot = true;
    }

    //under the lock of the map; a time ahead of the pod is not taken, as for heardAt
    private void confirm(final Metric metric, final Instant at) {
        if (!at.isAfter(clock.instant().plus(CLOCK_TOLERANCE))) {
            confirmed.merge(metric, at, (held, next) -> next.isAfter(held) ? next : held);
        }
    }

    private static boolean sameValue(final Reading held, final Reading reading) {
        return reading.sameAs(new Reading(held.number(), held.flag(), reading.measuredAt()));
    }

    //the state the service starts from: fills only what no message has brought since the start
    //the station heard then, too: a pod started while the gateway is away knows how long the station
    //has been silent from its last row, not from its own start
    public void restore(final Map<Metric, Reading> stored) {
        synchronized (readings) {
            stored.forEach((metric, reading) -> {
                heard(reading.measuredAt());
                final Reading held = readings.get(metric);
                if (held == null || held.measuredAt().isBefore(reading.measuredAt())) {
                    readings.put(metric, reading);
                    confirmed.put(metric, reading.measuredAt());
                }
            });
        }
    }

    //another station or gateway: what the last one measured is not a reading of this one
    public void clear() {
        synchronized (readings) {
            readings.clear();
            confirmed.clear();
            changedSinceSnapshot = false;
        }
        link.set(null);
        heardAt.set(null);
    }

    public void link(final StationLink current) {
        link.set(current);
    }

    //the station was heard at this time (its own) - only a later time moves heardAt
    public void heard(final Instant at) {
        if (at.isAfter(clock.instant().plus(CLOCK_TOLERANCE))) {
            log.debug("The station was heard at {} by the clock of the gateway, ahead of the service - not taken", at);
            return;
        }
        heardAt.accumulateAndGet(at, (held, next) -> held == null || next.isAfter(held) ? next : held);
    }

    //empty until the station was heard - since the start, or since another station replaced it
    public Optional<Instant> heardAt() {
        return Optional.ofNullable(heardAt.get());
    }

    public Map<Metric, Reading> readings() {
        synchronized (readings) {
            return Map.copyOf(readings);
        }
    }

    //the state as the read API shows it: each value with the newest time it was confirmed
    public Map<Metric, Reading> confirmedReadings() {
        synchronized (readings) {
            final Map<Metric, Reading> current = new EnumMap<>(Metric.class);
            readings.forEach((metric, reading) -> current.put(metric,
                new Reading(reading.number(), reading.flag(), confirmed.getOrDefault(metric, reading.measuredAt()))));
            return current;
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
