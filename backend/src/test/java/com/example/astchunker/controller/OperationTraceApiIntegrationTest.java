package com.example.astchunker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OperationTraceApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private MockMultipartFile file(String source) {
    return new MockMultipartFile(
        "file", "Sample.java", "text/plain", source.getBytes(StandardCharsets.UTF_8));
  }

  private static final String SOURCE =
      """
      public class Sample { public static void main(String[] args) {
        int[] a={7,3}; int i=0,j=1;
        { int temp=a[i]; int ordinary=10; a[i]=a[j]; a[j]=temp; }
      } }
      """;

  @Test
  void operationModeCreatesVisibleTempAndRetainsOrdinaryStatements() throws Exception {
    var response =
        mvc.perform(
                multipart("/api/debug/steps").file(file(SOURCE)).param("traceMode", "OPERATIONS"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var body = json.readTree(response.getContentAsString());
    assertThat(body.path("traceInfo").path("mode").asText()).isEqualTo("OPERATIONS");
    assertThat(body.path("traceInfo").path("captureStatus").asText()).isEqualTo("COMPLETE");
    boolean created = false, copy = false, destroyed = false, duplicate = false, ordinary = false;
    for (var step : body.path("steps")) {
      ordinary |= step.path("source").path("code").asText().contains("ordinary");
      for (var effect : step.path("effects")) {
        if (effect.path("type").asText().equals("VARIABLE_CREATED")
            && effect.path("after").path("name").asText().equals("temp")) {
          created = true;
          assertThat(effect.path("after").path("valueStatus").asText()).isEqualTo("UNINITIALIZED");
        }
        copy |=
            effect.path("type").asText().equals("VALUE_COPIED")
                && effect.path("source").path("kind").asText().equals("ARRAY_ELEMENT");
        destroyed |=
            effect.path("type").asText().equals("VARIABLE_DESTROYED")
                && effect.path("before").path("name").asText().equals("temp");
      }
      for (var object : step.path("objects"))
        duplicate |= object.path("values").toString().equals("[3,3]");
    }
    assertThat(created && copy && destroyed && duplicate && ordinary).isTrue();
    if (Boolean.getBoolean("operationTrace.exportExamples"))
      java.nio.file.Files.writeString(
          java.nio.file.Path.of(
              System.getProperty("java.io.tmpdir"), "ast-operation-trace-example.json"),
          body.toPrettyString());
  }

  @Test
  void rejectsUnknownModeRatherThanSilentlyUsingLineTrace() throws Exception {
    mvc.perform(multipart("/api/debug/steps").file(file(SOURCE)).param("traceMode", "WRONG"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unsupportedSourceReportsCoverageInsteadOfSkippingOperations() throws Exception {
    String source =
        "public class Sample {public static void main(String[] a){try {int x=1;}finally{}}}";
    var response =
        mvc.perform(
                multipart("/api/debug/steps").file(file(source)).param("traceMode", "OPERATIONS"))
            .andExpect(status().isUnprocessableEntity())
            .andReturn()
            .getResponse();
    var body = json.readTree(response.getContentAsString());
    assertThat(body.path("problems").isArray()).isTrue();
    assertThat(body.path("problems").size()).isPositive();
  }
}
