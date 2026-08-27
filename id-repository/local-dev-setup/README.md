# ID-Repository local development

Run **id-repository-service** on your laptop: Docker for dependencies, JVM on the host.

| Guide | Audience |
|-------|----------|
| **This README** | Clean startup / wipe / day-to-day |
| [`LOCAL-DEV-SETUP.md`](LOCAL-DEV-SETUP.md) | Full prerequisites, manual steps, DBs, troubleshooting |
| [`AGENTS.md`](AGENTS.md) | Agent rules, WireMock stubs, failure modes |

---

## Architecture

```text
Docker Compose (deps only)          Host
─────────────────────────           ────
Postgres :5455                      id-repository-service :8090
Config-server :51100
WireMock :8082  (IAM, BioSDK, …)
MinIO :9000
Keymanager :8088
websub-partner-ack :8085
```

There is **no** id-repo container. Auth uses WireMock JWTs (no Keycloak).

---

## Prerequisites

JDK **21**, Maven **3.9+**, Docker Desktop (Compose V2). Details: [`LOCAL-DEV-SETUP.md`](LOCAL-DEV-SETUP.md).

---

## Clean startup (recommended)

From the **git clone root**:

```bash
cd id-repository/id-repository/local-dev-setup

chmod +x run-local-stack.sh run-idrepo-local.sh   # macOS / Linux / WSL / Git Bash (once)

./run-local-stack.sh build                        # package id-repository-service
./run-local-stack.sh up                           # prep + start Docker deps
./run-idrepo-local.sh                             # host app on :8090 (keep running)
```

**Windows (cmd):**

`bash` on many PCs is **WSL** (not Git Bash). WSL does not inherit Windows `JAVA_HOME`, so prefer this sequence:

```bat
cd id-repository\id-repository\local-dev-setup
bash run-local-stack.sh up
run-idrepo-local.bat
```

- `up` — Docker deps only (no Maven). **Keeps Postgres/MinIO volumes** — same DB as last run.
- Fresh DB: use `bash run-local-stack.sh wipe` instead of `up` (see [Wipe DB](#wipe-db-and-start-fresh)).
- `run-idrepo-local.bat` — builds with **Windows** JDK/Maven, then starts the host app on `:8090` (does **not** reset the DB).

Optional: `bash run-local-stack.sh build` on WSL runs Maven through a short Windows `.cmd` (bash cannot drive Windows Maven/`java.exe` directly). Prefer `run-idrepo-local.bat` — same build, then starts `:8090`.

`up` creates PKCS12 / `keys-generator.jar` if missing, then starts Compose. Wait until `docker compose ps` shows healthy, then start the host app.

### Smoke

```bash
./run-local-stack.sh smoke
# or:
curl -s http://localhost:8090/actuator/health
curl -s http://localhost:8088/v1/keymanager/actuator/health
curl -s http://localhost:8082/v1/idgenerator/uin
```

### API tests

With deps + host app up:

```bash
cd ../../../api-test          # from local-dev-setup → git root api-test/
./run-local-smoke.sh smoke    # Windows: run-local-smoke.bat smoke
```

See [`api-test/README.md`](../../api-test/README.md).

---

## Wipe DB and start fresh

**`build` and `up` do not clear the database.** They keep Docker volumes, so UIN / VID / credential rows from earlier api-tests stay in Postgres.

| Command | Effect on DB |
|---------|----------------|
| `build` | Maven package only — **no** Docker / DB change |
| `up` / `down` | Start or stop containers — **volumes kept** |
| `wipe` | `docker compose down -v` then `up` — **empty DB**, re-runs `init.sql` |

### Fresh testing (recommended)

```bash
cd id-repository/id-repository/local-dev-setup
./run-local-stack.sh wipe     # remove volumes + re-init schemas/salts
./run-idrepo-local.sh         # restart host app against clean DB
# then: api-test smoke, Postman, etc.
```

**Windows:**

```bat
cd id-repository\id-repository\local-dev-setup
bash run-local-stack.sh wipe
run-idrepo-local.bat
```

Use before a clean api-test run, after leftover identities (`IDR-IDC-012`), bad credential rows, or `init.sql` / DDL changes. PKCS12 under `keys/` is **kept** — required when using static keymgr seed in `init.sql`. Do **not** delete `keys/mosip-idrepo-ks.p12` or crypto decrypt will fail after wipe.

**Manual equivalent:**

```bash
cd deps
docker compose down -v --remove-orphans
docker compose up -d --remove-orphans
# wait healthy, then run-idrepo-local.*
```

---

## Day-to-day commands

| Goal | Command |
|------|---------|
| Start Docker deps | `./run-local-stack.sh up` |
| Low memory (~3 GB Docker deps) | `./run-local-stack.sh up-low-mem` |
| Start host app | `./run-idrepo-local.sh` / `run-idrepo-local.bat` |
| Skip Maven rebuild | `run-idrepo-local.bat --no-build` |
| Wipe + low-mem | `./run-local-stack.sh wipe-low-mem` |
| Recreate Docker deps | `./run-local-stack.sh restart` (or `restart-low-mem`) |
| Stop (keep DB) | `./run-local-stack.sh down` |
| Wipe DB + re-init | `./run-local-stack.sh wipe` |
| Status / logs | `./run-local-stack.sh status` / `logs` |

### 6 GB Docker Desktop (low-mem)

1. Docker Desktop → Settings → Resources → Memory → **6 GB** → Apply & restart.
2. Stop other compose projects.
3. `./run-local-stack.sh up-low-mem` (or `wipe-low-mem` for a clean DB).
4. Start the host app — it uses **extra** laptop RAM (~1–2 GB), not the container budget.

Overlay: [`deps/docker-compose.low-mem.yml`](deps/docker-compose.low-mem.yml).

After jar or `application-local.properties` changes, restart the **host** JVM. Recreate WireMock only when stubs change:

```bash
cd deps
docker compose up -d --force-recreate --no-deps mock-service
# with low-mem caps:
docker compose -f docker-compose.yml -f docker-compose.low-mem.yml up -d --force-recreate --no-deps mock-service
```
---

## Ports

| Service | Port |
|---------|------|
| ID-Repository (host) | `8090` |
| PostgreSQL | `5455` |
| Config Server | `51100` |
| WireMock | `8082` |
| Key Manager | `8088` |
| MinIO | `9000` / `9001` |

Swagger (after host app is up):  
`http://localhost:8090/idrepository/v1/identity/swagger-ui/index.html`

---

## Layout

```text
local-dev-setup/
├── README.md                 ← this file
├── LOCAL-DEV-SETUP.md        ← detailed human guide
├── AGENTS.md                 ← agent rules
├── run-local-stack.sh        ← prep / up / wipe / smoke
├── run-idrepo-local.bat|.sh  ← host JVM
├── keys/                     ← PKCS12 (gitignored)
└── deps/
    ├── docker-compose.yml    ← deps only
    ├── init.sql
    ├── mosip-config/
    ├── wiremock/mappings/id-repository.json
    ├── keymanager/           ← DDL + bootstrap (static key DML is inlined in init.sql)
    └── websub-partner-ack/
```

---

*Last updated: 2026-08-25.*
