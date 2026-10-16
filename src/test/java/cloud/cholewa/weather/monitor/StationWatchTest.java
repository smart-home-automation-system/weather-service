package cloud.cholewa.weather.monitor;

import cloud.cholewa.commons.notification.NotificationPublishException;
import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.database.model.StationAlertEntity;
import cloud.cholewa.weather.database.repository.StationAlertRepository;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.station.WeatherStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StationWatchTest {

    //10:00 house time
    private static final Instant START = Instant.parse("2026-10-16T08:00:00Z");
    private static final Station STATION = new Station(55, "WS90", "08:b9:5f:d1:e6:71", 54,
        "shellyplugsg3-28372f2f0460", "10.78.30.36");

    private final MutableClock clock = new MutableClock(START);
    private final WeatherStation weatherStation = mock(WeatherStation.class);
    private final CurrentWeather currentWeather = new CurrentWeather(clock);
    private final StationAlertRepository repository = mock(StationAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final StationWatch sut = new StationWatch(weatherStation, currentWeather, repository, publisher,
        new MonitorProperties(Duration.ofMinutes(30), Duration.ofHours(1)), clock);

    //station_alert as the repository sees it
    private final Map<Long, StationAlertEntity> rows = new TreeMap<>();
    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(weatherStation.station()).thenReturn(Optional.of(STATION));
        when(weatherStation.gatewayAnsweredAt()).thenAnswer(invocation -> Optional.of(clock.instant()));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(repository.findAll()).thenAnswer(invocation -> Flux.defer(() -> Flux.fromIterable(rows.values())));
        when(repository.save(any(StationAlertEntity.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            final StationAlertEntity row = invocation.getArgument(0);
            final StationAlertEntity saved = row.id() == null
                ? new StationAlertEntity(ids.incrementAndGet(), row.stationId(), row.watch(), row.since(), row.lastAlertAt())
                : row;
            rows.put(saved.id(), saved);
            return saved;
        }));
        when(repository.delete(any(StationAlertEntity.class))).thenAnswer(invocation -> Mono.fromRunnable(() ->
            rows.remove(invocation.<StationAlertEntity>getArgument(0).id())));
    }

    @Test
    void should_say_nothing_while_the_station_is_heard() {
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(29));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(rows).isEmpty();
    }

    @Test
    void should_alert_once_when_the_station_is_silent() {
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(30));

        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(5));
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert("The weather station WS90 has sent no reading since 2026-10-16 10:00 (30 min).");
        assertThat(rows.values()).singleElement()
            .isEqualTo(new StationAlertEntity(1L, 55L, Watch.STATION, START, START.plus(Duration.ofMinutes(30))));
    }

    @Test
    void should_remind_every_interval_while_the_station_is_silent() {
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(30));
        sut.check().block();

        clock.move(Duration.ofMinutes(55));
        sut.check().block();
        verify(publisher, never()).publishReminder(anyString());

        clock.move(Duration.ofMinutes(5));
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishReminder("The weather station WS90 has still sent no reading - since 2026-10-16 10:00 (1 h 30 min).");
        assertThat(rows.values()).singleElement()
            .satisfies(row -> assertThat(row.lastAlertAt()).isEqualTo(START.plus(Duration.ofMinutes(90))));
    }

    @Test
    void should_say_once_when_the_station_is_heard_again() {
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(30));
        sut.check().block();

        clock.move(Duration.ofMinutes(15));
        currentWeather.heard(clock.instant().minusSeconds(60));
        sut.check().as(StepVerifier::create).verifyComplete();
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo(
            "The weather station WS90 is sending readings again. None came from 2026-10-16 10:00 to 2026-10-16 10:44 (44 min).");
        assertThat(rows).isEmpty();
    }

    //a station dead since before the deploy is reported counted from the start of the watch
    @Test
    void should_count_from_the_start_when_the_station_was_never_heard() {
        sut.check().block();
        clock.move(Duration.ofMinutes(30));

        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert(
            "The weather station WS90 has sent no reading since the service started watching it at 2026-10-16 10:00 (30 min).");
    }

    @Test
    void should_alert_when_the_gateway_does_not_answer() {
        when(weatherStation.gatewayAnsweredAt()).thenReturn(Optional.of(START));
        clock.move(Duration.ofMinutes(30));
        currentWeather.heard(clock.instant());

        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert(
            "The gateway shellyplugsg3-28372f2f0460 of the weather station has not answered since 2026-10-16 10:00 (30 min).");
        assertThat(rows.values()).singleElement().satisfies(row -> assertThat(row.watch()).isEqualTo(Watch.GATEWAY));
    }

    @Test
    void should_say_once_when_the_gateway_answers_again() {
        rows.put(1L, new StationAlertEntity(1L, 55L, Watch.GATEWAY, START, START.plus(Duration.ofMinutes(30))));
        clock.move(Duration.ofMinutes(40));
        currentWeather.heard(clock.instant());

        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishInfo(
            "The gateway shellyplugsg3-28372f2f0460 of the weather station answers again. It did not from 2026-10-16 10:00 to 2026-10-16 10:40 (40 min).");
        assertThat(rows).isEmpty();
    }

    //heard again and silent again while the pod was away: the old outage is over, a new one comes next
    @Test
    void should_end_the_alert_when_heard_after_it_even_if_silent_again() {
        rows.put(1L, new StationAlertEntity(1L, 55L, Watch.STATION, START.minus(Duration.ofHours(3)), START.minus(Duration.ofHours(2))));
        currentWeather.heard(START.minus(Duration.ofHours(2)));

        sut.check().block();
        verify(publisher).publishInfo(anyString());
        verify(publisher, never()).publishReminder(anyString());
        assertThat(rows).isEmpty();

        sut.check().block();
        verify(publisher).publishAlert("The weather station WS90 has sent no reading since 2026-10-16 08:00 (2 h 0 min).");
    }

    //an alert about a station the registry no longer has says nothing about the new one
    @Test
    void should_close_an_alert_of_another_station_without_a_word() {
        rows.put(1L, new StationAlertEntity(1L, 70L, Watch.STATION, START.minus(Duration.ofHours(3)), START.minus(Duration.ofHours(2))));
        currentWeather.heard(START);

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(rows).isEmpty();
    }

    //a restart in the middle of an outage: the row says it was told already
    @Test
    void should_go_on_with_the_reminders_after_a_restart() {
        rows.put(1L, new StationAlertEntity(1L, 55L, Watch.STATION, START.minus(Duration.ofHours(2)), START.minus(Duration.ofMinutes(30))));
        currentWeather.heard(START.minus(Duration.ofHours(2)));

        sut.check().block();
        verify(publisher, never()).publishAlert(anyString());
        verify(publisher, never()).publishReminder(anyString());

        clock.move(Duration.ofMinutes(30));
        sut.check().block();
        verify(publisher).publishReminder(anyString());
    }

    //over means a sign of life after the start of the alert: a new station not heard yet ends nothing
    @Test
    void should_end_no_alert_without_a_sign_of_life() {
        rows.put(1L, new StationAlertEntity(1L, 55L, Watch.STATION, START.minus(Duration.ofHours(2)), START.minus(Duration.ofMinutes(10))));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(rows).containsKey(1L);
    }

    //the state moves on only once the broker has taken the message: the next pass tries again
    @Test
    void should_alert_again_at_the_next_pass_when_the_alert_failed() {
        when(publisher.publishAlert(anyString()))
            .thenReturn(Mono.error(new NotificationPublishException("Notification not confirmed by the broker")))
            .thenReturn(Mono.empty());
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(30));

        sut.check().as(StepVerifier::create).verifyComplete();
        assertThat(rows).isEmpty();

        clock.move(Duration.ofMinutes(5));
        sut.check().block();
        assertThat(rows).hasSize(1);
    }

    //a send that hangs before the confirm must not stall the pass
    @Test
    void should_cut_off_a_notification_that_hangs() {
        when(publisher.publishAlert(anyString())).thenReturn(Mono.never());
        currentWeather.heard(START);
        clock.move(Duration.ofMinutes(30));

        StepVerifier.withVirtualTime(sut::check)
            .thenAwait(StationWatch.PUBLISH_TIMEOUT.multipliedBy(2))
            .verifyComplete();

        assertThat(rows).isEmpty();
    }

    @Test
    void should_complete_when_the_alerts_cannot_be_read() {
        when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("database away")));
        clock.move(Duration.ofHours(1));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
    }

    @Test
    void should_judge_nothing_without_a_station() {
        when(weatherStation.station()).thenReturn(Optional.empty());
        clock.move(Duration.ofHours(1));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(repository, publisher);
    }

    //another station is watched from the moment it replaced the last one
    @Test
    void should_count_a_new_station_from_when_it_came() {
        sut.check().block();
        clock.move(Duration.ofMinutes(25));
        when(weatherStation.station()).thenReturn(Optional.of(new Station(70, "WS90 garden", "aa:bb:cc:dd:ee:ff", 54,
            "shellyplugsg3-28372f2f0460", "10.78.30.36")));
        sut.check().block();

        clock.move(Duration.ofMinutes(25));
        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
    }

    //the Mono of the scheduled refresh is subscribed again for every run: the time is read each time
    @Test
    void should_read_the_time_again_on_every_subscription() {
        currentWeather.heard(START);
        final Mono<Void> check = sut.check();
        check.block();

        clock.move(Duration.ofMinutes(30));
        check.block();

        verify(publisher).publishAlert(anyString());
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void move(final Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Warsaw");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
