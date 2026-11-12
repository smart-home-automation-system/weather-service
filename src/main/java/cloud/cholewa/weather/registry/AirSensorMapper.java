package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.weather.model.AirSensor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

//The air quality sensor out of the list of the registry (HAS-427): the rules of the station - one is
//expected, with a second the lowest id is taken, so that the choice does not change from one read to the
//next, and duplicates() names what was found for AirQuality to log once. A device without its BleBox
//binding is skipped (the registry requires it, so it is a defect, not a state)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AirSensorMapper {

    public static Optional<AirSensor> toAirSensor(final List<SmartDevice> sensors) {
        return usable(sensors).stream()
            .findFirst()
            .map(device -> new AirSensor(device.getId(), device.getName(), device.getBlebox().getAddress(),
                device.getBlebox().getDeviceId()));
    }

    //"air quality sensor [145, 150]" with more than one, "" otherwise
    public static String duplicates(final List<SmartDevice> sensors) {
        final List<SmartDevice> usable = usable(sensors);
        return usable.size() > 1 ? "air quality sensor " + usable.stream().map(SmartDevice::getId).toList() : "";
    }

    private static List<SmartDevice> usable(final List<SmartDevice> sensors) {
        return sensors.stream()
            .filter(Objects::nonNull)
            .filter(device -> device.getId() != null && device.getBlebox() != null && device.getBlebox().getAddress() != null)
            .sorted(Comparator.comparing(SmartDevice::getId))
            .toList();
    }
}
