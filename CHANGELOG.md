# Changelog

## 1.0.1 (unreleased)

- Fix: operations now declare `UNLEASH:EVALUATION`. In 1.0.0 the runtime refused to raise it, so
  an `on-error` handler for `UNLEASH:EVALUATION` never matched an evaluation failure.
- Add `examples/`: a runnable app covering every operation and a Salesforce field rollout, a fake
  Unleash server, and `run-local.sh`, which checks the app on Mule CE and runs in CI.
- Add `.gitlab-ci.yml` to build, run the examples, and publish to Anypoint Exchange from GitLab.
- Add `ExtensionModelIT`, which checks the generated extension model on `./mvnw verify`.

## 1.0.0

- First release: `is-enabled`, `get-variant` and `get-variant-payload` against Unleash or
  GitLab feature flags.
