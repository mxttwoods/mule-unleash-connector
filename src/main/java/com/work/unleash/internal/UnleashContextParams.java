package com.work.unleash.internal;

import io.getunleash.UnleashContext;
import org.mule.sdk.api.annotation.param.Optional;
import org.mule.sdk.api.annotation.param.Parameter;
import org.mule.sdk.api.annotation.param.display.DisplayName;
import org.mule.sdk.api.annotation.param.display.Example;
import org.mule.sdk.api.annotation.param.display.Summary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Unleash evaluation context, mappable from flow variables.
 *
 * <p>Strategies such as {@code userWithId} and gradual rollouts only work when the relevant context
 * fields are supplied, e.g. {@code userId="#[vars.userId]"}. Operation-level values override the
 * configuration-level {@code Default Context} group field by field.
 */
public class UnleashContextParams {

  @Parameter
  @Optional
  @DisplayName("User ID")
  @Summary("Stable user identifier, e.g. vars.userId. Required for user-targeted strategies.")
  private String userId;

  @Parameter
  @Optional
  @DisplayName("Session ID")
  @Summary("Session identifier, e.g. vars.sessionId. Used for session stickiness.")
  private String sessionId;

  @Parameter
  @Optional
  @DisplayName("Remote address")
  @Summary("Client IP address, e.g. attributes.remoteAddress.")
  private String remoteAddress;

  @Parameter
  @Optional
  @DisplayName("Environment")
  @Summary("Environment value used by constraints. Set explicitly; the SDK applies no default.")
  private String environment;

  @Parameter
  @Optional
  @Summary("Custom context properties used by constraints and custom stickiness.")
  @Example("#[{'tenant': vars.tenant}]")
  private Map<String, String> properties;

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getRemoteAddress() {
    return remoteAddress;
  }

  public void setRemoteAddress(String remoteAddress) {
    this.remoteAddress = remoteAddress;
  }

  public String getEnvironment() {
    return environment;
  }

  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public void setProperties(Map<String, String> properties) {
    this.properties = properties;
  }

  /**
   * Resolves the effective SDK context: every non-null operation field wins, otherwise the
   * configuration default applies. Custom properties are merged with operation entries winning.
   */
  public static UnleashContext effectiveContext(UnleashContextParams operation,
      UnleashContextParams defaults) {
    UnleashContext.Builder builder = UnleashContext.builder()
        .userId(pick(operation, defaults, UnleashContextParams::getUserId))
        .sessionId(pick(operation, defaults, UnleashContextParams::getSessionId))
        .remoteAddress(pick(operation, defaults, UnleashContextParams::getRemoteAddress))
        .environment(pick(operation, defaults, UnleashContextParams::getEnvironment));
    Map<String, String> merged = new LinkedHashMap<>();
    if (defaults != null && defaults.getProperties() != null) {
      merged.putAll(defaults.getProperties());
    }
    if (operation != null && operation.getProperties() != null) {
      merged.putAll(operation.getProperties());
    }
    merged.forEach(builder::addProperty);
    return builder.build();
  }

  private static String pick(UnleashContextParams operation, UnleashContextParams defaults,
      Function<UnleashContextParams, String> getter) {
    String value = operation != null ? getter.apply(operation) : null;
    if (value != null && !value.isBlank()) {
      return value;
    }
    String fallback = defaults != null ? getter.apply(defaults) : null;
    return fallback != null && !fallback.isBlank() ? fallback : null;
  }
}
