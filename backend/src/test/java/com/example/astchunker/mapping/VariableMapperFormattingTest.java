package com.example.astchunker.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.astchunker.model.ObservationPoint;
import com.example.astchunker.model.ObservationResult;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    verify(array, times(2)).getValues(0, 6);
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
    verify(array, times(2)).getValues(0, 100);
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

  @Test
  void formatsBoxedPrimitiveObjectsAsTheirValues() throws Exception {
    assertThat(mapSingle(boxed("java.lang.Integer", 43L, "12")).runtimeValue()).isEqualTo("12");
    assertThat(mapSingle(boxed("java.lang.Boolean", 44L, "true")).runtimeValue()).isEqualTo("true");
  }

  @Test
  void formatsArrayListFromItsBackingArrayWithoutInvokingTargetMethods() throws Exception {
    ArrayReference elements =
        array(
            "java.lang.Object[]",
            4,
            List.of(
                boxed("java.lang.Integer", 51L, "1"),
                boxed("java.lang.Integer", 52L, "2"),
                boxed("java.lang.Integer", 53L, "3")));
    ObjectReference list =
        objectWithFields("java.util.ArrayList", 50L, Map.of("size", primitive("3"), "elementData", elements));

    ObservationResult result = mapSingle(list);
    assertThat(result.runtimeValue()).isEqualTo("[1, 2, 3]");
    assertThat(result.visualType()).isEqualTo("collection");
    assertThat(result.visualValue()).isEqualTo(List.of(1, 2, 3));
  }

  @Test
  void formatsHashMapEntriesFromItsTableNodes() throws Exception {
    ObjectReference node =
        objectWithFields(
            "java.util.HashMap$Node",
            61L,
            new HashMap<>(
                Map.of(
                    "key", string("left"),
                    "value", boxed("java.lang.Integer", 62L, "10"))));
    ArrayReference table = sparseArray("java.util.HashMap$Node[]", 4, Map.of(2, node));
    ObjectReference map =
        objectWithFields("java.util.HashMap", 60L, Map.of("size", primitive("1"), "table", table));

    ObservationResult result = mapSingle(map);
    assertThat(result.runtimeValue()).isEqualTo("{\"left\": 10}");
    assertThat(result.visualType()).isEqualTo("map");
    assertThat(result.visualValue()).isEqualTo(List.of(Map.of("key", "left", "value", 10)));
  }

  @Test
  void formatsLeetCodeListNodesAsAChain() throws Exception {
    ObjectReference third = listNode(73L, "3", null);
    ObjectReference second = listNode(72L, "2", third);
    ObjectReference first = listNode(71L, "1", second);

    ObservationResult result = mapSingle(first);
    assertThat(result.runtimeValue()).isEqualTo("[1 -> 2 -> 3]");
    assertThat(result.visualType()).isEqualTo("linked-list");
    assertThat(result.visualValue())
        .isEqualTo(Map.of("kind", "linked-list", "nodes", List.of(1, 2, 3), "cycle", false, "truncated", false));
  }

  @Test
  void formatsLeetCodeTreeNodesAsNestedObjects() throws Exception {
    ObjectReference left = treeNode(82L, "2", null, null);
    ObjectReference right = treeNode(83L, "3", null, null);
    ObjectReference root = treeNode(81L, "1", left, right);

    ObservationResult result = mapSingle(root);
    assertThat(result.runtimeValue())
        .isEqualTo(
            "{val: 1, left: {val: 2, left: null, right: null}, right: {val: 3, left: null, right: null}}");
    assertThat(result.visualType()).isEqualTo("tree");
    assertThat(result.visualValue()).isInstanceOf(Map.class);
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
            new ObservationPoint("statement", "demo.Sample", 1, 1, 1, 1, 20, "ExpressionStmt", "call();"),
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

  private ObjectReference objectWithFields(String typeName, long uniqueId, Map<String, Value> values) {
    ObjectReference object = object(typeName, uniqueId);
    ReferenceType type = object.referenceType();
    values.forEach(
        (name, value) -> {
          Field field = mock(Field.class);
          when(type.fieldByName(name)).thenReturn(field);
          when(object.getValue(field)).thenReturn(value);
        });
    return object;
  }

  private ObjectReference boxed(String typeName, long uniqueId, String value) {
    return objectWithFields(typeName, uniqueId, Map.of("value", primitive(value)));
  }

  private ObjectReference listNode(long uniqueId, String value, ObjectReference next) {
    Map<String, Value> fields = new HashMap<>();
    fields.put("val", primitive(value));
    if (next != null) {
      fields.put("next", next);
    }
    return objectWithFields("demo.ListNode", uniqueId, fields);
  }

  private ObjectReference treeNode(
      long uniqueId, String value, ObjectReference left, ObjectReference right) {
    Map<String, Value> fields = new HashMap<>();
    fields.put("val", primitive(value));
    if (left != null) {
      fields.put("left", left);
    }
    if (right != null) {
      fields.put("right", right);
    }
    return objectWithFields("demo.TreeNode", uniqueId, fields);
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

  private ArrayReference sparseArray(String typeName, int length, Map<Integer, Value> values) {
    ArrayReference array = mock(ArrayReference.class);
    ReferenceType type = mock(ReferenceType.class);
    when(type.name()).thenReturn(typeName);
    when(array.referenceType()).thenReturn(type);
    when(array.uniqueID()).thenReturn(nextArrayId++);
    when(array.length()).thenReturn(length);
    for (int index = 0; index < length; index++) {
      when(array.getValue(index)).thenReturn(values.get(index));
    }
    return array;
  }

  private StringReference string(String text) {
    StringReference value = mock(StringReference.class);
    when(value.value()).thenReturn(text);
    return value;
  }

  private Value asValue(Object value) {
    if (value == null || value instanceof Value) {
      return (Value) value;
    }
    return primitive(String.valueOf(value));
  }
}
