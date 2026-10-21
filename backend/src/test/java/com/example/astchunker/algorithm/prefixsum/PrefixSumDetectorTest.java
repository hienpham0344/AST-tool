package com.example.astchunker.algorithm.prefixsum;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.AlgorithmHint;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrefixSumDetectorTest {
  private List<AlgorithmHint> detect(String source) {
    return new AlgorithmPatternAnalyzer()
        .analyze(new AstAnalyzer().parseCompilationUnit(source)).stream()
            .filter(h -> h.type().equals("prefix-sum"))
            .toList();
  }

  @Test
  void recognizesRenamedSameLengthFor() {
    assertThat(
            detect(
                """
                class Sample { int[] build(int[] values) {
                  int[] accumulated = new int[values.length];
                  accumulated[0] = values[0];
                  for (int cursor=1; cursor<values.length; cursor++) {
                    accumulated[cursor] = accumulated[cursor-1] + values[cursor];
                  }
                  return accumulated;
                } }
                """))
        .singleElement()
        .satisfies(h -> assertThat(h.type()).isEqualTo("prefix-sum"));
  }

  private String sentinel(String before, String body, String after) {
    return "class Sample { long build(long[] data, int left, int right, boolean enabled) {"
        + "long[] prefix = new long[data.length+1];"
        + before
        + "for (int index=0; index<data.length; index++) {"
        + body
        + "}"
        + after
        + "return 0; } }";
  }

  private static final String RECURRENCE = "prefix[index+1] = prefix[index] + data[index];";

  @Test
  void recognizesImplicitSentinelCommutedAdditionAndOrdinaryScalar() {
    var hints =
        detect(
            sentinel("", "int ordinary=index+10; prefix[1+index]=data[index]+prefix[index];", ""));
    assertThat(hints)
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.prefixSum().representation()).isEqualTo("ZERO_SENTINEL");
              assertThat(h.prefixSum().schemaVersion()).isEqualTo(1);
              assertThat(h.prefixSum().declarationIds()).containsKeys("data", "prefix", "index");
              assertThat(h.prefixSum().operationAstNodeIds())
                  .containsKeys("seed", "allocation", "recurrence", "loop", "indexUpdate");
              assertThat(h.prefixSum().queries()).isEmpty();
              assertThat(h.slidingWindow()).isNull();
            });
  }

  @Test
  void recognizesExplicitSentinelWhileAndSameLengthLong() {
    assertThat(
            detect(
                """
                class Sample { long[] build(long[] values) {
                  long[] totals = new long[values.length+1];
                  totals[0] = 0L;
                  int cursor=0;
                  while(cursor<values.length) {
                    totals[cursor+1] = totals[cursor] + values[cursor];
                    cursor += 1;
                  }
                  return totals;
                } }
                """))
        .singleElement()
        .satisfies(h -> assertThat(h.prefixSum().representation()).isEqualTo("ZERO_SENTINEL"));
    assertThat(
            detect(
                """
                class Sample { long[] build(long[] values) {
                  long[] totals = new long[values.length]; totals[0]=values[0];
                  int cursor=1;
                  while(cursor<values.length) { totals[cursor]=values[cursor]+totals[cursor-1]; cursor=cursor+1; }
                  return totals;
                } }
                """))
        .singleElement()
        .satisfies(h -> assertThat(h.prefixSum().representation()).isEqualTo("SAME_LENGTH"));
  }

  @Test
  void rejectsIncorrectRecurrenceAndControlFlow() {
    for (String body :
        List.of(
            "prefix[index+1]=prefix[index]-data[index];",
            "prefix[index+1]=prefix[index]+data[index+1];",
            "prefix[index+1]=prefix[index-1]+data[index];",
            "prefix[index]=prefix[index]+data[index];",
            "if(enabled){" + RECURRENCE + "}",
            RECURRENCE + "prefix[index+1]=0;",
            RECURRENCE + "data[index]=0;",
            RECURRENCE + "data[index]++;",
            RECURRENCE + "index++;",
            RECURRENCE + "if(enabled) break;",
            "continue;" + RECURRENCE,
            "if(enabled) throw new RuntimeException();" + RECURRENCE,
            "unknown();" + RECURRENCE)) {
      assertThat(detect(sentinel("", body, ""))).as(body).isEmpty();
    }
  }

  @Test
  void rejectsWrongSeedBoundsInitializationAndAllocation() {
    String valid = sentinel("prefix[0]=0;", RECURRENCE, "");
    for (String source :
        List.of(
            valid.replace("prefix[0]=0", "prefix[0]=7"),
            valid.replace("prefix[0]=0;", "if(enabled) prefix[0]=7;"),
            valid.replace("index=0", "index=1"),
            valid.replace("index++", "index+=2"),
            valid.replace("index<data.length", "index<=data.length"),
            valid.replace("index<data.length", "index<prefix.length"),
            valid.replace("data.length+1", "data.length"),
            valid.replace("data.length+1", "10"),
            valid.replace("new long[data.length+1]", "data"),
            valid.replace("long[] prefix", "int[] prefix").replace("new long[", "new int["),
            valid.replace("prefix[0]=0;", "prefix[1]=0;"))) {
      assertThat(detect(source)).as(source).isEmpty();
    }
    assertThat(
            detect(
                """
                class Sample { int[] build(int[] a, boolean ready) {
                  int[] p=new int[a.length]; if(ready) p[0]=a[0];
                  for(int i=1;i<a.length;i++) p[i]=p[i-1]+a[i];
                  return p;
                } }
                """))
        .isEmpty();
  }

  @Test
  void rejectsAliasesEscapesReassignmentAndShadowing() {
    for (String before :
        List.of(
            "long[] alias=data;",
            "long[] alias=prefix;",
            "consume(data);",
            "consume(prefix);",
            "data=new long[2];",
            "prefix=new long[data.length+1];",
            "data[0]=1;",
            "prefix[0]=9;",
            "{long[] data=new long[3]; consume(data);}")) {
      assertThat(detect(sentinel(before, RECURRENCE, ""))).as(before).isEmpty();
    }
    for (String after :
        List.of("prefix[0]=2;", "data[0]=2;", "consume(prefix);", "long[] alias=prefix;")) {
      assertThat(detect(sentinel("", RECURRENCE, after))).as(after).isEmpty();
    }
  }

  @Test
  void provesOnlyDirectQueryAfterUnconditionalCompletedConstruction() {
    assertThat(detect(sentinel("", RECURRENCE, "long answer=prefix[right+1]-prefix[left];")))
        .singleElement()
        .satisfies(
            h ->
                assertThat(h.prefixSum().queries())
                    .singleElement()
                    .satisfies(
                        q -> {
                          assertThat(q.expressionAstNodeId()).isNotBlank();
                          assertThat(q.leftDeclarationId()).isNotEqualTo(q.rightDeclarationId());
                          assertThat(q.leftExpressionAstNodeId()).isNotBlank();
                          assertThat(q.rightExpressionAstNodeId()).isNotBlank();
                        }));
    for (String after :
        List.of(
            "long answer=prefix[right]-prefix[left];",
            "long answer=prefix[right+1]+prefix[left];",
            "if(enabled) {long answer=prefix[right+1]-prefix[left];}",
            "for(int k=0;k<2;k++){long answer=prefix[right+1]-prefix[left];}",
            "long answer=enabled ? prefix[right+1]-prefix[left] : 0;")) {
      assertThat(detect(sentinel("", RECURRENCE, after)))
          .singleElement()
          .satisfies(h -> assertThat(h.prefixSum().queries()).isEmpty());
    }
    String conditional =
        sentinel("if(enabled){", RECURRENCE, "} long answer=prefix[right+1]-prefix[left];");
    assertThat(detect(conditional))
        .singleElement()
        .satisfies(h -> assertThat(h.prefixSum().queries()).isEmpty());
  }

  @Test
  void rejectsInputLocalAliasAndUnprovenInputFactory() {
    for (String initialization : List.of("original", "factory()")) {
      assertThat(
              detect(
                  "class Sample { int[] build(int[] original){"
                      + "int[] data="
                      + initialization
                      + "; int[] prefix=new int[data.length+1];"
                      + "for(int index=0;index<data.length;index++){"
                      + RECURRENCE
                      + "}"
                      + "return prefix;} }"))
          .as(initialization)
          .isEmpty();
    }
  }

  @Test
  void rejectsWhileSkippedOrPrematureUpdateAndWrongSameLengthSeed() {
    for (String body :
        List.of(
            "if(enabled) index++;" + RECURRENCE,
            "index++;" + RECURRENCE,
            RECURRENCE + "index+=2;")) {
      assertThat(
              detect(
                  "class Sample { void build(int[] data,boolean enabled){"
                      + "int[] prefix=new int[data.length+1];int index=0;"
                      + "while(index<data.length){"
                      + body
                      + "}}}"))
          .as(body)
          .isEmpty();
    }
    for (String seed : List.of("p[0]=0;", "p[0]=a[1];", "p[1]=a[0];", "")) {
      assertThat(
              detect(
                  "class Sample {void build(int[] a){int[] p=new int[a.length];"
                      + seed
                      + "for(int i=1;i<a.length;i++){p[i]=p[i-1]+a[i];}}}"))
          .as(seed)
          .isEmpty();
    }
  }
}
