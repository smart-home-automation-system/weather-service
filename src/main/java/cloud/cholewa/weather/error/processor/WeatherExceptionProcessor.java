package cloud.cholewa.weather.error.processor;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.weather.error.WeatherException;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;

//Answers a refused request with the status and the code of its cause; the caller's mistake, a WARN,
//with its details answered
@Slf4j
public class WeatherExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final WeatherException exception = (WeatherException) throwable;
        final String code = ErrorId.codeOf(exception.getError());

        log.warn("Weather request refused ({}, {}): {}", exception.getHttpStatus().value(), code, exception.getMessage());

        return Errors.builder()
            .httpStatus(exception.getHttpStatus())
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(exception.getError().getDescription())
                    .details(exception.getMessage())
                    .code(code)
                    .build()
            ))
            .build();
    }
}
