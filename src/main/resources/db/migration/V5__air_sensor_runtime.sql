-- The hours the laser of the air quality sensor has run (HAS-437), read from GET /api/air/runtime once an hour.
-- One row per sensor (sensor_id is its id in the device registry), written on a change only - another hour, or
-- another warning: the time of the read is held in memory, read_at is the time of the read that changed the row.
-- It brings the counter back at a start, so that /status shows it before the first read and a dropped counter
-- (a replaced or reset sensor) is seen across a restart. warned_at_hours is the step of the last warning to
-- order a new sensor (weather.air.laser.warn-at-hours, then every 500 h more) - null before the first: a
-- restart never repeats a warning. Instants (timestamptz), as station_alert
CREATE TABLE air_sensor_runtime
(
    id              BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    sensor_id       BIGINT             NOT NULL,
    hours           BIGINT             NOT NULL,
    read_at         TIMESTAMPTZ        NOT NULL,
    warned_at_hours BIGINT,
    CONSTRAINT air_sensor_runtime_sensor_uq UNIQUE (sensor_id)
);
