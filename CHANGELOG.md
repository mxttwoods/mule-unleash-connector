# Changelog

## 1.0.1 (unreleased)

- Fix: operations now declare `UNLEASH:EVALUATION`. In 1.0.0 the runtime refused to raise it, so
  an `on-error` handler for `UNLEASH:EVALUATION` never matched an evaluation failure.
- Add `examples/`: a runnable app covering every operation and two Salesforce migrations (a new
  field, and a picklist moved to a checkbox with versioned endpoints and a Bulk API job), a fake
  Unleash server, and `run-local.sh`, which checks the app on Mule CE and runs in CI.
- Document migration patterns: flags versus versioned endpoints, choosing a strategy, and
  staging a Salesforce change around a maintenance window.
- Add `.gitlab-ci.yml` to build, run the examples, and publish to Anypoint Exchange from GitLab.
- Add `ExtensionModelIT`, which checks the generated extension model on `./mvnw verify`.

## 1.0.0

- First release: `is-enabled`, `get-variant` and `get-variant-payload` against Unleash or
  GitLab feature flags.
