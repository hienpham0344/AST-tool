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
            .andExpect(jsonPath("$.steps[0].startLine").isNumber())
            .andExpect(jsonPath("$.steps[0].endLine").isNumber())
            .andExpect(jsonPath("$.steps[0].code").isString())
            .andExpect(jsonPath("$.steps[0].variables").isArray())
            .andExpect(jsonPath("$.steps[0].visualStates").isEmpty())
            .andExpect(jsonPath("$.steps[0].visualEvents").isEmpty())
            .andExpect(jsonPath("$.steps[0].snapshotPhase").value("BEFORE_LOCATION"))
            .andExpect(jsonPath("$.steps[0].granularity").value("LINE_BREAKPOINT"))
            .andExpect(jsonPath("$.steps[0].context.methodName").value("main"))
            .andExpect(
                jsonPath("$.steps[0].context.methodSignature").value("([Ljava/lang/String;)V"))
            .andExpect(jsonPath("$.steps[0].context.threadId").isNumber())
            .andExpect(jsonPath("$.steps[0].context.stackDepth").isNumber())
            .andExpect(jsonPath("$.steps[0].context.codeIndex").isNumber())
            .andExpect(jsonPath("$.warnings").isArray())
            .andExpect(jsonPath("$.algorithmHints").isArray())
            .andExpect(jsonPath("$.algorithmHints").isEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("\"ast\"");
  }

  @Test
  void rejectsAFileWithoutTheJavaExtension() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "not-java.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "not java".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/ast/parse").file(file))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Only .java source files are supported."));
  }

  @Test
  void returnsBinarySearchHintsAlongsideRuntimeSteps() throws Exception {
    String source =
        """
        public class SearchSample {
          public static void main(String[] args) {
            int[] nums = {1, 3, 5, 7};
            int lo = 0, hi = nums.length - 1, target = 5;
            while (lo <= hi) {
              int m = lo + (hi - lo) / 2;
              if (nums[m] == target) break;
              if (nums[m] < target) lo = m + 1;
              else hi = m - 1;
            }
          }
        }
        """;
    var file =
        new MockMultipartFile(
            "file",
            "SearchSample.java",
            "text/x-java-source",
            source.getBytes(StandardCharsets.UTF_8));
    mockMvc
        .perform(multipart("/api/debug/steps").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps").isNotEmpty())
        .andExpect(jsonPath("$.algorithmHints.length()").value(1))
        .andExpect(jsonPath("$.algorithmHints[0].type").value("binary-search"))
        .andExpect(jsonPath("$.algorithmHints[0].patternAstNodeId").isNotEmpty())
        .andExpect(jsonPath("$.algorithmHints[0].methodAstNodeId").isNotEmpty())
        .andExpect(jsonPath("$.algorithmHints[0].variableDeclarationIds.mid").isNotEmpty())
        .andExpect(jsonPath("$.algorithmHints[0].variables.mid").value("m"))
        .andExpect(jsonPath("$.algorithmHints[0].startLine").value(5))
        .andExpect(jsonPath("$.algorithmHints[0].endLine").value(10))
        .andExpect(jsonPath("$.steps[0].visualStates").isEmpty())
        .andExpect(jsonPath("$.steps[4].visualStates[0].algorithm").value("binary-search"))
        .andExpect(jsonPath("$.steps[4].visualStates[0].pointers.mid.index").value(1))
        .andExpect(jsonPath("$.steps[4].visualStates[0].array.length").value(4))
        .andExpect(jsonPath("$.steps[4].visualEvents").isArray());
  }

  @Test
  void returnsStructuredBubbleSortMetadataWithoutInventingRuntimeSortStates() throws Exception {
    String source =
        """
        public class BubbleSample {
          public static void main(String[] args) {
            int[] data = {3, 1, 2};
            for (int pass = 0; pass < data.length - 1; pass++) {
              for (int scan = 0; scan < data.length - 1 - pass; scan++) {
                if (data[scan] > data[scan + 1]) {
                  int temp = data[scan];
                  data[scan] = data[scan + 1];
                  data[scan + 1] = temp;
                }
              }
            }
            System.out.println(java.util.Arrays.toString(data));
          }
        }
        """;
    var file =
        new MockMultipartFile(
            "file",
            "BubbleSample.java",
            "text/x-java-source",
            source.getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/debug/steps").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.algorithmHints.length()").value(1))
        .andExpect(jsonPath("$.algorithmHints[0].type").value("bubble-sort"))
        .andExpect(jsonPath("$.algorithmHints[0].sort.schemaVersion").value(1))
        .andExpect(jsonPath("$.algorithmHints[0].sort.direction").value("ASCENDING"))
        .andExpect(jsonPath("$.algorithmHints[0].sort.first.offset").value(0))
        .andExpect(jsonPath("$.algorithmHints[0].sort.second.offset").value(1))
        .andExpect(jsonPath("$.algorithmHints[0].sort.swapStatementAstNodeIds.length()").value(3))
        .andExpect(jsonPath("$.algorithmHints[0].variableDeclarationIds.scan").isNotEmpty())
        .andExpect(jsonPath("$.steps[*].visualStates[*].sortFrame.phase").isNotEmpty())
        .andExpect(jsonPath("$.steps[*].visualStates[*].sortFrame.snapshotPhase").isNotEmpty())
        .andExpect(jsonPath("$.steps").isNotEmpty());
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
        "file",
        "ApiSample.java",
        "text/x-java-source",
        sourceCode().getBytes(StandardCharsets.UTF_8));
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
