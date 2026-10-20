package com.example.astchunker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BfsApiIntegrationTest {
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;

  @Test
  void debugStepsSerializesBfsMetadataAndRuntimeFrame() throws Exception {
    byte[] source = Files.readAllBytes(Path.of("examples/graph/BfsMatrixSample.java"));
    var response =
        mvc.perform(
                multipart("/api/debug/steps")
                    .file(
                        new MockMultipartFile(
                            "file", "BfsMatrixSample.java", "text/plain", source)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readTree(response.getContentAsString());
    assertThat(body.path("algorithmHints").size()).isEqualTo(1);
    assertThat(body.path("algorithmHints").get(0).path("bfs").path("schemaVersion").asInt())
        .isEqualTo(1);
    boolean frameFound = false;
    for (var step : body.path("steps")) {
      assertThat(step.path("snapshotPhase").asText()).isEqualTo("BEFORE_LOCATION");
      for (var state : step.path("visualStates")) {
        if (state.has("bfsFrame")) {
          frameFound = true;
          assertThat(state.path("bfsFrame").path("graph").path("vertexCount").asInt()).isEqualTo(5);
          assertThat(state.path("bfsFrame").path("queue").isArray()).isTrue();
        }
      }
    }
    assertThat(frameFound).isTrue();
  }
}
