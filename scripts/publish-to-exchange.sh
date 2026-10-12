#!/usr/bin/env bash
# Publishes this connector to Anypoint Exchange as a Mule plugin asset.
#
# Builds the committed HEAD in a throwaway copy so the working tree and pom.xml stay untouched,
# rewrites the copy's groupId to the Exchange organization ID, and runs `mvn deploy` against the
# Exchange Maven facade. Credentials go into a temporary settings.xml that is deleted on exit.

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/publish-to-exchange.sh --org-id <id> [options]

Options:
  --org-id ID           Anypoint organization or business group ID (or set ANYPOINT_ORG_ID).
                        Access Management > Business Groups > <your group> > Settings.
  --region REGION       Control plane: us (default), eu, or gov.
  --version VERSION     Publish under this version instead of the one in pom.xml.
  --repository-url URL  Deploy to this Maven URL instead of the region's Exchange URL.
  --dry-run             Build and show what would be published, without uploading.
  -h, --help            Show this help.

Credentials come from the environment. The first complete set wins:
  ANYPOINT_CLIENT_ID + ANYPOINT_CLIENT_SECRET   Connected app with the Exchange Contributor scope.
  ANYPOINT_TOKEN                                Anypoint access token.
  ANYPOINT_USERNAME + ANYPOINT_PASSWORD         Anypoint user (not usable with SSO or MFA).

Exchange rejects a version that already exists, so bump --version (or pom.xml) for each publish.
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

org_id="${ANYPOINT_ORG_ID:-}"
region="us"
version=""
repository_url=""
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --org-id) org_id="${2:-}"; shift 2 ;;
    --region) region="${2:-}"; shift 2 ;;
    --version) version="${2:-}"; shift 2 ;;
    --repository-url) repository_url="${2:-}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

[[ -n "$org_id" ]] || { usage >&2; die "--org-id or ANYPOINT_ORG_ID is required"; }
[[ "$org_id" =~ ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$ ]] \
  || die "org ID '$org_id' is not a UUID. Use the ID, not the organization name."

case "$region" in
  us) maven_host="maven.anypoint.mulesoft.com"; api="v3"; exchange_host="anypoint.mulesoft.com" ;;
  eu) maven_host="maven.eu1.anypoint.mulesoft.com"; api="v3"; exchange_host="eu1.anypoint.mulesoft.com" ;;
  gov) maven_host="maven.gov.anypoint.mulesoft.com"; api="v2"; exchange_host="gov.anypoint.mulesoft.com" ;;
  *) die "--region must be us, eu, or gov" ;;
esac
repository_url="${repository_url:-https://$maven_host/api/$api/organizations/$org_id/maven}"

if [[ -n "${ANYPOINT_CLIENT_ID:-}" && -n "${ANYPOINT_CLIENT_SECRET:-}" ]]; then
  maven_user="~~~Client~~~"
  maven_password="$ANYPOINT_CLIENT_ID~?~$ANYPOINT_CLIENT_SECRET"
elif [[ -n "${ANYPOINT_TOKEN:-}" ]]; then
  maven_user="~~~Token~~~"
  maven_password="$ANYPOINT_TOKEN"
elif [[ -n "${ANYPOINT_USERNAME:-}" && -n "${ANYPOINT_PASSWORD:-}" ]]; then
  maven_user="$ANYPOINT_USERNAME"
  maven_password="$ANYPOINT_PASSWORD"
elif [[ "$dry_run" == true ]]; then
  maven_user=""
  maven_password=""
else
  die "no credentials. Set ANYPOINT_CLIENT_ID and ANYPOINT_CLIENT_SECRET (see --help)."
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

command -v git >/dev/null || die "git is required"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
command -v "$java_bin" >/dev/null || die "java is required (JDK 17 or 21)"
java_version="$("$java_bin" -XshowSettings:properties -version 2>&1 \
  | awk -F'= ' '/java.specification.version/ {print $2}')"
[[ "$java_version" == 17 || "$java_version" == 21 ]] \
  || die "JDK 17 or 21 is required (set JAVA_HOME), found ${java_version:-none}"

[[ -z "$(git status --porcelain)" ]] \
  || die "the working tree has uncommitted changes. Only committed code is published, so commit or stash first."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
build_dir="$work_dir/src"
mkdir -p "$build_dir"
git archive HEAD | tar -x -C "$build_dir"

# Rewrites the first element named $1 after </parent>, which is the project's own coordinate.
set_project_element() {
  local element="$1" value="$2" pom="$build_dir/pom.xml"
  awk -v el="$element" -v val="$value" '
    /<\/parent>/ { after_parent = 1 }
    after_parent && !done && $0 ~ "<" el ">[^<]*</" el ">" {
      sub("<" el ">[^<]*</" el ">", "<" el ">" val "</" el ">")
      done = 1
    }
    { print }
    END { if (!done) exit 1 }
  ' "$pom" >"$pom.tmp" || die "could not find the project <$element> in pom.xml"
  mv "$pom.tmp" "$pom"
}

project_element() {
  awk -v el="$1" '
    /<\/parent>/ { after_parent = 1; next }
    after_parent && match($0, "<" el ">[^<]*</" el ">") {
      v = substr($0, RSTART + length(el) + 2, RLENGTH - 2 * length(el) - 5); print v; exit
    }
  ' "$build_dir/pom.xml"
}

set_project_element groupId "$org_id"
[[ -z "$version" ]] || set_project_element version "$version"
artifact_id="$(project_element artifactId)"
version="$(project_element version)"

if [[ "$version" == *-SNAPSHOT ]]; then
  echo "warning: $version is a SNAPSHOT. Exchange keeps SNAPSHOT assets in the development state" \
    "and never lets you promote them." >&2
fi

xml_escape() {
  local s="$1"
  s="${s//&/&amp;}"; s="${s//</&lt;}"; s="${s//>/&gt;}"; s="${s//\"/&quot;}"; s="${s//\'/&apos;}"
  printf '%s' "$s"
}

settings="$work_dir/settings.xml"
(
  umask 077
  cat >"$settings" <<EOF
<settings>
  <servers>
    <server>
      <id>anypoint-exchange</id>
      <username>$(xml_escape "$maven_user")</username>
      <password>$(xml_escape "$maven_password")</password>
    </server>
  </servers>
</settings>
EOF
)

# Our file is the user settings; the caller's own settings (mirrors, proxies) stay in effect as
# the global layer underneath it.
maven_settings=(-s "$settings")
[[ ! -f "$HOME/.m2/settings.xml" ]] || maven_settings+=(-gs "$HOME/.m2/settings.xml")

echo "Commit:      $(git rev-parse --short HEAD)"
echo "Asset:       $org_id:$artifact_id:$version (mule-plugin)"
echo "Repository:  $repository_url"
echo

cd "$build_dir"
if [[ "$dry_run" == true ]]; then
  ./mvnw -B "${maven_settings[@]}" clean verify
  echo
  echo "Dry run: built the files below and uploaded nothing."
  ls -1 target/"$artifact_id-$version"*.jar
  exit 0
fi

./mvnw -B "${maven_settings[@]}" clean deploy \
  -DaltDeploymentRepository="anypoint-exchange::$repository_url"

echo
echo "Published $artifact_id $version."
echo "Exchange:    https://$exchange_host/exchange/$org_id/$artifact_id/"
