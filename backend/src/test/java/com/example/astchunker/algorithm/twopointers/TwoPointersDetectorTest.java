package com.example.astchunker.algorithm.twopointers;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.algorithm.AlgorithmPatternAnalyzer;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.model.TwoPointersPattern;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.junit.jupiter.api.Test;

class TwoPointersDetectorTest {
  private final AstAnalyzer parser = new AstAnalyzer();
  private final TwoPointersDetector detector = new TwoPointersDetector();

  @Test
  void recognizesReversalWithRenamedPointersAndSourceIds() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          for (int a = 0, b = values.length - 1; a < b; a++, b--) {
            int temp = values[a]; values[a] = values[b]; values[b] = temp;
          }
        } }
        """);
    var hint = detector.detect(unit, unit.findFirst(ForStmt.class).orElseThrow()).orElseThrow();
    assertThat(hint.type()).isEqualTo("two-pointers");
    assertThat(hint.twoPointers().movement()).isEqualTo(TwoPointersPattern.Movement.OPPOSING);
    assertThat(hint.twoPointers().declarationIds()).containsKeys("data", "left", "right");
    assertThat(hint.twoPointers().operationAstNodeIds())
        .containsKeys("guard", "firstUpdate", "secondUpdate");
  }

  @Test
  void recognizesConditionalPairSumWithReversedGuard() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { boolean run(int[] values, int target) {
          int a = 0, b = values.length - 1;
          while (b > a) {
            int sum = values[a] + values[b];
            if (sum == target) return true;
            if (sum < target) a++; else b--;
          }
          return false;
        } }
        """);
    assertThat(detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow()))
        .get().extracting(h -> h.twoPointers().movement())
        .isEqualTo(TwoPointersPattern.Movement.OPPOSING);
  }

  @Test
  void recognizesListGetAndSetWithoutDependingOnNames() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(java.util.List<Integer> values) {
          int a = 0, b = values.size() - 1;
          while (a < b) {
            int x = values.get(a);
            values.set(a, values.get(b)); values.set(b, x);
            a++; b--;
          }
        } }
        """);
    var hint = detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow()).orElseThrow();
    assertThat(hint.twoPointers().representation()).isEqualTo("INTEGER_LIST");
  }

  @Test
  void rejectsIndependentReadsWithNoPairEffect() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int a = 0, b = values.length - 1;
          while (a < b) {
            int x = values[a];
            int y = values[b];
            a++; b--;
          }
        } }
        """);
    assertThat(detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow())).isEmpty();
  }

  @Test
  void doesNotStealBinarySearchOrSlidingWindowHints() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values, int target) {
          int left = 0, right = values.length - 1;
          while (left <= right) {
            int mid = (left + right) / 2;
            if (values[mid] == target) return;
            if (values[mid] < target) left = mid + 1;
            else right = mid - 1;
          }
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit))
        .extracting(h -> h.type()).containsExactly("binary-search");
  }

  @Test
  void rejectsAccessAfterPointerHasAlreadyMoved() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          while (left < right) {
            left++;
            int sum = values[left] + values[right];
            right--;
          }
        } }
        """);
    assertThat(detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow())).isEmpty();
  }

  @Test
  void rejectsGuardThatStartsFalseForDerivedEnds() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          while (left > right) {
            int sum = values[left] + values[right];
            left++; right--;
          }
        } }
        """);
    assertThat(detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow())).isEmpty();
  }

  @Test
  void recognizesPairEvenWhenRightAccessAppearsFirst() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          while (left < right) {
            int sum = values[right] + values[left];
            left++; right--;
          }
        } }
        """);
    var hint = detector.detect(unit, unit.findFirst(WhileStmt.class).orElseThrow()).orElseThrow();
    assertThat(hint.variables()).containsEntry("left", "left").containsEntry("right", "right");
  }

  @Test
  void unaryReadBeforeLoopDoesNotCountAsPointerMutation() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          int ignored = -left;
          while (left < right) {
            int sum = values[left] + values[right];
            left++; right--;
          }
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit))
        .extracting(h -> h.type()).containsExactly("two-pointers");
  }

  @Test
  void recognizesSameDirectionDeduplicationFromSecondElement() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int slow = 0;
          for (int scan = 1; scan < values.length; scan++) {
            if (values[scan] != values[slow]) {
              slow++;
              values[slow] = values[scan];
            }
          }
        } }
        """);
    var hint = detector.detect(unit, unit.findFirst(ForStmt.class).orElseThrow()).orElseThrow();
    assertThat(hint.twoPointers().movement())
        .isEqualTo(TwoPointersPattern.Movement.SAME_DIRECTION);
    assertThat(hint.variables()).containsEntry("scan", "scan").containsEntry("slow", "slow");
  }

  @Test
  void recognizesSameDirectionScanStartingAtZeroWhenInitialUpdateIsImpossible() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int slow = 0;
          for (int scan = 0; scan < values.length; scan++) {
            if (values[scan] != values[slow]) {
              slow++;
              values[slow] = values[scan];
            }
          }
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit))
        .extracting(h -> h.twoPointers().movement())
        .containsExactly(TwoPointersPattern.Movement.SAME_DIRECTION);
  }

  @Test
  void rejectsTwoIndicesThatAlwaysAdvanceTogether() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int slow = 0;
          for (int scan = 0; scan < values.length; scan++, slow++) {
            values[slow] = values[scan];
          }
        } }
        """);
    assertThat(detector.detect(unit, unit.findFirst(ForStmt.class).orElseThrow())).isEmpty();
  }

  @Test
  void recognizesSameDirectionIntegerList() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(java.util.List<Integer> values) {
          int slow = 0;
          for (int scan = 1; scan < values.size(); scan++) {
            if (!values.get(scan).equals(values.get(slow))) {
              slow++; values.set(slow, values.get(scan));
            }
          }
        } }
        """);
    var hint = detector.detect(unit, unit.findFirst(ForStmt.class).orElseThrow()).orElseThrow();
    assertThat(hint.twoPointers().movement())
        .isEqualTo(TwoPointersPattern.Movement.SAME_DIRECTION);
    assertThat(hint.twoPointers().representation()).isEqualTo("INTEGER_LIST");
  }

  @Test
  void rejectsDoWhileWithUnrelatedEntryGuard() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          if (values.length == 42) return;
          do {
            int sum = values[left] + values[right];
            left++; right--;
          } while (left < right);
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit)).isEmpty();
  }

  @Test
  void acceptsDoWhileOnlyAfterAProvenMinimumLengthGuard() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int left = 0, right = values.length - 1;
          if (values.length < 2) return;
          do {
            int sum = values[left] + values[right];
            left++; right--;
          } while (left < right);
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit))
        .extracting(h -> h.type()).containsExactly("two-pointers");
  }

  @Test
  void rejectsInclusiveScanBoundThatCanReadPastEnd() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample { void run(int[] values) {
          int slow = 0;
          for (int scan = 1; scan <= values.length; scan++) {
            if (values[scan] != values[slow]) {
              slow++; values[slow] = values[scan];
            }
          }
        } }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit)).isEmpty();
  }

  @Test
  void rejectsAUserDefinedListTypeWithSameMethodNames() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample {
          static class List<T> {
            int size() { return 3; }
            T get(int i) { return null; }
            T set(int i, T value) { return value; }
          }
          void run(List<Integer> values) {
            int slow = 0;
            for (int scan = 1; scan < values.size(); scan++) {
              if (!values.get(scan).equals(values.get(slow))) {
                slow++; values.set(slow, values.get(scan));
              }
            }
          }
        }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit)).isEmpty();
  }

  private void rejects(String body) {
    CompilationUnit unit = parser.parseCompilationUnit(
        "class Sample { void run(int[] a, int[] b, boolean flag) {" + body + "} }");
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit)).isEmpty();
  }

  @Test
  void rejectsPointerOrInputReassignmentThatInvalidatesTheInitialBound() {
    rejects("int l=0,r=a.length-1; l=-1; while(l<r){int sum=a[l]+a[r];l++;r--;}");
    rejects("int l=0,r=a.length-1; while(l<r){a=b;int sum=a[l]+a[r];l++;r--;}");
  }

  @Test
  void rejectsUnconditionalOrOvertakingSlowPointers() {
    rejects("int slow=0;for(int scan=0;scan<a.length;scan++){a[slow]=a[scan];slow++;}");
    rejects("int slow=0;for(int scan=1;scan<a.length;scan++){"
        + "if(a[scan]!=a[slow]){slow++;slow++;a[slow]=a[scan];}}");
  }

  @Test
  void rejectsShortCircuitUpdatesThatDoNotProveProgress() {
    rejects("int l=0,r=a.length-1;while(l<r){int sum=a[l]+a[r];"
        + "boolean changed=flag && l++>0;if(flag)r--;}");
  }

  @Test
  void rejectsUnaryIndexAndFrozenAlias() {
    rejects("int l=0,r=a.length-1;while(l<r){int sum=a[-l]+a[r];l++;r--;}");
    rejects("int l=0,r=a.length-1;int alias=l;while(l<r){"
        + "int sum=a[alias]+a[r];l++;r--;}");
  }

  @Test
  void rejectsUnresolvedSizeHelperWithSideEffects() {
    CompilationUnit unit = parser.parseCompilationUnit("""
        class Sample {
          static int size(int[] a) { a[0]++; return 0; }
          void run(int[] a) {
            int l=0,r=a.length-1;
            while(l<r){size(a);int sum=a[l]+a[r];l++;r--;}
          }
        }
        """);
    assertThat(new AlgorithmPatternAnalyzer().analyze(unit)).isEmpty();
  }
}
