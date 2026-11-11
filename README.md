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
`target` + `choice` instead (see [`examples/`](examples/)).

Each operation can raise `UNLEASH:EVALUATION` for an unexpected SDK failure. An unknown flag or an
unreachable server is not an error: it returns the default.

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
It does not block deployment: `examples/run-local.sh` deploys with this set while the flag server
is down, and the app starts and serves defaults until the server is back.

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

## Examples

[`examples/`](examples/) has a runnable Mule app with every operation and a Salesforce field
rollout, plus a fake Unleash server, so it runs with no GitLab, Salesforce, Docker or EE license:

```bash
export JAVA_HOME=<jdk-17-home>
examples/run-local.sh            # builds, deploys to Mule CE 4.9.0, checks every endpoint
examples/run-local.sh --keep-running
```

CI runs the same script on every change.

## Releasing an API field ahead of a Salesforce change

Deploy the Mule change before the Salesforce maintenance window with the flag off, then turn the
flag on during the window. `examples/unleash-examples-app/src/main/mule/salesforce-field-rollout.xml`
is the worked example, and `run-local.sh` rehearses the flip and the rollback.

1. Create the flag in GitLab (**Deploy > Feature flags**) with a strategy scoped to the
   environment that matches the connection's `appName`, for example `production`. Leave it off.
2. In the flow, evaluate the flag once per request into a variable with `defaultValue="false"`,
   and gate everything that names the new field on it: the SOQL field list (selecting a missing
   field fails the whole query), the record you write, and the response mapping.
3. Deploy Mule any time before the window. With the flag off, nothing touches the new field.
4. In the window, after the field is deployed and the integration user has field-level security
   on it, turn the flag on. Each worker picks it up on its next poll, so allow up to
   `fetchTogglesInterval` seconds. No redeploy is needed. Turning the flag off is the rollback.
5. Once it is stable, remove the flag and the old branch in a normal release.

The fallback is the pre-window behavior. A worker that cannot reach GitLab uses its last-known
flags from the backup file, or `defaultValue` if it has none, which on CloudHub 2.0 is the case
after a restart. That is safe while the change is additive. If the Salesforce change removes or
renames a field the old path uses, the old path breaks after the window, so plan the rollout so
that both paths stay valid until the flag is removed.

## CloudHub 2.0 notes

- **Ephemeral filesystem**: the flag backup (by default `unleash-<hash of URL + appName>-repo.json`
  in the JVM temp dir) does not survive restarts; workers refetch on boot. `synchronousFetch`
  defaults to true so the first fetch happens at startup. If the server is down at boot, the app
  still deploys (a warning is logged) and evaluates against the backup file, or returns operation
  defaults until the next successful poll.
- **Native library**: Unleash Java client 12.x evaluates flags with the Yggdrasil engine, a native
  library (Linux, macOS and Windows on x86_64 and arm64) that is extracted to the JVM temp dir and
  loaded at startup. The temp dir must be writable and allow execution. If `/tmp` is mounted
  `noexec`, point the `io.getunleash.engine.native.path` system property at a directory holding
  the library. Each deployment extracts its own copy (about 3.5 MB), deleted when the JVM exits,
  so a long-lived on-prem runtime collects one per redeploy until it restarts.
- **Rate limits**: GitLab's flags API is per-IP limited. With many replicas, raise
  `fetchTogglesInterval` (e.g. 60s) and keep `disableMetrics="true"`.
- **Egress**: if workers reach GitLab through a proxy, set `proxyHost`/`proxyPort`; for gateway
  tokens use `customHeaders`. Secrets belong in secure properties, never in XML.

## Build

```bash
export JAVA_HOME=<jdk-17-home>
./mvnw -B clean verify    # unit tests, a live-HTTP round trip against a stub, and an extension model check
```

The project has no MUnit suites, so the parent's EE-only MUnit plugin is unbound and
`./mvnw verify`, `install` and `deploy` need no MuleSoft EE repository access.

To use the connector from Studio or a local app build, install it into your local Maven
repository with `./mvnw -B install`. Without building, download
`mule-unleash-connector-1.0.0-mule-plugin.jar` (the latest release; 1.0.1 is not released yet) from the
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

### From GitLab CI

After you import this repository into GitLab, `.gitlab-ci.yml` builds it, runs the examples, and
publishes with the same script:

1. Add these under **Settings > CI/CD > Variables**: `ANYPOINT_ORG_ID`, `ANYPOINT_CLIENT_ID` and
   `ANYPOINT_CLIENT_SECRET` (masked and protected). Add `ANYPOINT_REGION` for `eu` or `gov`, and a
   File variable `MAVEN_SETTINGS_XML` if your runners need a Maven mirror or proxy.
2. To seed Exchange with the current version, run the manual `publish-exchange` job on the
   default branch's pipeline.
3. For later releases, bump `<version>` in `pom.xml` and push a matching tag, for example
   `v1.0.2`. The tag pipeline publishes it, and fails if the tag and the pom disagree.

Protected variables reach only protected branches and tags, so protect the `v*` tag pattern
under **Settings > Repository > Protected tags**.

## Tests

- `UnleashContextParamsTest` — operation-over-defaults merge semantics.
- `UnleashVariantResultTest` — SDK `Variant` → DataWeave POJO mapping (incl. `FakeUnleash`).
- `UnleashOperationsTest` — operations against `FakeUnleash` (flag on/off/unknown + defaults).
- `UnleashLiveClientTest` — real `DefaultUnleash` HTTP round trip (register/fetch/evaluate) against
  an in-JVM stub serving `features-v1` JSON, GitLab-style with empty API key; also startup with
  the server down, connectivity validation, and per-project backup file names.
- `ExtensionModelIT` — runs after `package` and checks the generated extension model: every
  operation declares every `UNLEASH:*` error, without which the runtime cannot raise it.
- `examples/run-local.sh` — the examples app on a real Mule CE runtime (see [Examples](#examples)).

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
  UnleashErrorProvider.java      errors the operations declare (@Throws)
src/test/java/...               unit and live-HTTP tests, no Mule runtime needed
examples/                       runnable app, fake Unleash server, run-local.sh
scripts/publish-to-exchange.sh  Anypoint Exchange publisher
.gitlab-ci.yml                  GitLab build, examples and Exchange publish
```

## Roadmap

- Verify against your on-prem GitLab version (flag evaluation is standard Unleash; watch the
  per-IP rate limit with many workers).
- MUnit coverage (needs MuleSoft EE repository access for the MUnit plugin).
- Optional: custom Unleash strategies via `@Parameter`-supplied class names.

## License

MIT. See [LICENSE](LICENSE).
