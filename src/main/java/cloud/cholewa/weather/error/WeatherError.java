package cloud.cholewa.weather.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a request the read API refuses. Their names are the codes of the answer
//(ErrorId.codeOf) - wire contract for a caller that branches on them, the dashboard first of all,
//pinned by WeatherErrorTest. A range of the history that breaks a rule is a 400 without a code, as the
//hot-water history answers it
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum WeatherError implements ErrorId {

    //the metric of the history is none of the names of the read API
    UNKNOWN_METRIC("Unknown metric");

    private final String description;
}
