package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.model.Metric;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

//One metric of weather_reading from "from" up to, not including, "to", in buckets of "bucketSeconds",
//oldest first - the query of the hot-water history of water-service. measured_at is the wall-clock time
//of the house without a zone, and the epoch of such a value counts from its own midnight, so a bucket
//that divides a day starts on the clock of the house (00:00, 00:05, ...), whatever "from" is. A row
//without the metric (NULL - never received, or a value that did not fit) is no reading of it, so a
//bucket of such rows only has no point. The database aggregates: a month is up to 44 000 rows, read
//through the index on measured_at (V1) and answered as 372.
//A column cannot be a parameter of a statement, hence the SQL is built here - from the map below only,
//never from the request. PostgreSQL only: WeatherHistoryRepositoryTest runs it in Docker
@Repository
@RequiredArgsConstructor
public class WeatherHistoryRepository {

    private static final Map<Metric, String> COLUMNS = new EnumMap<>(Map.ofEntries(
        Map.entry(Metric.TEMPERATURE, "temperature"),
        Map.entry(Metric.HUMIDITY, "humidity"),
        Map.entry(Metric.DEW_POINT, "dew_point"),
        Map.entry(Metric.PRESSURE, "pressure"),
        Map.entry(Metric.ILLUMINANCE, "illuminance"),
        Map.entry(Metric.UV, "uv"),
        Map.entry(Metric.WIND, "wind"),
        Map.entry(Metric.GUST, "gust"),
        Map.entry(Metric.WIND_DIRECTION, "wind_direction"),
        Map.entry(Metric.RAIN, "raining"),
        Map.entry(Metric.PRECIPITATION, "precipitation"),
        Map.entry(Metric.BATTERY, "battery"),
        Map.entry(Metric.VOLTAGE, "voltage")
    ));

    private static final String HISTORY = """
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM measured_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               round(avg(%1$s), 2) AS avg,
               min(%1$s) AS min,
               max(%1$s) AS max
        FROM weather_reading
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %2$s IS NOT NULL
        GROUP BY at
        ORDER BY at
        """;

    private final DatabaseClient databaseClient;

    public Flux<WeatherHistoryReply.Point> findHistory(
        final Metric metric,
        final LocalDateTime from,
        final LocalDateTime to,
        final long bucketSeconds
    ) {
        return databaseClient.sql(HISTORY.formatted(value(metric), COLUMNS.get(metric)))
            .bind("bucketSeconds", bucketSeconds)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new WeatherHistoryReply.Point(
                row.get("at", LocalDateTime.class),
                row.get("avg", BigDecimal.class),
                row.get("min", BigDecimal.class),
                row.get("max", BigDecimal.class)))
            .all();
    }

    //as numeric, whatever the type of the column: the codec reads the three aggregates as BigDecimal.
    //The rain is a flag - 1 when it rained, 0 when not
    private static String value(final Metric metric) {
        return metric.isFlag()
            ? "(CASE WHEN %s THEN 1 ELSE 0 END)::numeric".formatted(COLUMNS.get(metric))
            : COLUMNS.get(metric) + "::numeric";
    }
}
