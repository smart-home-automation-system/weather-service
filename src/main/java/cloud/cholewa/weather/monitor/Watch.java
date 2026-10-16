package cloud.cholewa.weather.monitor;

//What StationWatch watches; the name is stored in station_alert.watch - renaming a constant needs a
//migration of the rows
public enum Watch {
    //the station sends no reading
    STATION,
    //the gateway does not answer the read of its components
    GATEWAY
}
