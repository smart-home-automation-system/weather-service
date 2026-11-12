package cloud.cholewa.weather.air;

import java.util.List;

//What GET /api/air/state of a BleBox airSensor answers (apiLevel 20210118) - the wire, kept apart from
//the readings it becomes (AirReadings). Read on 2026-11-12: an object air with airQualityLevel 1 and three
//sensors - pm1 7, pm2.5 12 and pm10 12 ug/m3, each in state 0, measured 374 s before the read.
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
