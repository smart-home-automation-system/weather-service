package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.weather.model.Station;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//The station and its gateway out of the two lists of the registry. The house has one of each; a
//second one is a registry to correct, said at WARN, and the lowest id is taken so that the choice
//does not change from one read to the next. A device without what it is reached by is skipped
//(the registry requires it, so it is a defect, not a state)
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StationMapper {

    public static Optional<Station> toStation(final List<SmartDevice> stations, final List<SmartDevice> gateways) {
        final Optional<SmartDevice> station = first(stations.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getBthome() != null && device.getBthome().getAddress() != null)
            .toList(), "weather station");
        final Optional<SmartDevice> gateway = first(gateways.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getShelly() != null
                && device.getShelly().getDeviceId() != null
                && device.getShelly().getAddress() != null)
            .toList(), "bthome gateway");

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

    private static Optional<SmartDevice> first(final List<SmartDevice> devices, final String kind) {
        if (devices.size() > 1) {
            log.warn("The device registry has {} switched-on devices of kind {}, the one with the lowest id is used: {}",
                devices.size(), kind, devices.stream().map(SmartDevice::getId).sorted().toList());
        }
        return devices.stream().min(Comparator.comparing(SmartDevice::getId));
    }
}
