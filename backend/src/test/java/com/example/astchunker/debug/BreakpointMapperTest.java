package com.example.astchunker.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.astchunker.model.ObservationPoint;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class BreakpointMapperTest {

  private final BreakpointMapper mapper = new BreakpointMapper();

  @Test
  void mapsAnObservationPointToTheFirstExecutableLocationOnItsLine() throws Exception {
    ReferenceType referenceType = mock(ReferenceType.class);
    Location first = mock(Location.class);
    Location second = mock(Location.class);
    ObservationPoint point = new ObservationPoint("stmt-12", "demo.Sample", 12, 12, 12, "ExpressionStmt");
    when(referenceType.name()).thenReturn("demo.Sample");
    when(referenceType.locationsOfLine(12)).thenReturn(List.of(first, second));

    BreakpointMapper.MappingResult result = mapper.map(referenceType, List.of(point));

    assertThat(result.mappings()).hasSize(1);
    assertThat(result.mappings().get(0).location()).isSameAs(first);
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void reportsAWarningWhenLineInformationIsUnavailable() throws Exception {
    ReferenceType referenceType = mock(ReferenceType.class);
    ObservationPoint point = new ObservationPoint("stmt-12", "demo.Sample", 12, 12, 12, "ExpressionStmt");
    when(referenceType.name()).thenReturn("demo.Sample");
    when(referenceType.locationsOfLine(12)).thenThrow(new AbsentInformationException());

    BreakpointMapper.MappingResult result = mapper.map(referenceType, List.of(point));

    assertThat(result.mappings()).isEmpty();
    assertThat(result.warnings()).singleElement().asString().contains("line information");
  }

  @Test
  void deduplicatesObservationPointsThatResolveToTheSameJdiLocation() throws Exception {
    ReferenceType referenceType = mock(ReferenceType.class);
    Location location = mock(Location.class);
    ObservationPoint first =
        new ObservationPoint("stmt-12-a", "demo.Sample", 12, 12, 12, "IfStmt");
    ObservationPoint second =
        new ObservationPoint("stmt-12-b", "demo.Sample", 12, 12, 12, "ExpressionStmt");
    when(referenceType.name()).thenReturn("demo.Sample");
    when(referenceType.locationsOfLine(12)).thenReturn(List.of(location));

    BreakpointMapper.MappingResult result = mapper.map(referenceType, List.of(first, second));

    assertThat(result.mappings()).singleElement().extracting(mapping -> mapping.observationPoint()).isEqualTo(first);
    assertThat(result.warnings()).singleElement().asString().contains("same JDI location");
  }

  @Test
  void searchesTheWholeAstRangeForAnExecutableLocation() throws Exception {
    ReferenceType referenceType = mock(ReferenceType.class);
    Location location = mock(Location.class);
    ObservationPoint point =
        new ObservationPoint("stmt-12", "demo.Sample", 12, 12, 14, "VariableDeclarationStmt");
    when(referenceType.name()).thenReturn("demo.Sample");
    when(referenceType.locationsOfLine(12)).thenReturn(List.of());
    when(referenceType.locationsOfLine(13)).thenReturn(List.of(location));

    BreakpointMapper.MappingResult result = mapper.map(referenceType, List.of(point));

    assertThat(result.mappings()).singleElement().extracting(mapping -> mapping.location()).isEqualTo(location);
    assertThat(result.warnings()).isEmpty();
  }
}
