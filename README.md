# weather-service

[![CI](https://github.com/smart-home-automation-system/weather-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/weather-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_weather-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_weather-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/weather-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/weather-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/weather-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
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

Since HAS-236 the service **takes in the readings and stores them**; it has no endpoints yet - the
read API comes with HAS-238, the watch over the gateway with HAS-237.

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
  `bthomedevice:<id>` is the station. Read when the station or the gateway changes, once an hour,
  and at the next refresh after a sensor the map does not know. Each read also brings what every
  sensor holds now into the current state - a value that never changes (the battery, the rain for
  days) is never published, and would otherwise stay unknown.
- **Device events** arrive from RabbitMQ, virtual host `/device` (user `device`), queue
  `device.prod.weather` (`device.dev.weather` in the `local` profile). The queue is bound to
  `amq.topic` with `#` - every event of every device, about 130 a minute; the routing key decides
  before any body is read. `<gateway>.status.bthomesensor:<id>` of a sensor of the station updates
  the **current state in memory**: per metric the last value and when the station measured it. The
  gateway publishes a sensor only when its value changes, so the state is made of the changes; the
  same reading again or an older one changes nothing. A sensor of another Bluetooth device on the
  gateway is ignored without a word, one the map does not have is a WARN (once) and the map is read
  again. `<gateway>.status.bthomedevice:<id>` gives the signal and the battery of the station.
- **Once a minute** the state becomes a row of `weather_reading` - only when something changed since
  the last row; each row is logged at INFO. At the start the state is restored from the latest row
  before anything is stored, and when the pod stops the state is stored once more.
- **Notifications** go to the `notification` exchange of the virtual host `/notification` over a
  second connection (`NotificationPublisher` of `cholewa-commons`), named `<pod>/notification` -
  none is sent yet (HAS-237).

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

Base path `/home/weather` (`spring.webflux.base-path`). No endpoints yet (HAS-238); the
service is not routed by `api-gateway-service` (HAS-239).

# Database

- **Own database** `home-automation-weather` on the managed PostgreSQL, reactive access via
  `r2dbc-postgresql`, migrations by Flyway from `src/main/resources/db/migration`.
- `V1__weather_reading.sql` - `weather_reading`: a snapshot of the current state, at most one row a
  minute and only after a change. `measured_at` is the newest measurement in the row (house time);
  a metric never received is NULL; a value that does not fit its column is stored as NULL (WARN).
  Columns: `temperature` (°C, 0.1), `humidity` (%), `dew_point` (°C, 0.01), `pressure` (hPa at
  the station, 0.01), `illuminance` (lx), `uv` (0.1), `wind` and `gust` (m/s, 0.01),
  `wind_direction` (°), `raining`, `precipitation` (the running total of the station - its unit is
  checked before HAS-238 shows it), `battery` (%), `voltage` (V, 0.01). No retention yet (HAS-238).
