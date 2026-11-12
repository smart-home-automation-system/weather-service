package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.BleboxBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.weather.model.AirSensor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AirSensorMapperTest {

    private static SmartDevice sensor(final long id, final String address) {
        return SmartDevice.builder().id(id).name("Air quality sensor").room(RoomName.GARDEN)
            .kind(DeviceKind.AIR_QUALITY_SENSOR).enabled(true).vendor(SmartVendor.BLEBOX)
            .blebox(address == null ? null : BleboxBinding.builder().address(address).deviceId("aa48fad9faa6").build())
            .build();
    }

    @Test
    void should_take_the_sensor_with_its_address_and_device_id() {
        assertThat(AirSensorMapper.toAirSensor(List.of(sensor(145, "10.78.30.23"))))
            .contains(new AirSensor(145, "Air quality sensor", "10.78.30.23", "aa48fad9faa6"));
        assertThat(AirSensorMapper.duplicates(List.of(sensor(145, "10.78.30.23")))).isEmpty();
    }

    //with a second one the lowest id, so the choice does not change between reads
    @Test
    void should_take_the_lowest_id_of_two_and_name_both() {
        final List<SmartDevice> two = List.of(sensor(150, "10.78.30.24"), sensor(145, "10.78.30.23"));

        assertThat(AirSensorMapper.toAirSensor(two)).get().extracting(AirSensor::id).isEqualTo(145L);
        assertThat(AirSensorMapper.duplicates(two)).isEqualTo("air quality sensor [145, 150]");
    }

    @Test
    void should_skip_a_device_without_its_binding_and_find_none_in_an_empty_list() {
        assertThat(AirSensorMapper.toAirSensor(Arrays.asList(sensor(145, null), null))).isEmpty();
        assertThat(AirSensorMapper.toAirSensor(List.of())).isEmpty();
    }
}
