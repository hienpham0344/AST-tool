package com.example.astchunker.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.astchunker.ast.AstAnalyzer;
import com.example.astchunker.mapping.VariableMapper;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class JdiSessionDisconnectTest {

  @Test
  void stopsTheEventLoopImmediatelyWhenTheTargetVmDisconnects() throws Exception {
    AstAnalyzer.Analysis analysis = new AstAnalyzer().analyze(sourceCode());
    VirtualMachine virtualMachine = mock(VirtualMachine.class);
    VirtualMachineManager virtualMachineManager = mock(VirtualMachineManager.class);
    LaunchingConnector connector = mock(LaunchingConnector.class);
    Connector.Argument mainArgument = mock(Connector.Argument.class);
    Connector.Argument optionsArgument = mock(Connector.Argument.class);
    EventRequestManager requestManager = mock(EventRequestManager.class);
    ClassPrepareRequest classPrepareRequest = mock(ClassPrepareRequest.class);
    EventQueue eventQueue = mock(EventQueue.class);

    when(connector.defaultArguments()).thenReturn(Map.of("main", mainArgument, "options", optionsArgument));
    when(connector.launch(Map.of("main", mainArgument, "options", optionsArgument)))
        .thenReturn(virtualMachine);
    when(virtualMachine.eventRequestManager()).thenReturn(requestManager);
    when(requestManager.createClassPrepareRequest()).thenReturn(classPrepareRequest);
    when(virtualMachine.classesByName(analysis.mainClassName())).thenReturn(List.of());
    when(virtualMachine.eventQueue()).thenReturn(eventQueue);
    when(eventQueue.remove(anyLong())).thenThrow(new VMDisconnectedException());

    JdiSession session =
        new JdiSession(new BreakpointMapper(), new VariableMapper(), Duration.ofMillis(200));
    TargetCompiler.CompiledTarget target =
        new TargetCompiler.CompiledTarget(
            Path.of("workspace"), Path.of("classes"), analysis.mainClassName());

    try (MockedStatic<Bootstrap> bootstrap = org.mockito.Mockito.mockStatic(Bootstrap.class)) {
      bootstrap.when(Bootstrap::virtualMachineManager).thenReturn(virtualMachineManager);
      when(virtualMachineManager.defaultConnector()).thenReturn(connector);

      JdiSession.DebugRun run = session.observe(target, analysis);

      assertThat(run.results()).isEmpty();
      assertThat(run.warnings()).contains("Target VM disconnected before it produced another event.");
      assertThat(run.warnings()).noneMatch(warning -> warning.startsWith("JDI observation timed out"));
    }
  }

  private String sourceCode() {
    return """
        public class DisconnectSample {
          public static void main(String[] args) {
            int value = 1;
          }
        }
        """;
  }
}
