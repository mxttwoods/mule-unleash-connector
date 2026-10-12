package com.work.unleash.internal;

import io.getunleash.FakeUnleash;
import io.getunleash.variant.Payload;
import io.getunleash.variant.Variant;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnleashVariantResultTest {

  @Test
  void documentsThreeArgConstructorPayloadSlot() {
    // The 3-arg constructor always attaches a "string" payload slot, even for a null value.
    // Real SDK "no payload" is Optional.empty() (see below), not this.
    Variant variant = new Variant("control", null, true);

    UnleashVariantResult result = UnleashVariantResult.from(variant);

    assertThat(result.getName()).isEqualTo("control");
    assertThat(result.isEnabled()).isTrue();
    assertThat(result.getPayloadType()).isEqualTo("string");
    assertThat(result.getPayloadValue()).isNull();
  }

  @Test
  void mapsVariantWithPayload() {
    Variant variant = mock(Variant.class);
    when(variant.getName()).thenReturn("treatment");
    when(variant.isEnabled()).thenReturn(true);
    when(variant.isFeatureEnabled()).thenReturn(true);
    when(variant.getPayload()).thenReturn(Optional.of(new Payload("json", "{\"color\":\"blue\"}")));

    UnleashVariantResult result = UnleashVariantResult.from(variant);

    assertThat(result.getName()).isEqualTo("treatment");
    assertThat(result.isEnabled()).isTrue();
    assertThat(result.isFeatureEnabled()).isTrue();
    assertThat(result.getPayloadType()).isEqualTo("json");
    assertThat(result.getPayloadValue()).isEqualTo("{\"color\":\"blue\"}");
  }

  @Test
  void mapsVariantWithEmptyPayload() {
    Variant variant = mock(Variant.class);
    when(variant.getName()).thenReturn("baseline");
    when(variant.isEnabled()).thenReturn(true);
    when(variant.isFeatureEnabled()).thenReturn(true);
    when(variant.getPayload()).thenReturn(Optional.empty());

    UnleashVariantResult result = UnleashVariantResult.from(variant);

    assertThat(result.getName()).isEqualTo("baseline");
    assertThat(result.isEnabled()).isTrue();
    assertThat(result.getPayloadType()).isNull();
    assertThat(result.getPayloadValue()).isNull();
  }

  @Test
  void disabledVariantSurvivesMapping() {
    // FakeUnleash returns a disabled "disabled" variant for unknown flags.
    FakeUnleash fake = new FakeUnleash();
    Variant variant = fake.getVariant("unknown-flag");

    UnleashVariantResult result = UnleashVariantResult.from(variant);

    assertThat(result.isEnabled()).isFalse();
  }
}
