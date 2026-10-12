package com.work.unleash.internal;

import io.getunleash.DefaultUnleash;
import io.getunleash.util.UnleashConfig;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.sdk.api.annotation.param.Optional;
import org.mule.sdk.api.annotation.param.Parameter;
import org.mule.sdk.api.annotation.param.display.DisplayName;
import org.mule.sdk.api.annotation.param.display.Example;
import org.mule.sdk.api.annotation.param.display.Placement;
import org.mule.sdk.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.semantics.security.Password;
import org.mule.sdk.api.connectivity.CachedConnectionProvider;
import org.mule.sdk.api.connectivity.ConnectionValidationResult;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Creates and caches the {@link UnleashConnection} (one SDK client per configuration).
 *
 * <p>For GitLab, use the values from <b>Deploy &gt; Feature flags &gt; Configure</b>:
 * <ul>
 *   <li>{@code unleashApiUrl} = API URL, e.g.
 *       {@code https://gitlab.example.com/api/v4/feature_flags/unleash/42}</li>
 *   <li>{@code instanceId} = Instance ID (leave {@code apiKey} empty)</li>
 *   <li>{@code appName} = Application name, i.e. the environment such as {@code production}</li>
 * </ul>
 * For Unleash OSS/Enterprise, {@code unleashApiUrl} is the client API URL and {@code apiKey} is a
 * client API token.
 */
public class UnleashConnectionProvider implements CachedConnectionProvider<UnleashConnection> {

  @Parameter
  @DisplayName("Unleash API URL")
  @Summary("Unleash client API URL, or a GitLab project's feature flags URL.")
  @Example("https://gitlab.example.com/api/v4/feature_flags/unleash/42")
  @Placement(order = 1)
  private String unleashApiUrl;

  @Parameter
  @Password
  @DisplayName("Instance ID")
  @Summary("GitLab Instance ID, or the Unleash instance id. Sent as UNLEASH-INSTANCEID.")
  @Placement(order = 2)
  private String instanceId;

  @Parameter
  @DisplayName("App name")
  @Summary("Application name. For GitLab this is the environment the app runs in (e.g. production).")
  @Example("production")
  @Placement(order = 3)
  private String appName;

  @Parameter
  @Optional(defaultValue = "")
  @Password
  @DisplayName("API key")
  @Summary("Unleash client API token. Leave empty for GitLab, which authenticates via Instance ID.")
  @Placement(order = 4)
  private String apiKey;

  @Parameter
  @Optional(defaultValue = "15")
  @DisplayName("Poll interval (seconds)")
  @Summary("How often the SDK refreshes flags. 0 fetches once at startup. Raise this (e.g. 60) when "
      + "many workers share a GitLab instance to stay under its rate limit.")
  private long fetchTogglesInterval;

  @Parameter
  @Optional(defaultValue = "60")
  @DisplayName("Metrics interval (seconds)")
  @Summary("How often usage metrics are reported. Ignored when metrics are disabled.")
  private long sendMetricsInterval;

  @Parameter
  @Optional(defaultValue = "true")
  @DisplayName("Disable metrics")
  @Summary("Recommended true for GitLab to avoid extra API traffic against its per-IP rate limit.")
  private boolean disableMetrics;

  @Parameter
  @Optional(defaultValue = "true")
  @DisplayName("Synchronous initial fetch")
  @Summary("Block connection startup until the first flag fetch completes, so the first event never "
      + "evaluates against an empty cache. Startup still succeeds if the server is down.")
  private boolean synchronousFetch;

  @Parameter
  @Optional
  @DisplayName("Backup file")
  @Summary("Where the SDK caches flags for offline evaluation. Defaults to the JVM temp dir. "
      + "On CloudHub 2.0 the filesystem is ephemeral, so restarts refetch from the server.")
  private String backupFile;

  @Parameter
  @Optional
  @DisplayName("Project name")
  @Summary("Only fetch toggles from this Unleash project (Unleash server only).")
  private String projectName;

  @Parameter
  @Optional
  @DisplayName("Flag name prefix")
  @Summary("Only fetch toggles whose name starts with this prefix (Unleash server only).")
  private String namePrefix;

  @Parameter
  @Optional(defaultValue = "10")
  @DisplayName("Connect timeout (seconds)")
  @Summary("TCP connect timeout for flag and metrics requests.")
  private long connectTimeoutSeconds;

  @Parameter
  @Optional(defaultValue = "10")
  @DisplayName("Read timeout (seconds)")
  @Summary("HTTP read timeout for flag and metrics requests.")
  private long readTimeoutSeconds;

  @Parameter
  @Optional
  @DisplayName("Proxy host")
  @Summary("Optional corporate egress proxy between the worker and Unleash/GitLab.")
  private String proxyHost;

  @Parameter
  @Optional(defaultValue = "8080")
  @DisplayName("Proxy port")
  private int proxyPort;

  @Parameter
  @Optional
  @DisplayName("Custom headers")
  @Summary("Extra HTTP headers sent with every SDK request (e.g. corporate gateway tokens).")
  private Map<String, String> customHeaders;

  @Parameter
  @Optional(defaultValue = "false")
  @DisplayName("Test connectivity")
  @Summary("When true, connection validation performs a live GET on the flags endpoint with the "
      + "configured timeouts and credentials, so a bad URL or credentials fail fast instead of "
      + "silently evaluating to defaults. Leave false for pure local evaluation.")
  private boolean testConnectivity;

  @Override
  public UnleashConnection connect() throws ConnectionException {
    try {
      UnleashConfig.Builder builder = UnleashConfig.builder()
          .unleashAPI(unleashApiUrl)
          .instanceId(instanceId)
          .appName(appName)
          .fetchTogglesInterval(fetchTogglesInterval)
          .sendMetricsInterval(sendMetricsInterval)
          .synchronousFetchOnInitialisation(synchronousFetch)
          .fetchTogglesConnectTimeoutSeconds(connectTimeoutSeconds)
          .fetchTogglesReadTimeoutSeconds(readTimeoutSeconds)
          .sendMetricsConnectTimeoutSeconds(connectTimeoutSeconds)
          .sendMetricsReadTimeoutSeconds(readTimeoutSeconds);
      if (apiKey != null && !apiKey.isBlank()) {
        builder.apiKey(apiKey);
      }
      if (disableMetrics) {
        builder.disableMetrics();
      }
      if (backupFile != null && !backupFile.isBlank()) {
        builder.backupFile(backupFile);
      }
      if (projectName != null && !projectName.isBlank()) {
        builder.projectName(projectName);
      }
      if (namePrefix != null && !namePrefix.isBlank()) {
        builder.namePrefix(namePrefix);
      }
      if (proxyHost != null && !proxyHost.isBlank()) {
        builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
      }
      if (customHeaders != null) {
        customHeaders.forEach(builder::customHttpHeader);
      }
      return new UnleashConnection(new DefaultUnleash(builder.build()));
    } catch (Exception e) {
      throw new ConnectionException("Failed to create Unleash client for " + unleashApiUrl, e);
    }
  }

  @Override
  public void disconnect(UnleashConnection connection) {
    connection.disconnect();
  }

  /**
   * Without the connectivity test (default), validation always succeeds: evaluation is local, so
   * a stale cache still evaluates and connectivity problems surface as operation defaults at
   * runtime, not as validation failures. With the test enabled, a live GET on the flags endpoint
   * must return 2xx or validation fails fast.
   */
  @Override
  public ConnectionValidationResult validate(UnleashConnection connection) {
    if (!testConnectivity) {
      return ConnectionValidationResult.success();
    }
    return checkFlagsEndpoint();
  }

  private ConnectionValidationResult checkFlagsEndpoint() {
    HttpURLConnection http = null;
    try {
      String base = unleashApiUrl != null ? unleashApiUrl.replaceAll("/+$", "") : "";
      URL url = new URL(base + "/client/features");
      http = (HttpURLConnection) url.openConnection();
      http.setRequestMethod("GET");
      http.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(connectTimeoutSeconds));
      http.setReadTimeout((int) TimeUnit.SECONDS.toMillis(readTimeoutSeconds));
      http.setRequestProperty("UNLEASH-APPNAME", appName);
      http.setRequestProperty("UNLEASH-INSTANCEID", instanceId);
      if (apiKey != null && !apiKey.isBlank()) {
        http.setRequestProperty("Authorization", apiKey);
      }
      if (customHeaders != null) {
        for (Map.Entry<String, String> header : customHeaders.entrySet()) {
          http.setRequestProperty(header.getKey(), header.getValue());
        }
      }
      int status = http.getResponseCode();
      if (status >= 200 && status < 300) {
        return ConnectionValidationResult.success();
      }
      return ConnectionValidationResult.failure(
          "Unleash flags endpoint returned HTTP " + status + " for " + url,
          new IOException("Unexpected HTTP status " + status));
    } catch (Exception e) {
      return ConnectionValidationResult.failure(
          "Unleash flags endpoint unreachable: " + e.getMessage(), e);
    } finally {
      if (http != null) {
        http.disconnect();
      }
    }
  }

  // Getters/setters (setters used by tests; the runtime injects fields directly).

  public String getUnleashApiUrl() {
    return unleashApiUrl;
  }

  public void setUnleashApiUrl(String unleashApiUrl) {
    this.unleashApiUrl = unleashApiUrl;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public long getFetchTogglesInterval() {
    return fetchTogglesInterval;
  }

  public void setFetchTogglesInterval(long fetchTogglesInterval) {
    this.fetchTogglesInterval = fetchTogglesInterval;
  }

  public long getSendMetricsInterval() {
    return sendMetricsInterval;
  }

  public void setSendMetricsInterval(long sendMetricsInterval) {
    this.sendMetricsInterval = sendMetricsInterval;
  }

  public boolean isDisableMetrics() {
    return disableMetrics;
  }

  public void setDisableMetrics(boolean disableMetrics) {
    this.disableMetrics = disableMetrics;
  }

  public boolean isSynchronousFetch() {
    return synchronousFetch;
  }

  public void setSynchronousFetch(boolean synchronousFetch) {
    this.synchronousFetch = synchronousFetch;
  }

  public String getBackupFile() {
    return backupFile;
  }

  public void setBackupFile(String backupFile) {
    this.backupFile = backupFile;
  }

  public String getProjectName() {
    return projectName;
  }

  public void setProjectName(String projectName) {
    this.projectName = projectName;
  }

  public String getNamePrefix() {
    return namePrefix;
  }

  public void setNamePrefix(String namePrefix) {
    this.namePrefix = namePrefix;
  }

  public long getConnectTimeoutSeconds() {
    return connectTimeoutSeconds;
  }

  public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
    this.connectTimeoutSeconds = connectTimeoutSeconds;
  }

  public long getReadTimeoutSeconds() {
    return readTimeoutSeconds;
  }

  public void setReadTimeoutSeconds(long readTimeoutSeconds) {
    this.readTimeoutSeconds = readTimeoutSeconds;
  }

  public String getProxyHost() {
    return proxyHost;
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHost = proxyHost;
  }

  public int getProxyPort() {
    return proxyPort;
  }

  public void setProxyPort(int proxyPort) {
    this.proxyPort = proxyPort;
  }

  public Map<String, String> getCustomHeaders() {
    return customHeaders;
  }

  public void setCustomHeaders(Map<String, String> customHeaders) {
    this.customHeaders = customHeaders;
  }

  public boolean isTestConnectivity() {
    return testConnectivity;
  }

  public void setTestConnectivity(boolean testConnectivity) {
    this.testConnectivity = testConnectivity;
  }
}
