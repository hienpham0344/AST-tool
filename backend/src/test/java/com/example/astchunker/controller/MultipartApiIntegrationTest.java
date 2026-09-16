package com.example.astchunker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MultipartApiIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void acceptsOneJavaFileForStructureAstAndRuntimeObservation() throws Exception {
    mockMvc
        .perform(multipart("/api/analyze").file(javaFile()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chunks[0].kind").value("class"));

    mockMvc
        .perform(multipart("/api/ast/parse").file(javaFile()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.astNodeId").isNotEmpty());

    String body =
        mockMvc
            .perform(multipart("/api/debug").file(javaFile()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("\"variableName\":\"value\"");
    assertThat(body).contains("\"runtimeValue\":\"7\"");
    assertThat(body.trim()).startsWith("[");
  }

  @Test
  void exposesGroupedExecutionStepsWithoutChangingTheLegacyDebugArray() throws Exception {
    String body =
        mockMvc
            .perform(multipart("/api/debug/steps").file(javaFile()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.steps").isArray())
            .andExpect(jsonPath("$.steps[0].sequence").isNumber())
            .andExpect(jsonPath("$.steps[0].lineNumber").isNumber())
            .andExpect(jsonPath("$.steps[0].variables").isArray())
            .andExpect(jsonPath("$.warnings").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("\"ast\"");
  }

  @Test
  void rejectsAFileWithoutTheJavaExtension() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "not-java.txt", MediaType.TEXT_PLAIN_VALUE, "not java".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/ast/parse").file(file))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Only .java source files are supported."));
  }

  @Test
  void exposesCorsForTheStandaloneUi() throws Exception {
    mockMvc
        .perform(
            options("/api/analyze")
                .header("Origin", "http://localhost:5500")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }

  private MockMultipartFile javaFile() {
    return new MockMultipartFile(
        "file", "ApiSample.java", "text/x-java-source", sourceCode().getBytes(StandardCharsets.UTF_8));
  }

  private String sourceCode() {
    return """
        public class ApiSample {
          public static void main(String[] args) {
            int value = 7;
            System.out.println(value);
          }
        }
        """;
  }
}
