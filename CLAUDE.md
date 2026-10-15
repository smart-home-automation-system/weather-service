# weather-service

The weather station of the house (epic HAS-233): a Shelly WS90 read locally through a BTHome
gateway, its readings kept and served to the dashboard, the blinds schedules (HAS-247) and the
overview. Since HAS-235 a skeleton: it consumes the device events, has its client of the device
registry and the notification publisher, and acts on nothing yet.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). Claude implements the tasks of the epic
here as in blinds-service (owner, 2026-10-15), through PRs with both reviews.

## Role in the system

- Talks to: RabbitMQ — consumes `device.prod.weather` on `/device` (user `device`), publishes
  notifications to `/notification` (user `notification`, a second connection); calls
  `database-service` (device registry) over k8s DNS (`internal.service.database`), and the
  gateway of the station through `shelly-client` (HAS-236). No endpoints yet, not routed by
  `api-gateway-service` (HAS-239).
- Uses libraries: `cholewa-commons` 1.9.0 (pool, error contract, connection names,
  `NotificationPublisher`, the required properties), `smart-home-sdk` 1.9.0, `shelly-client`
  2.0.0 (its three beans are registered — nothing calls a device yet).
- Database: own database `home-automation-weather`, pool `database.pool.max-size: 2` (heating 2 /
  database 4 / water 2 / presence 2 / blinds 2 / weather 2 = 14 of 22), pinned by
  `WeatherServiceApplicationTest`. No migration yet.
- Integrates with hardware: the WS90 through a Shelly with the BTHome components (paired in
  HAS-234); its `bthomesensor:2xx` deltas arrive in the `NotifyStatus` frames of the gateway.

## Build & run

- Build + tests: `mvn verify` — no Docker needed yet; with the first repository test of
  PostgreSQL-only SQL (Testcontainers, dependencies already in the pom) it will be.
- Local run: `home,local` Spring profiles, port `6010` (Actuator `8010`); in-cluster port
  `6200`, Actuator `8200`. Locally the broker is `localhost`, the registry is on 6005, and the
  broker passwords default to a dummy.

## Specifics

- **The queue gets every event of every device** (bound with `#`, about 130 a minute): Shelly
  status topics (routing key `<id>.status.<component>`) and Eaton `DeviceEvent`s from
  `amx-service` (`eaton.<gateway>.<point>`). Only the frames of the gateway named in the
  registry matter (HAS-236). `DeviceEventConsumer` takes the raw `Message`, logs at `DEBUG`
  only and acknowledges; manual ack, prefetch 3, `.contextCapture()` last, errors logged and
  completed — a `Mono` that errors is redelivered at once.
- **The deployment rolls** (no state that must be single, unlike blinds-service): for a moment
  the old and the new pod each hold a pool of 2, and Flyway opens one more.
  `@EnableScheduling` is in `SchedulingConfig` under `@Profile("!test")`.
- **The broker passwords have no default in `home`**: `cholewa.required-properties` of
  `cholewa-commons` refuses the start without `rabbitmq-password` or
  `notification-rabbitmq-password`, before the first bean. `BrokerPasswordsTest` ties the list
  to the placeholders of `application.yaml`. No copy of `BrokerPasswordCheck` here — it moved
  into the library with this task.
- **The notification connection is not a bean** (`NotificationRabbitConfig`, the same as in
  heating- and blinds-service): a `ConnectionFactory` or `RabbitOperations` bean would make
  `RabbitAutoConfiguration` back off, and the listener would lose manual ack, prefetch and
  observation.
- **Timeouts of the client**: `internal.service.database.{connect,response}-timeout`, a bare
  number is seconds, bounded 1–60 s (`InternalServicePropertiesTest`). Its own named
  `ConnectionProvider` with 2 min idle / 30 min life (the gateway's HAS-212 limits).
- **Shelly calls through `shelly-client` reach Logbook only via the `ShellyHttpClientCustomizer`
  bean** (`shellyCallsInTheLog`): the library's clients put their own connector on the builder.
- **The test document points everything outward at port 1** (broker, database, the registry),
  so a context test reaches nothing even where the names resolve.
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database (recipe in blinds-service's
  CLAUDE.md).
- **Surefire activates the `test` profile for every class**; the `includes` match only
  `**/*Test.java` and `**/*IT.java`. Tests use `mockwebserver3`.
