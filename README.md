# weather-service

[![CI](https://github.com/smart-home-automation-system/weather-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/weather-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/weather-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/weather-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/weather-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/weather-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/weather-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/weather-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/weather-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/weather-service?style=plastic)

---


# Description

The weather station of the house — a Shelly WS90 — read locally: its readings kept and served to
the dashboard, the blinds schedules and the overview (epic HAS-233). The station broadcasts
BTHome packets and has no Wi-Fi; a Shelly with the BTHome components adopts it and publishes the
readings over MQTT.

Since HAS-236 the service **takes in the readings and stores them**, since HAS-237 it **reconciles
with the gateway and tells the household when the station goes silent**, since HAS-238 it **serves
the current weather, the status of the station and the history** (reads only), and since HAS-427 it
**reads the air quality outside the house** - a BleBox airSensor polled over HTTP.

Everything is non-blocking (Spring WebFlux / Reactor):

- **The station and its gateway** come from the device registry of `database-service`: the
  switched-on device of kind `weather station` (its Bluetooth address) and the one of kind
  `bthome gateway` (the id it publishes under and its address), read at the start and every five
  minutes. The registry does not bind a Bluetooth device to a gateway, so the house has one of each;
  a second one is a WARN and the lowest id is taken. A failed read keeps the last station.
- **The map of the sensors** comes from the gateway: `Shelly.GetComponents` (dynamic components,
  page by page, through `shelly-client`) says which `bthomesensor:<id>` carries which BTHome object
  of the station - temperature, humidity, dew point, pressure, illuminance, UV index, wind speed and
  gust, wind direction, rain, precipitation, battery, voltage (13 metrics) - and which
  `bthomedevice:<id>` is the station. Read with every refresh, every five minutes - the
  **reconciliation with the gateway** (HAS-237): each read also brings what every sensor holds now
  into the current state when it is newer than the state, so a status the broker lost, a value that
  never changes (the battery, the rain for days - never published) and the first state after a
  deploy all come in within five minutes. A component the service cannot use is a WARN once, again
  only after a read without it.
- **Device events** arrive from RabbitMQ, virtual host `/device` (user `device`), queue
  `device.prod.weather` (`device.dev.weather` in the `local` profile). The queue is bound to
  `amq.topic` with `#` - every event of every device, about 130 a minute; the routing key decides
  before any body is read. `<gateway>.status.bthomesensor:<id>` of a sensor of the station updates
  the **current state in memory**: per metric the last value and when the station measured it. The
  gateway publishes a sensor only when its value changes, so the state is made of the changes; the
  same reading again or an older one changes nothing. A sensor of another Bluetooth device on the
  gateway is ignored without a word, one the map does not have is a WARN (once per map).
  `<gateway>.status.bthomedevice:<id>` gives the signal and the battery of the station.
- **The air quality sensor** (HAS-427, epic HAS-423) is the switched-on device of kind
  `air quality sensor` of the registry (its BleBox binding: the address and the id of `GET /info`), read
  with every refresh of the station - one is expected, a second is a WARN once and the lowest id is
  taken, a failed read keeps the last one, another sensor clears what the last one measured. The
  sensor pushes nothing, so the service reads `GET http://<address>/api/air/state` **every minute**
  (not configurable, 5 s response timeout; `/s/kick` - a measurement at once - is never sent, it
  shortens the life of the laser). PM1, PM2.5 and PM10 in µg/m³ and the level of the air: the device
  has no clock, so a reading is measured at the time of the read minus its `elapsedTimeS`; a sensor in
  error (`state` 3), without a measurement (`elapsedTimeS` -1) or without a value is no reading; the
  same value read again confirms it. Held in memory apart from the station (its silence is not the
  station's); stored in `weather_reading` beside the weather since HAS-428. A failed read keeps the readings and is one WARN an hour per kind
  of failure.
- **Once a minute** the state becomes a row of `weather_reading` - only when something changed since
  the last row; each row is logged at INFO. At the start the state is restored from the latest row
  before anything is stored, and when the pod stops the state is stored once more. **Every night at
  03:10** the rows older than `weather.retention` (default 730 days; a bare number is days, 30 days
  to 10 years) are deleted - never the latest one, which the state is restored from.
- **The watch over the air quality sensor and the smog** (HAS-429), after every read of the sensor (every
  minute): no valid reading for `weather.air.silent-after` (30 min; 10 min - 24 h, a bare number is minutes) is
  an alert on the technical channel, reminded every `weather.monitor.reminder-interval`, one info when a valid
  reading is back (the row of `station_alert` with the watch `AIR_SENSOR`). **Smog** is a level of the air at
  `weather.air.smog-level` (5 - bad; 2 - 6) or above held for `weather.air.smog-after` (30 min; 5 min - 6 h): it
  opens an episode with one household message (`smog warning`, the particles of the reading); a level at
  `weather.air.clear-level` (3 - moderate; below the smog level) or below held as long closes it (`smog over`).
  The hold is judged by the times the sensor measured; no reminders, no SMS (owner). The messages are the JSON
  `HouseholdNotification` of smart-home-sdk 1.21.0 through the `household` category, worded by
  notification-service (which must read the two events first - HAS-430). The episodes are rows of
  `air_quality_alert` (V4), so a restart goes on with the open one.
- **The laser of the air quality sensor** (HAS-437): `GET /api/air/runtime` once an hour (not configurable) -
  the hours the laser has run, a whole number from 0 up (anything else is a failed read, a WARN, the last value
  kept). BleBox gives the measuring system about `weather.air.laser.life-hours` (8000; 1000 - 100 000); at
  `weather.air.laser.warn-at-hours` (7000; 500 - 100 000, below the life) the technical channel is told to order
  a new sensor (level `warn`), again every 500 h more; a counter that drops (a replaced or reset sensor) is one
  info, and the warnings start anew. Never the household, never an SMS. The counter and the step of the last
  warning are the row of the sensor in `air_sensor_runtime` (V5), written on a change only, so a restart neither
  forgets the counter nor repeats a warning.
- **The watch over the station** (HAS-237), after every refresh: no reading from the station for
  `weather.monitor.silent-after` (default 30 min, 10 min - 24 h) is an **alert** (level `error`),
  repeated as a **reminder** (`warn`) every `weather.monitor.reminder-interval` (default 1 h,
  15 min - 7 days) while it lasts, and ends with one **info** when a reading comes again. A gateway
  that does not answer the read for the same time is a second alert of the same kind. "Heard" is the
  newest `last_updated_ts` of any reading of the station or of its `bthomedevice` (the gateway's
  clock; a time more than a minute ahead of the service is not taken); without one since the start the
  time counts from the start, and the alert says so. The open alerts are rows of `station_alert`, so
  a restart goes on with the reminders. A notification never fails or stalls the pass (cut off after
  30 s, logged, tried again with the next pass).
- **Notifications** go to the `notification` exchange of the virtual host `/notification` over a
  second connection (`NotificationPublisher` of `cholewa-commons`), named `<pod>/notification`;
  `env` is `prod`, `dev` in the `local` profile.

# Run locally

- Build: `mvn verify` (JDK 21) - needs a running Docker: the repository test runs on a PostgreSQL
  in Testcontainers, the migration included.
- Ports: local profile `6010` (management `8010`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The probes hit
  `/actuator/health/{readiness,liveness}` and Prometheus scrapes `/actuator/prometheus`.
- Profiles `home,local`. The broker is `localhost:5672`; without `rabbitmq-password` and
  `notification-rabbitmq-password` a local run starts with a dummy password and neither
  consumes nor publishes. In the `home` profile both variables are mandatory — without one
  the service does not start (`cholewa.required-properties` of `cholewa-commons`), and says
  which one to set.
- `database-service` is called on its local port 6005; a local run reads the real gateway named in
  the registry (`Shelly.GetComponents`, reads only).
- The database connection properties (`database-host`, `database-port`, `database-name`,
  `database-user`, `database-password`) are bound to the `database.*` prefix that
  `cholewa-commons` consumes; Flyway derives its JDBC URL from them (`--flyway-url=...`
  overrides it). Only `database.pool.max-size: 2` is pinned here, this service's share of the
  22 backend connections of the managed database. **With the real values a local run writes to the
  production database** - rehearse on a throwaway PostgreSQL instead (CLAUDE.md).

# API

Base path `/home/weather` (`spring.webflux.base-path`), reads only. Not routed by
`api-gateway-service` yet (HAS-239): reachable inside the cluster or through a port-forward. Every
date-time is house time without an offset.

| Method | Path | What |
|---|---|---|
| GET | `/current` | per metric `{value, measuredAt, stale}` |
| GET | `/status` | `gateway {deviceId, reachable, checkedAt}`, `lastReadingAt`, `silent`, `airSensor {address, reachable, checkedAt, lastReadingAt, silent, smog {since, level}, laser {hours, readAt, lifeHours, warn}}` (HAS-427, HAS-429, HAS-437) |
| GET | `/history?metric=&from=&to=` | one metric over a range: `metric`, `from`, `to`, `bucketSeconds`, `points: [{at, avg, min, max}]` |

**Metrics** (the names of `/current` and of `metric`; the unit is the one the gateway reports):

| Metric | Unit | Notes |
|---|---|---|
| `temperature` | °C | |
| `humidity` | % | |
| `dewPoint` | °C | |
| `pressure` | hPa | at the station, not reduced to sea level |
| `illuminance` | lx | |
| `uv` | UV index | |
| `wind` | m/s | |
| `gust` | m/s | |
| `windDirection` | ° | history: the circular mean (350 and 10 average to 0), no `min` / `max` |
| `raining` | `true` / `false` | history: 1 / 0 - `avg` is the share of the readings with rain |
| `precipitation` | **not confirmed** | the running total of the station as the gateway reports it (4311 in October 2026); compare with the app on the first rain. History: `max - min` of a bucket is its rain |
| `battery` | % | |
| `voltage` | V | |
| `pm1` | µg/m³ | the air quality sensor (HAS-427), a whole number; history since HAS-428 |
| `pm25` | µg/m³ | PM2.5, the same |
| `pm10` | µg/m³ | PM10, the same |
| `airQualityLevel` | 1 - 6 | the level the sensor gives (the GIOS scale, 1 very good ... 6 very bad), measured with the newest of the three |

- `/current`: a metric never received is missing - never `null` or zero; `{}` before the first
  reading. `measuredAt` is the newest time the station is known to have had the value (a value that
  does not change is confirmed by every read of the gateway), `stale` is true when that is older
  than `weather.monitor.silent-after` (30 min), the threshold of the alerts.
- `/status`: what is not known yet is left out (`gateway` without a station in the registry,
  `reachable` / `checkedAt` before the first read of the gateway, `lastReadingAt` before anything
  was heard; `airSensor` without a sensor in the registry, its `reachable` / `checkedAt` before its first
  read, its `lastReadingAt` before it measured, its `laser` before the counter was first read - or brought back
  from its row). `laser.warn` is true from `weather.air.laser.warn-at-hours`. `silent` - of the station alone - is true when nothing was heard for 30 min - or at all since the start, while
  the alert waits for the threshold.
- `/history`: the rules of the hot-water history of water-service. `from` and `to` are local
  date-times without an offset (`2026-10-16T00:00:00`), `from` before `to`, the start included
  and the end not, at most 31 days, years 2000-9999 - anything else is a 400 without a code. The
  **buckets** are chosen by the service and named in `bucketSeconds`: **5 min** for up to 2 days,
  **30 min** up to 8 days, **2 h** beyond, aligned to the clock of the house; a bucket without a
  reading has no point. `avg` is rounded to 2 decimals. A `metric` outside the list is a 400 with
  the code `UNKNOWN_METRIC`. One range is queried at a time, each cut off after 10 s. The night the
  summer time begins has a gap of an hour, the night it ends puts the repeated hour into the same
  buckets.

# Database

- **Own database** `home-automation-weather` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- `V1__weather_reading.sql` - `weather_reading`: a snapshot of the current state, at most one row a
  minute and only after a change. `measured_at` is the newest measurement in the row (house time);
  a metric never received is NULL; a value that does not fit its column is stored as NULL (WARN).
  Columns: `temperature` (°C, 0.1), `humidity` (%), `dew_point` (°C, 0.01), `pressure` (hPa at
  the station, 0.01), `illuminance` (lx), `uv` (0.1), `wind` and `gust` (m/s, 0.01),
  `wind_direction` (°), `raining`, `precipitation` (the running total of the station - unit not
  confirmed yet), `battery` (%), `voltage` (V, 0.01). Kept for `weather.retention` (730 days).
- `V3__air_quality.sql` (HAS-428) - the air quality on the same rows: `pm1`, `pm25`, `pm10` (µg/m³) and
  `air_quality_level` (1 - 6), whole numbers, nullable - a row written before V3, or while the sensor was
  never read, has none. A new reading of the sensor alone writes a row of the whole state; the retention
  deletes them with their rows.
- `V4__air_quality_alert.sql` (HAS-429) - `air_quality_alert`: the episodes of smog - the sensor (its registry
  id), `since`, `ended_at` (empty while open), the highest `level` and `peak_pm10` / `peak_pm25`; one open at a
  time (a partial unique index), the ended ones kept as history (`timestamptz`).
- `V5__air_sensor_runtime.sql` (HAS-437) - `air_sensor_runtime`: the hours the laser of a sensor has run, one
  row per sensor (its registry id, unique), `read_at` the read that changed it, `warned_at_hours` the step of the
  last warning to order a new sensor (empty before the first); written on a change only (`timestamptz`).
- `V2__station_alert.sql` - `station_alert`: the alerts of the watch that are open now, one row per
  watch (`STATION`, `GATEWAY`, `AIR_SENSOR` since HAS-429 - the id of the air quality sensor then) with the id of the station in the registry, since when and when the
  household was last told (`timestamptz`); removed when it is over. An alert about a station the
  registry no longer has is closed without a word.
