package com.work.unleash.internal;

import com.work.unleash.api.UnleashVariantResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual live check against a real Unleash / GitLab endpoint. No Docker, no Mule runtime.
 *
 * <p>Reads config from env so secrets never land in git:
 * <ul>
 *   <li>UNLEASH_API_URL (required, e.g. https://gitlab.com/api/v4/feature_flags/unleash/42)</li>
 *   <li>UNLEASH_INSTANCE_ID (required for GitLab, empty API key)</li>
 *   <li>UNLEASH_APP_NAME (default: production — for GitLab this is the environment)</li>
 *   <li>UNLEASH_API_KEY (default: empty — set for Unleash OSS/Enterprise client token)</li>
 *   <li>UNLEASH_FLAG (default: new-checkout)</li>
 *   <li>UNLEASH_USER_ID (default: manual-test-user)</li>
 * </ul>
 * Run with e.g.:
 * <pre>
 * UNLEASH_API_URL=... UNLEASH_INSTANCE_ID=... ./mvnw -B -Dtest=UnleashManualLiveTest test
 * </pre>
 * Skips (does not fail) when UNLEASH_API_URL is unset.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class UnleashManualLiveTest {

  @TempDir
  private Path tempDir;

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value != null && !value.isBlank() ? value : fallback;
  }

  @Test
  void liveEndpointEvaluates() throws Exception {
    String apiUrl = System.getenv("UNLEASH_API_URL");
    assumeTrue(apiUrl != null && !apiUrl.isBlank(),
        "Skipping: set UNLEASH_API_URL to run the manual live test");

    String instanceId = env("UNLEASH_INSTANCE_ID", "");
    String appName = env("UNLEASH_APP_NAME", "production");
    String apiKey = env("UNLEASH_API_KEY", "");
    String flag = env("UNLEASH_FLAG", "new-checkout");
    String userId = env("UNLEASH_USER_ID", "manual-test-user");

    UnleashConnectionProvider provider = new UnleashConnectionProvider();
    provider.setUnleashApiUrl(apiUrl);
    provider.setInstanceId(instanceId);
    provider.setAppName(appName);
    provider.setApiKey(apiKey);
    provider.setFetchTogglesInterval(15);
    provider.setSendMetricsInterval(60);
    provider.setDisableMetrics(true);
    provider.setSynchronousFetch(true);
    provider.setBackupFile(tempDir.resolve("manual-repo.json").toString());
    provider.setConnectTimeoutSeconds(10);
    provider.setReadTimeoutSeconds(10);
    provider.setTestConnectivity(true);

    UnleashConnection connection = provider.connect();
    try {
      System.out.println("VALIDATE: " + provider.validate(connection).isValid());

      UnleashConfiguration config = new UnleashConfiguration();
      UnleashOperations operations = new UnleashOperations();
      UnleashContextParams context = new UnleashContextParams();
      context.setUserId(userId);

      boolean enabled = operations.isEnabled(config, connection, flag, false, context);
      System.out.println("FLAG " + flag + " enabled=" + enabled + " user=" + userId);

      UnleashVariantResult variant = operations.getVariant(config, connection, flag, context);
      System.out.println("VARIANT name=" + variant.getName()
          + " enabled=" + variant.isEnabled()
          + " featureEnabled=" + variant.isFeatureEnabled()
          + " payloadType=" + variant.getPayloadType()
          + " payloadValue=" + variant.getPayloadValue());
    } finally {
      connection.disconnect();
    }
  }
}
