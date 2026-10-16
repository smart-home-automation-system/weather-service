package cloud.cholewa.weather.model;

import java.time.LocalDateTime;

//How the gateway hears the station, from the status of its bthomedevice component: the signal, the
//battery the station reported last and the id of its last packet, with the time the service got the
//status. A field the gateway left out is null (the battery comes only with some of the packets)
public record StationLink(Integer rssi, Integer battery, Integer packetId, LocalDateTime receivedAt) {
}
