package com.example.astchunker.debug.operation;

import static org.assertj.core.api.Assertions.*;

import com.example.astchunker.ast.AstAnalyzer;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationInstrumenterTest {
  @TempDir Path directory;
  private final AstAnalyzer analyzer = new AstAnalyzer();

  private OperationProgram instrument(String source) {
    return new OperationInstrumenter().instrument(source, analyzer.analyze(source));
  }

  @Test
  void separatesSameLineDeclarationsAndArrayWrites() throws Exception {
    String source =
        "public class Sample { public static int run() { int[] a={7,3}; int i=0,j=1; int temp=a[i];"
            + " a[i]=a[j]; a[j]=temp; return a[0]*10+a[1]; } }";
    OperationProgram p = instrument(source);
    assertThat(p.sites().values())
        .filteredOn(s -> s.phase().equals("AFTER") && s.kind().equals("DECLARATION"))
        .hasSize(4);
    assertThat(p.sites().values())
        .filteredOn(s -> s.phase().equals("AFTER") && s.kind().equals("ARRAY_WRITE"))
        .hasSize(2);
    assertThat(p.sites().values())
        .filteredOn(s -> s.phase().equals("BEFORE"))
        .extracting(OperationSite::operationId)
        .doesNotHaveDuplicates();
    assertThat(p.sites().values())
        .allSatisfy(
            s -> {
              assertThat(s.source().lineNumber()).isEqualTo(1);
              assertThat(s.source().code()).doesNotContain(p.helperMethodName());
            });
    assertEquivalent(source, p);
  }

  @Test
  void retainsDeclarationVisibilityAndUninitializedLocals() throws Exception {
    String source =
        "public class Sample { public static int run(){ int x, y=2; x=y; {int z=x; x=z+1;} return"
            + " x; } }";
    OperationProgram p = instrument(source);
    var declarations =
        p.sites().values().stream()
            .filter(s -> s.kind().equals("DECLARATION") && s.phase().equals("AFTER"))
            .toList();
    assertThat(declarations).hasSize(3);
    declarations.forEach(
        s -> assertThat(s.visibleDeclarationIds()).contains(s.attributes().get("declarationId")));
    assertEquivalent(source, p);
  }

  @Test
  void preservesLoopContinueBreakAndAllHeaderPhases() throws Exception {
    String source =
        "public class Sample { public static int run(){int s=0; for(int"
            + " i=0,j=3;i<5;i++,j--){if(i==1)continue; s+=j; if(i==3)break;} int k=0;"
            + " while(k++<2)s++; do {s++;}while(--k>0); return s;} }";
    OperationProgram p = instrument(source);
    assertThat(p.sites().values())
        .extracting(OperationSite::kind)
        .contains("LOOP_INIT", "LOOP_CONDITION", "LOOP_UPDATE", "BREAK", "CONTINUE");
    assertEquivalent(source, p);
  }

  @Test
  void preservesEnhancedForRecursionAndReturns() throws Exception {
    String source =
        "public class Sample {static int f(int n){if(n==0)return 1; return n*f(n-1);} public static"
            + " int run(){int s=0; for(int x:new int[]{1,2,3}){s+=f(x);} return s;} }";
    OperationProgram p = instrument(source);
    assertThat(p.sites().values())
        .anySatisfy(
            s -> {
              assertThat(s.kind()).isEqualTo("LOOP_BINDING");
              assertThat(s.phase()).isEqualTo("AFTER");
            });
    assertEquivalent(source, p);
  }

  @Test
  void preservesShortCircuitSideEffectsAndVoidReturn() throws Exception {
    String source =
        "public class Sample {static int n; static void f(){n++;return;} public static int"
            + " run(){int i=0;if(i++==0 || i++==9)f(); if(false && ++i>0)n++; return i+n;} }";
    assertEquivalent(source, instrument(source));
  }

  @Test
  void avoidsHelperAndReturnTemporaryCollisions() throws Exception {
    String source =
        "public class Sample {static boolean __astTraceMarker(int i,boolean b){return b;} public"
            + " static int run(){int __astTraceReturn=4;return __astTraceReturn;} }";
    OperationProgram p = instrument(source);
    assertThat(p.helperMethodName()).isNotEqualTo("__astTraceMarker");
    assertEquivalent(source, p);
  }

  @Test
  void preservesInfiniteLoopReachability() throws Exception {
    String source = "public class Sample {public static int run(){while(true){return 3;}}}";
    assertEquivalent(source, instrument(source));
  }

  @Test
  void rejectsUnsupportedSyntaxWithOriginalRanges() {
    for (String body :
        List.of(
            "try {return 1;}finally{}",
            "switch(1){default:return 2;}",
            "Runnable r=()->{};return 0;",
            "outer: while(true){break outer;}return 0;")) {
      String source = "public class Sample {public static int run(){" + body + "}}";
      assertThatThrownBy(() -> instrument(source))
          .isInstanceOf(OperationCoverageException.class)
          .hasMessageContaining("1:");
    }
  }

  @Test
  void preservesConstantExpressionReachabilityAndDefiniteAssignment() throws Exception {
    List<String> bodies =
        List.of(
            "do {return 3;}while(true);",
            "int i=0; do {if(++i==3)return i;}while(true);",
            "int i=0; do {if(++i<3)continue;return i;}while(true);",
            "while(1<2){return 4;}",
            "final boolean yes=true; int n; if(yes)n=5;return n;",
            "final boolean yes=true; for(;yes;){return 6;}");
    for (int i = 0; i < bodies.size(); i++) {
      String source = "public class Sample {public static int run(){" + bodies.get(i) + "}}";
      OperationProgram p = instrument(source);
      assertThat(run(source, "constant-original" + i))
          .isEqualTo(run(p.sourceCode(), "constant-instrumented" + i));
    }
  }

  @Test
  void resolvesQualifiedConstantFieldsIndependentlyOfLocalShadowing() throws Exception {
    String source =
        "public class Sample {static final boolean YES=true;"
            + "static int f(){boolean YES=false;while(Sample.YES){}}"
            + "public static int run(){return 7;}}";
    assertEquivalent(source, instrument(source));
  }

  @Test
  void recordsEveryExecutedConstantLoopConditionIncludingContinue() throws Exception {
    String source =
        "public class Sample {public static int run(){int i=0;do"
            + " {if(++i<3)continue;break;}while(true);while(true){if(++i<5)continue;break;}for(;true;){if(++i<7)continue;break;}return"
            + " i;}}";
    OperationProgram p = instrument(source);
    String logged =
        p.sourceCode()
            .replace("class Sample {", "class Sample { public static String __events=\"\";")
            .replace("return value;", "__events += id + \",\"; return value;");
    Path folder = Files.createDirectory(directory.resolve("constant-events"));
    Class<?> type = compile(logged, folder);
    assertThat(type.getMethod("run").invoke(null)).isEqualTo(7);
    List<Integer> events =
        java.util.Arrays.stream(((String) type.getField("__events").get(null)).split(","))
            .map(Integer::valueOf)
            .toList();
    assertThat(
            events.stream()
                .map(p.sites()::get)
                .filter(s -> s.kind().equals("LOOP_CONDITION") && s.phase().equals("AFTER"))
                .count())
        .isEqualTo(6);
  }

  @Test
  void keepsSiblingScopeVisibilitySeparateOnOneLine() {
    OperationProgram p =
        instrument(
            "public class Sample {public static int run(){int x=1;{int y=x;x=y;}{int"
                + " y=x;x=y+1;}return x;}}");
    var declared =
        p.sites().values().stream()
            .filter(s -> s.kind().equals("DECLARATION") && s.phase().equals("AFTER"))
            .toList();
    String firstSibling = declared.get(1).attributes().get("declarationId");
    String secondSibling = declared.get(2).attributes().get("declarationId");
    assertThat(declared.get(2).visibleDeclarationIds())
        .contains(secondSibling)
        .doesNotContain(firstSibling);
    assertThat(
            p.sites().values().stream()
                .filter(s -> s.kind().equals("RETURN"))
                .findFirst()
                .orElseThrow()
                .visibleDeclarationIds())
        .containsExactly(declared.get(0).attributes().get("declarationId"));
  }

  @Test
  void copiesRecordMetadataDefensively() {
    var ids = new java.util.ArrayList<String>();
    ids.add("declaration");
    var scopes = new java.util.ArrayList<String>();
    scopes.add("scope");
    var attrs = new java.util.LinkedHashMap<String, String>();
    attrs.put("key", "value");
    var original =
        instrument("public class Sample {public static int run(){return 1;}}")
            .sites()
            .values()
            .iterator()
            .next();
    OperationSite site =
        new OperationSite(
            1, "operation", "BEFORE", "RETURN", original.source(), ids, scopes, attrs);
    var sites = new java.util.LinkedHashMap<Integer, OperationSite>();
    sites.put(1, site);
    OperationProgram program = new OperationProgram("source", "helper", sites);
    ids.clear();
    scopes.clear();
    attrs.clear();
    sites.clear();
    assertThat(site.visibleDeclarationIds()).containsExactly("declaration");
    assertThat(site.scopePath()).containsExactly("scope");
    assertThat(site.attributes()).containsEntry("key", "value");
    assertThat(program.sites()).containsKey(1);
    assertThatThrownBy(() -> program.sites().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void givesEachEnhancedForBindingItsOwnScopeAndCompletesIterableEvaluation() throws Exception {
    String source =
        "public class Sample {static int calls; static int[] values(){calls++;return new"
            + " int[]{1,2,3};}public static int run(){int sum=0;for(int"
            + " value:values()){if(value==2)continue;sum+=value;}return sum*10+calls;}}";
    OperationProgram p = instrument(source);
    OperationSite binding =
        p.sites().values().stream()
            .filter(site -> site.kind().equals("LOOP_BINDING"))
            .findFirst()
            .orElseThrow();
    assertThat(binding.attributes().get("scopeId"))
        .isEqualTo(binding.attributes().get("declarationId"));
    assertThat(binding.scopePath()).contains(binding.attributes().get("scopeId"));
    assertThat(p.sites().values())
        .anySatisfy(
            site -> {
              assertThat(site.kind()).isEqualTo("LOOP_INIT");
              assertThat(site.phase()).isEqualTo("AFTER");
            });
    assertEquivalent(source, p);
  }

  @Test
  void preservesExceptionTypeAndSideEffectsWithoutFabricatingAfter() throws Exception {
    String source =
        "public class Sample {public static int effects;public static int run(){int[]"
            + " a={1};a[effects++]=3;a[effects++]=4;return effects;}}";
    OperationProgram p = instrument(source);
    Class<?> original =
        compile(source, Files.createDirectory(directory.resolve("exception-original")));
    String logged =
        p.sourceCode()
            .replace("class Sample {", "class Sample { public static String __events=\"\";")
            .replace("return value;", "__events += id + \",\"; return value;");
    Class<?> changed =
        compile(logged, Files.createDirectory(directory.resolve("exception-instrumented")));
    for (Class<?> type : List.of(original, changed)) {
      assertThatThrownBy(() -> type.getMethod("run").invoke(null))
          .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
          .hasCauseInstanceOf(ArrayIndexOutOfBoundsException.class);
      assertThat(type.getField("effects").get(null)).isEqualTo(2);
    }
    List<Integer> events =
        java.util.Arrays.stream(((String) changed.getField("__events").get(null)).split(","))
            .map(Integer::valueOf)
            .toList();
    assertThat(
            events.stream()
                .map(p.sites()::get)
                .filter(site -> site.kind().equals("ARRAY_WRITE") && site.phase().equals("AFTER"))
                .count())
        .isEqualTo(1);
  }

  private Class<?> compile(String source, Path folder) throws Exception {
    Path file = folder.resolve("Sample.java");
    Files.writeString(file, source);
    var output = new java.io.ByteArrayOutputStream();
    int exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                output,
                output,
                "--release",
                "17",
                "-g",
                "-d",
                folder.toString(),
                file.toString());
    assertThat(exit).as(output.toString() + "\n" + source).isZero();
    var loader = new URLClassLoader(new java.net.URL[] {folder.toUri().toURL()}, null);
    return loader.loadClass("Sample");
  }

  private void assertEquivalent(String source, OperationProgram p) throws Exception {
    assertThat(run(source, "original")).isEqualTo(run(p.sourceCode(), "instrumented"));
  }

  private Object run(String source, String name) throws Exception {
    Path folder = Files.createDirectory(directory.resolve(name));
    Path file = folder.resolve("Sample.java");
    Files.writeString(file, source);
    var output = new java.io.ByteArrayOutputStream();
    int exit =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                output,
                output,
                "--release",
                "17",
                "-g",
                "-d",
                folder.toString(),
                file.toString());
    assertThat(exit).as(output.toString() + "\n" + source).isZero();
    try (var loader = new URLClassLoader(new java.net.URL[] {folder.toUri().toURL()}, null)) {
      return loader.loadClass("Sample").getMethod("run").invoke(null);
    }
  }
}
