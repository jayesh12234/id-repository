#!/usr/bin/env bash
# Rebuild id-repository-service and run it on the host against local Docker deps.
#
# Prerequisites:
#   Deps up:  ./run-local-stack.sh up   (or deps/restart-idrepo.sh)
#
# Usage (from local-dev-setup):
#   ./run-idrepo-local.sh              rebuild jar, then start
#   ./run-idrepo-local.sh --no-build   start existing jar only
#   ./run-idrepo-local.sh --build      same as default (kept for compatibility)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAVEN_PARENT="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_DIR="$MAVEN_PARENT/id-repository-service/target"
CONFIG_URI="${SPRING_CLOUD_CONFIG_URI:-http://localhost:51100/config}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1536m}"
DO_BUILD=1

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) DO_BUILD=0; shift ;;
    --build)    DO_BUILD=1; shift ;;
    *) die "unknown argument: $1
Usage: $0 [--build|--no-build]" ;;
  esac
done

if [[ "$DO_BUILD" -eq 1 ]]; then
  info "Building id-repository-service..."
  # Full package lifecycle so git-commit-id (validate) + spring-boot:build-info run;
  # cherry-picked compile/jar/repackage skips META-INF/build-info.properties and git.properties.
  (cd "$MAVEN_PARENT" && mvn -pl id-repository-service clean package -Dmaven.test.skip=true -Dgpg.skip=true -Dmaven.javadoc.skip=true)
fi

JAR="$(ls -1 "$TARGET_DIR"/id-repository-service-*.jar 2>/dev/null \
  | grep -vE 'sources|javadoc|tests' | head -n 1 || true)"
[[ -n "$JAR" ]] || die "jar not found under $TARGET_DIR
Run without --no-build, or:
  cd id-repository && mvn -pl id-repository-service -am package -DskipTests -Dgpg.skip=true"

info "Waiting for config-server at $CONFIG_URI ..."
ready=0
for i in $(seq 1 60); do
  if curl -sf "$CONFIG_URI/id-repository/default" >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
[[ "$ready" -eq 1 ]] || die "config-server not reachable. Start deps first:
  ./run-local-stack.sh up
  or: deps/restart-idrepo.sh"

info "config-server is ready."
info ""
info "Starting id-repository-service on http://localhost:8090"
info "Jar: $JAR"
info "Stop with Ctrl+C"
info ""

# shellcheck disable=SC2086
exec java $JAVA_OPTS \
  -Dfile.encoding=UTF-8 \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  -Dspring.cloud.config.uri="$CONFIG_URI" \
  -Dspring.config.import="configserver:$CONFIG_URI" \
  -Dspring.cloud.config.label="${SPRING_CLOUD_CONFIG_LABEL:-develop}" \
  -Dspring.profiles.active=default,local \
  -Dspring.cloud.config.override-none=true \
  -Dspring.cloud.loadbalancer.enabled=false \
  -Dserver.port=8090 \
  -jar "$JAR"
