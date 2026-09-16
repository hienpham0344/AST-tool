package com.example.astchunker.debug;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.mapping.VariableMapper;
import com.example.astchunker.model.ObservationPoint;
import com.example.astchunker.model.ObservationResult;
import com.example.astchunker.model.ExecutionObservation;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Launches one compiled target VM and collects local-variable observations through JDI.
 *
 * <p>The event loop uses a fixed deadline and polls the JDI queue. It never waits indefinitely for
 * a user program: on timeout the debuggee is terminated and the caller receives a warning.
 */
@Component
public class JdiSession {

  private static final String OBSERVATION_POINT_PROPERTY = "observationPoint";
  private static final long EVENT_POLL_MILLIS = 250;

  private final BreakpointMapper breakpointMapper;
  private final VariableMapper variableMapper;
  private final Duration timeout;

  @Autowired
  public JdiSession(BreakpointMapper breakpointMapper, VariableMapper variableMapper) {
    this(breakpointMapper, variableMapper, Duration.ofSeconds(30));
  }

  public JdiSession(
      BreakpointMapper breakpointMapper, VariableMapper variableMapper, Duration timeout) {
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("JDI observation timeout must be positive.");
    }
    this.breakpointMapper = breakpointMapper;
    this.variableMapper = variableMapper;
    this.timeout = timeout;
  }

  public DebugRun observe(TargetCompiler.CompiledTarget target, AstAnalyzer.Analysis analysis) {
    VirtualMachine virtualMachine = null;
    try {
      virtualMachine = launch(target);
      EventRequestManager requestManager = virtualMachine.eventRequestManager();
      ClassPrepareRequest classPrepareRequest = requestManager.createClassPrepareRequest();
      classPrepareRequest.addClassFilter(target.mainClassName());
      classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      classPrepareRequest.enable();

      List<ExecutionObservation> executions = new ArrayList<>();
      List<String> warnings = new ArrayList<>();
      Set<ReferenceType> installedTypes = new HashSet<>();
      installAlreadyPreparedTargetType(
          virtualMachine, requestManager, analysis, installedTypes, warnings);

      // CommandLineLaunch returns a suspended VM. Resuming only after the ClassPrepareRequest is
      // active guarantees that a lazily-loaded target class cannot miss breakpoint installation.
      virtualMachine.resume();
      runEventLoop(
          virtualMachine,
          requestManager,
          analysis,
          installedTypes,
          executions,
          warnings);
      return new DebugRun(List.copyOf(executions), List.copyOf(warnings));
    } catch (VMDisconnectedException ex) {
      return new DebugRun(
          List.of(), List.of("Target VM disconnected before observation completed."));
    } finally {
      disposeQuietly(virtualMachine);
    }
  }

  private VirtualMachine launch(TargetCompiler.CompiledTarget target) {
    LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
    Map<String, Connector.Argument> arguments = connector.defaultArguments();
    Connector.Argument mainArgument = arguments.get("main");
    Connector.Argument optionsArgument = arguments.get("options");
    if (mainArgument == null || optionsArgument == null) {
      throw new DebugException("The installed JDI connector does not support launching a main class.");
    }

    mainArgument.setValue(target.mainClassName());
    optionsArgument.setValue(classPathOption(target.classesDirectory()));
    try {
      return connector.launch(arguments);
    } catch (IOException | IllegalConnectorArgumentsException | VMStartException ex) {
      throw new DebugException("Unable to launch the compiled target through JDI.", ex);
    }
  }

  private String classPathOption(Path classesDirectory) {
    return "-cp \"" + classesDirectory.toAbsolutePath() + "\"";
  }

  private void installAlreadyPreparedTargetType(
      VirtualMachine virtualMachine,
      EventRequestManager requestManager,
      AstAnalyzer.Analysis analysis,
      Set<ReferenceType> installedTypes,
      List<String> warnings) {
    for (ReferenceType referenceType : virtualMachine.classesByName(analysis.mainClassName())) {
      installBreakpoints(referenceType, requestManager, analysis, installedTypes, warnings);
    }
  }

  private void runEventLoop(
      VirtualMachine virtualMachine,
      EventRequestManager requestManager,
      AstAnalyzer.Analysis analysis,
      Set<ReferenceType> installedTypes,
      List<ExecutionObservation> executions,
      List<String> warnings) {
    Instant deadline = Instant.now().plus(timeout);
    boolean targetFinished = false;

    while (!targetFinished && Instant.now().isBefore(deadline)) {
      EventSet eventSet;
      try {
        eventSet = removeNextEventSet(virtualMachine);
      } catch (VMDisconnectedException ex) {
        warnings.add("Target VM disconnected before it produced another event.");
        return;
      }
      if (eventSet == null) {
        continue;
      }

      try {
        for (Event event : eventSet) {
          if (event instanceof ClassPrepareEvent classPrepareEvent) {
            installBreakpoints(
                classPrepareEvent.referenceType(),
                requestManager,
                analysis,
                installedTypes,
                warnings);
          } else if (event instanceof BreakpointEvent breakpointEvent) {
            captureVariables(breakpointEvent, analysis, executions, warnings);
          } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
            targetFinished = true;
          }
        }
      } finally {
        resumeQuietly(eventSet);
      }
    }

    if (!targetFinished && Instant.now().isAfter(deadline)) {
      warnings.add("JDI observation timed out after " + timeout.toSeconds() + " seconds.");
      stopTarget(virtualMachine);
    }
  }

  private EventSet removeNextEventSet(VirtualMachine virtualMachine) {
    try {
      return virtualMachine.eventQueue().remove(EVENT_POLL_MILLIS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new DebugException("JDI event loop was interrupted.", ex);
    }
  }

  private void installBreakpoints(
      ReferenceType referenceType,
      EventRequestManager requestManager,
      AstAnalyzer.Analysis analysis,
      Set<ReferenceType> installedTypes,
      List<String> warnings) {
    if (!referenceType.name().equals(analysis.mainClassName()) || !installedTypes.add(referenceType)) {
      return;
    }

    BreakpointMapper.MappingResult mappingResult =
        breakpointMapper.map(referenceType, analysis.observationPoints());
    warnings.addAll(mappingResult.warnings());
    for (BreakpointMapper.MappedBreakpoint mapping : mappingResult.mappings()) {
      BreakpointRequest request = requestManager.createBreakpointRequest(mapping.location());
      request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
      request.putProperty(OBSERVATION_POINT_PROPERTY, mapping.observationPoint());
      request.enable();
    }
  }

  private void captureVariables(
      BreakpointEvent breakpointEvent,
      AstAnalyzer.Analysis analysis,
      List<ExecutionObservation> executions,
      List<String> warnings) {
    Object property = breakpointEvent.request().getProperty(OBSERVATION_POINT_PROPERTY);
    if (!(property instanceof ObservationPoint observationPoint)) {
      warnings.add("A breakpoint event did not include its source observation metadata.");
      return;
    }

    ThreadReference eventThread = breakpointEvent.thread();
    try {
      // The frame comes from the event's own ThreadReference. This is essential when several target
      // threads hit independent breakpoints at approximately the same time.
      StackFrame frame = eventThread.frame(0);
      List<ObservationResult> variables =
          variableMapper.map(frame, observationPoint, analysis.variables());
      int lineNumber = observationPoint.lineNumber();
      try {
        lineNumber = breakpointEvent.location().lineNumber();
      } catch (RuntimeException ignored) {
        // Fall back to the AST start line when the bytecode location lacks source metadata.
      }
      executions.add(
          new ExecutionObservation(
              executions.size() + 1L,
              observationPoint.astNodeId(),
              lineNumber,
              observationPoint.statementKind(),
              variables));
    } catch (AbsentInformationException ex) {
      throw new DebugException(
          "Target local-variable debug information is unavailable. Compile the target with -g:vars.",
          ex);
    } catch (IncompatibleThreadStateException | InvalidStackFrameException ex) {
      warnings.add(
          "Unable to inspect source line "
              + observationPoint.lineNumber()
              + " on its event thread: "
              + ex.getMessage());
    }
  }

  private void stopTarget(VirtualMachine virtualMachine) {
    try {
      virtualMachine.exit(1);
    } catch (RuntimeException ignored) {
      // The process may already have terminated naturally between timeout detection and exit().
    }
  }

  private void resumeQuietly(EventSet eventSet) {
    try {
      eventSet.resume();
    } catch (VMDisconnectedException ignored) {
      // A VMDeathEvent/VMDisconnectEvent may make resume unnecessary.
    }
  }

  private void disposeQuietly(VirtualMachine virtualMachine) {
    if (virtualMachine == null) {
      return;
    }
    try {
      virtualMachine.dispose();
    } catch (VMDisconnectedException ignored) {
      // Normal after the target process exits.
    }
  }

  public record DebugRun(List<ExecutionObservation> executions, List<String> warnings) {

    /** Compatibility projection consumed by the existing CLI and /api/debug endpoint. */
    public List<ObservationResult> results() {
      return executions.stream().flatMap(execution -> execution.variables().stream()).toList();
    }
  }
}
