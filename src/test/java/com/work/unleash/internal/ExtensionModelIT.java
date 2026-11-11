package com.work.unleash.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the extension model that {@code mule-extensions-maven-plugin} generates at package time.
 * The runtime refuses to raise an error type an operation does not declare, so a flow's
 * {@code UNLEASH:EVALUATION} handler only works if every operation lists it.
 */
class ExtensionModelIT {

  @Test
  void everyOperationDeclaresEveryConnectorError() throws IOException {
    JsonObject model = JsonParser.parseString(
        Files.readString(Path.of("target", "temporal-extension-model.json"))).getAsJsonObject();
    Set<String> connectorErrors = Arrays.stream(UnleashErrorType.values())
        .map(type -> "UNLEASH:" + type.name())
        .collect(Collectors.toSet());

    JsonArray operations = model.getAsJsonArray("configurations").get(0).getAsJsonObject()
        .getAsJsonArray("operations");
    assertThat(operations).hasSize(3);
    for (JsonElement operation : operations) {
      Set<String> declared = StreamSupport
          .stream(operation.getAsJsonObject().getAsJsonArray("errors").spliterator(), false)
          .map(JsonElement::getAsString)
          .collect(Collectors.toSet());
      assertThat(declared)
          .as("errors declared by %s", operation.getAsJsonObject().get("name").getAsString())
          .containsAll(connectorErrors);
    }
  }
}
