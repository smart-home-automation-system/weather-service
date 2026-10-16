package cloud.cholewa.weather.history;

import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.database.repository.WeatherHistoryRepository;
import cloud.cholewa.weather.error.WeatherException;
import cloud.cholewa.weather.model.Metric;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class WeatherHistoryService {

    private final WeatherHistoryRepository repository;
    private final OneAtATime oneAtATime = new OneAtATime();

    public WeatherHistoryService(final WeatherHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * The stored readings of one metric from {@code from} up to, not including, {@code to}, in buckets
     * whose width follows from the length of the range. A name that is no metric of the read API is a
     * 400 with the code UNKNOWN_METRIC, a range that breaks a rule a 400 without one. A range without
     * a reading is answered with no points. One range at a time.
     */
    public Mono<WeatherHistoryReply> queryHistory(final String metricName, final LocalDateTime from, final LocalDateTime to) {
        return Mono.defer(() -> {
            final Metric metric = Metric.ofApiName(metricName).orElse(null);
            if (metric == null) {
                return Mono.error(WeatherException.unknownMetric(metricName));
            }
            return HistoryRange.violation(from, to)
                .<Mono<WeatherHistoryReply>>map(Mono::error)
                .orElseGet(() -> {
                    final long bucketSeconds = HistoryRange.bucket(from, to).toSeconds();

                    return oneAtATime.run(repository.findHistory(metric, from, to, bucketSeconds).collectList())
                        .map(points -> new WeatherHistoryReply(metric.apiName(), from, to, bucketSeconds, points));
                });
        });
    }
}
