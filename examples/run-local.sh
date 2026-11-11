#!/usr/bin/env bash
# Builds the connector and the examples app, deploys the app to a Mule CE runtime against
# examples/fake-unleash.py, and checks every example endpoint. CI runs this same script.
#
# It rehearses the moments that matter in production:
#   1. The app deploys while the flag server is down, and starts evaluating once it is back.
#   2. A flag flipped on the server (the Salesforce maintenance window) reaches the running app
#      without a redeploy, for both the API and the bulk job, and flipping it back rolls back.

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: examples/run-local.sh [--keep-running]

  --keep-running  Leave Mule and the stub running after the checks so you can call the
                  endpoints yourself. Stop them with Ctrl-C.

Environment:
  JAVA_HOME     JDK 17 or 21.
  MULE_HOME     A Mule 4.12+ standalone runtime to deploy to. When unset, Mule CE $MULE_VERSION
                is downloaded once to ~/.cache/mule-unleash-examples.
  MULE_VERSION  CE version to download (default 4.12.0).
  HTTP_PORT     Port for the example endpoints (default 8081).
  STUB_PORT     Port for the fake Unleash server (default 4242).
EOF
}

keep_running=false
case "${1:-}" in
  "") ;;
  --keep-running) keep_running=true ;;
  -h | --help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

die() {
  echo "error: $*" >&2
  exit 1
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_dir="$repo_root/examples/unleash-examples-app"
app_name="unleash-examples-app"
http_port="${HTTP_PORT:-8081}"
stub_port="${STUB_PORT:-4242}"
mule_version="${MULE_VERSION:-4.12.0}"
base_url="http://127.0.0.1:$http_port"
stub_url="http://127.0.0.1:$stub_port"

java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_bin" >/dev/null || die "java is required (JDK 17 or 21)"
java_version="$("$java_bin" -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ {print $2}')"
[[ "$java_version" == 17 || "$java_version" == 21 ]] \
  || die "JDK 17 or 21 is required (set JAVA_HOME), found ${java_version:-none}"
command -v python3 >/dev/null || die "python3 is required for the fake Unleash server"
command -v curl >/dev/null || die "curl is required"

echo "==> Installing the connector"
cd "$repo_root"
./mvnw -B -q install -DskipTests
connector_version="$(./mvnw -B -q help:evaluate -Dexpression=project.version -DforceStdout)"

echo "==> Packaging $app_name against connector $connector_version"
./mvnw -B -q -f "$app_dir/pom.xml" clean package -Dunleash.connector.version="$connector_version"
app_jar="$(ls "$app_dir"/target/"$app_name"-*-mule-application.jar)"

if [[ -z "${MULE_HOME:-}" ]]; then
  cache="${XDG_CACHE_HOME:-$HOME/.cache}/mule-unleash-examples"
  MULE_HOME="$cache/mule-standalone-$mule_version"
  if [[ ! -d "$MULE_HOME/lib/boot" ]]; then
    echo "==> Downloading Mule CE $mule_version"
    mkdir -p "$cache"
    curl -fsSL "https://repository.mulesoft.org/nexus/content/repositories/releases/org/mule/distributions/mule-standalone/$mule_version/mule-standalone-$mule_version.tar.gz" \
      | tar -xz -C "$cache"
  fi
fi
[[ -d "$MULE_HOME/lib/boot" ]] || die "MULE_HOME=$MULE_HOME is not a Mule standalone runtime"

# bin/mule needs the Tanuki wrapper, which ships no binary for current macOS, so start the
# container JVM directly with the module flags this runtime version declares for itself.
jvm_conf="$(find "$MULE_HOME/conf" -name 'wrapper*.conf' -exec cat {} + \
  | sed -e ':a' -e '/\\$/N; s/\\\n[[:space:]]*//; ta')"
add_modules="$(sed -n 's/.*--add-modules=//p' <<<"$jvm_conf" | head -n 1 | tr -d ' ' \
  | sed 's/,org\.mule\.boot\.tanuki//')"
[[ -n "$add_modules" ]] || die "could not read --add-modules from $MULE_HOME/conf"
add_opens=()
while read -r flag; do
  add_opens+=("$flag")
done < <(grep -o -- '--add-opens=[^[:space:]"]*' <<<"$jvm_conf")

stub_pid=""
mule_pid=""
cleanup() {
  [[ -z "$stub_pid" ]] || kill "$stub_pid" 2>/dev/null || true
  if [[ -n "$mule_pid" ]]; then
    kill "$mule_pid" 2>/dev/null || true
    wait "$mule_pid" 2>/dev/null || true
  fi
  rm -rf "$MULE_HOME/apps/$app_name" "$MULE_HOME/apps/$app_name-anchor.txt" \
    "$MULE_HOME/apps/$app_name.jar"
}
trap cleanup EXIT

console_log="$MULE_HOME/logs/run-local-console.log"
dump_logs() {
  echo "---- last lines of $console_log" >&2
  tail -n 80 "$console_log" >&2 2>/dev/null || true
}

failures=0
# Asserts that $1 $2 (method, path) returns HTTP $3 and a body matching the regex $4.
# An optional $5 is sent as a JSON request body.
check() {
  local method="$1" path="$2" want_status="$3" pattern="$4" data="${5:-}" out status body
  local args=(-s -X "$method" -w $'\n%{http_code}')
  [[ -z "$data" ]] || args+=(-H 'Content-Type: application/json' -d "$data")
  out="$(curl "${args[@]}" "$base_url$path" || true)"
  status="${out##*$'\n'}"
  body="$(tr -d '\n' <<<"${out%$'\n'*}")"
  if [[ "$status" == "$want_status" && "$body" =~ $pattern ]]; then
    echo "ok   $method $path -> $status $body"
  else
    echo "FAIL $method $path -> $status $body (want $want_status, /$pattern/)" >&2
    failures=$((failures + 1))
  fi
}

# Polls $method $1 until the body matches the regex $2, failing after $3 seconds. Prints the
# seconds it took.
wait_for() {
  local path="$1" pattern="$2" timeout="$3" body="" start=$SECONDS
  while ((SECONDS - start < timeout)); do
    body="$(curl -s -f -X "${method:-GET}" "$base_url$path" | tr -d '\n' || true)"
    if [[ "$body" =~ $pattern ]]; then
      echo $((SECONDS - start))
      return 0
    fi
    sleep 1
  done
  echo "FAIL GET $path never matched /$pattern/ in ${timeout}s, last: ${body:-<no response>}" >&2
  return 1
}

wait_for_post() {
  method=POST wait_for "$@"
}

# Turns flag $1 on or off ($2 = true|false) on the fake server, like toggling it in GitLab.
flip() {
  curl -s -f -o /dev/null -X POST "$stub_url/admin/flags/$1?enabled=$2"
}

echo "==> Deploying to $MULE_HOME with the flag server down"
curl -s -o /dev/null "$stub_url" && die "something is already listening on $stub_url"
curl -s -o /dev/null "$base_url" && die "something is already listening on $base_url"
rm -rf "$MULE_HOME/apps/$app_name" "$MULE_HOME/apps/$app_name-anchor.txt"
# A backup left by an earlier run would serve last-known flags at boot, which is the production
# behavior but not the cold start this step checks.
backup_file="$MULE_HOME/logs/run-local-flags-backup.json"
rm -f "$backup_file"
cp "$app_jar" "$MULE_HOME/apps/$app_name.jar"
(
  cd "$MULE_HOME"
  exec "$java_bin" \
    -Dmule.home="$MULE_HOME" -Dmule.base="$MULE_HOME" \
    -Dmule.bootstrap.container.wrapper.class=org.mule.runtime.module.boot.internal.MuleContainerBasicWrapper \
    -Xms1024m -Xmx1024m -Djava.net.preferIPv4Stack=TRUE -XX:MaxMetaspaceSize=256m \
    -Djava.locale.providers=COMPAT,CLDR,SPI -Dlog4j2.disable.jmx=true \
    -Dhttp.port="$http_port" \
    -Dunleash.api.url="$stub_url/api/v4/feature_flags/unleash/42" \
    -Dunleash.backup.file="$backup_file" \
    --module-path="$MULE_HOME/lib/boot" --add-modules="$add_modules" "${add_opens[@]}" \
    --module=org.mule.boot/org.mule.runtime.module.reboot.MuleContainerBootstrap
) >"$console_log" 2>&1 &
mule_pid=$!

for ((i = 0; i < 300; i++)); do
  [[ -f "$MULE_HOME/apps/$app_name-anchor.txt" ]] && break
  kill -0 "$mule_pid" 2>/dev/null || { dump_logs; die "Mule exited during startup"; }
  if grep -q "Failed to deploy artifact \[$app_name\]" "$console_log" 2>/dev/null; then
    dump_logs
    die "$app_name failed to deploy"
  fi
  sleep 1
done
[[ -f "$MULE_HOME/apps/$app_name-anchor.txt" ]] || { dump_logs; die "$app_name did not deploy within 300s"; }
echo "ok   deployed while the flag server was down"
check GET "/flags/new-checkout" 200 '"enabled": ?false'

echo "==> Starting the fake Unleash server on port $stub_port"
python3 "$repo_root/examples/fake-unleash.py" "$stub_port" >"$MULE_HOME/logs/fake-unleash.log" 2>&1 &
stub_pid=$!
took="$(wait_for "/flags/new-checkout" '"enabled": ?true' 60)" || { dump_logs; die "flags never recovered"; }
echo "ok   flags evaluate ${took}s after the server came back"

echo "==> Checking the operation examples"
check GET "/flags/new-checkout" 200 '"enabled": ?true'
check GET "/flags/retired-flag" 200 '"enabled": ?false'
check GET "/flags/no-such-flag" 200 '"enabled": ?false'
check GET "/flags/vip-flag?userId=vip-user" 200 '"enabled": ?true'
check GET "/flags/vip-flag?userId=someone-else" 200 '"enabled": ?false'
check GET "/variants/checkout-copy" 200 '"name": ?"treatment".*"payloadValue": ?"blue"'
check GET "/variants/new-checkout" 200 '"name": ?"disabled".*"featureEnabled": ?true'
check GET "/payloads/checkout-copy" 200 '^blue$'
check GET "/payloads/new-checkout" 404 '"no payload"'

account='{"name": "Acme Corp", "industry": "Manufacturing", "loyaltyTier": "Gold"}'

echo "==> Salesforce field rollout: before the maintenance window (flag off)"
check GET "/accounts/001xx0000001" 200 '^\{[^}]*"industry": ?"Manufacturing" *\}$'
check POST "/accounts" 201 '"loyaltyTierWritten": ?false' "$account"

echo "==> Salesforce field rollout: flipping salesforce-loyalty-tier on"
flip salesforce-loyalty-tier true
took="$(wait_for "/accounts/001xx0000001" '"loyaltyTier": ?"Gold"' 60)" || failures=$((failures + 1))
[[ -z "$took" ]] || echo "ok   flag flip reached the app in ${took}s with no redeploy"
check GET "/accounts/001xx0000001" 200 '"loyaltyTier": ?"Gold"'
check POST "/accounts" 201 '"Loyalty_Tier__c": ?"Gold".*"loyaltyTierWritten": ?true' "$account"

echo "==> Salesforce field rollout: rolling back (flag off)"
flip salesforce-loyalty-tier false
took="$(wait_for "/accounts/001xx0000001" '^\{[^}]*"industry": ?"Manufacturing" *\}$' 60)" \
  || failures=$((failures + 1))
[[ -z "$took" ]] || echo "ok   rollback reached the app in ${took}s"

old_csv='"csv": ?"External_Id__c,Name,Status__c\\[rn]'
new_csv='"csv": ?"External_Id__c,Name,Is_Active__c,Status__c\\[rn]'

echo "==> Picklist to checkbox: Salesforce still has only the picklist (flag off)"
check GET "/v1/customers/001xx0000001" 200 '"status": ?"Active"'
check GET "/v2/customers/001xx0000001" 200 '"isActive": ?true'
check POST "/jobs/customer-sync" 200 "\"mapping\": ?\"picklist\".*$old_csv"

echo "==> Picklist to checkbox: Is_Active__c deployed and backfilled, flipping the flag on"
flip salesforce-is-active-checkbox true
took="$(wait_for_post "/jobs/customer-sync" '"mapping": ?"checkbox"' 60)" || failures=$((failures + 1))
[[ -z "$took" ]] || echo "ok   bulk job switched mapping ${took}s after the flip"
check POST "/jobs/customer-sync" 200 "$new_csv"
check GET "/v1/customers/001xx0000001" 200 '"status": ?"Active"'
check GET "/v2/customers/001xx0000001" 200 '"isActive": ?true'

echo "==> Picklist to checkbox: rolling back (flag off)"
flip salesforce-is-active-checkbox false
wait_for_post "/jobs/customer-sync" '"mapping": ?"picklist"' 60 >/dev/null || failures=$((failures + 1))
check POST "/jobs/customer-sync" 200 "$old_csv"

if ((failures > 0)); then
  dump_logs
  die "$failures check(s) failed"
fi
echo "==> All checks passed"

if [[ "$keep_running" == true ]]; then
  echo "Mule and the stub are running. Try:"
  echo "  curl $base_url/accounts/001xx0000001"
  echo "  curl -X POST '$stub_url/admin/flags/salesforce-loyalty-tier?enabled=true'"
  echo "  curl -X POST $base_url/jobs/customer-sync"
  echo "Ctrl-C to stop."
  wait "$mule_pid"
fi
