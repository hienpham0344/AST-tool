package com.example.astchunker.debug.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.operation.OperationEffect;
import com.example.astchunker.visualization.operation.OperationTraceBuilder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationJdiSessionIntegrationTest {
  private static final String SOURCE =
      """
      public class Sample {
        public static void main(String[] args) {
          int[] a={7,3};
          for(int i=0;i<2;i++) { int temp=a[0]; a[0]=a[1]; a[1]=temp; int ordinary=i+10; }
        }
      }
      """;

  @Test
  void capturesSeparateTemporaryEntitiesAndIntermediateArrayWrites() {
    var analysis = new AstAnalyzer().analyze(SOURCE);
    var program = new OperationInstrumenter().instrument(SOURCE, analysis);
    try (var target = new TargetCompiler().compile(program.sourceCode(), analysis)) {
      var run =
          new OperationJdiSession(new VariableMapper(), Duration.ofSeconds(20), 2000)
              .observe(target, analysis, program);
      assertThat(run.captureStatus()).isEqualTo("COMPLETE");
      var result = new OperationTraceBuilder().build(analysis, run);
      assertThat(result.steps())
          .anySatisfy(
              step ->
                  assertThat(step.objects().values())
                      .anySatisfy(object -> assertThat(object.values()).containsExactly(3, 3)));
      assertThat(result.steps())
          .anySatisfy(
              step ->
                  assertThat(step.objects().values())
                      .anySatisfy(object -> assertThat(object.values()).containsExactly(7, 7)));
      var effects = result.steps().stream().flatMap(s -> s.effects().stream()).toList();
      var created =
          effects.stream()
              .filter(e -> e.type().equals("VARIABLE_CREATED"))
              .filter(
                  e ->
                      e.after()
                              instanceof com.example.astchunker.model.operation.OperationVariable v
                          && v.name().equals("temp"))
              .toList();
      assertThat(created).hasSize(2);
      assertThat(created).extracting(OperationEffect::entityId).doesNotHaveDuplicates();
      for (var create : created)
        assertThat(effects)
            .anyMatch(
                e ->
                    e.type().equals("VARIABLE_DESTROYED")
                        && e.entityId().equals(create.entityId()));
      assertThat(result.steps()).anyMatch(s -> s.source().code().contains("ordinary"));
      assertThat(result.steps())
          .noneMatch(s -> s.source().code().contains(program.helperMethodName()));
    }
  }

  @Test
  void explicitCaptureLimitNeverPretendsToFinish() {
    var analysis = new AstAnalyzer().analyze(SOURCE);
    var program = new OperationInstrumenter().instrument(SOURCE, analysis);
    try (var target = new TargetCompiler().compile(program.sourceCode(), analysis)) {
      var run =
          new OperationJdiSession(new VariableMapper(), Duration.ofSeconds(20), 5)
              .observe(target, analysis, program);
      assertThat(run.captureStatus()).isEqualTo("TRUNCATED");
      assertThat(run.terminationReason()).isEqualTo("MAX_MARKERS");
      assertThat(run.captures()).hasSize(5);
    }
  }

  private com.example.astchunker.dto.OperationTraceResponse trace(String source) {
    var analysis = new AstAnalyzer().analyze(source);
    var program = new OperationInstrumenter().instrument(source, analysis);
    try (var target = new TargetCompiler().compile(program.sourceCode(), analysis)) {
      var run =
          new OperationJdiSession(new VariableMapper(), Duration.ofSeconds(20), 3000)
              .observe(target, analysis, program);
      assertThat(run.captureStatus()).isEqualTo("COMPLETE");
      return new OperationTraceBuilder().build(analysis, run);
    }
  }

  @Test
  void recursiveInvocationsKeepCallerPendingAndTemporariesSeparate() {
    var result =
        trace(
            "public class Sample {static int f(int n){int temp=n;if(n==0)return 1;return"
                + " temp*f(n-1);}public static void main(String[] a){int answer=f(3);}}");
    var temps =
        result.steps().stream()
            .flatMap(s -> s.effects().stream())
            .filter(e -> e.type().equals("VARIABLE_CREATED"))
            .filter(
                e ->
                    e.after() instanceof com.example.astchunker.model.operation.OperationVariable v
                        && v.name().equals("temp"))
            .toList();
    assertThat(temps).hasSize(4);
    assertThat(temps).extracting(OperationEffect::entityId).doesNotHaveDuplicates();
    assertThat(result.steps())
        .anySatisfy(
            step ->
                assertThat(step.variables())
                    .anySatisfy(
                        v -> {
                          assertThat(v.name()).isEqualTo("answer");
                          assertThat(v.value()).isEqualTo(6);
                        }));
    assertThat(
            result.steps().stream()
                .flatMap(s -> s.effects().stream())
                .filter(e -> e.type().equals("RETURN_VALUE"))
                .map(OperationEffect::after)
                .toList())
        .contains(1, 2, 6);
    var before =
        result.steps().stream()
            .filter(
                s ->
                    s.context().methodName().equals("main")
                        && s.phase().equals("BEFORE")
                        && s.source().code().contains("answer"))
            .findFirst()
            .orElseThrow();
    var after =
        result.steps().stream()
            .filter(
                s ->
                    s.context().methodName().equals("main")
                        && s.phase().equals("AFTER")
                        && s.source().code().contains("answer"))
            .findFirst()
            .orElseThrow();
    assertThat(after.operationExecutionId()).isEqualTo(before.operationExecutionId());
    assertThat(result.steps())
        .anyMatch(
            s ->
                s.context().methodName().equals("f")
                    && s.sequence() > before.sequence()
                    && s.sequence() < after.sequence());
  }

  @Test
  void uninitializedAndSiblingSameNameVariablesHaveTruthfulLifetimes() {
    var result =
        trace(
            "public class Sample {public static void main(String[] a){int value; value=7;{int"
                + " temp=1;}{int temp=2;}}}");
    assertThat(result.steps())
        .anySatisfy(
            s ->
                assertThat(s.variables())
                    .anySatisfy(
                        v -> {
                          assertThat(v.name()).isEqualTo("value");
                          assertThat(v.valueStatus()).isEqualTo("UNINITIALIZED");
                          assertThat(v.value()).isNull();
                        }));
    assertThat(result.steps())
        .anySatisfy(
            s ->
                assertThat(s.variables())
                    .anySatisfy(
                        v -> {
                          assertThat(v.name()).isEqualTo("value");
                          assertThat(v.value()).isEqualTo(7);
                        }));
    var temps =
        result.steps().stream()
            .flatMap(s -> s.effects().stream())
            .filter(e -> e.type().equals("VARIABLE_CREATED"))
            .filter(
                e ->
                    e.after() instanceof com.example.astchunker.model.operation.OperationVariable v
                        && v.name().equals("temp"))
            .toList();
    assertThat(temps).hasSize(2);
    assertThat(temps).extracting(OperationEffect::entityId).doesNotHaveDuplicates();
  }

  @Test
  void continuePerformsForUpdateAndBreakEndsTheLoop() {
    var result =
        trace(
            "public class Sample {public static void main(String[] a){int sum=0;for(int"
                + " i=0;i<4;i++){if(i==1)continue;sum+=i;if(i==2)break;}int done=sum;}}");
    var updates =
        result.steps().stream()
            .filter(s -> s.kind().equals("LOOP_UPDATE") && s.phase().equals("AFTER"))
            .map(
                s ->
                    s.variables().stream()
                        .filter(v -> v.name().equals("i"))
                        .findFirst()
                        .orElseThrow()
                        .value())
            .toList();
    assertThat(updates).containsExactly(1, 2);
    assertThat(result.steps())
        .anySatisfy(
            s ->
                assertThat(s.variables())
                    .anySatisfy(
                        v -> {
                          assertThat(v.name()).isEqualTo("done");
                          assertThat(v.value()).isEqualTo(2);
                        }));
    assertThat(result.steps()).anyMatch(s -> s.kind().equals("LOOP") && s.phase().equals("EXIT"));
  }

  @Test
  void shortCircuitConditionIsEvaluatedOnceAndHasObservedOutcome() {
    var result =
        trace(
            "public class Sample {public static void main(String[] a){int i=0;if(i++==0 ||"
                + " i++==9){int answer=i;}}}");
    var condition =
        result.steps().stream()
            .filter(s -> s.kind().equals("CONDITION") && s.phase().equals("AFTER"))
            .findFirst()
            .orElseThrow();
    assertThat(condition.conditionOutcome()).isTrue();
    assertThat(condition.variables())
        .anySatisfy(
            v -> {
              assertThat(v.name()).isEqualTo("i");
              assertThat(v.value()).isEqualTo(1);
            });
  }

  @Test
  void abruptVmExitDoesNotReportComplete() {
    for (String termination : List.of("System.exit(0)", "Runtime.getRuntime().halt(0)")) {
      String source =
          "public class Sample {public static void main(String[] a){int x=1;"
              + termination
              + ";x=2;}}";
      var analysis = new AstAnalyzer().analyze(source);
      var program = new OperationInstrumenter().instrument(source, analysis);
      try (var target = new TargetCompiler().compile(program.sourceCode(), analysis)) {
        var run =
            new OperationJdiSession(new VariableMapper(), Duration.ofSeconds(20), 1000)
                .observe(target, analysis, program);
        assertThat(run.captureStatus()).isEqualTo("FAILED");
        assertThat(run.terminationReason()).isEqualTo("VM_TERMINATED_WITH_ACTIVE_INVOCATIONS");
        assertThat(run.captures()).noneMatch(c -> c.site().kind().equals("METHOD_EXIT"));
      }
    }
  }

  @Test
  void cumulativeCapturedTextStopsBeforeRetainingAnOversizedTrace() {
    String source =
        "public class Sample {public static void main(String[] a){"
            + "String text=\"x\".repeat(1000000);for(int i=0;i<20;i++){int n=i;}}}";
    var analysis = new AstAnalyzer().analyze(source);
    var program = new OperationInstrumenter().instrument(source, analysis);
    try (var target = new TargetCompiler().compile(program.sourceCode(), analysis)) {
      var run =
          new OperationJdiSession(new VariableMapper(), Duration.ofSeconds(20), 1000)
              .observe(target, analysis, program);
      assertThat(run.captureStatus()).isEqualTo("TRUNCATED");
      assertThat(run.terminationReason()).isEqualTo("MAX_CAPTURE_TEXT_CHARS");
      assertThat(run.captures()).hasSizeLessThan(20);
      assertThat(run.limits()).containsKeys("maxCaptureValues", "maxCaptureTextChars");
    }
  }

  @Test
  void heapCachePrunesDeadObjectsButPreservesAliasesAcrossInvocations() {
    var result =
        trace(
            "public class Sample {static void touch(int[] v){v[0]=3;}"
                + "public static void main(String[] args){int[] a={1};int[] alias=a;"
                + "a=new int[]{2};touch(alias);alias=null;int done=1;}}");
    var reassigned =
        result.steps().stream()
            .filter(s -> s.phase().equals("AFTER") && s.source().code().equals("a=new int[]{2};"))
            .findFirst()
            .orElseThrow();
    String original =
        reassigned.variables().stream()
            .filter(v -> v.name().equals("alias"))
            .findFirst()
            .orElseThrow()
            .objectId();
    String replacement =
        reassigned.variables().stream()
            .filter(v -> v.name().equals("a"))
            .findFirst()
            .orElseThrow()
            .objectId();
    assertThat(reassigned.objects()).containsKeys(original, replacement);
    assertThat(result.steps())
        .filteredOn(s -> s.context().methodName().equals("touch"))
        .allSatisfy(s -> assertThat(s.objects()).containsKeys(original, replacement));
    var released =
        result.steps().stream()
            .filter(s -> s.phase().equals("AFTER") && s.source().code().equals("alias=null;"))
            .findFirst()
            .orElseThrow();
    assertThat(released.objects()).containsKey(replacement).doesNotContainKey(original);
    assertThat(result.steps().get(result.steps().size() - 1).objects()).isEmpty();
  }
}
