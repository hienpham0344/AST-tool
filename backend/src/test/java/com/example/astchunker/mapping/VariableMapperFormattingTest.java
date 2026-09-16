package com.example.astchunker.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.astchunker.model.ObservationPoint;
import com.example.astchunker.model.ObservationResult;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.CharValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariableMapperFormattingTest {

  private final VariableMapper mapper = new VariableMapper();
  private long nextArrayId = 1L;

  @Test
  void formatsPrimitiveValuesWithoutChangingTheirValueText() throws Exception {
    assertThat(mapSingle(primitive("10")).runtimeValue()).isEqualTo("10");
    assertThat(mapSingle(primitive("true")).runtimeValue()).isEqualTo("true");
    assertThat(mapSingle(primitive("3.14")).runtimeValue()).isEqualTo("3.14");
  }

  @Test
  void formatsCharValuesWithQuotesAndEscapes() throws Exception {
    CharValue value = mock(CharValue.class);
    when(value.value()).thenReturn('\n');

    assertThat(mapSingle(value).runtimeValue()).isEqualTo("'\\n'");
  }

  @Test
  void formatsAndEscapesStrings() throws Exception {
    StringReference value = mock(StringReference.class);
    when(value.value()).thenReturn("quote=\" slash=\\ line=\n tab=\t");

    assertThat(mapSingle(value).runtimeValue())
        .isEqualTo("\"quote=\\\" slash=\\\\ line=\\n tab=\\t\"");
  }

  @Test
  void escapesOtherControlCharactersInStringsAndChars() throws Exception {
    StringReference string = mock(StringReference.class);
    when(string.value()).thenReturn("nul=\0 control=\u0001");
    CharValue control = mock(CharValue.class);
    when(control.value()).thenReturn('\u0007');

    assertThat(mapSingle(string).runtimeValue()).isEqualTo("\"nul=\\u0000 control=\\u0001\"");
    assertThat(mapSingle(control).runtimeValue()).isEqualTo("'\\u0007'");
  }

  @Test
  void formatsSmallPrimitiveArraysByReadingOnlyTheirElements() throws Exception {
    ArrayReference array = array("int[]", 6, List.of("2", "1", "5", "1", "3", "2"));

    assertThat(mapSingle(array).runtimeValue()).isEqualTo("[2, 1, 5, 1, 3, 2]");
    verify(array).getValues(0, 6);
    verify(array, never()).getValues();
  }

  @Test
  void formatsBooleanDoubleAndCharArraysAsElements() throws Exception {
    CharValue newline = mock(CharValue.class);
    when(newline.value()).thenReturn('\n');

    assertThat(mapSingle(array("boolean[]", 2, List.of("true", "false"))).runtimeValue())
        .isEqualTo("[true, false]");
    assertThat(mapSingle(array("double[]", 2, List.of("3.14", "2.5"))).runtimeValue())
        .isEqualTo("[3.14, 2.5]");
    assertThat(mapSingle(array("char[]", 1, List.of(newline))).runtimeValue())
        .isEqualTo("['\\n']");
  }

  @Test
  void formatsObjectArraysWithStringsObjectsAndNullElements() throws Exception {
    ObjectReference object = object("demo.Foo", 42L);
    StringReference string = mock(StringReference.class);
    when(string.value()).thenReturn("abc");
    ArrayReference array = array("java.lang.Object[]", 3, Arrays.asList(string, object, null));

    assertThat(mapSingle(array).runtimeValue())
        .isEqualTo("[\"abc\", demo.Foo#42, null]");
  }

  @Test
  void formatsNestedArraysRecursively() throws Exception {
    ArrayReference first = array("int[]", 2, List.of("1", "2"));
    ArrayReference second = array("int[]", 2, List.of("3", "4"));
    ArrayReference outer = array("int[][]", 2, List.of(first, second));

    assertThat(mapSingle(outer).runtimeValue()).isEqualTo("[[1, 2], [3, 4]]");
  }

  @Test
  void truncatesLargeArraysBeforeReadingTheWholeArray() throws Exception {
    List<String> values = new ArrayList<>();
    for (int index = 0; index < 100; index++) {
      values.add(String.valueOf(index));
    }
    ArrayReference array = array("int[]", 10_000, values);

    String runtimeValue = mapSingle(array).runtimeValue();

    assertThat(runtimeValue).startsWith("[0, 1, 2,");
    assertThat(runtimeValue).endsWith(", ...] (length=10000)");
    verify(array).getValues(0, 100);
  }

  @Test
  void protectsAgainstSelfReferencingArrays() throws Exception {
    ArrayReference array = mock(ArrayReference.class);
    ReferenceType type = mock(ReferenceType.class);
    when(type.name()).thenReturn("java.lang.Object[]");
    when(array.referenceType()).thenReturn(type);
    when(array.uniqueID()).thenReturn(7L);
    when(array.length()).thenReturn(1);
    when(array.getValues(0, 1)).thenReturn(List.of(array));

    assertThat(mapSingle(array).runtimeValue()).contains("<cycle>");
  }

  @Test
  void truncatesLongStrings() throws Exception {
    StringReference value = mock(StringReference.class);
    when(value.value()).thenReturn("a".repeat(2_001));

    assertThat(mapSingle(value).runtimeValue()).isEqualTo("\"" + "a".repeat(2_000) + "...\" (length=2001)");
  }

  @Test
  void doesNotSplitSurrogatePairsWhenTruncatingStrings() throws Exception {
    StringReference value = mock(StringReference.class);
    when(value.value()).thenReturn("a".repeat(1_999) + "\uD83D\uDE00" + "z");

    assertThat(mapSingle(value).runtimeValue())
        .isEqualTo("\"" + "a".repeat(1_999) + "...\" (length=2002)");
  }

  @Test
  void rendersUnavailableStringReferencesWithoutFailingTheObservation() throws Exception {
    StringReference value = mock(StringReference.class);
    ReferenceType type = mock(ReferenceType.class);
    when(type.name()).thenReturn("java.lang.String");
    when(value.referenceType()).thenReturn(type);
    when(value.uniqueID()).thenReturn(9L);
    when(value.value()).thenThrow(new RuntimeException("collected"));

    assertThat(mapSingle(value).runtimeValue()).isEqualTo("java.lang.String#9[unavailable]");
  }

  @Test
  void keepsOrdinaryObjectsAsReferencesWithoutInvokingToString() throws Exception {
    ObjectReference object = object("demo.Foo", 42L);
    doThrow(new AssertionError("target toString must not be invoked")).when(object).toString();

    assertThat(mapSingle(object).runtimeValue()).isEqualTo("demo.Foo#42");
  }

  private ObservationResult mapSingle(Value value) throws Exception {
    StackFrame frame = mock(StackFrame.class);
    LocalVariable local = mock(LocalVariable.class);
    when(local.name()).thenReturn("value");
    when(local.typeName()).thenReturn("java.lang.Object");
    when(frame.visibleVariables()).thenReturn(List.of(local));
    when(frame.getValue(local)).thenReturn(value);

    return mapper
        .map(
            frame,
            new ObservationPoint("statement", "demo.Sample", 1, 1, 1, "ExpressionStmt"),
            List.of())
        .get(0);
  }

  private PrimitiveValue primitive(String text) {
    PrimitiveValue value = mock(PrimitiveValue.class);
    when(value.toString()).thenReturn(text);
    return value;
  }

  private ObjectReference object(String typeName, long uniqueId) {
    ObjectReference object = mock(ObjectReference.class);
    ReferenceType type = mock(ReferenceType.class);
    when(type.name()).thenReturn(typeName);
    when(object.referenceType()).thenReturn(type);
    when(object.uniqueID()).thenReturn(uniqueId);
    return object;
  }

  private ArrayReference array(String typeName, int length, List<?> values) {
    ArrayReference array = mock(ArrayReference.class);
    ReferenceType type = mock(ReferenceType.class);
    when(type.name()).thenReturn(typeName);
    when(array.referenceType()).thenReturn(type);
    when(array.uniqueID()).thenReturn(nextArrayId++);
    when(array.length()).thenReturn(length);
    List<Value> jdiValues = values.stream().map(this::asValue).toList();
    when(array.getValues(0, values.size())).thenReturn(jdiValues);
    return array;
  }

  private Value asValue(Object value) {
    if (value == null || value instanceof Value) {
      return (Value) value;
    }
    return primitive(String.valueOf(value));
  }
}
