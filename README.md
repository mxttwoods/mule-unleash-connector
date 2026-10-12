# Mule Unleash Connector

Mule 4 connector for [Unleash](https://docs.getunleash.io/) feature flags, including **GitLab's
Unleash-compatible feature flags API** (self-managed or GitLab.com). Evaluate flags and variants
inside flows and hold the results in flow variables.

## Compatibility

| Piece | Version |
|---|---|
| Mule runtime | 4.6.0+ (`min.mule.version`; SDK 1.13.0 builds against the 4.13.0 runtime model) |
| Java | 17 or 21 (`@JavaVersionSupport`) |
| Mule SDK | 1.13.0 (`mule-java-extension-parent`) |
| Unleash Java client | 12.3.0 (`io.getunleash:unleash-client-java`) |
| Maven | 3.9.8+ |
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

- **Ephemeral filesystem**: the SDK's `unleash-repo.json` backup does not survive restarts; workers
  refetch on boot (`synchronousFetch` defaults to true so the first fetch happens at startup — but
  if the server is down at boot, early events evaluate against an empty cache and get defaults).
  You can still set `backupFile` to a path under `/tmp` for in-lifetime resilience.
- **Rate limits**: GitLab's flags API is per-IP limited. With many replicas, raise
  `fetchTogglesInterval` (e.g. 60s) and keep `disableMetrics="true"`.
- **Egress**: if workers reach GitLab through a proxy, set `proxyHost`/`proxyPort`; for gateway
  tokens use `customHeaders`. Secrets belong in secure properties, never in XML.

## Build

```bash
export JAVA_HOME=<jdk-17-home>
mvn -B clean package   # 22 tests, incl. live-HTTP round trip against a stub server
```

`mvn install` / `mvn verify` run the `integration-test` phase, which needs the **enterprise**
`munit-extensions-maven-plugin` from MuleSoft's EE repository. Add your Anypoint credentials to
`~/.m2/settings.xml`:

```xml
<server>
  <id>mulesoft-ee</id>
  <username>YOUR_ANYPOINT_USERNAME</username>
  <password>YOUR_ANYPOINT_PASSWORD</password>
</server>
```

(plus the matching EE repository/pluginRepository entries) — the same credentials you later need to
publish to Exchange. Without them, install the packaged plugin locally for Studio testing:

```bash
mvn -B org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
  -Dfile=target/mule-unleash-connector-1.0.0-SNAPSHOT-mule-plugin.jar \
  -DgroupId=com.work -DartifactId=mule-unleash-connector \
  -Dversion=1.0.0-SNAPSHOT -Dclassifier=mule-plugin -Dpackaging=mule-extension \
  -DpomFile=pom.xml
```

then depend on it from your app:

```xml
<dependency>
  <groupId>com.work</groupId>
  <artifactId>mule-unleash-connector</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <classifier>mule-plugin</classifier>
</dependency>
```

## Tests

- `UnleashContextParamsTest` — operation-over-defaults merge semantics.
- `UnleashVariantResultTest` — SDK `Variant` → DataWeave POJO mapping (incl. `FakeUnleash`).
- `UnleashOperationsTest` — operations against `FakeUnleash` (flag on/off/unknown + defaults).
- `UnleashLiveClientTest` — real `DefaultUnleash` HTTP round trip (register/fetch/evaluate) against
  an in-JVM stub serving `features-v1` JSON, GitLab-style with empty API key.

## Project layout

```
pom.xml
src/main/java/com/work/unleash/internal/
  UnleashExtension.java          @Extension, namespace unleash
  UnleashConfiguration.java      operations binding + default context
  UnleashConnectionProvider.java cached SDK client factory (all tuning params)
  UnleashConnection.java         DefaultUnleash wrapper (shutdown on disconnect)
  UnleashOperations.java         is-enabled / get-variant / get-variant-payload
  UnleashContextParams.java      context group + merge logic
  UnleashVariantResult.java      DataWeave-friendly variant POJO
  UnleashErrorType.java          CONNECTION / EVALUATION
src/test/java/...               22 tests, no Mule runtime needed
demo/unleash-demo-flow.xml      copy-paste flow sample
```

## Roadmap

- Verify against your on-prem GitLab version (flag evaluation is standard Unleash; watch the
  per-IP rate limit with many workers).
- MUnit coverage + Exchange publishing (needs EE credentials above).
- Optional: custom Unleash strategies via `@Parameter`-supplied class names.
