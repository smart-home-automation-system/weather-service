-- The alerts about the weather station that are open right now: one row per watch (STATION - no
-- reading, GATEWAY - no answer to the read of its components), removed when it is fine again - the
-- shape of temperature_sensor_alert of heating-service. In the database, not in memory, so that a
-- restart in the middle of an outage goes on with reminders instead of a second alert, and still says
-- when it is over.
-- Instants (timestamptz), unlike measured_at of weather_reading: a reminder is due by the time passed,
-- and house time runs an hour backwards the night the clocks go back
CREATE TABLE station_alert
(
    id            BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    watch         VARCHAR(20)        NOT NULL UNIQUE,
    since         TIMESTAMPTZ        NOT NULL,
    last_alert_at TIMESTAMPTZ        NOT NULL
);
