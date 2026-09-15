package com.example.astchunker.service;

import com.example.astchunker.dto.AstNode;
import com.example.astchunker.exception.CodeParseException;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AstService {

  private static final String SNIPPET_WRAPPER_PREFIX = "class __AstSnippet { void __parse() {";
  private static final String SNIPPET_WRAPPER_SUFFIX = "} }";

  private final JavaParser javaParser;
  private final AstConverter astConverter;

  public AstService() {
    ParserConfiguration config =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
    this.javaParser = new JavaParser(config);
    this.astConverter = new AstConverter();
  }

  public AstNode parse(String sourceCode) {
    ParseResult<CompilationUnit> sourceResult = javaParser.parse(sourceCode);
    if (sourceResult.isSuccessful() && sourceResult.getResult().isPresent()) {
      return astConverter.convert(sourceResult.getResult().get());
    }

    String wrappedSource = SNIPPET_WRAPPER_PREFIX + sourceCode + SNIPPET_WRAPPER_SUFFIX;
    ParseResult<CompilationUnit> snippetResult = javaParser.parse(wrappedSource);
    if (snippetResult.isSuccessful() && snippetResult.getResult().isPresent()) {
      return convertSnippet(snippetResult.getResult().get());
    }

    List<String> problems = new ArrayList<>();
    for (Problem problem : sourceResult.getProblems()) {
      problems.add(formatProblem(problem));
    }
    for (Problem problem : snippetResult.getProblems()) {
      problems.add(formatProblem(problem));
    }

    throw new CodeParseException("Unable to parse Java source code", "INVALID_JAVA_CODE", problems);
  }

  private AstNode convertSnippet(CompilationUnit compilationUnit) {
    AstNode root = astConverter.createNode(compilationUnit);
    compilationUnit
        .findFirst(MethodDeclaration.class, method -> "__parse".equals(method.getNameAsString()))
        .flatMap(MethodDeclaration::getBody)
        .map(BlockStmt::getStatements)
        .ifPresent(
            statements ->
                statements.forEach(statement -> root.addChild(astConverter.convert(statement))));
    return root;
  }

  private String formatProblem(Problem problem) {
    return problem
        .getLocation()
        .map(
            loc ->
                "Dong "
                    + loc.toRange().map(r -> r.begin.line).orElse(-1)
                    + ": "
                    + problem.getMessage())
        .orElse(problem.getMessage());
  }
}
