-- The episodes of smog outside the house (HAS-429): the level of the air held at the smog level for a while
-- opens one, a clear level held as long closes it - one household message at the start and one at the end.
-- In the database, not in memory, so that a restart in the middle of an episode goes on with it instead of a
-- second message, and still says when it is over. The rows of ended episodes stay - their peaks are the
-- history of the smog; the retention of weather_reading does not touch them.
-- sensor_id is the id of the air quality sensor in the device registry: an open episode of a sensor the
-- registry no longer has is closed without a word. level is the highest level of the episode, peak_pm10 and
-- peak_pm25 its highest particles (ug/m3).
-- Instants (timestamptz), as station_alert: a hold is judged by the time passed
CREATE TABLE air_quality_alert
(
    id        BIGINT PRIMARY KEY NOT NULL GENERATED ALWAYS AS IDENTITY,
    sensor_id BIGINT             NOT NULL,
    since     TIMESTAMPTZ        NOT NULL,
    ended_at  TIMESTAMPTZ,
    level     SMALLINT           NOT NULL,
    peak_pm10 SMALLINT,
    peak_pm25 SMALLINT
);

-- one episode open at a time
CREATE UNIQUE INDEX air_quality_alert_one_open_uq ON air_quality_alert ((ended_at IS NULL)) WHERE ended_at IS NULL;
