# Deployment

Mayday is one process: a Spring Boot jar (`build/libs/mayday.jar`). It needs Java 21 or later (the container image includes Java 25). PostgreSQL is optional.

| Port | Default | Serves |
|---|---|---|
| HTTP | 8080 | Web interface, gRPC-Web, MCP (`/mcp`), alert intake and webhooks, sign-in, probes, `/metrics` |
| gRPC | 9090 | Native gRPC (h2c), reflection, gRPC health. Optional: open it only for clients that use native gRPC. |

| Mode | Storage | Replicas | Use |
|---|---|---|---|
| Memory | RAM only (`MAYDAY_DATA_DIR=none`) | 1 | Demos and tests |
| Journal | RAM + append-only file in `MAYDAY_DATA_DIR` | 1 | Small teams, bare metal, a single VM |
| PostgreSQL | `MAYDAY_DATABASE_URL` | 1 to many | Production and large organizations |

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `MAYDAY_PORT` | 8080 | HTTP port |
| `MAYDAY_HOST` | `0.0.0.0` | HTTP bind address |
| `MAYDAY_GRPC_PORT` | 9090 | Native gRPC port |
| `MAYDAY_PUBLIC_URL` | `http://localhost:<port>` | External URL (OIDC callback, links for agents). With `https`, cookies are `Secure`. |
| `MAYDAY_SECRET_KEY` | development key | Signs sessions and delegated tokens, encrypts stored secrets. Use 32+ random characters. Keep it stable. |
| `MAYDAY_DATA_DIR` | `./data` | Journal directory. `none` = memory only. |
| `MAYDAY_DATABASE_URL` | – | `postgresql://host:5432/db` or a JDBC URL |
| `MAYDAY_DATABASE_USER`, `MAYDAY_DATABASE_PASSWORD`, `MAYDAY_DATABASE_POOL` | –, –, 20 | Database access |
| `MAYDAY_OIDC_ISSUER`, `MAYDAY_OIDC_CLIENT_ID`, `MAYDAY_OIDC_CLIENT_SECRET` | – | Single sign-on. Redirect URI: `<public URL>/auth/callback` |
| `MAYDAY_OIDC_SCOPES` | `openid email profile` | Requested scopes |
| `MAYDAY_OIDC_AUDIENCES` | – | Extra audiences accepted on SSO access tokens (on-behalf-of) |
| `MAYDAY_ADMIN_EMAILS` | – | These users become administrators at first login. The first user is always an administrator. |
| `ANTHROPIC_API_KEY` | – | LLM key for the built-in agent (or set it in the interface) |
| `MAYDAY_DEV_LOGIN` | false | Email sign-in without a password. Never in production. |
| `MAYDAY_DEMO` | false | Sample data when the store is empty |
| `MAYDAY_SESSION_HOURS` | 12 | Session lifetime |
| `MAYDAY_MAX_LIVE_CONNECTIONS` | 100000 | Live event streams (`EventService.Subscribe`) per instance |
| `MAYDAY_AGENT_CONCURRENCY` | 16 | Parallel built-in agent runs per instance |
| `MAYDAY_WEB_DIR` | – | Serve the interface from a directory instead of the built-in copy |

Spring Boot reads all other `spring.*` and `server.*` properties from the environment too, for example `spring.grpc.server.ssl.*` (as `SPRING_GRPC_SERVER_SSL_*`) for TLS on the gRPC port. The defaults are in `src/main/resources/application.yaml`.

## Probes and metrics

| Path (port 8080) | Use |
|---|---|
| `/livez` | Liveness probe |
| `/readyz` | Readiness probe. The container health check uses it. |
| `/healthz` | Overall health |
| `/metrics` | Prometheus text format |
| `grpc.health.v1.Health/Check` (port 9090) | gRPC health for gRPC load balancers |

Important metrics: `http_server_requests_seconds` (HTTP and gRPC-Web), `grpc_server_*` (native gRPC and gRPC-Web calls), `mayday_live_connections` (open event streams), `hikaricp_connections_*` (database pool), `jvm_*`.

## On your machine

With Java 21+ and Node.js 22+ (`make build` runs `npm run build` in `web/` and `./gradlew bootJar`):

```bash
make demo
```

With Docker only:

```bash
docker compose up --build
```

Both open http://localhost:8080 with sample data and email sign-in.

## Container

```bash
docker build -t mayday .
```

```bash
docker run -p 8080:8080 -p 9090:9090 -v mayday-data:/data -e MAYDAY_SECRET_KEY=$(openssl rand -hex 32) -e MAYDAY_DEV_LOGIN=true mayday
```

The image runs `java -XX:+UseZGC -XX:MaxRAMPercentage=75 -jar /opt/mayday/mayday.jar` as user 10001. It exposes ports 8080 and 9090 and has a health check on `/readyz`.

## Kubernetes (Helm)

```bash
helm install mayday deploy/helm/mayday --set secretKey=$(openssl rand -hex 32) --set publicUrl=https://mayday.example.com --set database.url=postgresql://db:5432/mayday --set database.user=mayday --set database.password=... --set ingress.enabled=true --set ingress.host=mayday.example.com
```

- With `database.url`, the chart runs two or more replicas, a PodDisruptionBudget and (optional) a HorizontalPodAutoscaler.
- Without it, the chart forces one replica and the `Recreate` strategy. Set `persistence.enabled=true` to keep the journal.
- The pod has the ports `http` (8080) and `grpc` (9090). Probes: `/livez` and `/readyz`.
- The service has port `http` (`service.port`, 80) and, with `grpc.enabled` (default), port `grpc` (`service.grpcPort`, 9090, `appProtocol: kubernetes.io/h2c`).
- The ingress (`ingress.*`) serves the web interface, gRPC-Web, MCP and webhooks. It turns off proxy buffering for the live event stream.
- Native gRPC through an ingress: set `grpc.ingress.enabled=true`, `grpc.ingress.host` and `grpc.ingress.tls`. The default annotation is for ingress-nginx (`backend-protocol: GRPC`). Clients need TLS, because the ingress accepts HTTP/2 only with TLS.

## Terraform

`deploy/terraform` installs the chart on any Kubernetes cluster, creates the namespace, a random secret key and the secrets. `grpc_enabled` (default true) adds the gRPC service port. `grpc_ingress_host` adds a gRPC ingress with the TLS secret `ingress_tls_secret`.

```bash
cd deploy/terraform && terraform init && terraform apply -var-file=example.tfvars
```

Use your cloud's managed PostgreSQL (RDS, Cloud SQL, Azure Database) and pass its URL in `database_url`.

## Bare metal

Run `java -XX:+UseZGC -jar mayday.jar` with systemd, set the variables in an environment file, and put a TLS reverse proxy in front. Turn off response buffering for gRPC-Web (`/mayday.v1.*`), because `EventService.Subscribe` is a long stream. For native gRPC, the proxy must forward HTTP/2 to port 9090 (h2c).

## Sizing

For 50,000 to 100,000 concurrent users, start with 3 to 6 replicas of 2 CPU and 2 GiB, PostgreSQL with 4 CPU and 16 GiB, and `MAYDAY_DATABASE_POOL=30` per replica. Each idle live connection uses about 10 KiB. Scale on CPU. Watch `http_server_requests_seconds`, `grpc_server_*` and `mayday_live_connections` on `/metrics`.

## Back up and restore

- PostgreSQL: use your normal database backups.
- Journal: copy `MAYDAY_DATA_DIR/mayday.journal` while the service runs. It is append-only and compacted at start.
