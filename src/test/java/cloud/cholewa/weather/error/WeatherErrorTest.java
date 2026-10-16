package cloud.cholewa.weather.error;

import cloud.cholewa.commons.error.model.ErrorId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherErrorTest {

    //the codes are wire contract: the dashboard words them, a renamed constant still compiles
    @Test
    void should_keep_the_codes_callers_branch_on() {
        assertThat(Arrays.stream(WeatherError.values()).map(ErrorId::codeOf)).containsExactly("UNKNOWN_METRIC");
    }
}
