# weather-service

The weather station of the house (epic HAS-233): a Shelly WS90 read locally through a BTHome
gateway, its readings kept and served to the dashboard, the blinds schedules (HAS-247) and the
overview. Since HAS-236 it takes in the readings of the station and stores them, since HAS-237 it
reconciles with the gateway every 5 min and alerts on silence, since HAS-238 it serves the read API
(`/current`, `/status`, `/history`) and deletes readings past the retention.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). Claude implements the tasks of the epics
HAS-233 (weather) and HAS-242 (blinds) - the owner's decision of 2026-10-15, when HAS-246 and
HAS-235 were started - through PRs with both reviews; the default of the org (the owner writes
the service code) holds everywhere else.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.weather` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection - the alerts of
  `StationWatch`, Discord channel `alerts` through `notification-service`);
  calls `database-service` (device registry) over k8s DNS (`internal.service.database`), and the
  gateway of the station through `shelly-client` (`Shelly.GetComponents`, reads only). The read API
  (HAS-238) is not routed by `api-gateway-service` yet (HAS-239 - GET-only, like the presence
  allowlist). Its callers to come: the dashboard (`UNKNOWN_METRIC` needs a text there), the blinds
  schedules (HAS-247), the overview.
- Uses libraries: `cholewa-commons` 1.11.0 (pool, error contract, connection names,
  `NotificationPublisher`, the required properties, Logbook in the reactive chain, the scheduler pool and
  `ScheduledPass`), `smart-home-sdk` 1.12.0, `shelly-client`
  2.1.0 (the decoder, `dynamicComponents` - added to the library for this service in HAS-236).
- Database: own database `home-automation-weather`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 / weather 2 = 14 of 22), pinned by
  `WeatherServiceApplicationTest`. `V1__weather_reading.sql`, `V2__station_alert.sql`; the next
  migration is V3.
- Integrates with hardware: the WS90 (`08:b9:5f:d1:e6:71`, registry id 55) through the Plug S Gen3
  TREADMILL (registry id 54), paired in HAS-234 - the notes, the sensor ids and how to re-pair are in
  `deployment-tools/scripts/shelly/devices.md`. Every device runs with `enable_rpc: false`, so only
  the status topics arrive: `<gatewayId>.status.bthomesensor:<id>` (`{id, value, last_updated_ts}`)
  and `<gatewayId>.status.bthomedevice:<id>` (`rssi`, `battery`, `packet_id`).

## Build & run

- Build + tests: `mvn verify` — **needs a running Docker** (`WeatherReadingRepositoryTest` and
  `StationAlertRepositoryTest` run the migrations on a PostgreSQL 17 in Testcontainers).
- Local run: `home,local` Spring profiles, port `6010` (Actuator `8010`); in-cluster port
  `6200`, Actuator `8200`. Locally the broker is `localhost`, the registry is on 6005 (a stub with
  the two entries is enough), and the broker passwords default to a dummy - nothing is consumed.
  A local run reads the real gateway of the registry (reads only).

## Specifics

- **The station is two entries of the registry**, `weather station` (the Bluetooth address) and
  `bthome gateway` (the device id and the address of the Shelly) - the registry does not bind a
  Bluetooth device to a gateway (V13 of `database-service`). One of each is expected; a second is a
  WARN once (until the registry changes), the lowest id is used. Read at the start and every 5 min
  (`StationRefresh`); a failed read keeps the last one. Another station or gateway clears the
  current state.
- **The map of the sensors is read from the gateway**, never configured: `Shelly.GetComponents`
  (`dynamicComponents` of `shelly-client` - `Shelly.GetStatus` leaves the BTHome components out)
  gives the config of each `bthomesensor` (address, `obj_id`, `idx` -> `Metric`) and of each
  `bthomedevice`. A re-pairing gives other ids, and the gateway says nothing on the broker: the map
  is read with **every refresh, every 5 min** (HAS-237 - it is also the reconciliation, below; it was
  hourly in 0.2.x). The WARNs of the read (a sensor without a metric, a component that is not the
  model) are said once, keyed by what they are about - their text may quote a changing value. The
  sensors of other Bluetooth devices on the gateway are on the map as `others` and ignored without a
  word - there may be many; an unknown id is a WARN once per map (DoD of HAS-236).
- **The current state is made of the changes**: the gateway publishes a sensor only when its value
  changes (and a status of a component again now and then). `CurrentWeather` holds per metric the
  last value with its `last_updated_ts` as an `Instant` - never house time, which runs an hour
  backwards the night the clocks go back; the same reading again changes nothing, an older one is
  dropped. The packets of the WS90 come in two kinds in turn (A: light, rain, wind, gust, UV,
  direction; B: the rest) and the sensors of one packet differ by 1-2 s, so nothing groups by packet.
  A value that never changes is never published either: every read of the map (`Shelly.GetComponents`
  lists the status with the config) brings what each sensor holds now into the state (`seed`: a
  metric not held yet, or another value measured strictly later - never over an event of the same
  second, never the same value at a later time; the link of the station the same way) - HAS-236
  0.2.0 had only the metrics that changed since the start in its first rows. **This is the
  reconciliation of HAS-237**: a status the broker lost and the first state after a deploy come in
  within 5 min. The task named `Shelly.GetStatus`; it leaves the BTHome components out.
- **The watch over the station** (`StationWatch`, HAS-237), chained after every refresh in
  `StationRefresh`: `STATION` - nothing heard for `weather.monitor.silent-after` (30m, 10m-24h, a bare
  number is minutes); `GATEWAY` - no answer to the read for the same time (comes with the other: a
  dead gateway is silent on the broker too). Alert (`error`), reminder (`warn`) every
  `reminder-interval` (1h, 15m-7d), one info when over. "Heard" is `CurrentWeather.heardAt`: the
  newest `last_updated_ts` of any reading (taken into the state or not) or of the `bthomedevice`,
  the gateway's clock - a time more than a minute ahead of the pod is not taken (it would hold the
  watch off); restored from the latest row, cleared with another station. Never heard: counted from
  the start of the watch (or of the station), and the alert says so. Over = a sign of life after the
  start of the alert - it ends the alert even when silent again (passes missed), the next pass opens
  a new one. Open alerts are rows of `station_alert` (V2, `timestamptz`, `station_id` - a row of
  another station is deleted without a word; no station in the registry - nothing judged).
  Notification first, row after (a failed write repeats the message); each watch cut off after 30 s,
  logged, swallowed. Proven with `env: dev` (gateway at `127.0.0.1:1`, `silent-after=10m`, the
  passwords from the secret `rabbitmq`, a stub of the registry, a throwaway PostgreSQL): see PR #4.
- **A row a minute, after a change only** (`WeatherSnapshot`, the "insert on a change" rule of the
  status tables): a snapshot of the whole state, `measured_at` the newest measurement in it, house
  time. Nothing is stored before the state was restored from the latest row (a partial row would
  become the latest and the next restore would read it); a failed insert is retried with the next
  pass; a value that does not fit its column is stored as NULL (WARN) - one refused value would
  refuse every row after it. On shutdown the state is stored once more (`SmartLifecycle`, after the
  listener, asynchronous - no block).
- **`Recreate`, never scaled** (decided in HAS-236): the state is in the memory of one pod, and two
  pods of a rollout would split the queue between them - a change taken by the old pod is lost until
  the next one. Recreate also means no second pool during a rollout. The watch over the station
  lives in the same pod.
- **The queue gets every event of every device** (bound with `#`, about 130 a minute): Shelly
  status topics (routing key `<id>.status.<component>`) and Eaton `DeviceEvent`s from
  `amx-service` (`eaton.<gateway>.<point>`). `WeatherEventRouter` decides by the routing key and the
  registry before any body is read. `DeviceEventConsumer` logs each event at `DEBUG` only; manual
  ack, prefetch 3, `.contextCapture()` last, errors logged and completed — a `Mono` that errors is
  redelivered at once. `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **The broker passwords have no default in `home`**: `cholewa.required-properties` of
  `cholewa-commons` refuses the start without `rabbitmq-password` or
  `notification-rabbitmq-password`, before the first bean. `BrokerPasswordsTest` ties the list
  to the placeholders of `application.yaml`. No copy of `BrokerPasswordCheck` here — it moved
  into the library with HAS-235.
- **The notification connection is not a bean** (`NotificationRabbitConfig`, one of five copies -
  heating, blinds, light and amx have the others; kept copied by the owner's choice, HAS-257 / HAS-309,
  a change goes into every copy): a `ConnectionFactory` or `RabbitOperations` bean would make
  `RabbitAutoConfiguration` back off, and the listener would lose manual ack, prefetch and
  observation. `ClockConfig` is the second copy (blinds-service).
- **Timeouts of the client**: `internal.service.database.{connect,response}-timeout`, a bare
  number is seconds, bounded 1–60 s (`InternalServicePropertiesTest`). Its own named
  `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits).
- **Logbook logs every call from the reactive chain, with its `traceId`** (`cholewa-commons` >= 1.10.0,
  since HAS-304): the injected `WebClient.Builder` carries Logbook's exchange filter and the clients of
  `shelly-client` are built from it, so the service adds **no Logbook handler of its own** - the
  `LogbookClientHandler` of `WebClientConfig` and the `shellyCallsInTheLog` customizer are gone, or every
  call would be logged twice; `WeatherServiceApplicationTest` pins that no `ShellyHttpClientCustomizer` is back.
- **Every scheduled task is bounded and they run on a pool** (HAS-304, after blinds-service froze on
  2026-10-22: Spring blocks the scheduler thread until a reactive fixed-delay pass completes, and the one
  thread of Boot stopped every task with the health UP). The pool of ten comes from `cholewa-commons` 1.11.0
  (pinned by `WeatherServiceApplicationTest`); each `@Scheduled` method returns
  `ScheduledPass.bounded(task, LIMIT, ...)`, pinned by a test that hangs the pass: `WeatherSnapshot` 50 s
  (`fixedRate` - Spring does not wait for it, so a hung pass would only add up; a snapshot cut on its way
  keeps its readings, `doOnCancel` -> `snapshotFailed`), `StationRefresh` 4 min (the registry, the gateway
  and the watch with its 30 s notifications), `WeatherRetention` 15 min. **What runs at the same time now**
  was checked: `CurrentWeather` is `synchronized` / atomic, as the listener already wrote it beside the
  passes; the database passes (the snapshot, the alerts of the watch, the nightly DELETE) take one
  connection per statement and fit the pool of 2 - a third waits `max-acquire-time`, as before.
- **The test document points everything outward at port 1** (broker, database, the registry),
  so a context test reaches nothing even where the names resolve.
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database. The recipe: `postgres:17` in
  Docker with `-c ssl=on` and the snakeoil certificate of the image (`MSYS_NO_PATHCONV=1` in Git
  Bash), the released image first (`SPRING_PROFILES_ACTIVE=home,local`, the `database-*` variables,
  `flyway-url=...?sslmode=require`, host `host.docker.internal`), then the new jar on the same
  database.
- **The read API** (HAS-238): `WeatherController` -> `CurrentWeatherQuery` (memory) and
  `WeatherHistoryService` (database). The names of the metrics are `Metric.apiName()` and the columns
  `Metric.column()`, both pinned by `MetricTest` - wire contract of the dashboard and the blinds
  schedules. `/current` shows each value with `CurrentWeather.confirmed`: the newest time the held
  value was seen again (`seed` of the same value at a later time moves it, without a new row), so a
  value that never changes is not `stale`. `/history` - `HistoryRange` is a **copy** of the one of
  water-service (heating has the rules too; three copies, no move to `cholewa-commons`); the query is
  built by `WeatherHistoryRepository` from `Metric` only (a column cannot be a parameter),
  PostgreSQL SQL tested in Testcontainers; the wind direction is a circular mean without min/max, the
  rain 1/0. `OneAtATime` queues the history queries (the pool has 2; a waiter that is cancelled
  hands its turn on only after the one before it), each cut off after 10 s. `UNKNOWN_METRIC`
  (`WeatherError`, pinned) lists the names there are and never echoes the request. The unit of
  `precipitation` is **not confirmed** (raw 4311, the running total) - compare a day of rain with the
  Shelly app.
- **The retention** (`WeatherRetention`, 03:10 house time): rows older than `weather.retention`
  (730d, `@DurationUnit(DAYS)`, 30d-3650d, `RetentionPropertiesTest`), never the latest row.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`.
