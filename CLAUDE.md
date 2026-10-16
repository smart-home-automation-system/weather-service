# weather-service

The weather station of the house (epic HAS-233): a Shelly WS90 read locally through a BTHome
gateway, its readings kept and served to the dashboard, the blinds schedules (HAS-247) and the
overview. Since HAS-236 it takes in the readings of the station and stores them; no endpoints yet
(HAS-238).

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). Claude implements the tasks of the epics
HAS-233 (weather) and HAS-242 (blinds) - the owner's decision of 2026-10-15, when HAS-246 and
HAS-235 were started - through PRs with both reviews; the default of the org (the owner writes
the service code) holds everywhere else.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.weather` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection - nothing sent yet);
  calls `database-service` (device registry) over k8s DNS (`internal.service.database`), and the
  gateway of the station through `shelly-client` (`Shelly.GetComponents`, reads only). No endpoints
  yet, not routed by `api-gateway-service` (HAS-239).
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, connection names,
  `NotificationPublisher`, the required properties), `smart-home-sdk` 1.9.0, `shelly-client`
  2.1.0 (the decoder, `dynamicComponents` - added to the library for this service in HAS-236).
- Database: own database `home-automation-weather`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 / weather 2 = 14 of 22), pinned by
  `WeatherServiceApplicationTest`. `V1__weather_reading.sql`; the next migration is V2.
- Integrates with hardware: the WS90 (`08:b9:5f:d1:e6:71`, registry id 55) through the Plug S Gen3
  TREADMILL (registry id 54), paired in HAS-234 - the notes, the sensor ids and how to re-pair are in
  `deployment-tools/scripts/shelly/devices.md`. Every device runs with `enable_rpc: false`, so only
  the status topics arrive: `<gatewayId>.status.bthomesensor:<id>` (`{id, value, last_updated_ts}`)
  and `<gatewayId>.status.bthomedevice:<id>` (`rssi`, `battery`, `packet_id`).

## Build & run

- Build + tests: `mvn verify` — **needs a running Docker** (`WeatherReadingRepositoryTest` runs V1
  on a PostgreSQL 17 in Testcontainers).
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
  is read hourly, when the station changes, and at the next refresh after a status of a sensor it
  does not know. The sensors of other Bluetooth devices on the gateway are on the map as `others` and
  ignored without a word - there may be many; an unknown id is a WARN once per map (DoD of HAS-236).
- **The current state is made of the changes**: the gateway publishes a sensor only when its value
  changes (and a status of a component again now and then). `CurrentWeather` holds per metric the
  last value with its `last_updated_ts` as an `Instant` - never house time, which runs an hour
  backwards the night the clocks go back; the same reading again changes nothing, an older one is
  dropped. The packets of the WS90 come in two kinds in turn (A: light, rain, wind, gust, UV,
  direction; B: the rest) and the sensors of one packet differ by 1-2 s, so nothing groups by packet.
- **A row a minute, after a change only** (`WeatherSnapshot`, the "insert on a change" rule of the
  status tables): a snapshot of the whole state, `measured_at` the newest measurement in it, house
  time. Nothing is stored before the state was restored from the latest row (a partial row would
  become the latest and the next restore would read it); a failed insert is retried with the next
  pass; a value that does not fit its column is stored as NULL (WARN) - one refused value would
  refuse every row after it. On shutdown the state is stored once more (`SmartLifecycle`, after the
  listener, asynchronous - no block).
- **`Recreate`, never scaled** (decided in HAS-236): the state is in the memory of one pod, and two
  pods of a rollout would split the queue between them - a change taken by the old pod is lost until
  the next one. Recreate also means no second pool during a rollout. The watch over the gateway of
  HAS-237 lives in the same pod.
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
- **The notification connection is not a bean** (`NotificationRabbitConfig`, the third copy -
  heating- and blinds-service have the others; up to three stay, a fourth moves it to
  `cholewa-commons`): a `ConnectionFactory` or `RabbitOperations` bean would make
  `RabbitAutoConfiguration` back off, and the listener would lose manual ack, prefetch and
  observation. `ClockConfig` is the second copy (blinds-service).
- **Timeouts of the client**: `internal.service.database.{connect,response}-timeout`, a bare
  number is seconds, bounded 1–60 s (`InternalServicePropertiesTest`). Its own named
  `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits).
- **Shelly calls through `shelly-client` reach Logbook only via the `ShellyHttpClientCustomizer`
  bean** (`shellyCallsInTheLog`): the library's clients put their own connector on the builder.
- **The test document points everything outward at port 1** (broker, database, the registry),
  so a context test reaches nothing even where the names resolve.
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database. The recipe: `postgres:17` in
  Docker with `-c ssl=on` and the snakeoil certificate of the image (`MSYS_NO_PATHCONV=1` in Git
  Bash), the released image first (`SPRING_PROFILES_ACTIVE=home,local`, the `database-*` variables,
  `flyway-url=...?sslmode=require`, host `host.docker.internal`), then the new jar on the same
  database.
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`.
