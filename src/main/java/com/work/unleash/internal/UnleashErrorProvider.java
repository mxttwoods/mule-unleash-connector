package com.work.unleash.internal;

import org.mule.sdk.api.annotation.error.ErrorTypeProvider;
import org.mule.sdk.api.error.ErrorTypeDefinition;

import java.util.Set;

/**
 * Errors the operations may raise. The runtime rejects a {@code ModuleException} whose type an
 * operation does not declare, so without this an {@code UNLEASH:EVALUATION} handler never matches.
 */
public class UnleashErrorProvider implements ErrorTypeProvider {

  @Override
  public Set<ErrorTypeDefinition> getErrorTypes() {
    return Set.of(UnleashErrorType.EVALUATION);
  }
}
