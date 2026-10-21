package com.example.astchunker.debug.operation;

import com.example.astchunker.algorithm.LocalVariableBindings;
import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.debug.DebugException;
import com.example.astchunker.debug.TargetCompiler;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.*;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Captures the original caller frame at synthetic markers; never invokes target methods via JDI.
 */
@Component
public class OperationJdiSession {
  private final VariableMapper mapper;
  private final Duration timeout;
  private final int maxMarkers;
  private static final int MAX_VISIBLE_VARIABLES = 200;

  @Autowired
  public OperationJdiSession(VariableMapper mapper) {
    this(mapper, Duration.ofSeconds(30), 10000);
  }

  public OperationJdiSession(VariableMapper mapper, Duration timeout, int maxMarkers) {
    if (timeout.isZero() || timeout.isNegative() || maxMarkers < 1)
      throw new IllegalArgumentException("Invalid operation trace limits");
    this.mapper = mapper;
    this.timeout = timeout;
    this.maxMarkers = maxMarkers;
  }

  private record Invocation(long id, String method, String signature, int depth) {}

  public OperationRun observe(
      TargetCompiler.CompiledTarget target,
      AstAnalyzer.Analysis analysis,
      OperationProgram program) {
    VirtualMachine vm = null;
    List<OperationCapture> captures = new ArrayList<>();
    OperationTraceBudget budget = new OperationTraceBudget();
    List<String> warnings = new ArrayList<>();
    String status = "COMPLETE", reason = null;
    Map<Long, Deque<Invocation>> invocations = new HashMap<>();
    long nextInvocation = 0;
    Map<String, com.github.javaparser.ast.Node> nodes = new HashMap<>();
    analysis.compilationUnit().walk(n -> nodes.put(AstAnalyzer.nodeId(n), n));
    Map<Long, MethodDeclaration> methods = new HashMap<>();
    Map<String, AstVariable> variables = new HashMap<>();
    analysis.variables().forEach(v -> variables.put(v.astNodeId(), v));
    try {
      vm = launch(target);
      drain(vm.process().getInputStream());
      drain(vm.process().getErrorStream());
      var requests = vm.eventRequestManager();
      var entries = requests.createMethodEntryRequest();
      entries.addClassFilter(target.mainClassName());
      entries.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      entries.enable();
      var exits = requests.createMethodExitRequest();
      exits.addClassFilter(target.mainClassName());
      exits.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      exits.enable();
      var exceptions = requests.createExceptionRequest(null, false, true);
      exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      exceptions.enable();
      Instant deadline = Instant.now().plus(timeout);
      vm.resume();
      boolean done = false;
      while (!done) {
        if (Instant.now().isAfter(deadline)) {
          status = "TRUNCATED";
          reason = "TIMEOUT";
          warnings.add("Operation trace timed out after " + timeout.toSeconds() + " seconds.");
          break;
        }
        EventSet events = vm.eventQueue().remove(100);
        if (events == null) continue;
        try {
          for (Event event : events) {
            if (event instanceof MethodEntryEvent entry) {
              var thread = entry.thread();
              long threadId = thread.uniqueID();
              Deque<Invocation> stack =
                  invocations.computeIfAbsent(threadId, k -> new ArrayDeque<>());
              if (!entry.method().name().equals(program.helperMethodName())) {
                if (!entry.method().isSynthetic()
                    && !entry.method().isConstructor()
                    && !entry.method().isStaticInitializer())
                  stack.push(
                      new Invocation(
                          ++nextInvocation,
                          entry.method().name(),
                          entry.method().signature(),
                          thread.frameCount()));
                continue;
              }
              var arguments = thread.frame(0).getArgumentValues();
              if (arguments.size() != 2
                  || !(arguments.get(0) instanceof IntegerValue id)
                  || !(arguments.get(1) instanceof BooleanValue outcome))
                throw new DebugException("Invalid operation marker arguments");
              OperationSite site = program.sites().get(id.value());
              if (site == null || stack.isEmpty())
                throw new DebugException("Operation marker has no source/invocation mapping");
              if (captures.size() >= maxMarkers) {
                status = "TRUNCATED";
                reason = "MAX_MARKERS";
                done = true;
                break;
              }
              if (site.visibleDeclarationIds().size() > MAX_VISIBLE_VARIABLES) {
                status = "TRUNCATED";
                reason = "MAX_VISIBLE_VARIABLES";
                done = true;
                break;
              }
              StackFrame caller = thread.frame(1);
              var location = caller.location();
              Invocation invocation = stack.peek();
              if (!location.method().name().equals(invocation.method())
                  || !location.method().signature().equals(invocation.signature())
                  || invocation.depth() != thread.frameCount() - 1)
                throw new DebugException("Operation caller context is inconsistent");
              var sourceNode = nodes.get(site.source().astNodeId());
              if (sourceNode != null
                  && LocalVariableBindings.callable(sourceNode)
                      instanceof MethodDeclaration sourceMethod)
                methods.put(invocation.id(), sourceMethod);
              List<AstVariable> visible =
                  site.visibleDeclarationIds().stream()
                      .map(variables::get)
                      .filter(Objects::nonNull)
                      .toList();
              var raw = mapper.map(caller, site.source(), visible, site.source().lineNumber());
              List<ObservationResult> values = new ArrayList<>();
              for (ObservationResult value : raw) {
                if (site.visibleDeclarationIds().contains(value.astNodeId())) values.add(value);
                if (value.variableName().equals(site.attributes().get("returnTemporary")))
                  values.add(
                      new ObservationResult(
                          "@return:" + site.operationId(),
                          "$return",
                          value.declaredType(),
                          value.runtimeValue(),
                          value.visualType(),
                          value.visualValue(),
                          site.source().lineNumber(),
                          value.arrayObjectId(),
                          value.objectId()));
              }
              if (!budget.include(values, site.source().code(), site.attributes())) {
                status = "TRUNCATED";
                reason = "MAX_CAPTURE_" + budget.exceeded();
                done = true;
                break;
              }
              var context =
                  new ExecutionContext(
                      threadId,
                      thread.name(),
                      location.declaringType().name(),
                      location.method().name(),
                      location.method().signature(),
                      invocation.depth(),
                      location.codeIndex(),
                      invocation.id());
              Boolean condition =
                  site.phase().equals("AFTER")
                          && (site.kind().equals("CONDITION")
                              || site.kind().equals("LOOP_CONDITION"))
                      ? outcome.value()
                      : null;
              captures.add(
                  new OperationCapture(captures.size() + 1L, site, context, values, condition));
            } else if (event instanceof MethodExitEvent exit) {
              if (exit.method().name().equals(program.helperMethodName())
                  || exit.method().isSynthetic()
                  || exit.method().isConstructor()
                  || exit.method().isStaticInitializer()) continue;
              Deque<Invocation> stack = invocations.get(exit.thread().uniqueID());
              if (stack == null || stack.isEmpty()) continue;
              Invocation invocation = stack.pop();
              if (!invocation.method().equals(exit.method().name())
                  || !invocation.signature().equals(exit.method().signature()))
                throw new DebugException("Operation method-exit context is inconsistent");
              if (captures.size() >= maxMarkers) {
                status = "TRUNCATED";
                reason = "MAX_MARKERS";
                done = true;
                break;
              }
              MethodDeclaration source = methods.remove(invocation.id());
              // Scope markers carry precise original IDs; method exit supplies confirmed control
              // transfer.
              if (source != null) {
                var r = source.getRange().orElseThrow();
                var point =
                    new ObservationPoint(
                        AstAnalyzer.nodeId(source),
                        analysis.mainClassName(),
                        r.end.line,
                        r.begin.line,
                        r.end.line,
                        r.begin.column,
                        r.end.column,
                        "MethodDeclaration",
                        source.getDeclarationAsString(false, false, false));
                var site =
                    new OperationSite(
                        -1,
                        AstAnalyzer.nodeId(source) + ":METHOD_EXIT",
                        "EXIT",
                        "METHOD_EXIT",
                        point,
                        List.of(),
                        List.of(),
                        Map.of());
                var location = exit.location();
                var context =
                    new ExecutionContext(
                        exit.thread().uniqueID(),
                        exit.thread().name(),
                        analysis.mainClassName(),
                        invocation.method(),
                        invocation.signature(),
                        invocation.depth(),
                        location.codeIndex(),
                        invocation.id());
                captures.add(
                    new OperationCapture(captures.size() + 1L, site, context, List.of(), null));
              }
            } else if (event instanceof ExceptionEvent exception) {
              status = "FAILED";
              reason = "UNCAUGHT_EXCEPTION";
              warnings.add(
                  "Uncaught target exception: " + exception.exception().referenceType().name());
              done = true;
              break;
            } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
              if (invocations.values().stream().anyMatch(stack -> !stack.isEmpty())) {
                status = "FAILED";
                reason = "VM_TERMINATED_WITH_ACTIVE_INVOCATIONS";
              }
              done = true;
            }
          }
        } finally {
          try {
            events.resume();
          } catch (VMDisconnectedException ignored) {
          }
        }
      }
      if (!status.equals("COMPLETE") && warnings.isEmpty())
        warnings.add("Operation trace stopped: " + reason);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      status = "TRUNCATED";
      reason = "INTERRUPTED";
      warnings.add("Operation trace interrupted.");
    } catch (VMDisconnectedException disconnected) {
      status = "FAILED";
      reason = "VM_DISCONNECTED";
      warnings.add("Target disconnected before operation tracing completed.");
    } catch (AbsentInformationException | IncompatibleThreadStateException ex) {
      status = "FAILED";
      reason = "JDI_METADATA_UNAVAILABLE";
      warnings.add("Operation trace could not read the suspended frame.");
    } finally {
      if (vm != null)
        try {
          vm.dispose();
        } catch (VMDisconnectedException ignored) {
        }
    }
    return new OperationRun(
        captures,
        warnings,
        status,
        reason,
        Map.of(
            "maxMarkers",
            maxMarkers,
            "timeoutMillis",
            timeout.toMillis(),
            "maxVisibleVariables",
            MAX_VISIBLE_VARIABLES,
            "maxCaptureValues",
            OperationTraceBudget.MAX_VALUES,
            "maxCaptureTextChars",
            OperationTraceBudget.MAX_TEXT_CHARS));
  }

  private VirtualMachine launch(TargetCompiler.CompiledTarget target) {
    LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
    Map<String, Connector.Argument> args = connector.defaultArguments();
    args.get("main").setValue(target.mainClassName());
    args.get("options").setValue("-cp \"" + target.classesDirectory().toAbsolutePath() + "\"");
    try {
      return connector.launch(args);
    } catch (IOException | IllegalConnectorArgumentsException | VMStartException ex) {
      throw new DebugException("Unable to launch operation trace target", ex);
    }
  }

  private static void drain(java.io.InputStream input) {
    Thread thread =
        new Thread(
            () -> {
              try (input) {
                input.transferTo(OutputStream.nullOutputStream());
              } catch (IOException ignored) {
                /* VM closes its pipes on termination. */
              }
            },
            "operation-target-output");
    thread.setDaemon(true);
    thread.start();
  }
}
