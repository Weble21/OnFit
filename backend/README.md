# OnFit Backend

Spring Boot API server for the OnFit job recommendation project.

## Stack

- Java 21
- Spring Boot 4
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle

## Run

Start PostgreSQL from the repository root:

```powershell
docker compose -f infra/compose.yaml up -d
```

The Compose file uses `onfit` / `onfit_local` for local development. Override
`DB_PASSWORD` (and `DB_PORT` if needed) before starting the container. The
backend accepts `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables.
If you change `DB_PORT`, set `DB_URL` to the matching host port as well.

Then run from the `backend` directory:

```powershell
./gradlew.bat bootRun
```

Flyway creates the schema on startup. Hibernate validates it and does not alter
tables automatically. PostgreSQL must be running for the API to start.

```powershell
./gradlew.bat test
```

Tests use an in-memory H2 database in PostgreSQL compatibility mode to run the
same Flyway migration and validate the entity mappings. They do not replace a
real PostgreSQL startup check.
