-- The air quality outside the house (HAS-428, epic HAS-423): the BleBox airSensor read over HTTP since
-- HAS-427 rides the rows of the weather - one snapshot of the whole current state a minute, written only
-- when something changed. PM1, PM2.5 and PM10 in ug/m3 as the sensor gives them, whole numbers; the level
-- of the air 1 - 6 (the GIOS scale). Nullable: a metric never received is NULL, as for the weather, and
-- every row written before this migration has none. No CHECK on the values, the rule of V1: a reading
-- the sensor got wrong is still what it said, and a refused insert would lose the whole minute.
-- The retention of weather_reading deletes them with their rows - nothing to add for them
ALTER TABLE weather_reading
    ADD COLUMN pm1               SMALLINT,
    ADD COLUMN pm25              SMALLINT,
    ADD COLUMN pm10              SMALLINT,
    ADD COLUMN air_quality_level SMALLINT;
