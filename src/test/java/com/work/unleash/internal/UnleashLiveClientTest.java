package com.work.unleash.internal;

import com.work.unleash.api.UnleashVariantResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the real Unleash SDK HTTP path (register, fetch, evaluate) against a stub
 * Unleash/GitLab-compatible server, exercising {@link UnleashConnectionProvider} and
 * {@link UnleashOperations} together — including the GitLab-style setup with an empty API key.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class UnleashLiveClientTest {

  private static final String FEATURES_JSON = """
      {
        "version": 1,
        "features": [
          {
            "name": "new-checkout",
            "description": "enabled for everyone",
            "enabled": true,
            "strategies": [{"name": "default"}]
          },
          {
            "name": "retired-flag",
            "description": "switched off",
            "enabled": false,
            "strategies": [{"name": "default"}]
          },
          {
            "name": "checkout-copy",
            "description": "single variant with payload",
            "enabled": true,
            "strategies": [{"name": "default"}],
            "variants": [
              {"name": "treatment", "weight": 1000,
               "payload": {"type": "string", "value": "blue"}}
            ]
          },
          {
            "name": "vip-flag",
            "description": "only for the vip user",
            "enabled": true,
            "strategies": [{"name": "userWithId", "parameters": {"userIds": "vip-user"}}]
          }
        ]
      }
      """;

  @TempDir
  private Path tempDir;

  private HttpServer server;
  private volatile String lastInstanceIdHeader;
  private volatile String lastAuthorizationHeader;
  private UnleashConnection connection;

  @BeforeEach
  void startStubServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/client/features", exchange -> {
      lastInstanceIdHeader = exchange.getRequestHeaders().getFirst("UNLEASH-INSTANCEID");
      lastAuthorizationHeader = exchange.getRequestHeaders().getFirst("Authorization");
      byte[] body = FEATURES_JSON.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.createContext("/api/client/register", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.createContext("/api/client/metrics", exchange -> {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopAll() {
    if (connection != null) {
      connection.disconnect();
      connection = null;
    }
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private UnleashConnectionProvider liveProvider() {
    UnleashConnectionProvider provider = new UnleashConnectionProvider();
    provider.setUnleashApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
    provider.setInstanceId("test-instance");
    provider.setAppName("test-app");
    provider.setApiKey(""); // GitLab style: no API key, auth via instance id
    provider.setFetchTogglesInterval(0); // fetch once, then stop polling
    provider.setSendMetricsInterval(60);
    provider.setDisableMetrics(true);
    provider.setSynchronousFetch(true);
    provider.setBackupFile(tempDir.resolve("repo.json").toString());
    provider.setConnectTimeoutSeconds(5);
    provider.setReadTimeoutSeconds(5);
    return provider;
  }

  private UnleashOperations operationsWithLiveConnection() throws Exception {
    connection = liveProvider().connect();
    return new UnleashOperations();
  }

  @Test
  void evaluatesFlagsFetchedOverHttp() throws Exception {
    UnleashOperations operations = operationsWithLiveConnection();
    UnleashConfiguration config = new UnleashConfiguration();

    UnleashContextParams context = new UnleashContextParams();
    context.setUserId("user-123");

    assertThat(operations.isEnabled(config, connection, "new-checkout", false, context)).isTrue();
    assertThat(operations.isEnabled(config, connection, "retired-flag", true, context)).isFalse();
    assertThat(operations.isEnabled(config, connection, "missing-flag", true, context)).isTrue();
    assertThat(operations.isEnabled(config, connection, "missing-flag", false, context)).isFalse();

    assertThat(lastInstanceIdHeader).isEqualTo("test-instance");
    assertThat(lastAuthorizationHeader).isNull();
  }

  @Test
  void constrainedFlagFollowsContextEndToEnd() throws Exception {
    UnleashOperations operations = operationsWithLiveConnection();
    UnleashConfiguration config = new UnleashConfiguration();

    UnleashContextParams vip = new UnleashContextParams();
    vip.setUserId("vip-user");
    UnleashContextParams other = new UnleashContextParams();
    other.setUserId("regular-user");

    assertThat(operations.isEnabled(config, connection, "vip-flag", false, vip)).isTrue();
    assertThat(operations.isEnabled(config, connection, "vip-flag", false, other)).isFalse();
  }

  @Test
  void validationSucceedsWithoutConnectivityCheck() throws Exception {
    // No server listens on this port and the check is off: pure local evaluation.
    UnleashConnectionProvider provider = liveProvider();
    provider.setUnleashApiUrl("http://127.0.0.1:1/api");
    provider.setSynchronousFetch(false);
    provider.setConnectTimeoutSeconds(2);
    provider.setReadTimeoutSeconds(2);
    connection = provider.connect();

    assertThat(provider.validate(connection).isValid()).isTrue();
  }

  @Test
  void synchronousFetchAgainstDownServerStillConnects() throws Exception {
    // The SDK throws on a failed synchronous fetch unless a startup handler swallows it; without
    // that, a GitLab outage at boot would fail the Mule app's deployment.
    UnleashConnectionProvider provider = liveProvider();
    provider.setUnleashApiUrl("http://127.0.0.1:1/api");
    provider.setSynchronousFetch(true);
    provider.setConnectTimeoutSeconds(2);
    provider.setReadTimeoutSeconds(2);
    connection = provider.connect();

    UnleashContextParams context = new UnleashContextParams();
    UnleashOperations operations = new UnleashOperations();
    UnleashConfiguration config = new UnleashConfiguration();
    assertThat(operations.isEnabled(config, connection, "new-checkout", true, context)).isTrue();
    assertThat(operations.isEnabled(config, connection, "new-checkout", false, context)).isFalse();
  }

  @Test
  void defaultBackupFileIsDistinctPerProjectWithSameAppName() {
    String projectA = UnleashConnectionProvider.defaultBackupFile(
        "https://gitlab.example.com/api/v4/feature_flags/unleash/1", "production");
    String projectB = UnleashConnectionProvider.defaultBackupFile(
        "https://gitlab.example.com/api/v4/feature_flags/unleash/2", "production");

    assertThat(projectA).isNotEqualTo(projectB);
    assertThat(projectA).isEqualTo(UnleashConnectionProvider.defaultBackupFile(
        "https://gitlab.example.com/api/v4/feature_flags/unleash/1", "production"));
  }

  @Test
  void connectivityCheckPassesAgainstLiveServer() throws Exception {
    UnleashConnectionProvider provider = liveProvider();
    provider.setTestConnectivity(true);
    connection = provider.connect();

    assertThat(provider.validate(connection).isValid()).isTrue();
    assertThat(lastInstanceIdHeader).isEqualTo("test-instance");
  }

  @Test
  void connectivityCheckFailsWhenServerDown() throws Exception {
    UnleashConnectionProvider provider = liveProvider();
    provider.setTestConnectivity(true);
    provider.setConnectTimeoutSeconds(2);
    provider.setReadTimeoutSeconds(2);
    connection = provider.connect();
    server.stop(0);
    server = null;

    assertThat(provider.validate(connection).isValid()).isFalse();
  }

  @Test
  void returnsVariantWithPayload() throws Exception {
    UnleashOperations operations = operationsWithLiveConnection();
    UnleashConfiguration config = new UnleashConfiguration();

    UnleashContextParams context = new UnleashContextParams();
    context.setUserId("user-123");

    UnleashVariantResult variant = operations.getVariant(config, connection, "checkout-copy", context);
    assertThat(variant.getName()).isEqualTo("treatment");
    assertThat(variant.isEnabled()).isTrue();
    assertThat(variant.isFeatureEnabled()).isTrue();
    assertThat(variant.getPayloadType()).isEqualTo("string");
    assertThat(variant.getPayloadValue()).isEqualTo("blue");

    assertThat(operations.getVariantPayload(config, connection, "checkout-copy", context))
        .isEqualTo("blue");
    assertThat(operations.getVariantPayload(config, connection, "new-checkout", context)).isNull();
  }
}
