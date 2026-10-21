package com.example.astchunker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PrefixSumApiIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void defaultModeSerializesSentinelProofAndObservedBeforeLocationProgress() throws Exception {
    var body = request("PrefixSumSentinelSample.java", false);
    export(body, "line");
    var hint = prefixHint(body, "ZERO_SENTINEL");
    var metadata = hint.path("prefixSum");
    assertThat(metadata.path("queries").size()).isEqualTo(1);
    var query = metadata.path("queries").get(0);
    for (String field :
        List.of(
            "expressionAstNodeId",
            "leftDeclarationId",
            "rightDeclarationId",
            "leftExpressionAstNodeId",
            "rightExpressionAstNodeId")) {
      assertThat(query.path(field).asText()).isNotBlank();
    }

    var frames = frames(body);
    var pending =
        frames.stream().filter(f -> f.path("recurrenceStatus").asText().equals("PENDING")).toList();
    assertThat(pending).extracting(f -> f.path("currentIndex").asInt()).containsExactly(0, 1, 2, 3);
    for (int index = 0; index < pending.size(); index++) {
      var frame = pending.get(index);
      assertThat(frame.path("sourceIndex").asInt()).isEqualTo(index);
      assertThat(frame.path("previousPrefixIndex").asInt()).isEqualTo(index);
      assertThat(frame.path("targetIndex").asInt()).isEqualTo(index + 1);
      assertThat(frame.path("confirmedPrefixEndExclusive").asInt()).isEqualTo(index + 1);
      assertThat(frame.path("confirmedCells").size()).isEqualTo(index + 1);
      assertThat(frame.path("targetValue").asInt()).isZero();
      assertThat(frame.path("source").path("values")).isEqualTo(json.readTree("[2,-1,3,0]"));
      assertThat(frame.path("sourceArrayObjectId").isIntegralNumber()).isTrue();
      assertThat(frame.path("resultArrayObjectId").isIntegralNumber()).isTrue();
      assertThat(frame.path("sourceArrayObjectId")).isNotEqualTo(frame.path("resultArrayObjectId"));
    }
    assertThat(pending.get(0).path("result").path("values"))
        .isEqualTo(json.readTree("[0,0,0,0,0]"));
    assertThat(pending.get(3).path("sourceValue").asInt()).isZero();
    assertThat(pending.get(3).path("previousPrefixValue").asInt()).isEqualTo(4);
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.path("confirmedPrefixEndExclusive").asInt()).isEqualTo(5);
              assertThat(frame.path("confirmedCells").size()).isEqualTo(5);
              assertThat(frame.path("result").path("values").toString()).isEqualTo("[0,2,1,4,4]");
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              var value = frame.path("query");
              assertThat(value.path("status").asText()).isEqualTo("PENDING");
              assertThat(value.path("expressionAstNodeId"))
                  .isEqualTo(query.path("expressionAstNodeId"));
              assertThat(value.path("left").asInt()).isEqualTo(1);
              assertThat(value.path("right").asInt()).isEqualTo(3);
              assertThat(value.path("upperPrefixIndex").asInt()).isEqualTo(4);
              assertThat(value.path("lowerPrefixIndex").asInt()).isEqualTo(1);
              assertThat(value.path("upperValue").asInt()).isEqualTo(4);
              assertThat(value.path("lowerValue").asInt()).isEqualTo(2);
              assertThat(value.path("result").isNull()).isTrue();
            });
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.path("query").path("status").asText()).isEqualTo("OBSERVED");
              assertThat(frame.path("query").path("result").asInt()).isEqualTo(2);
            });
    assertThat(nodes(body.path("steps")))
        .anySatisfy(
            step -> {
              assertThat(step.path("code").asText()).contains("int total =");
              assertThat(nodes(step.path("variables")))
                  .noneMatch(v -> v.path("variableName").asText().equals("total"));
            });
  }

  @Test
  void sameLengthWhileSerializesLongArraysAndPendingSeed() throws Exception {
    var body = request("PrefixSumSameLengthSample.java", false);
    var hint = prefixHint(body, "SAME_LENGTH");
    assertThat(hint.path("prefixSum").path("queries").isEmpty()).isTrue();
    var frames = frames(body);
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.path("seedStatus").asText()).isEqualTo("PENDING");
              assertThat(frame.path("confirmedCells").isEmpty()).isTrue();
              assertThat(frame.path("result").path("values").toString()).isEqualTo("[0,0,0,0]");
            });
    var pending =
        frames.stream().filter(f -> f.path("recurrenceStatus").asText().equals("PENDING")).toList();
    assertThat(pending).extracting(f -> f.path("currentIndex").asInt()).containsExactly(1, 2, 3);
    for (var frame : pending) {
      int index = frame.path("currentIndex").asInt();
      assertThat(frame.path("sourceIndex").asInt()).isEqualTo(index);
      assertThat(frame.path("previousPrefixIndex").asInt()).isEqualTo(index - 1);
      assertThat(frame.path("targetIndex").asInt()).isEqualTo(index);
      assertThat(frame.path("confirmedPrefixEndExclusive").asInt()).isEqualTo(index);
    }
    assertThat(frames)
        .anySatisfy(
            frame -> {
              assertThat(frame.path("confirmedPrefixEndExclusive").asInt()).isEqualTo(4);
              assertThat(frame.path("source").path("values").toString()).isEqualTo("[2,-1,3,0]");
              assertThat(frame.path("result").path("values").toString()).isEqualTo("[2,1,4,4]");
            });
    assertThat(nodes(body.path("steps")))
        .anySatisfy(
            step -> {
              assertThat(nodes(step.path("variables")))
                  .anySatisfy(
                      variable -> {
                        assertThat(variable.path("variableName").asText()).isEqualTo("cumulative");
                        assertThat(variable.path("declaredType").asText()).isEqualTo("long[]");
                      });
            });
  }

  @Test
  void operationModeKeepsOrdinaryLifetimesAndEveryIndividualPrefixWrite() throws Exception {
    var body = request("PrefixSumSentinelSample.java", true);
    export(body, "operations");
    var hint = prefixHint(body, "ZERO_SENTINEL");
    assertThat(hint.path("prefixSum").path("queries").size()).isEqualTo(1);
    assertThat(body.path("traceInfo").path("schemaVersion").asInt()).isEqualTo(2);
    assertThat(body.path("traceInfo").path("mode").asText()).isEqualTo("OPERATIONS");
    assertThat(body.path("traceInfo").path("granularity").asText()).isEqualTo("SOURCE_OPERATION");
    assertThat(body.path("traceInfo").path("captureStatus").asText()).isEqualTo("COMPLETE");

    var steps = nodes(body.path("steps"));
    var created = new ArrayList<String>();
    var destroyed = new ArrayList<String>();
    var ordinaryValues = new ArrayList<Integer>();
    var writtenIndices = new ArrayList<Integer>();
    var writtenValues = new ArrayList<Integer>();
    var writtenObjectIds = new ArrayList<String>();
    long previousSequence = 0;
    for (var step : steps) {
      long sequence = step.path("sequence").asLong();
      assertThat(sequence).isGreaterThan(previousSequence);
      previousSequence = sequence;
      assertThat(step.has("visualStates")).isFalse();
      for (var effect : step.path("effects")) {
        String type = effect.path("type").asText();
        var after = effect.path("after");
        if (type.equals("VARIABLE_CREATED") && after.path("name").asText().equals("ordinary")) {
          assertThat(created).hasSize(destroyed.size());
          created.add(after.path("instanceId").asText());
          assertThat(after.path("valueStatus").asText()).isEqualTo("UNINITIALIZED");
        }
        if (type.equals("VARIABLE_INITIALIZED") && after.path("name").asText().equals("ordinary")) {
          ordinaryValues.add(after.path("value").asInt());
        }
        if (type.equals("VARIABLE_DESTROYED")
            && effect.path("before").path("name").asText().equals("ordinary")) {
          assertThat(writtenIndices).hasSize(created.size());
          assertThat(destroyed).hasSize(created.size() - 1);
          destroyed.add(effect.path("before").path("instanceId").asText());
        }
        if (type.equals("ARRAY_ELEMENT_WRITTEN")) {
          assertThat(step.path("phase").asText()).isEqualTo("AFTER");
          writtenIndices.add(effect.path("target").path("index").asInt());
          writtenValues.add(after.asInt());
          writtenObjectIds.add(effect.path("target").path("objectId").asText());
        }
      }
    }
    assertThat(created).hasSize(4).doesNotHaveDuplicates();
    assertThat(destroyed).containsExactlyElementsOf(created);
    assertThat(ordinaryValues).containsExactly(10, 11, 12, 13);
    assertThat(writtenIndices).containsExactly(1, 2, 3, 4);
    assertThat(writtenValues).containsExactly(2, 1, 4, 4);
    assertThat(writtenObjectIds).hasSize(4).doesNotContain("");
    assertThat(writtenObjectIds.stream().distinct()).hasSize(1);
    assertThat(steps)
        .anySatisfy(
            step -> {
              assertThat(step.path("source").path("code").asText()).startsWith("total =");
              assertThat(step.path("phase").asText()).isEqualTo("BEFORE");
              assertThat(nodes(step.path("variables")))
                  .noneMatch(
                      v ->
                          v.path("name").asText().equals("total")
                              && v.path("valueStatus").asText().equals("AVAILABLE"));
            });
    assertThat(steps)
        .anySatisfy(
            step -> {
              assertThat(step.path("source").path("code").asText()).startsWith("total =");
              assertThat(step.path("phase").asText()).isEqualTo("AFTER");
              assertThat(nodes(step.path("variables")))
                  .anySatisfy(
                      variable -> {
                        assertThat(variable.path("name").asText()).isEqualTo("total");
                        assertThat(variable.path("valueStatus").asText()).isEqualTo("AVAILABLE");
                        assertThat(variable.path("value").asInt()).isEqualTo(2);
                      });
            });
  }

  private JsonNode request(String filename, boolean operations) throws Exception {
    var request =
        multipart("/api/debug/steps")
            .file(
                new MockMultipartFile(
                    "file",
                    filename,
                    "text/plain",
                    Files.readAllBytes(Path.of("examples/prefixsum", filename))));
    if (operations) request.param("traceMode", "OPERATIONS");
    return json.readTree(
        mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode prefixHint(JsonNode body, String representation) {
    var hints = nodes(body.path("algorithmHints"));
    assertThat(hints).hasSize(1);
    var hint = hints.get(0);
    assertThat(hint.path("type").asText()).isEqualTo("prefix-sum");
    var metadata = hint.path("prefixSum");
    assertThat(metadata.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(metadata.path("representation").asText()).isEqualTo(representation);
    for (String role : List.of("data", "prefix", "index")) {
      assertThat(metadata.path("declarationIds").path(role).asText()).isNotBlank();
    }
    for (String operation : List.of("allocation", "seed", "recurrence", "loop", "indexUpdate")) {
      assertThat(metadata.path("operationAstNodeIds").path(operation).asText()).isNotBlank();
    }
    return hint;
  }

  private List<JsonNode> frames(JsonNode body) {
    var frames = new ArrayList<JsonNode>();
    for (var step : body.path("steps")) {
      assertThat(step.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
      for (var state : step.path("visualStates")) {
        if (state.has("prefixSumFrame")) {
          assertThat(state.path("hintIndex").asInt()).isZero();
          var frame = state.path("prefixSumFrame");
          assertThat(frame.path("schemaVersion").asInt()).isEqualTo(1);
          assertThat(frame.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
          frames.add(frame);
        }
      }
    }
    assertThat(frames).isNotEmpty();
    return frames;
  }

  private List<JsonNode> nodes(JsonNode array) {
    var result = new ArrayList<JsonNode>();
    array.forEach(result::add);
    return result;
  }

  private void export(JsonNode body, String mode) throws Exception {
    if (Boolean.getBoolean("prefixSum.exportExamples")) {
      Files.writeString(
          Path.of(System.getProperty("java.io.tmpdir"), "ast-prefix-sum-" + mode + ".json"),
          body.toPrettyString());
    }
  }
}
