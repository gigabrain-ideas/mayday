# Deployment

Mayday is one process. It needs Java 21 or later (the container image includes Java 25). PostgreSQL is optional.

| Mode | Storage | Replicas | Use |
|---|---|---|---|
| Memory | RAM only (`MAYDAY_DATA_DIR=none`) | 1 | Demos and tests |
| Journal | RAM + append-only file in `MAYDAY_DATA_DIR` | 1 | Small teams, bare metal, a single VM |
| PostgreSQL | `MAYDAY_DATABASE_URL` | 1 to many | Production and large organizations |

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` / `MAYDAY_PORT` | 8080 | HTTP port |
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
| `MAYDAY_MAX_LIVE_CONNECTIONS` | 100000 | SSE connections per instance |
| `MAYDAY_AGENT_CONCURRENCY` | 16 | Parallel built-in agent runs per instance |
| `MAYDAY_WEB_DIR` | – | Serve the interface from a directory instead of the built-in copy |

## On your machine

With Java 21+ and Node.js 22+:

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
docker run -p 8080:8080 -v mayday-data:/data -e MAYDAY_SECRET_KEY=$(openssl rand -hex 32) -e MAYDAY_DEV_LOGIN=true mayday
```

The image runs as user 10001, exposes port 8080 and has a health check on `/healthz`.

## Kubernetes (Helm)

```bash
helm install mayday deploy/helm/mayday --set secretKey=$(openssl rand -hex 32) --set publicUrl=https://mayday.example.com --set database.url=postgresql://db:5432/mayday --set database.user=mayday --set database.password=... --set ingress.enabled=true --set ingress.host=mayday.example.com
```

- With `database.url`, the chart runs two or more replicas, a PodDisruptionBudget and (optional) a HorizontalPodAutoscaler.
- Without it, the chart forces one replica and the `Recreate` strategy. Set `persistence.enabled=true` to keep the journal.
- The ingress turns off proxy buffering for live updates.

## Terraform

`deploy/terraform` installs the chart on any Kubernetes cluster, creates the namespace, a random secret key and the secrets.

```bash
cd deploy/terraform && terraform init && terraform apply -var-file=example.tfvars
```

Use your cloud's managed PostgreSQL (RDS, Cloud SQL, Azure Database) and pass its URL in `database_url`.

## Bare metal

Run `build/install/mayday/bin/mayday` with systemd, set the variables in an environment file, and put a TLS reverse proxy in front. Turn off response buffering for `/api/v1/events`.

## Sizing

For 50,000 to 100,000 concurrent users, start with 3 to 6 replicas of 2 CPU and 2 GiB, PostgreSQL with 4 CPU and 16 GiB, and `MAYDAY_DATABASE_POOL=30` per replica. Each idle live connection uses about 10 KiB. Scale on CPU. Watch `mayday_http_request_duration_seconds` and `mayday_sse_clients` on `/metrics`.

## Back up and restore

- PostgreSQL: use your normal database backups.
- Journal: copy `MAYDAY_DATA_DIR/mayday.journal` while the service runs. It is append-only and compacted at start.
