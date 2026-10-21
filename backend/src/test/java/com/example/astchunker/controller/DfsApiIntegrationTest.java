package com.example.astchunker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DfsApiIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void matrixLineModeShowsObservedVisitedAndRecursiveInvocations() throws Exception {
    verifyLine("DfsMatrixSample.java", "ADJACENCY_MATRIX");
  }

  @Test
  void listLineModeUsesBoundedSharedGraphProjection() throws Exception {
    verifyLine("DfsListSample.java", "ADJACENCY_LIST");
  }

  private void verifyLine(String fixture, String representation) throws Exception {
    var body = request(fixture, false);
    var hint = hint(body, representation);
    if (representation.equals("ADJACENCY_MATRIX")) export(body, "line");
    var steps = nodes(body.path("steps"));
    assertThat(steps.get(0).path("context").path("methodName").asText()).isEqualTo("main");
    List<JsonNode> frames = new ArrayList<>();
    for (var step : steps) {
      assertThat(step.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
      for (var state : step.path("visualStates"))
        if (state.has("dfsFrame")) frames.add(state.path("dfsFrame"));
    }
    assertThat(frames).isNotEmpty();
    assertThat(frames.stream().map(f -> f.path("invocationId").asLong()).distinct()).hasSize(4);
    assertThat(frames.stream().map(f -> f.path("graphObjectId").asLong()).distinct()).hasSize(1);
    assertThat(frames.stream().map(f -> f.path("visitedObjectId").asLong()).distinct()).hasSize(1);
    assertThat(frames)
        .allSatisfy(
            f -> {
              assertThat(f.path("graphStatus").asText()).isEqualTo("AVAILABLE");
              assertThat(f.path("visitedStatus").asText()).isEqualTo("AVAILABLE");
              assertThat(f.path("graph").path("vertexCount").asInt()).isEqualTo(5);
              assertThat(nodes(f.path("visitedVertices"))).noneMatch(v -> v.asInt() == 4);
            });
    assertThat(frames)
        .anySatisfy(
            f -> {
              assertThat(f.path("markStatus").asText()).isEqualTo("PENDING");
              assertThat(nodes(f.path("visitedVertices")))
                  .noneMatch(v -> v.asInt() == f.path("currentVertex").asInt());
            });
    assertThat(frames)
        .anySatisfy(f -> assertThat(f.path("visitedVertices").toString()).isEqualTo("[0,1,2,3]"));
    assertThat(hint.path("dfs").path("operationAstNodeIds").has("recursiveCall")).isTrue();
  }

  @Test
  void matrixOperationsStartAtMainPreserveLocalsAndReturnThroughEachCaller() throws Exception {
    var body = request("DfsMatrixSample.java", true);
    var hint = hint(body, "ADJACENCY_MATRIX");
    assertThat(body.path("traceInfo").path("captureStatus").asText()).isEqualTo("COMPLETE");
    var steps = nodes(body.path("steps"));
    assertThat(steps.get(0).path("context").path("methodName").asText()).isEqualTo("main");
    String currentId = hint.path("dfs").path("declarationIds").path("current").asText();
    String graphId = hint.path("dfs").path("declarationIds").path("graph").asText();
    String visitedId = hint.path("dfs").path("declarationIds").path("visited").asText();
    Map<Long, Integer> discoveries = new LinkedHashMap<>();
    Set<String> graphObjects = new HashSet<>(), visitedObjects = new HashSet<>();
    Set<String> created = new HashSet<>(), destroyed = new HashSet<>();
    List<Integer> marks = new ArrayList<>();
    List<Long> exits = new ArrayList<>();
    for (var step : steps) {
      boolean dfs = step.path("context").path("methodName").asText().equals("explore");
      long invocation = step.path("context").path("invocationId").asLong();
      for (var value : step.path("variables")) {
        String declaration = value.path("declarationId").asText();
        if (dfs && declaration.equals(currentId) && value.path("value").isIntegralNumber())
          discoveries.putIfAbsent(invocation, value.path("value").asInt());
        if (dfs && declaration.equals(graphId) && value.path("objectId").isTextual())
          graphObjects.add(value.path("objectId").asText());
        if (dfs && declaration.equals(visitedId) && value.path("objectId").isTextual())
          visitedObjects.add(value.path("objectId").asText());
      }
      if (dfs && step.path("kind").asText().equals("METHOD_EXIT")) exits.add(invocation);
      for (var effect : step.path("effects")) {
        String type = effect.path("type").asText();
        if (type.equals("VARIABLE_CREATED")
            && effect.path("after").path("name").asText().equals("local"))
          created.add(effect.path("entityId").asText());
        if (type.equals("VARIABLE_DESTROYED")
            && effect.path("before").path("name").asText().equals("local"))
          destroyed.add(effect.path("entityId").asText());
        if (type.equals("ARRAY_ELEMENT_WRITTEN")
            && effect.path("after").isBoolean()
            && effect.path("after").asBoolean())
          marks.add(effect.path("target").path("index").asInt());
      }
    }
    assertThat(discoveries.values()).containsExactly(0, 1, 2, 3);
    assertThat(graphObjects).hasSize(1);
    assertThat(visitedObjects).hasSize(1);
    assertThat(created).hasSize(4).isEqualTo(destroyed);
    assertThat(marks).containsExactly(0, 1, 2, 3);
    List<Long> reverse = new ArrayList<>(discoveries.keySet());
    Collections.reverse(reverse);
    assertThat(exits).containsExactlyElementsOf(reverse);
    assertThat(steps)
        .anySatisfy(
            step -> {
              assertThat(step.path("context").path("methodName").asText()).isEqualTo("main");
              assertThat(step.path("phase").asText()).isEqualTo("AFTER");
              assertThat(step.path("source").path("code").asText())
                  .contains("explore(graph, start, visited)");
              var object = step.path("objects").path(visitedObjects.iterator().next());
              assertThat(object.path("values").toString()).isEqualTo("[true,true,true,true,false]");
              assertThat(step.path("sequence").asLong()).isGreaterThan(exitsSequence(steps));
            });
    export(body, "operations");
  }

  private static long exitsSequence(List<JsonNode> steps) {
    return steps.stream()
        .filter(
            s ->
                s.path("kind").asText().equals("METHOD_EXIT")
                    && s.path("context").path("methodName").asText().equals("explore"))
        .mapToLong(s -> s.path("sequence").asLong())
        .max()
        .orElseThrow();
  }

  @Test
  void unsupportedListConstructionDoesNotSilentlyFallBackInOperations() throws Exception {
    mvc.perform(
            multipart("/api/debug/steps")
                .file(file("DfsListSample.java"))
                .param("traceMode", "OPERATIONS"))
        .andExpect(status().isUnprocessableEntity());
  }

  private JsonNode request(String fixture, boolean operations) throws Exception {
    var request = multipart("/api/debug/steps").file(file(fixture));
    if (operations) request.param("traceMode", "OPERATIONS");
    var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
    var body = json.readTree(response.getContentAsString());
    assertThat(body.path("warnings").isEmpty()).isTrue();
    return body;
  }

  private static MockMultipartFile file(String fixture) throws Exception {
    return new MockMultipartFile(
        "file", fixture, "text/plain", Files.readAllBytes(Path.of("examples/graph", fixture)));
  }

  private static JsonNode hint(JsonNode body, String representation) {
    var hints =
        nodes(body.path("algorithmHints")).stream()
            .filter(h -> h.path("type").asText().equals("dfs"))
            .toList();
    assertThat(hints).hasSize(1);
    var hint = hints.get(0);
    assertThat(hint.path("dfs").path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(hint.path("dfs").path("representation").asText()).isEqualTo(representation);
    return hint;
  }

  private static List<JsonNode> nodes(JsonNode array) {
    List<JsonNode> result = new ArrayList<>();
    array.forEach(result::add);
    return result;
  }

  private void export(JsonNode body, String mode) throws Exception {
    if (Boolean.getBoolean("dfs.exportExamples"))
      Files.writeString(
          Path.of(System.getProperty("java.io.tmpdir"), "ast-dfs-" + mode + ".json"),
          body.toPrettyString());
  }
}
