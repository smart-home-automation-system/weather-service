package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.weather.model.Station;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//The station and its gateway out of the two lists of the registry. The house has one of each; with
//a second one the lowest id is taken, so that the choice does not change from one read to the next,
//and duplicates() says what was found for WeatherStation to log once. A device without what it is
//reached by is skipped (the registry requires it, so it is a defect, not a state)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StationMapper {

    public static Optional<Station> toStation(final List<SmartDevice> stations, final List<SmartDevice> gateways) {
        final Optional<SmartDevice> station = usableStations(stations).stream().findFirst();
        final Optional<SmartDevice> gateway = usableGateways(gateways).stream().findFirst();
        if (station.isEmpty() || gateway.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Station(
            station.get().getId(),
            station.get().getName(),
            station.get().getBthome().getAddress(),
            gateway.get().getId(),
            gateway.get().getShelly().getDeviceId(),
            gateway.get().getShelly().getAddress()
        ));
    }

    //"weather station [55, 70]; bthome gateway [54, 80]" for a kind with more than one, "" otherwise
    public static String duplicates(final List<SmartDevice> stations, final List<SmartDevice> gateways) {
        final List<String> found = new ArrayList<>();
        describe(found, "weather station", usableStations(stations));
        describe(found, "bthome gateway", usableGateways(gateways));
        return String.join("; ", found);
    }

    private static List<SmartDevice> usableStations(final List<SmartDevice> stations) {
        return byId(stations.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getBthome() != null && device.getBthome().getAddress() != null)
            .toList());
    }

    private static List<SmartDevice> usableGateways(final List<SmartDevice> gateways) {
        return byId(gateways.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getShelly() != null
                && device.getShelly().getDeviceId() != null
                && device.getShelly().getAddress() != null)
            .toList());
    }

    private static List<SmartDevice> byId(final List<SmartDevice> devices) {
        return devices.stream().sorted(Comparator.comparing(SmartDevice::getId)).toList();
    }

    private static void describe(final List<String> found, final String kind, final List<SmartDevice> devices) {
        if (devices.size() > 1) {
            found.add(kind + " " + devices.stream().map(SmartDevice::getId).toList());
        }
    }
}
