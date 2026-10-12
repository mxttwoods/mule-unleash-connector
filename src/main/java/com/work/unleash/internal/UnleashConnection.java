package com.work.unleash.internal;

import io.getunleash.Unleash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A live Unleash SDK client held by the connection provider.
 *
 * <p>The SDK pools nothing per call: it background-polls the Unleash/GitLab API and evaluates flags
 * locally, so a single instance is shared by all flows using the same configuration and shut down
 * when the configuration stops.
 */
public class UnleashConnection {

  private static final Logger LOGGER = LoggerFactory.getLogger(UnleashConnection.class);

  private final Unleash unleash;

  public UnleashConnection(Unleash unleash) {
    if (unleash == null) {
      throw new IllegalArgumentException("unleash must not be null");
    }
    this.unleash = unleash;
  }

  public Unleash getUnleash() {
    return unleash;
  }

  public void disconnect() {
    try {
      unleash.shutdown();
    } catch (Exception e) {
      LOGGER.debug("Error shutting down Unleash client", e);
    }
  }
}
