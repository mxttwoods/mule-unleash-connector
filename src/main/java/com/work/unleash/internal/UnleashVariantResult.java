package com.work.unleash.internal;

import io.getunleash.variant.Payload;
import io.getunleash.variant.Variant;

import java.io.Serializable;
import java.util.Objects;

/**
 * DataWeave-friendly view of an Unleash {@link Variant}.
 *
 * <p>{@code enabled} mirrors {@link Variant#isEnabled()} (this client instance selected the
 * variant). {@code featureEnabled} mirrors {@link Variant#isFeatureEnabled()} (the toggle itself
 * is on for this context). Payload is split into {@code payloadType}/{@code payloadValue}, both
 * null when the variant carries no payload.
 */
public class UnleashVariantResult implements Serializable {

  private static final long serialVersionUID = 1L;

  private String name;
  private boolean enabled;
  private boolean featureEnabled;
  private String payloadType;
  private String payloadValue;

  public UnleashVariantResult() {
  }

  public static UnleashVariantResult from(Variant variant) {
    Objects.requireNonNull(variant, "variant");
    UnleashVariantResult result = new UnleashVariantResult();
    result.setName(variant.getName());
    result.setEnabled(variant.isEnabled());
    result.setFeatureEnabled(variant.isFeatureEnabled());
    Payload payload = variant.getPayload().orElse(null);
    if (payload != null) {
      result.setPayloadType(payload.getType());
      result.setPayloadValue(payload.getValue());
    }
    return result;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isFeatureEnabled() {
    return featureEnabled;
  }

  public void setFeatureEnabled(boolean featureEnabled) {
    this.featureEnabled = featureEnabled;
  }

  public String getPayloadType() {
    return payloadType;
  }

  public void setPayloadType(String payloadType) {
    this.payloadType = payloadType;
  }

  public String getPayloadValue() {
    return payloadValue;
  }

  public void setPayloadValue(String payloadValue) {
    this.payloadValue = payloadValue;
  }
}
