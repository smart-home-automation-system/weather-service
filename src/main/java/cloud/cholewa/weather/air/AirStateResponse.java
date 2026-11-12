package cloud.cholewa.weather.air;

import java.util.List;

//What GET /api/air/state of a BleBox airSensor answers (apiLevel 20210118) - the wire, kept apart from
//the readings it becomes (AirReadings). Read on 2026-11-12:
//{"air":{"airQualityLevel":1,"sensors":[{"type":"pm1","value":7,"trend":3,"state":0,"qualityLevel":-1,
//"elapsedTimeS":374},{"type":"pm2.5",...},{"type":"pm10",...}]}}
//Every field may be missing; what is not there is null, and {} has no air at all
public record AirStateResponse(Air air) {

    public record Air(Integer airQualityLevel, List<Sensor> sensors) {
    }

    //type pm1 / pm2.5 / pm10; value in ug/m3; state 0 - 3, 3 an error; elapsedTimeS the seconds since the
    //last valid measurement, -1 for none. trend and qualityLevel are not served (the dashboard judges the
    //level from the value and the trend from the history)
    public record Sensor(String type, Integer value, Integer trend, Integer state, Integer qualityLevel, Long elapsedTimeS) {
    }
}
