# AGENTS.md

Instructions for coding agents working in this repository. The repo has no CI, so the commands
below are the checks a change must pass. Every one of them was run on `main` and passes.

## Layout

| Folder | What | Stack |
| --- | --- | --- |
| `backend/` | URL shortener API | Java 21, Spring Boot 4, Maven (wrapper `./mvnw`), Redis |
| `frontend/` | Web UI | Angular 22, Node 24.21.0 (`mise.toml`), npm, Vitest |
| `bruno/` | Bruno collection for manual API calls | — |

Run each project's commands from inside its folder.

## Redis

The backend tests `RateLimitServiceTest` and `TinylinkApplicationTests` need Redis on
`localhost:6379`. Start only Redis from the repository root:

```bash
docker compose up -d --wait redis
```

The container is named `tinylink-redis`; if it is already running, the command is a no-op.
Tests write keys to this Redis, so don't point them at anything but a local instance.

## Backend (`backend/`)

```bash
cd backend
./mvnw -B verify          # compiles and runs all tests (needs Redis, see above)
./mvnw -B test -Dtest=UrlShortenerServiceTest   # a single test class
```

- Java 21 (`java.version` in `pom.xml`, `eclipse-temurin:21` in the Dockerfile). A newer JDK
  works, but don't use language or API features beyond Java 21.
- There is no linter or formatter configured (no Checkstyle, Spotless or similar). Follow the
  style of the surrounding code: tabs in `pom.xml`, the existing package layout
  (`controller`, `service`, ...), Lombok where the code already uses it.
- Links are stored in memory; Redis only holds the redirect cache and rate-limit counters.
- If you change the HTTP API, update the API tables in `backend/README.md` and the matching
  request in `bruno/`.

## Frontend (`frontend/`)

```bash
cd frontend
npm ci                          # install exactly what package-lock.json pins
npm run build                   # production build into dist/
npx ng test --watch=false       # unit tests (Vitest), single run
npx prettier --write <files>    # format only the files you changed
```

- Use Node 24.21.0 (`mise.toml`, same as the Dockerfile) and npm; don't switch package
  managers or regenerate `package-lock.json` unless the change is about dependencies.
- Prettier config is in `.prettierrc` (print width 100, single quotes). Several files on
  `main` are not Prettier-formatted, so `prettier --check src` fails today: don't reformat
  files you didn't otherwise touch.
- `npm start` serves on http://localhost:4200 and proxies `/api` to the backend on
  http://localhost:8080 (`proxy.conf.json`).

## Don't touch

Unless the issue explicitly asks for it, don't change:

- `docker-compose.yml`, the `Dockerfile`s and `frontend/nginx.conf`: they define how the whole
  app runs.
- `.github/` (it doesn't exist yet; don't create CI workflows).
- `.mvn/`, `mvnw`, `mvnw.cmd` and the Spring Boot parent version in `pom.xml`.
- Dependencies in `pom.xml` and `package.json`: a new dependency is a decision for a human.
