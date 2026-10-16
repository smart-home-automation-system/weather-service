-- The readings of the weather station: a snapshot of the current state once a minute, not a row
-- per BTHome packet - the gateway publishes a sensor only when its value changes, so a packet never
-- arrives whole. A row is written only when something arrived since the last one; a metric the
-- service never received is NULL. measured_at is the newest last_updated_ts of the metrics in the
-- row, house time. No CHECK on the values: a reading the station got wrong is still what it said,
-- and a refused insert would lose the whole minute.
-- The scales follow what the gateway reports (HAS-234): dew point and voltage with two decimals,
-- illuminance and the wind direction as whole numbers; precipitation is the running total of the
-- station, its unit is checked before the read API shows it (HAS-238)
CREATE TABLE weather_reading
(
    id             BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    measured_at    TIMESTAMP          NOT NULL,
    temperature    NUMERIC(4, 1),
    humidity       SMALLINT,
    dew_point      NUMERIC(5, 2),
    pressure       NUMERIC(6, 2),
    illuminance    INTEGER,
    uv             NUMERIC(3, 1),
    wind           NUMERIC(5, 2),
    gust           NUMERIC(5, 2),
    wind_direction SMALLINT,
    raining        BOOLEAN,
    precipitation  NUMERIC(6, 1),
    battery        SMALLINT,
    voltage        NUMERIC(4, 2)
);

-- the read API asks for the latest rows and for a period back from now
CREATE INDEX weather_reading_measured_at_idx ON weather_reading (measured_at DESC);
