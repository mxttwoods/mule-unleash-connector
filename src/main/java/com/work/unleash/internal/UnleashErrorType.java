package com.work.unleash.internal;

import org.mule.sdk.api.error.ErrorTypeDefinition;

import java.util.Optional;

/**
 * Errors raised by the Unleash connector.
 *
 * <p>Flag evaluation itself rarely throws: the Unleash SDK is designed for local evaluation and
 * returns the caller-supplied default when the server is unreachable or the flag is unknown. This
 * type covers unexpected evaluation failures (bad parameters, SDK errors). Connection setup
 * problems surface as Mule connectivity errors from the connection provider instead.
 */
public enum UnleashErrorType implements ErrorTypeDefinition<UnleashErrorType> {

  /** Flag or variant evaluation failed unexpectedly. */
  EVALUATION;

  @Override
  public Optional<ErrorTypeDefinition<? extends Enum<?>>> getParent() {
    return Optional.empty();
  }
}
