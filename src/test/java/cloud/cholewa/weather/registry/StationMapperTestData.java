package cloud.cholewa.weather.registry;

import cloud.cholewa.home.model.BthomeBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.ShellyBinding;
import cloud.cholewa.home.model.SmartDevice;

//the station and the gateway of the house as the registry answers them (ids 55 and 54)
public final class StationMapperTestData {

    private StationMapperTestData() {
    }

    public static SmartDevice station(final long id, final String address) {
        return SmartDevice.builder()
            .id(id)
            .name("Weather station")
            .kind(DeviceKind.WEATHER_STATION)
            .bthome(BthomeBinding.builder().address(address).build())
            .build();
    }

    public static SmartDevice gateway(final long id, final String deviceId) {
        return SmartDevice.builder()
            .id(id)
            .name("Treadmill")
            .kind(DeviceKind.BTHOME_GATEWAY)
            .shelly(ShellyBinding.builder().address("10.78.30.36").generation(3).deviceId(deviceId).build())
            .build();
    }
}
