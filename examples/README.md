# Examples

A Mule app that uses every connector operation, and a fake Unleash server to run it against. It
needs no GitLab, Salesforce, Docker or EE license: everything runs on Mule CE.

## Run it

```bash
export JAVA_HOME=<jdk-17-or-21-home>
examples/run-local.sh
```

The script installs the connector, packages the app, downloads Mule CE 4.9.0 once to
`~/.cache/mule-unleash-examples` (or uses `MULE_HOME`), deploys, and checks every endpoint. It
takes about a minute after the first download. CI runs the same script.

It deploys with the flag server down first, to show the app still starts and serves defaults,
then starts the server and flips `salesforce-loyalty-tier` on and off to rehearse a maintenance
window.

Add `--keep-running` to try the endpoints yourself:

```bash
curl 'http://127.0.0.1:8081/flags/vip-flag?userId=vip-user'
curl http://127.0.0.1:8081/accounts/001xx0000001
curl -X POST 'http://127.0.0.1:4242/admin/flags/salesforce-loyalty-tier?enabled=true'
curl http://127.0.0.1:8081/accounts/001xx0000001      # loyaltyTier appears within 5 seconds
```

## What is in the app

| File | Endpoints | Shows |
|---|---|---|
| [`global.xml`](unleash-examples-app/src/main/mule/global.xml) | | One shared config, properties per target, an `UNLEASH:EVALUATION` handler |
| [`flags.xml`](unleash-examples-app/src/main/mule/flags.xml) | `GET /flags/{flag}`, `GET /variants/{flag}`, `GET /payloads/{flag}` | `is-enabled`, `get-variant` and `get-variant-payload`, with `?userId=` as context |
| [`salesforce-field-rollout.xml`](unleash-examples-app/src/main/mule/salesforce-field-rollout.xml) | `GET /accounts/{id}`, `POST /accounts` | Shipping an API field before the Salesforce field exists. See the runbook in the [main README](../README.md#releasing-an-api-field-ahead-of-a-salesforce-change). |

Salesforce calls are simulated with `set-payload`. A comment next to each one shows the
Salesforce connector operation it stands in for.

The fake server's flags are in [`fake-unleash.py`](fake-unleash.py). It serves any base path, so
the app points at it with a GitLab-style URL and no API key, the way it would point at GitLab.

## Point it at a real server

The app reads `config-${env}.properties`, `local` by default. Package it and deploy with:

| Target | Deploy properties |
|---|---|
| GitLab | `env=gitlab`, `unleash.api.url=<API URL>`, `unleash.instance.id=<Instance ID>` |
| Unleash OSS/Enterprise | `env=unleash`, `unleash.api.url=<client API URL>`, `unleash.api.key=<client token>` |

GitLab values come from **Deploy > Feature flags > Configure** in your project. Pass credentials
as deployment properties (Runtime Manager, or `-M-D` on a standalone runtime). In a production
app, keep them in secure properties.
