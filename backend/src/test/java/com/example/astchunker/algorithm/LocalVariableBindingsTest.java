package com.example.astchunker.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.astchunker.ast.AstAnalyzer;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

class LocalVariableBindingsTest {
  private CompilationUnit parse(String source) {
    return new AstAnalyzer().parseCompilationUnit(source);
  }

  @Test
  void distinguishesSiblingDeclarationsOnTheSameLine() {
    var unit = parse("class S { void f() { {int x=1; use(x);} {int x=2; use(x);} } }");
    var declarations = unit.findAll(VariableDeclarator.class);
    var references = unit.findAll(NameExpr.class);
    assertThat(LocalVariableBindings.resolve(references.get(0)))
        .contains(AstAnalyzer.nodeId(declarations.get(0)));
    assertThat(LocalVariableBindings.resolve(references.get(1)))
        .contains(AstAnalyzer.nodeId(declarations.get(1)));
  }

  @Test
  void bindsParameterFromNestedBlockButDoesNotCrossLambdaOrClassBoundary() {
    var unit =
        parse(
            "class S { void f(int x) { {use(x);} Runnable r=()->use(x); class T { void g(){use(x);} } } }");
    var refs =
        unit.findAll(NameExpr.class).stream().filter(n -> n.getNameAsString().equals("x")).toList();
    assertThat(LocalVariableBindings.resolve(refs.get(0))).isPresent();
    assertThat(LocalVariableBindings.resolve(refs.get(1))).isEmpty();
    assertThat(LocalVariableBindings.resolve(refs.get(2))).isEmpty();
  }

  @Test
  void loopInitializerIsVisibleInsideLoopButNotAfterIt() {
    var unit = parse("class S { int i; void f() { for(int i=0;i<3;i++){use(i);} use(i); } }");
    var refs =
        unit.findAll(NameExpr.class).stream().filter(n -> n.getNameAsString().equals("i")).toList();
    assertThat(refs.subList(0, 3))
        .allSatisfy(n -> assertThat(LocalVariableBindings.resolve(n)).isPresent());
    assertThat(LocalVariableBindings.resolve(refs.get(3))).isEmpty();
  }

  @Test
  void doesNotBindFieldUseToALocalDeclaredLaterOnTheSameLine() {
    var unit = parse("class S { int x; void f() { use(x); int x=1; use(x); } }");
    var refs = unit.findAll(NameExpr.class);
    assertThat(LocalVariableBindings.resolve(refs.get(0))).isEmpty();
    assertThat(LocalVariableBindings.resolve(refs.get(1)))
        .contains(AstAnalyzer.nodeId(unit.findAll(VariableDeclarator.class).get(1)));
  }

  @Test
  void catchParameterDoesNotEscapeItsHandler() {
    var unit =
        parse("class S { Object e; void f() { try {} catch(Exception e) {use(e);} use(e); } }");
    var refs = unit.findAll(NameExpr.class);
    assertThat(LocalVariableBindings.resolve(refs.get(0))).isPresent();
    assertThat(LocalVariableBindings.resolve(refs.get(1))).isEmpty();
  }

  @Test
  void methodsWithSameParameterNamesHaveDifferentBindings() {
    var unit = parse("class S { void f(int x){use(x);} void g(int x){use(x);} }");
    var refs = unit.findAll(NameExpr.class);
    assertThat(LocalVariableBindings.resolve(refs.get(0)))
        .isPresent()
        .isNotEqualTo(LocalVariableBindings.resolve(refs.get(1)));
  }
}
