package com.work.unleash.internal;

import io.getunleash.FakeUnleash;
import io.getunleash.Unleash;
import io.getunleash.UnleashContext;
import io.getunleash.variant.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnleashOperationsTest {

  private UnleashOperations operations;
  private FakeUnleash fake;
  private UnleashConfiguration config;
  private UnleashConnection connection;

  @BeforeEach
  void setUp() {
    operations = new UnleashOperations();
    fake = new FakeUnleash();
    fake.enable("new-checkout");
    fake.disable("old-checkout");
    // FakeUnleash only returns the stored variant for enabled flags.
    fake.enable("checkout-copy");
    fake.setVariant("checkout-copy", new Variant("treatment", null, true));
    config = new UnleashConfiguration();
    connection = new UnleashConnection(fake);
  }

  @Test
  void isEnabledReturnsTrueForEnabledFlag() {
    assertThat(operations.isEnabled(config, connection, "new-checkout", false, null)).isTrue();
  }

  @Test
  void isEnabledReturnsFalseForDisabledFlagEvenWhenDefaultIsTrue() {
    assertThat(operations.isEnabled(config, connection, "old-checkout", true, null)).isFalse();
  }

  @Test
  void isEnabledFallsBackToDefaultForUnknownFlag() {
    assertThat(operations.isEnabled(config, connection, "missing-flag", false, null)).isFalse();
    assertThat(operations.isEnabled(config, connection, "missing-flag", true, null)).isTrue();
  }

  @Test
  void isEnabledAcceptsContextBuiltFromFlowVars() {
    UnleashContextParams context = new UnleashContextParams();
    context.setUserId("user-123");
    context.setSessionId("sess-abc");

    assertThat(operations.isEnabled(config, connection, "new-checkout", false, context)).isTrue();
  }

  @Test
  void getVariantReturnsMappedVariant() {
    UnleashVariantResult result = operations.getVariant(config, connection, "checkout-copy", null);

    assertThat(result.getName()).isEqualTo("treatment");
    assertThat(result.isEnabled()).isTrue();
  }

  @Test
  void getVariantPayloadIsNullWhenVariantHasNoPayload() {
    assertThat(operations.getVariantPayload(config, connection, "checkout-copy", null)).isNull();
  }

  @Test
  void emptyContextGroupBehavesLikeAbsentGroup() {
    assertThat(operations.isEnabled(config, connection, "new-checkout", false,
        new UnleashContextParams())).isTrue();
  }

  @Test
  void configDefaultContextReachesSdkEvaluation() {
    Unleash unleash = mock(Unleash.class);
    ArgumentCaptor<UnleashContext> context = ArgumentCaptor.forClass(UnleashContext.class);
    when(unleash.isEnabled(eq("new-checkout"), context.capture(), eq(false))).thenReturn(true);

    UnleashContextParams defaults = new UnleashContextParams();
    defaults.setEnvironment("production");
    config.setDefaultContext(defaults);

    assertThat(operations.isEnabled(config, new UnleashConnection(unleash),
        "new-checkout", false, null)).isTrue();
    assertThat(context.getValue().getEnvironment()).contains("production");
  }
}
