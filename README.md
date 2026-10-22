# Flash Sale Survival System

A resilient API gateway for an e-commerce checkout flow. It authenticates every request, rate-limits abusive traffic, protects itself from a failing payment service with a circuit breaker, and publishes an alert event to Kafka when the payment service degrades.

Built as a Term Paper project (PC-399) to show how several backend patterns work together as layers in one system, instead of in isolation.

## The problem

During a flash sale, thousands of users hit "buy" at once. Two things tend to go wrong:

1. **Too much traffic from one source.** Bots or impatient users can hammer the checkout endpoint and starve everyone else.
2. **A downstream service starts failing.** If the payment service slows down or crashes, every checkout request that depends on it hangs waiting. Threads pile up, and the gateway itself goes down even though its own code is fine. This is a cascading failure.

This gateway handles both.

## Architecture

Every checkout request passes through the layers in order. A request only moves to the next layer if it passes the current one.

```
Client  POST /checkout
   |
   v
Layer 1: JWT authentication      -> no valid token: rejected (403)
   |
   v
Layer 2: Rate limiter (Redis)    -> bucket empty: rejected ("Rate limit exceeded")
   |
   v
Layer 3: Circuit breaker         -> wraps the call to the Payment Service
   |
   +-- Closed: Payment Service processes the order
   |
   +-- Open / call fails: fallback message returned,
                          alert published to Kafka (topic: system-alerts)
                                   |
                                   v
                          Alerting consumer logs the event
```

### Layer 1: JWT authentication
A Spring Security filter reads the `Authorization: Bearer <token>` header, verifies the token's signature and expiry, and sets the caller's identity. Sessions are disabled, so the server stores no login state. Anything without a valid token gets a 403.

### Layer 2: Rate limiter
A Token Bucket algorithm backed by Redis. The whole check-and-decrement runs inside a single Lua script, so it is atomic. Two simultaneous requests cannot both read "one token left" and both get through.

- Capacity: 5 tokens
- Refill: 1 token per second
- Tokens are calculated lazily from elapsed time on each request, so there is no background timer.

### Layer 3: Circuit breaker
Resilience4j wraps the call to the payment service.

| State | Behavior |
|---|---|
| Closed | Calls go through. The breaker tracks the failure rate. |
| Open | Calls are skipped entirely and the fallback is returned instantly. |
| Half-Open | After the cooldown, a few test calls are allowed. Success closes the breaker, failure reopens it. |

Configuration:

```properties
resilience4j.circuitbreaker.instances.paymentService.sliding-window-size=5
resilience4j.circuitbreaker.instances.paymentService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.paymentService.wait-duration-in-open-state=10s
resilience4j.circuitbreaker.instances.paymentService.permitted-number-of-calls-in-half-open-state=2
resilience4j.circuitbreaker.instances.paymentService.automatic-transition-from-open-to-half-open-enabled=true
```

The breaker judges health over the last 5 calls, trips at 50% failures, waits 10 seconds before testing recovery, and needs 2 successful test calls to close again.

### Layer 4: Kafka alerting
When the circuit breaker's fallback runs, the gateway publishes a message to the `system-alerts` topic. A separate consumer (group `alerting-group`) reads it and logs it. The gateway does not know or care who is listening, which is the point of using an event broker: the monitoring side stays decoupled from the checkout flow.

## Services

| Service | What it is | Port |
|---|---|---|
| flash-sale-gateway | This project (Spring Boot) | 8080 |
| flaky-service | Fake payment service with a failure toggle (separate project) | 8081 |
| Redis | Rate limiter state (Docker) | 6379 |
| Kafka + Zookeeper | Alert events (Docker) | 9092 |

`flaky-service` exists only so failures can be triggered on demand. It has two endpoints: `POST /process-payment` and `POST /toggle-failure`.

## Endpoints

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /token?username=<name>` | No | Issues a JWT for testing |
| `POST /checkout?orderId=<id>` | Yes | The protected checkout flow |
| `GET /actuator/circuitbreakers` | Yes | Shows the live circuit breaker state |

## Tech stack

- Java 21
- Spring Boot 4, Spring Web, Spring Security
- JJWT 0.12.6 (JWT creation and verification)
- Redis with a Lua script (rate limiting)
- Resilience4j 2.4.0 (circuit breaker)
- Apache Kafka via Spring for Apache Kafka (alerts)
- Spring Boot Actuator
- Maven, Docker

## Running it

Start things in this order. Kafka must be up before the gateway, or the Kafka consumer spams reconnect warnings.

```bash
# 1. Redis
docker start redis-ratelimit

# 2. Kafka + Zookeeper (from the folder containing docker-compose.yml), then wait ~30s
docker-compose up -d

# 3. Flaky payment service (its own folder), port 8081
./mvnw spring-boot:run

# 4. The gateway (this project), port 8080
./mvnw spring-boot:run
```

Check the Docker containers with `docker ps`. All three should show "Up".

## Demo (PowerShell)

The JWT signing key is regenerated on every restart, so fetch a fresh token after restarting the gateway.

```powershell
$token = Invoke-RestMethod -Uri "http://localhost:8080/token?username=aryan"
$h = @{Authorization = "Bearer $token"}
```

**Layer 1, JWT:**
```powershell
# No token: 403
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/checkout?orderId=j1"
# Valid token: succeeds
Invoke-RestMethod -Method POST -Uri "http://localhost:8080/checkout?orderId=j1" -Headers $h
```

**Layer 2, rate limiter:**
```powershell
# About 5 succeed, the rest are rejected
1..10 | ForEach-Object { Invoke-RestMethod -Method POST -Uri "http://localhost:8080/checkout?orderId=rate-demo" -Headers $h }
```

**Layer 3 and 4, circuit breaker and Kafka alerts:**
```powershell
# Make the payment service fail (must print True)
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/toggle-failure"

# Fire requests: fallback message each time, alerts appear in the gateway console
1..6 | ForEach-Object { Invoke-RestMethod -Method POST -Uri "http://localhost:8080/checkout?orderId=cb-$_" -Headers $h }

# Breaker state should now be OPEN
(Invoke-RestMethod -Uri "http://localhost:8080/actuator/circuitbreakers" -Headers $h).circuitBreakers.paymentService.state

# Recover: toggle back (must print False), wait out the 10s cooldown, send 2 requests
Invoke-RestMethod -Method POST -Uri "http://localhost:8081/toggle-failure"
Start-Sleep 11
1..2 | ForEach-Object { Invoke-RestMethod -Method POST -Uri "http://localhost:8080/checkout?orderId=rec-$_" -Headers $h }
# State should be CLOSED again
```

## Known limitations

These are deliberate simplifications for a demo, not oversights.

- **Simplified login.** `/token` issues a token for any username with no password check. The full register/login flow with BCrypt and a user database was built separately and is not repeated here.
- **Rate limit is keyed by order ID**, not by user. A production version would key on the username from the JWT.
- **One alert per failed request**, not one per breaker state change. A more precise version would publish only when the breaker transitions to Open.
- **The fallback also catches ordinary failures**, not only calls rejected by an open breaker.
- **Kafka is in the failure path.** The alert is sent inside the fallback, so if the broker is unreachable the send can block and slow responses. A short send timeout and a try/catch around the publish would fix this.
- **The JWT signing key is generated at startup**, so tokens stop working after a restart. A real deployment would load a fixed key from a secret store.
- **Single-node Kafka and Redis** with no replication.

## Possible next steps

- Rate limit by authenticated user
- Publish alerts only on breaker state transitions
- Add a retry with exponential backoff for transient payment failures
- Per-user tiers with different rate limits
- Load test the gateway to compare behavior with and without each layer