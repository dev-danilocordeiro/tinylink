# TinyLink backend

URL shortener API built with Spring Boot 4 and Java 21. Links are kept in memory, and Redis
holds the redirect cache and the rate-limit counters.

> Links live in memory only: restarting the backend deletes every link created.

## Requirements

- Java 21
- Docker, for Redis

## Running

From the repository root, start Redis (and Redis Commander on http://localhost:8081):

```bash
docker compose up -d
```

Then start the API on http://localhost:8080:

```bash
cd backend
./mvnw spring-boot:run
```

`spring-boot:run` activates the `local` profile (`application-local.yaml`): the expired-link
cleanup runs every minute instead of every 5, and application and Redis logs are at DEBUG.
When running from an IDE, set `local` as the active profile to get the same behaviour.

## Tests

```bash
./mvnw test
```

`RateLimitServiceTest` and `TinylinkApplicationTests` need the Redis from `docker compose`
running. The other tests don't.

## API

All routes are under `/api`.

| Method   | Path                          | Description                                    | Success |
|----------|-------------------------------|------------------------------------------------|---------|
| `POST`   | `/api/shorten`                | Create a short link                            | 200     |
| `GET`    | `/api/{shortCode}`            | Redirect to the original URL and record a click | 302     |
| `GET`    | `/api/stats/{shortCode}`      | Link details and click count                   | 200     |
| `GET`    | `/api/analytics/{shortCode}`  | Recent clicks and clicks by referer, hour, day | 200     |
| `DELETE` | `/api/{shortCode}`            | Deactivate a link                              | 204     |

### Create a link

```bash
curl -X POST http://localhost:8080/api/shorten \
  -H 'Content-Type: application/json' \
  -d '{"originalUrl": "https://github.com", "customAlias": "gh", "expiresAt": "2026-12-31T23:59:59"}'
```

| Field         | Required | Rules                                                                 |
|---------------|----------|-----------------------------------------------------------------------|
| `originalUrl` | yes      | Must start with `http://` or `https://`                               |
| `customAlias` | no       | 3–30 letters, digits, `-` or `_`. Empty or missing generates a 6-character base62 code |
| `expiresAt`   | no       | ISO local date-time. After it passes, the link returns 404            |

```json
{
  "shortUrl": "http://localhost:8080/api/gh",
  "shortCode": "gh",
  "originalUrl": "https://github.com",
  "createdAt": "2026-09-30T19:51:48.878",
  "expiresAt": "2026-12-31T23:59:59"
}
```

### Errors

Errors follow [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) and are returned as
`application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Short code not found",
  "status": 404,
  "detail": "Short code 'abc123' not found",
  "instance": "/api/stats/abc123",
  "shortCode": "abc123"
}
```

| Status | Title                  | When                                             | Extra fields                          |
|--------|------------------------|--------------------------------------------------|---------------------------------------|
| 400    | `Invalid request`      | Request body fails validation                    | `errors: [{field, message}]`          |
| 404    | `Short code not found` | Code doesn't exist, is expired or was deleted    | `shortCode`                           |
| 409    | `Alias already exists` | `customAlias` is taken                           | `alias`                               |
| 429    | `Rate limit exceeded`  | Too many links created from one IP               | `remainingRequests`, `timeUntilReset`, plus a `Retry-After` header |
| 500    | `Internal Server Error`| Unexpected error. Details go to the log only    | —                                     |

## How it works

- **Redirect cache:** each link's URL is cached in Redis under `url:<code>` for up to
  `tinylink.cache.ttl-minutes`, but never past the link's expiry. Deleting a link evicts it.
- **Rate limiting:** only `POST /api/shorten` is limited, per client IP, with a per-minute and
  a per-hour window. `scripts/rate_limit.lua` checks and increments both counters
  (`ratelimit:<ip>:minute` and `ratelimit:<ip>:hour`) atomically. If Redis is unavailable,
  requests are allowed and a warning is logged.
- **Client IP:** Tomcat reads `X-Forwarded-For` only from trusted proxies (loopback and
  private ranges), so clients can't spoof their IP to bypass the rate limit.
- **Expiry cleanup:** `CleanupScheduler` runs every `tinylink.cleanup.interval-minutes` and
  deactivates links past their `expiresAt`.
- **Delete:** a soft delete. The link stops redirecting, but its stats stay available with
  `"active": false`.

## Configuration

Set in `src/main/resources/application.yaml`, under `tinylink`:

| Property                           | Default                 | Description                                  |
|------------------------------------|-------------------------|----------------------------------------------|
| `base-url`                         | `http://localhost:8080` | Prefix used to build `shortUrl`              |
| `short-code.length`                | `6`                     | Length of generated codes                    |
| `short-code.max-attempts`          | `10`                    | Retries when a generated code collides       |
| `rate-limit.requests-per-minute`   | `2`                     | Links one IP can create per minute           |
| `rate-limit.requests-per-hour`     | `10`                    | Links one IP can create per hour             |
| `cache.ttl-minutes`                | `30`                    | Maximum lifetime of a cached redirect        |
| `cleanup.interval-minutes`         | `5` (`1` in `local`)    | How often expired links are deactivated      |

Redis connects to `localhost:6379` by default (`spring.data.redis.*`). CORS allows
`http://localhost:4200` and `http://localhost:8082` (`WebConfig`).

## Trying it with Bruno

The `bruno/` collection at the repository root covers every route and error case. Select
the **Local** environment and run the collection in order with the Runner: later requests
reuse the codes saved by earlier ones.
