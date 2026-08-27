# MOSIP ID-Repository

Consolidated identity + VID + credential service. **JDK 21.** Nested `AGENTS.md` apply only when you work in that folder — do not load every guide up front.

| Work | Folder |
|------|--------|
| Java / Maven | `id-repository/` |
| Local Docker deps | `id-repository/local-dev-setup/` |
| Fresh DB | `db_scripts/` |
| Version hops | `db_upgrade_scripts/` |
| Release SQL | `db_release_scripts/` |
| Helm | `helm/` |
| Cluster install | `deploy/` |
| API tests | `api-test/` |
| OpenAPI | `api-docs/` |

**Build:** `cd id-repository && mvn clean install`

## Agent ops (keep turns small)

- Edit one area; read only that folder’s `AGENTS.md`. Do not open sibling guides or all READMEs.
- Prefer Grep/Glob over reading a whole package. Skip `*.iso`, `*.p12`, `target/`, and WireMock mapping JSON unless the task needs that file.
- How-to lives in README — open it only when you need a command. Start a new chat after a large explore.

## Hard rules

- Three DBs: `mosip_idrepo` (UIN), `mosip_idmap` (VID), `mosip_credential` (store + `BATCH_*`). Never merge schemas or salt tables.
- Salts (`uin_hash_salt`, `uin_encrypt_salt`) exist in **both** idrepo and idmap. Populate via Job `helm/idrepo-saltgen` — never a Deployment or the HTTP JVM.
- Schema change → update `db_scripts` + `db_upgrade_scripts` + `db_release_scripts` (paired rollback/revoke).
- Preserve REST paths, WebSub topics, IDA-facing `core.*` APIs. Update `api-test` + `api-docs` when they change.
- Local laptop: deps in Docker, **app on host** (`local-dev-setup/run-idrepo-local.*`). No id-repo container.
- IDA does **not** use id-repo salt tables (`ida.uin_hash_salt` is separate).

## Contracts

- `/idrepository/v1/identity`, `/idrepository/v1/vid`, `/v1/credentialservice`, `/v1/credentialrequest`
- WebSub: `{partnerId}/CREDENTIAL_ISSUED`, `CREDENTIAL_STATUS_UPDATE`
- Keycloak client: `mosip-idrepo-client`
