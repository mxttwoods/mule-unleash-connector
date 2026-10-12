# Mule Unleash Connector

[![build](https://github.com/mxttwoods/mule-unleash-connector/actions/workflows/build.yml/badge.svg)](https://github.com/mxttwoods/mule-unleash-connector/actions/workflows/build.yml)

Mule 4 connector for [Unleash](https://docs.getunleash.io/) feature flags, including **GitLab's
Unleash-compatible feature flags API** (self-managed or GitLab.com). Evaluate flags and variants
inside flows and hold the results in flow variables.

## Compatibility

| Piece | Version |
|---|---|
| Mule runtime | 4.6.0+ (`min.mule.version`; SDK 1.13.0 builds against the 4.13.0 runtime model) |
| Java | 17 or 21 (`supportedJavaVersions` in `pom.xml`) |
| Mule SDK | 1.13.0 (`mule-java-extension-parent`) |
| Unleash Java client | 12.3.0 (`io.getunleash:unleash-client-java`) |
| Maven | 3.9.16 via the bundled wrapper (`./mvnw`). The GitHub runner's default Maven fails in `mule-extensions-maven-plugin` with a resolver `NoSuchMethodError`. |
| GitLab strategies supported | all-users, percent rollout, percent of users, user IDs, user list |

## Operations

| Operation | Returns | Purpose |
|---|---|---|
| `is-enabled` | `boolean` | Flag state for the context; `defaultValue` when unknown/unreachable |
| `get-variant` | `{name, enabled, featureEnabled, payloadType, payloadValue}` | Selected variant + payload |
| `get-variant-payload` | `string \| null` | Just the variant payload value |

There is deliberately **no Router**: the Mule SDK forbids routers from receiving a connection or
configuration, and a router with its own polling client would double API traffic. Use
`target` + `choice` instead (see `demo/unleash-demo-flow.xml`).

## Configure

GitLab values come from **Deploy > Feature flags > Configure** in your project.

```xml
<unleash:config name="Unleash_GitLab">
  <unleash:connection unleashApiUrl="https://gitlab.example.com/api/v4/feature_flags/unleash/42"
                      instanceId="${secure::gitlab.unleash.instanceId}"
                      appName="production"
                      fetchTogglesInterval="60"
                      disableMetrics="true" />
</unleash:config>
```

For Unleash OSS/Enterprise add `apiKey="<client-token>"` and use the client API URL. `apiKey` is
left empty for GitLab: the client sends the `UNLEASH-INSTANCEID` header and no `Authorization`
header (the integration test asserts both against the stub; confirm against your GitLab instance).

Set `testConnectivity="true"` on the connection to make Test Connection perform a live GET on the
flags endpoint, so a bad URL or credentials fail fast instead of silently evaluating to defaults.

### Context (per operation, or config-wide defaults)

| Field | Flow mapping example | Needed for |
|---|---|---|
| `userId` | `#[vars.userId]` | user IDs / percent-of-users |
| `sessionId` | `#[attributes.headers['x-session-id']]` | session stickiness |
| `remoteAddress` | `#[attributes.remoteAddress]` | IP strategies |
| `environment` | set explicitly, e.g. `#[vars.env]` | constraints (no default is applied) |
| `properties` (map) | `#[{'tenant': vars.tenant}]` | custom constraints/stickiness |

Operation-level fields override the config's **Default Context** group field by field; `properties`
maps are merged.

## Use in a flow

```xml
<unleash:is-enabled config-ref="Unleash_GitLab" toggleName="new-checkout"
                    defaultValue="false" target="newCheckoutEnabled"
                    userId="#[vars.userId]" />
<choice>
  <when expression="#[vars.newCheckoutEnabled]"> ... </when>
  <otherwise> ... </otherwise>
</choice>

<unleash:get-variant config-ref="Unleash_GitLab" toggleName="checkout-copy"
                     target="copy" userId="#[vars.userId]" />
<!-- #[vars.copy.name], #[vars.copy.payloadValue], #[vars.copy.enabled] -->
```

## CloudHub 2.0 notes

- **Ephemeral filesystem**: the flag backup (by default `unleash-<hash of URL + appName>-repo.json`
  in the JVM temp dir) does not survive restarts; workers refetch on boot. `synchronousFetch`
  defaults to true so the first fetch happens at startup. If the server is down at boot, the app
  still deploys (a warning is logged) and evaluates against the backup file, or returns operation
  defaults until the next successful poll.
- **Native library**: Unleash Java client 12.x evaluates flags with the Yggdrasil engine, a native
  library that is extracted to the JVM temp dir and loaded at startup. The temp dir must be
  writable and allow execution. If `/tmp` is mounted `noexec`, point the
  `io.getunleash.engine.native.path` system property at a directory holding the library.
- **Rate limits**: GitLab's flags API is per-IP limited. With many replicas, raise
  `fetchTogglesInterval` (e.g. 60s) and keep `disableMetrics="true"`.
- **Egress**: if workers reach GitLab through a proxy, set `proxyHost`/`proxyPort`; for gateway
  tokens use `customHeaders`. Secrets belong in secure properties, never in XML.

## Build

```bash
export JAVA_HOME=<jdk-17-home>
./mvnw -B clean package   # 24 tests, incl. live-HTTP round trip against a stub server
```

The project has no MUnit suites, so the parent's EE-only MUnit plugin is unbound and
`./mvnw verify`, `install` and `deploy` need no MuleSoft EE repository access.

To use the connector from Studio or a local app build, install it into your local Maven
repository with `./mvnw -B install`. Without building, download
`mule-unleash-connector-1.0.0-mule-plugin.jar` from the
[v1.0.0 release](https://github.com/mxttwoods/mule-unleash-connector/releases/tag/v1.0.0) and
install that file:

```bash
./mvnw -B org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile=mule-unleash-connector-1.0.0-mule-plugin.jar \
  -DgroupId=com.work -DartifactId=mule-unleash-connector \
  -Dversion=1.0.0 -Dclassifier=mule-plugin -Dpackaging=mule-extension \
  -DpomFile=pom.xml
```

then depend on it from your app:

```xml
<dependency>
  <groupId>com.work</groupId>
  <artifactId>mule-unleash-connector</artifactId>
  <version>1.0.0</version>
  <classifier>mule-plugin</classifier>
</dependency>
```

## Publish to Anypoint Exchange

`scripts/publish-to-exchange.sh` publishes the committed `HEAD` to your organization's Exchange.
It builds a throwaway copy, sets that copy's `groupId` to your organization ID as Exchange requires,
and runs `mvn deploy` against the Exchange Maven endpoint. Your working tree and `pom.xml` are
not changed.

1. Find the organization or business group ID in Anypoint Platform under **Access Management >
   Business Groups > your group > Settings**.
2. Create a connected app that acts on its own behalf, with the **Exchange Contributor** scope for
   that business group. Note its client ID and secret.
3. Check the build without uploading anything:

   ```bash
   export JAVA_HOME=<jdk-17-home>
   scripts/publish-to-exchange.sh --org-id <org-id> --dry-run
   ```

4. Publish:

   ```bash
   export ANYPOINT_CLIENT_ID=<client-id> ANYPOINT_CLIENT_SECRET=<client-secret>
   scripts/publish-to-exchange.sh --org-id <org-id>
   ```

Use `--region eu` or `--region gov` for those control planes. `ANYPOINT_TOKEN`, or
`ANYPOINT_USERNAME` with `ANYPOINT_PASSWORD`, also work in place of a connected app. Exchange
rejects a version that already exists, so pass `--version 1.0.1` (or bump `pom.xml`) for each new
publish. Apps that use the published asset depend on it with your organization ID as the
`groupId`.

## Tests

- `UnleashContextParamsTest` — operation-over-defaults merge semantics.
- `UnleashVariantResultTest` — SDK `Variant` → DataWeave POJO mapping (incl. `FakeUnleash`).
- `UnleashOperationsTest` — operations against `FakeUnleash` (flag on/off/unknown + defaults).
- `UnleashLiveClientTest` — real `DefaultUnleash` HTTP round trip (register/fetch/evaluate) against
  an in-JVM stub serving `features-v1` JSON, GitLab-style with empty API key; also startup with
  the server down, connectivity validation, and per-project backup file names.

## Project layout

```
pom.xml
src/main/java/com/work/unleash/api/
  UnleashVariantResult.java      DataWeave-friendly variant POJO (exported type)
src/main/java/com/work/unleash/internal/
  UnleashExtension.java          @Extension, namespace unleash
  UnleashConfiguration.java      operations binding + default context
  UnleashConnectionProvider.java cached SDK client factory (all tuning params)
  UnleashConnection.java         DefaultUnleash wrapper (shutdown on disconnect)
  UnleashOperations.java         is-enabled / get-variant / get-variant-payload
  UnleashContextParams.java      context group + merge logic
  UnleashErrorType.java          EVALUATION
src/test/java/...               24 tests, no Mule runtime needed
demo/unleash-demo-flow.xml      copy-paste flow sample
scripts/publish-to-exchange.sh  Anypoint Exchange publisher
```

## Roadmap

- Verify against your on-prem GitLab version (flag evaluation is standard Unleash; watch the
  per-IP rate limit with many workers).
- MUnit coverage (needs MuleSoft EE repository access for the MUnit plugin).
- Optional: custom Unleash strategies via `@Parameter`-supplied class names.

## License

MIT. See [LICENSE](LICENSE).
