package cloud.cholewa.weather.model;

//The weather station and the gateway that hears it, as the device registry has them: the station is
//found by its Bluetooth address, the gateway by the id it publishes under (the first part of its
//topics) and called at its address. The registry does not bind a Bluetooth sensor to a gateway, so
//the pair is made here - the one gateway of the house with the station on it
public record Station(
    long stationId,
    String stationName,
    String stationAddress,
    long gatewayId,
    String gatewayDeviceId,
    String gatewayAddress
) {
}
