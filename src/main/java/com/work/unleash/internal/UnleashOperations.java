package com.work.unleash.internal;

import static org.mule.sdk.api.annotation.param.MediaType.ANY;

import io.getunleash.UnleashContext;
import io.getunleash.variant.Payload;
import com.work.unleash.api.UnleashVariantResult;
import org.mule.sdk.api.annotation.param.Config;
import org.mule.sdk.api.annotation.param.Connection;
import org.mule.sdk.api.annotation.param.MediaType;
import org.mule.sdk.api.annotation.param.Optional;
import org.mule.sdk.api.annotation.param.ParameterGroup;
import org.mule.sdk.api.annotation.param.display.DisplayName;
import org.mule.sdk.api.annotation.param.display.Summary;
import org.mule.sdk.api.exception.ModuleException;

/**
 * Flag evaluation operations. Results are meant to be held in flow variables via {@code target},
 * e.g. {@code target="newCheckoutEnabled"}, and consumed with DataWeave or a Choice router.
 */
public class UnleashOperations {

  @MediaType(value = ANY, strict = false)
  @DisplayName("Is enabled")
  @Summary("Evaluates a feature flag for the effective context. Returns the default value when the "
      + "flag is unknown or Unleash is unreachable (local evaluation with cached flags).")
  public boolean isEnabled(@Config UnleashConfiguration config,
      @Connection UnleashConnection connection,
      @DisplayName("Flag name") @Summary("Toggle name exactly as defined in Unleash or GitLab.") String toggleName,
      @DisplayName("Default value") @Optional(defaultValue = "false")
      @Summary("Fallback when the flag is unknown or the server cannot be reached.") boolean defaultValue,
      @ParameterGroup(name = "Context") UnleashContextParams context) {
    try {
      UnleashContext effective = UnleashContextParams.effectiveContext(context, config.getDefaultContext());
      return connection.getUnleash().isEnabled(toggleName, effective, defaultValue);
    } catch (Exception e) {
      throw new ModuleException("Error evaluating feature flag '" + toggleName + "'",
          UnleashErrorType.EVALUATION, e);
    }
  }

  @MediaType(value = ANY, strict = false)
  @DisplayName("Get variant")
  @Summary("Returns the selected variant (name, enabled state and payload) for a flag and context. "
      + "A disabled variant means the flag is off for this context or has no matching variant.")
  public UnleashVariantResult getVariant(@Config UnleashConfiguration config,
      @Connection UnleashConnection connection,
      @DisplayName("Flag name") @Summary("Toggle name exactly as defined in Unleash or GitLab.") String toggleName,
      @ParameterGroup(name = "Context") UnleashContextParams context) {
    try {
      UnleashContext effective = UnleashContextParams.effectiveContext(context, config.getDefaultContext());
      return UnleashVariantResult.from(connection.getUnleash().getVariant(toggleName, effective));
    } catch (Exception e) {
      throw new ModuleException("Error getting variant for feature flag '" + toggleName + "'",
          UnleashErrorType.EVALUATION, e);
    }
  }

  @MediaType(value = ANY, strict = false)
  @DisplayName("Get variant payload")
  @Summary("Returns only the variant payload value (e.g. a JSON string or text) for a flag and "
      + "context, or null when the selected variant carries no payload or the flag is off or unknown.")
  public String getVariantPayload(@Config UnleashConfiguration config,
      @Connection UnleashConnection connection,
      @DisplayName("Flag name") @Summary("Toggle name exactly as defined in Unleash or GitLab.") String toggleName,
      @ParameterGroup(name = "Context") UnleashContextParams context) {
    try {
      UnleashContext effective = UnleashContextParams.effectiveContext(context, config.getDefaultContext());
      return connection.getUnleash()
          .getVariant(toggleName, effective)
          .getPayload()
          .map(Payload::getValue)
          .orElse(null);
    } catch (Exception e) {
      throw new ModuleException("Error getting variant payload for feature flag '" + toggleName + "'",
          UnleashErrorType.EVALUATION, e);
    }
  }
}
