package com.work.unleash.internal;

import org.mule.sdk.api.annotation.Operations;
import org.mule.sdk.api.annotation.connectivity.ConnectionProviders;
import org.mule.sdk.api.annotation.param.Optional;
import org.mule.sdk.api.annotation.param.ParameterGroup;
import org.mule.sdk.api.annotation.param.display.DisplayName;
import org.mule.sdk.api.annotation.param.display.Summary;

/**
 * Connector configuration. Holds the operations and the connection provider, plus an optional
 * default evaluation context applied to every operation unless overridden per call.
 */
@Operations(UnleashOperations.class)
@ConnectionProviders(UnleashConnectionProvider.class)
public class UnleashConfiguration {

  @ParameterGroup(name = "Default Context")
  @DisplayName("Default context")
  @Summary("Fallback context values (e.g. environment). Operation-level fields win field by field.")
  private UnleashContextParams defaultContext;

  public UnleashContextParams getDefaultContext() {
    return defaultContext;
  }

  public void setDefaultContext(UnleashContextParams defaultContext) {
    this.defaultContext = defaultContext;
  }
}
