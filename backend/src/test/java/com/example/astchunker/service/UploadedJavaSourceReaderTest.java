package com.example.astchunker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class UploadedJavaSourceReaderTest {

  private final UploadedJavaSourceReader reader = new UploadedJavaSourceReader();

  @Test
  void readsUtf8JavaSourceFromAnUploadedFile() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "Demo.java", "text/x-java-source", "class Demo {}".getBytes(StandardCharsets.UTF_8));

    assertThat(reader.read(file)).isEqualTo("class Demo {}");
  }

  @Test
  void rejectsFilesThatDoNotHaveAJavaExtension() {
    MockMultipartFile file =
        new MockMultipartFile("file", "notes.txt", "text/plain", "not Java".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> reader.read(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(".java");
  }

  /** Allows these assertions to run while Maven is unavailable in this environment. */
  public static void main(String[] args) {
    UploadedJavaSourceReaderTest test = new UploadedJavaSourceReaderTest();
    test.readsUtf8JavaSourceFromAnUploadedFile();
    test.rejectsFilesThatDoNotHaveAJavaExtension();
  }
}
