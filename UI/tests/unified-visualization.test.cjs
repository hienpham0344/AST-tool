const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ui = require('../unified-visualization.js');

test('exposes only the frozen public visualization helper contract', () => {
  assert.deepEqual(Object.keys(ui).sort(), ['createTimeline', 'renderFrame', 'renderSelector', 'responseLookup', 'safeHtml']);
  assert.ok(Object.isFrozen(ui));
});

function state(id, algorithmId, extra = {}) {
  return {
    id, algorithmId, status: 'READY',
    array: { variableName: 'nums', astNodeId: 'array-id', status: 'AVAILABLE', values: [1, null, 3], length: 3, truncated: false },
    pointers: { cursor: { variableName: 'i', astNodeId: 'i-id', index: 1, status: 'VALID' } },
    scalars: { target: { variableName: 'target', astNodeId: 'target-id', value: 0, status: 'AVAILABLE' } },
    range: { kind: 'WINDOW', start: 0, endExclusive: 2, status: 'VALID' },
    operations: [], highlights: [{ kind: 'POINTER', index: 1, role: 'cursor', source: 'pointer' }],
    notes: ['snapshot only'], ...extra
  };
}

function response(frames, algorithms) {
  return { steps: [{ sequence: '9', variables: [{ variableName: 'raw-nine' }] }, { sequence: 4, variables: [{ variableName: 'raw-four' }] }], visualization: { schemaVersion: '1.0', algorithms, frames } };
}

function dashboardHarness(fetchImpl = () => Promise.resolve({ ok: true, json: async () => ({ steps: [] }) })) {
  class Element {
    constructor() { this.innerHTML = ''; this.textContent = ''; this.hidden = false; this.value = ''; this.files = []; this.disabled = false; this.listeners = new Map(); }
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    emit(type) { return this.listeners.get(type)?.({ target: this }); }
    click() { return this.emit('click'); }
    setAttribute() {}
    querySelector() { return null; }
  }
  const elements = new Map();
  const document = { getElementById(id) { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); } };
  const html = fs.readFileSync(require.resolve('../dashboardPage.html'), 'utf8');
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
  const inline = scripts.at(-1)[1];
  class FormData { append() {} }
  class FileReader { readAsText() {} }
  const context = { document, console, UnifiedVisualization: ui, fetch: fetchImpl, FormData, FileReader, setInterval: () => 1, clearInterval() {}, Blob: class {}, URL: { createObjectURL() {}, revokeObjectURL() {} } };
  context.globalThis = context;
  vm.runInNewContext(`${inline}\nglobalThis.__dashboard = { renderExecutionSteps, updateExecutionStep, handleFileSelection, runRuntimeObservation, setExecution(data) { lastExecutionData = data; }, setSource(value) { lastSourceCode = value; }, state() { return { currentExecutionIndex, selectedAlgorithmId, mode: currentTimeline && currentTimeline.mode }; } };`, context);
  return { element: id => document.getElementById(id), dashboard: context.__dashboard };
}

test('renders every supported algorithm type with generic state evidence', () => {
  const types = ['bubble-sort', 'selection-sort', 'insertion-sort', 'binary-search', 'two-pointers', 'sliding-window'];
  const algorithms = types.map((type, index) => ({ id: `a-${index}`, type, visualPlan: 'generic-plan' }));
  const frames = [{ sequence: 9, source: { lineNumber: 7 }, states: algorithms.map((a, index) => state(`s-${index}`, a.id, index === 5 ? { range: { kind: 'VARIABLE_WINDOW', start: 1, endExclusive: 3, status: 'VALID' } } : {})).concat([
    state('fixed-window', 'a-5', { range: { kind: 'FIXED_SIZE_CANDIDATE', start: 0, endExclusive: 2, status: 'VALID' } })
  ]), changes: [] }];
  const output = ui.renderFrame(ui.createTimeline(response(frames, algorithms)), 9, null);
  for (const type of types) assert.match(output.html, new RegExp(type));
  assert.match(output.html, /value null/);
  assert.match(output.html, /target.*0/);
  assert.match(output.html, /range WINDOW.*\[0, 2\)/);
  assert.match(output.html, /VARIABLE_WINDOW/);
  assert.match(output.html, /FIXED_SIZE_CANDIDATE/);
});

test('preserves frame order and joins raw data by sequence rather than array position', () => {
  const frames = [{ sequence: 9, source: { lineNumber: 90 }, states: [], changes: [] }, { sequence: 4, source: { lineNumber: 40 }, states: [], changes: [] }];
  const timeline = ui.createTimeline(response(frames, []));
  assert.deepEqual(timeline.frames.map(frame => frame.sequence), [9, 4]);
  const rendered = ui.renderFrame(timeline, 4, null);
  assert.equal(rendered.source.lineNumber, 40);
  assert.equal(rendered.rawStep.variables[0].variableName, 'raw-four');
  assert.equal(ui.responseLookup({ steps: [{ sequence: 9, label: 'first' }, { sequence: '9', label: 'later' }] }).get('9').label, 'first');
});

test('selector defaults to All and filtering never assigns metadata by variable name', () => {
  const algorithms = [{ id: 'a', type: 'binary-search' }, { id: 'b', type: 'two-pointers' }];
  const frames = [{ sequence: 9, source: {}, states: [state('one', 'a'), state('unknown', null), state('two', 'b')], changes: [] }];
  const timeline = ui.createTimeline(response(frames, algorithms));
  assert.match(ui.renderSelector(timeline, null), /<option value="" selected>All<\/option>/);
  assert.match(ui.renderSelector(timeline, 'b'), /value="b" selected/);
  assert.equal(ui.renderFrame(timeline, 9, null).stateCount, 3);
  const filtered = ui.renderFrame(timeline, 9, 'b');
  assert.equal(filtered.stateCount, 1);
  assert.match(ui.renderFrame(timeline, 9, null).html, /Unknown algorithm metadata/);
});

test('keeps partial and invalid evidence honest without fabricated cells or markers', () => {
  const partial = state('p', 'a', {
    array: { variableName: 'nums', status: 'AVAILABLE', values: [7], length: 4, truncated: true },
    pointers: { bad: { variableName: 'bad', index: 2, status: 'NOT_CAPTURED' }, invalid: { variableName: 'x', index: 0, status: 'INVALID' } },
    scalars: { missing: { variableName: 'missing', value: null, status: 'MISSING' } },
    range: { kind: 'WINDOW', start: 0, endExclusive: 9, status: 'UNKNOWN' },
    highlights: [{ kind: 'POINTER', index: 3, role: 'bad' }, { kind: 'HIGHLIGHT', index: 0, role: 'visible' }]
  });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [partial], changes: [] }], [{ id: 'a', type: 'sliding-window' }])), 9, null).html;
  assert.match(html, /captured 1 of actual length 4.*truncated true/);
  assert.doesNotMatch(html, /data-index="[123]"/);
  assert.doesNotMatch(html, /marker bad/);
  assert.match(html, /missing.*null.*MISSING/);
  assert.match(html, /range WINDOW.*UNKNOWN/);
});

test('distinguishes empty, unavailable and malformed values; escapes response text', () => {
  const empty = state('e', 'safe', { array: { variableName: '<img>', status: 'AVAILABLE', values: [], length: 0, truncated: false }, scalars: { x: { variableName: '<script>', value: false, status: 'AVAILABLE' } } });
  const unavailable = state('u', 'safe', { array: { variableName: 'other', status: 'UNAVAILABLE' } });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [empty, unavailable], changes: [] }], [{ id: 'safe', type: '<b>type</b>' }])), 9, null).html;
  assert.match(html, /empty captured array/);
  assert.match(html, /array unavailable: UNAVAILABLE/);
  assert.match(html, /false/);
  assert.doesNotMatch(html, /<img>|<script>|<b>type<\/b>/);
  assert.match(ui.safeHtml('<&"\'>'), /&lt;&amp;&quot;&#39;&gt;/);
});

test('keeps operations and snapshot changes in supplied order without inferred swap or completion', () => {
  const ops = [
    { type: 'COMPARISON', kind: 'COMPARISON', lifecycle: 'UNKNOWN', sourceStatus: 'AVAILABLE_FROM_SNAPSHOT' },
    { type: 'MUTATION', kind: 'WRITE_LEFT', lifecycle: 'PENDING', sourceStatus: 'PENDING_BEFORE_LOCATION' },
    { type: 'MUTATION', kind: 'WRITE_RIGHT', lifecycle: 'OBSERVED', sourceStatus: 'OBSERVED_AFTER_WRITE' }
  ];
  const frame = { sequence: 9, source: {}, context: { thread: '<worker>', stackDepth: 2 }, states: [state('s', 'a', { operations: ops })], changes: [{ stateId: 's', type: 'ARRAY_CHANGED', target: 'nums', before: '<old>', after: '<new>' }] };
  const html = ui.renderFrame(ui.createTimeline(response([frame], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
  assert.ok(html.indexOf('COMPARISON') < html.indexOf('WRITE_LEFT') && html.indexOf('WRITE_LEFT') < html.indexOf('WRITE_RIGHT'));
  assert.match(html, /UNKNOWN/);
  assert.match(html, /stateId s.*ARRAY_CHANGED.*&lt;old&gt;/);
  assert.match(html, /context thread &lt;worker&gt;.*stackDepth 2/);
  assert.doesNotMatch(html, /inferred swap|algorithm complete/i);
});

test('returns legacy only when visualization is absent and rejects future or malformed v1 explicitly', () => {
  assert.equal(ui.createTimeline({ steps: [{ sequence: 1 }] }).mode, 'legacy');
  const future = ui.createTimeline({ steps: [{ sequence: 1 }], visualization: { schemaVersion: '2.0', frames: [{ sequence: 99 }] } });
  assert.equal(future.mode, 'unsupported');
  assert.match(future.message, /Unsupported visualization version: 2\.0/);
  assert.equal(future.frames[0].sequence, 1);
  const malformed = ui.createTimeline({ steps: [], visualization: { schemaVersion: '1.0', algorithms: [] } });
  assert.equal(malformed.mode, 'unsupported');
  assert.match(malformed.message, /Malformed visualization 1\.0/);
});

test('dashboard leaves variables empty when a normalized frame has no raw sequence match', () => {
  const harness = dashboardHarness();
  const payload = {
    steps: [{ sequence: 1, lineNumber: 1, code: 'wrong();', variables: [{ variableName: 'wrong-index-variable' }] }],
    visualization: { schemaVersion: '1.0', algorithms: [], frames: [{ sequence: 2, source: { lineNumber: 2 }, states: [], changes: [] }] }
  };
  harness.dashboard.setSource('first();\nnormalizedSource();');
  harness.dashboard.setExecution(payload);
  harness.dashboard.renderExecutionSteps(payload);
  harness.dashboard.updateExecutionStep();
  assert.match(harness.element('executionVariables').innerHTML, /No visible local variables/);
  assert.doesNotMatch(harness.element('executionVariables').innerHTML, /wrong-index-variable/);
  assert.match(harness.element('executionLocation').innerHTML, /normalizedSource/);
  assert.doesNotMatch(harness.element('executionLocation').innerHTML, /wrong\(\)/);
});

test('dashboard resets to an empty unsupported response instead of retaining a prior model', () => {
  const harness = dashboardHarness();
  const oldPayload = response([{ sequence: 9, source: { lineNumber: 1 }, states: [state('old', 'a')], changes: [] }], [{ id: 'a', type: 'binary-search' }]);
  harness.dashboard.setExecution(oldPayload);
  harness.dashboard.renderExecutionSteps(oldPayload);
  const emptyFuture = { steps: [], visualization: { schemaVersion: '2.0' } };
  const html = harness.dashboard.renderExecutionSteps(emptyFuture);
  assert.equal(harness.dashboard.state().currentExecutionIndex, -1);
  assert.equal(harness.dashboard.state().selectedAlgorithmId, null);
  assert.equal(harness.dashboard.state().mode, 'unsupported');
  assert.match(html, /Unsupported visualization version: 2\.0/);
});

test('dashboard file selection clears prior runtime cards immediately', () => {
  const harness = dashboardHarness();
  harness.element('results').innerHTML = '<section class="unified-card">old runtime card</section>';
  harness.element('sortVisualization').innerHTML = 'old visual';
  harness.element('fileInput').files = [];
  harness.dashboard.handleFileSelection();
  assert.doesNotMatch(harness.element('results').innerHTML, /old runtime card/);
});

test('renders complete ordered operation evidence and escaped provenance without inference', () => {
  const ops = [
    { type: 'COMPARISON', kind: 'COMPARISON', firstIndex: 0, secondIndex: null, firstValue: 0, secondValue: null, value: null, swapRequired: false, action: 'KEEP', actionRequired: false, lifecycle: 'UNKNOWN', sourceStatus: '<available>', provenance: { source: '<frame>', field: 'comparison', sequence: 9, phase: 'P', snapshotPhase: 'BEFORE_LOCATION' } },
    { type: 'MUTATION', kind: 'WRITE_LEFT', fromIndex: 0, toIndex: 1, value: { unsafe: '<value>' }, lifecycle: 'PENDING', sourceStatus: 'PENDING_BEFORE_LOCATION', provenance: { source: 'SortFrame', field: 'pendingMutation', sequence: 9, phase: 'P', snapshotPhase: 'BEFORE_LOCATION' } },
    { type: 'MUTATION', kind: 'WRITE_RIGHT', fromIndex: 1, toIndex: 0, value: false, lifecycle: 'OBSERVED', sourceStatus: 'OBSERVED_AFTER_WRITE', provenance: { source: 'SortFrame', field: 'completedMutation', sequence: 10, phase: 'Q', snapshotPhase: 'BEFORE_LOCATION' } }
  ];
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [state('ops', 'a', { operations: ops })], changes: [] }], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
  assert.ok(html.indexOf('COMPARISON') < html.indexOf('WRITE_LEFT') && html.indexOf('WRITE_LEFT') < html.indexOf('WRITE_RIGHT'));
  for (const evidence of ['firstIndex 0', 'secondIndex null', 'firstValue 0', 'swapRequired false', 'action KEEP', 'fromIndex 0', 'toIndex 1', 'value {&quot;unsafe&quot;:&quot;&lt;value&gt;&quot;}', 'sequence 10', 'completedMutation']) assert.match(html, new RegExp(evidence));
  assert.match(html, /UNKNOWN/);
  assert.doesNotMatch(html, /<available>|<frame>|<value>/);
});

test('requires a matching valid pointer before drawing a POINTER highlight', () => {
  const invalid = state('invalid-pointer', 'a', {
    pointers: { cursor: { variableName: 'i', astNodeId: 'i-id', index: 0, status: 'INVALID' } },
    highlights: [{ kind: 'POINTER', index: 0, role: 'cursor', source: 'pointer' }]
  });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [invalid], changes: [] }], [{ id: 'a', type: 'binary-search' }])), 9, null).html;
  assert.doesNotMatch(html, /<i>POINTER<\/i>|marker cursor/);
  assert.match(html, /cursor.*INVALID/);
});

test('preserves structured change values, context values and both event sequences', () => {
  const frame = { sequence: 9, source: {}, context: { captured: [2, null] }, states: [], changes: [{ stateId: 's', type: 'ARRAY_CHANGED', target: 'nums', fromSequence: 3, toSequence: 9, before: [2, null], after: [null, 2] }] };
  const html = ui.renderFrame(ui.createTimeline(response([frame], [])), 9, null).html;
  assert.match(html, /fromSequence 3.*toSequence 9/);
  assert.match(html, /before \[2,null\].*after \[null,2\]/);
  assert.match(html, /context captured \[2,null\]/);
});

test('keeps a valid range status but does not draw bounds for an unavailable array', () => {
  const unavailable = state('unavailable-range', 'a', {
    array: { variableName: 'nums', status: 'UNAVAILABLE', values: [1], length: 1, truncated: false },
    range: { kind: 'WINDOW', start: 0, endExclusive: 1, status: 'VALID' }
  });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [unavailable], changes: [] }], [{ id: 'a', type: 'sliding-window' }])), 9, null).html;
  assert.match(html, /range WINDOW VALID/);
  assert.doesNotMatch(html, /range WINDOW VALID \[0, 1\)/);
});

test('rejects a structurally invalid normalized frame instead of rendering it', () => {
  const timeline = ui.createTimeline({ steps: [{ sequence: 1 }], visualization: { schemaVersion: '1.0', algorithms: [], frames: [null] } });
  assert.equal(timeline.mode, 'unsupported');
  assert.match(timeline.message, /Malformed visualization 1\.0/);
});

test('an old request rejection cannot overwrite the selected file request UI', async () => {
  let rejectOld;
  let requestCount = 0;
  const harness = dashboardHarness(() => {
    requestCount += 1;
    if (requestCount === 1) return new Promise((resolve, reject) => { rejectOld = reject; });
    return new Promise(() => {});
  });
  const first = { name: 'First.java', size: 1 };
  const second = { name: 'Second.java', size: 1 };
  harness.element('fileInput').files = [first];
  const oldRequest = harness.dashboard.runRuntimeObservation();
  harness.element('fileInput').files = [second];
  harness.dashboard.handleFileSelection();
  const newRequest = harness.dashboard.runRuntimeObservation();
  rejectOld(new Error('old request failed'));
  await oldRequest;
  assert.match(harness.element('results').innerHTML, /Đang chạy target/);
  assert.equal(harness.element('debugBtn').disabled, true);
  assert.match(harness.element('hint').textContent, /Đang chạy target/);
  void newRequest;
});

test('file handoff re-enables the real runtime button without letting request A control request B', async () => {
  const pending = [];
  const harness = dashboardHarness(() => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const button = harness.element('debugBtn');
  const input = harness.element('fileInput');
  input.files = [{ name: 'First.java', size: 1 }];
  input.emit('change');
  button.click();
  assert.equal(button.disabled, true);
  input.files = [{ name: 'Second.java', size: 1 }];
  input.emit('change');
  assert.equal(button.disabled, false);
  const secondRequest = button.click();
  assert.equal(pending.length, 2);
  assert.equal(button.disabled, true);
  pending[0].reject(new Error('A failed'));
  await Promise.resolve();
  assert.equal(button.disabled, true);
  pending[1].resolve({ ok: true, json: async () => ({ steps: [], visualization: { schemaVersion: '1.0', algorithms: [], frames: [] } }) });
  await secondRequest;
  assert.equal(button.disabled, false);
});

const sortedRegionCases = [
  { name: 'UNKNOWN beyond capture', status: 'UNKNOWN', start: 0, endExclusive: 4, array: { status: 'AVAILABLE', values: [9], length: 4, truncated: true } },
  { name: 'UNKNOWN inside capture', status: 'UNKNOWN', start: 0, endExclusive: 1, array: { status: 'AVAILABLE', values: [9], length: 1, truncated: false } },
  { name: 'TRUNCATED inside capture', status: 'TRUNCATED', start: 0, endExclusive: 1, array: { status: 'AVAILABLE', values: [9], length: 4, truncated: true } },
  { name: 'verified beyond capture', status: 'VERIFIED_SORTED', start: 0, endExclusive: 4, array: { status: 'AVAILABLE', values: [9], length: 4, truncated: true } },
  { name: 'verified beyond actual length', status: 'VERIFIED_SORTED', start: 0, endExclusive: 2, array: { status: 'AVAILABLE', values: [1, 2], length: 1, truncated: false } },
  { name: 'unavailable array', status: 'VERIFIED_SORTED', start: 0, endExclusive: 1, array: { status: 'UNAVAILABLE', values: [9], length: 1, truncated: false } },
  { name: 'unknown actual length', status: 'VERIFIED_SORTED', start: 0, endExclusive: 1, array: { status: 'AVAILABLE', values: [9], length: null, truncated: false } },
  { name: 'negative bound', status: 'VERIFIED_SORTED', start: -1, endExclusive: 1, array: { status: 'AVAILABLE', values: [9], length: 1, truncated: false } },
  { name: 'reversed bounds', status: 'NOT_SORTED', start: 1, endExclusive: 0, array: { status: 'AVAILABLE', values: [9], length: 1, truncated: false } },
  { name: 'fractional bound', status: 'VERIFIED_SORTED', start: 0, endExclusive: 0.5, array: { status: 'AVAILABLE', values: [9], length: 1, truncated: false } },
  { name: 'future status', status: 'FUTURE', start: 0, endExclusive: 1, array: { status: 'AVAILABLE', values: [9], length: 1, truncated: false } }
];

for (const fixture of sortedRegionCases) {
  test(`sorted region keeps status and basis without drawing ${fixture.name}`, () => {
    const sample = state('region', 'a', { array: fixture.array, sortedRegion: { status: fixture.status, basis: 'RUNTIME_CHECK', start: fixture.start, endExclusive: fixture.endExclusive } });
    const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [sample], changes: [] }], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
    const regionHtml = html.match(/<p class="unified-region">(.*?)<\/p>/)[1];
    assert.match(regionHtml, new RegExp(`sorted region ${fixture.status} RUNTIME_CHECK`));
    assert.doesNotMatch(regionHtml, /\[[^\]]*, [^\]]*\)/);
  });
}

test('sorted region shows eligible verified captured bounds without claiming completion', () => {
  const sample = state('region', 'a', { array: { status: 'AVAILABLE', values: [1, 2], length: 4, truncated: true }, sortedRegion: { status: 'VERIFIED_SORTED', basis: 'PREFIX_RUNTIME_CHECK', start: 0, endExclusive: 2 } });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [sample], changes: [] }], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
  assert.match(html, /sorted region VERIFIED_SORTED PREFIX_RUNTIME_CHECK \[0, 2\)/);
  assert.doesNotMatch(html, /algorithm complete|finished|success/i);
});

test('sorted region retains NOT_SORTED zero and null inversion evidence', () => {
  const sample = state('region', 'a', { sortedRegion: { status: 'NOT_SORTED', basis: 'RUNTIME_CHECK', start: 0, endExclusive: 3, violationIndex: 0, previousValue: 0, currentValue: null } });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [sample], changes: [] }], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
  assert.match(html, /sorted region NOT_SORTED RUNTIME_CHECK \[0, 3\)/);
  assert.match(html, /violationIndex 0.*previousValue 0.*currentValue null/);
});

test('sorted region safely preserves structured inversion evidence and untrusted labels', () => {
  const sample = state('region', 'a', { sortedRegion: { status: '<status>', basis: '<basis>', start: 0, endExclusive: 3, violationIndex: { supplied: '<index>' }, previousValue: ['<img>', null], currentValue: { text: '<script>', zero: 0 } } });
  const html = ui.renderFrame(ui.createTimeline(response([{ sequence: 9, source: {}, states: [sample], changes: [] }], [{ id: 'a', type: 'bubble-sort' }])), 9, null).html;
  assert.match(html, /sorted region &lt;status&gt; &lt;basis&gt;/);
  assert.match(html, /violationIndex \{&quot;supplied&quot;:&quot;&lt;index&gt;&quot;\}/);
  assert.match(html, /previousValue \[&quot;&lt;img&gt;&quot;,null\]/);
  assert.match(html, /currentValue \{&quot;text&quot;:&quot;&lt;script&gt;&quot;,&quot;zero&quot;:0\}/);
  assert.doesNotMatch(html, /<status>|<basis>|<index>|<img>|<script>|\[object Object\]/);
});
