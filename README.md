# UTEWedding

UTEWedding is the Java/Spring Boot web application currently deployed as `/opt/webthiep/webthiep.jar`.

## Stack

- Java 21 and Spring Boot
- Thymeleaf, Spring Security, Spring Data JPA
- PostgreSQL
- WebSocket/STOMP
- VNPay Sandbox and GHN Staging integrations

## Build

On Windows PowerShell:

```powershell
./mvnw.cmd -Dmaven.test.skip=true package
```

On Linux/macOS:

```bash
./mvnw -Dmaven.test.skip=true package
```

The packaged JAR is written under `target/`. Tests are included in `src/test`; run them separately with `./mvnw test` when the required local database and environment are available.

## Configuration

Runtime secrets are supplied through environment variables. `src/main/resources/application.properties` contains the application defaults and environment-variable references. Do not add live provider tokens, database passwords, signing keys, or mail credentials to Git. Use `.env.example` only as a local template.

## Runtime source snapshot

This repository is synchronized to the deployed application snapshot from 27 October 2026. The live service remains on the server; this repository update does not deploy or restart it. See [the runtime sync notes](docs/server-runtime-sync-20261027.md) for the source-to-JAR details.
