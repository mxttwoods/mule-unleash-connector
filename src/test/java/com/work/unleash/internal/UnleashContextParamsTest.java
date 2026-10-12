package com.work.unleash.internal;

import io.getunleash.UnleashContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnleashContextParamsTest {

  @Test
  void nullInputsYieldEmptyContext() {
    UnleashContext ctx = UnleashContextParams.effectiveContext(null, null);
    assertThat(ctx.getUserId()).isEmpty();
    assertThat(ctx.getSessionId()).isEmpty();
    assertThat(ctx.getRemoteAddress()).isEmpty();
    assertThat(ctx.getEnvironment()).isEmpty();
    assertThat(ctx.getProperties()).isEmpty();
  }

  @Test
  void operationFieldsWinOverDefaultsFieldByField() {
    UnleashContextParams defaults = new UnleashContextParams();
    defaults.setUserId("default-user");
    defaults.setSessionId("default-session");
    defaults.setEnvironment("production");
    defaults.setProperties(Map.of("tenant", "default-tenant", "region", "eu"));

    UnleashContextParams operation = new UnleashContextParams();
    operation.setSessionId("op-session");
    operation.setProperties(Map.of("tenant", "op-tenant"));

    UnleashContext ctx = UnleashContextParams.effectiveContext(operation, defaults);

    assertThat(ctx.getUserId()).contains("default-user");
    assertThat(ctx.getSessionId()).contains("op-session");
    assertThat(ctx.getEnvironment()).contains("production");
    // merged properties, operation wins per key
    assertThat(ctx.getProperties()).containsEntry("tenant", "op-tenant");
    assertThat(ctx.getProperties()).containsEntry("region", "eu");
  }

  @Test
  void blankOperationFieldsFallBackToDefaults() {
    UnleashContextParams defaults = new UnleashContextParams();
    defaults.setUserId("default-user");

    UnleashContextParams operation = new UnleashContextParams();
    operation.setUserId("   ");

    UnleashContext ctx = UnleashContextParams.effectiveContext(operation, defaults);

    assertThat(ctx.getUserId()).contains("default-user");
  }

  @Test
  void operationOnlyContextIsUsedVerbatim() {    UnleashContextParams operation = new UnleashContextParams();
    operation.setUserId("u1");
    operation.setRemoteAddress("10.0.0.1");

    UnleashContext ctx = UnleashContextParams.effectiveContext(operation, null);

    assertThat(ctx.getUserId()).contains("u1");
    assertThat(ctx.getRemoteAddress()).contains("10.0.0.1");
    assertThat(ctx.getSessionId()).isEmpty();
  }
}
