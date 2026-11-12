package cloud.cholewa.weather.model;

//The air quality sensor outside the house as the device registry has it (HAS-427): a BleBox airSensor of
//the kind air quality sensor, polled at its address over HTTP; the device id is the one of its GET /info
public record AirSensor(long id, String name, String address, String deviceId) {
}
