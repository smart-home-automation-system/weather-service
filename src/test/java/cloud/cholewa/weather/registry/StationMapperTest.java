package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.weather.model.Station;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cloud.cholewa.weather.registry.StationMapperTestData.gateway;
import static cloud.cholewa.weather.registry.StationMapperTestData.station;
import static org.assertj.core.api.Assertions.assertThat;

class StationMapperTest {

    @Test
    void should_pair_the_station_with_the_gateway() {
        assertThat(StationMapper.toStation(List.of(station(55, "08:b9:5f:d1:e6:71")), List.of(gateway(54, "shellyplugsg3-28372f2f0460"))))
            .contains(new Station(55, "Weather station", "08:b9:5f:d1:e6:71", 54, "shellyplugsg3-28372f2f0460", "10.78.30.36"));
    }

    @Test
    void should_know_no_station_without_a_gateway_or_without_a_station() {
        assertThat(StationMapper.toStation(List.of(station(55, "08:b9:5f:d1:e6:71")), List.of())).isEmpty();
        assertThat(StationMapper.toStation(List.of(), List.of(gateway(54, "shellyplugsg3-28372f2f0460")))).isEmpty();
    }

    //a second one is a registry to correct: the lowest id, whatever the order of the answer
    @Test
    void should_take_the_lowest_id_of_two() {
        final Station station = StationMapper.toStation(
            List.of(station(70, "aa:bb:cc:dd:ee:ff"), station(55, "08:b9:5f:d1:e6:71")),
            List.of(gateway(80, "shellypro1pm-8813bfda5e94"), gateway(54, "shellyplugsg3-28372f2f0460"))
        ).orElseThrow();

        assertThat(station.stationId()).isEqualTo(55);
        assertThat(station.gatewayId()).isEqualTo(54);
    }

    @Test
    void should_say_what_the_registry_has_twice() {
        assertThat(StationMapper.duplicates(
            List.of(station(70, "aa:bb:cc:dd:ee:ff"), station(55, "08:b9:5f:d1:e6:71")),
            List.of(gateway(54, "shellyplugsg3-28372f2f0460"))))
            .isEqualTo("weather station [55, 70]");
        assertThat(StationMapper.duplicates(List.of(station(55, "08:b9:5f:d1:e6:71")),
            List.of(gateway(54, "shellyplugsg3-28372f2f0460")))).isEmpty();
    }

    @Test
    void should_skip_a_device_without_what_it_is_reached_by() {
        final SmartDevice gatewayWithoutId = gateway(54, null);
        final SmartDevice stationWithoutAddress = station(55, null);

        assertThat(StationMapper.toStation(List.of(stationWithoutAddress), List.of(gateway(54, "shellyplugsg3-28372f2f0460")))).isEmpty();
        assertThat(StationMapper.toStation(List.of(station(55, "08:b9:5f:d1:e6:71")), List.of(gatewayWithoutId))).isEmpty();
    }
}
