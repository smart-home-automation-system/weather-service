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
the current weather, the status of the station and the history** (reads only).

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
- **Once a minute** the state becomes a row of `weather_reading` - only when something changed since
  the last row; each row is logged at INFO. At the start the state is restored from the latest row
  before anything is stored, and when the pod stops the state is stored once more. **Every night at
  03:10** the rows older than `weather.retention` (default 730 days; a bare number is days, 30 days
  to 10 years) are deleted - never the latest one, which the state is restored from.
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
| GET | `/status` | `gateway {deviceId, reachable, checkedAt}`, `lastReadingAt`, `silent` |
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

- `/current`: a metric never received is missing - never `null` or zero; `{}` before the first
  reading. `measuredAt` is the newest time the station is known to have had the value (a value that
  does not change is confirmed by every read of the gateway), `stale` is true when that is older
  than `weather.monitor.silent-after` (30 min), the threshold of the alerts.
- `/status`: what is not known yet is left out (`gateway` without a station in the registry,
  `reachable` / `checkedAt` before the first read of the gateway, `lastReadingAt` before anything
  was heard). `silent` is true when nothing was heard for 30 min - or at all since the start, while
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
- `V2__station_alert.sql` - `station_alert`: the alerts of the watch that are open now, one row per
  watch (`STATION`, `GATEWAY`) with the id of the station in the registry, since when and when the
  household was last told (`timestamptz`); removed when it is over. An alert about a station the
  registry no longer has is closed without a word.
