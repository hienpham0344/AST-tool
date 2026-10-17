(function (root, factory) {
    const api = Object.freeze(factory());
    if (typeof module === 'object' && module.exports) module.exports = api;
    if (root) root.UnifiedVisualization = api;
}(typeof globalThis === 'undefined' ? this : globalThis, function () {
    'use strict';

    function safeHtml(value) {
        return String(value === null ? 'null' : value === undefined ? 'missing' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function sequenceKey(sequence) { return String(sequence); }

    function responseLookup(response) {
        const lookup = new Map();
        for (const step of Array.isArray(response && response.steps) ? response.steps : []) {
            const key = sequenceKey(step && step.sequence);
            if (!lookup.has(key)) lookup.set(key, step);
        }
        return lookup;
    }

    function rawTimeline(response, mode, message) {
        const steps = Array.isArray(response && response.steps) ? response.steps : [];
        return { mode, message: message || '', frames: steps, algorithmsById: new Map(), rawStepsBySequence: responseLookup(response) };
    }

    function createTimeline(response) {
        const visualization = response && response.visualization;
        if (visualization === null || visualization === undefined) return rawTimeline(response, 'legacy');
        if (!visualization || visualization.schemaVersion !== '1.0') {
            return rawTimeline(response, 'unsupported', `Unsupported visualization version: ${safeHtml(visualization && visualization.schemaVersion)}`);
        }
        if (!Array.isArray(visualization.algorithms) || !Array.isArray(visualization.frames) ||
            visualization.frames.some(frame => !frame || typeof frame !== 'object' || frame.sequence === null || frame.sequence === undefined)) {
            return rawTimeline(response, 'unsupported', 'Malformed visualization 1.0: algorithms and frames must be arrays.');
        }
        const algorithmsById = new Map();
        for (const algorithm of visualization.algorithms) {
            if (algorithm && algorithm.id !== null && algorithm.id !== undefined && !algorithmsById.has(String(algorithm.id))) {
                algorithmsById.set(String(algorithm.id), algorithm);
            }
        }
        return { mode: 'normalized', message: '', frames: visualization.frames, algorithmsById, rawStepsBySequence: responseLookup(response) };
    }

    function renderSelector(timeline, selectedAlgorithmId) {
        if (!timeline || timeline.mode !== 'normalized') return '';
        const all = selectedAlgorithmId === null || selectedAlgorithmId === undefined;
        const options = [`<option value=""${all ? ' selected' : ''}>All</option>`];
        for (const [id, algorithm] of timeline.algorithmsById) {
            const selected = !all && String(selectedAlgorithmId) === id ? ' selected' : '';
            const label = `${algorithm.type === undefined ? 'unknown' : algorithm.type} (${id})`;
            options.push(`<option value="${safeHtml(id)}"${selected}>${safeHtml(label)}</option>`);
        }
        return `<label class="unified-selector">Algorithm <select id="algorithmSelector" aria-label="Algorithm selector">${options.join('')}</select></label>`;
    }

    function isCapturedIndex(index, values, actualLength) {
        return Number.isInteger(index) && index >= 0 && index < values.length &&
            (Number.isInteger(actualLength) ? index < actualLength : true);
    }

    function fieldLines(title, record) {
        return Object.entries(record && typeof record === 'object' ? record : {}).map(([role, item]) => {
            const value = item && typeof item === 'object' ? item.value : undefined;
            const index = item && typeof item === 'object' ? item.index : undefined;
            const status = item && typeof item === 'object' ? item.status : 'MISSING';
            const identity = item && typeof item === 'object' ? `${item.variableName === undefined ? 'missing' : item.variableName} · ${item.astNodeId === undefined ? 'missing' : item.astNodeId}` : 'missing';
            const shown = index === undefined ? value : index;
            return `<li><b>${safeHtml(role)}</b>: ${safeHtml(identity)} = ${safeHtml(shown)} <em>${safeHtml(status)}</em></li>`;
        }).join('');
    }

    function renderArray(state) {
        const array = state && state.array;
        if (!array || array.status !== 'AVAILABLE' || !Array.isArray(array.values)) {
            return `<p class="unified-array">array unavailable: ${safeHtml(array && array.status)}</p>`;
        }
        const values = array.values;
        const actualLength = Number.isInteger(array.length) && array.length >= 0 ? array.length : null;
        const highlights = new Map();
        const pointerMarkers = new Map();
        for (const highlight of Array.isArray(state.highlights) ? state.highlights : []) {
            const pointer = highlight && highlight.kind === 'POINTER' ? state.pointers && state.pointers[highlight.role] : null;
            if (highlight && highlight.kind === 'POINTER' && (!pointer || pointer.status !== 'VALID' || pointer.index !== highlight.index)) continue;
            if (highlight && isCapturedIndex(highlight.index, values, actualLength)) {
                const list = highlights.get(highlight.index) || [];
                list.push(highlight.kind === undefined ? 'highlight' : highlight.kind);
                highlights.set(highlight.index, list);
                if (pointer) {
                    const markers = pointerMarkers.get(highlight.index) || [];
                    markers.push(highlight.role);
                    pointerMarkers.set(highlight.index, markers);
                }
            }
        }
        const cells = values.map((value, index) => `<span class="unified-cell" data-index="${index}"><small>${index}</small> value ${safeHtml(value)}${highlights.has(index) ? ` <i>${safeHtml(highlights.get(index).join(', '))}</i>` : ''}${pointerMarkers.has(index) ? ` <i>marker ${safeHtml(pointerMarkers.get(index).join(', '))}</i>` : ''}</span>`).join('');
        const lengthText = actualLength === null ? 'actual length unknown' : `captured ${values.length} of actual length ${actualLength}`;
        return `<div class="unified-array"><p>${safeHtml(array.variableName)} · ${safeHtml(array.status)} · ${lengthText} · truncated ${safeHtml(array.truncated)}</p>${values.length ? `<div class="unified-cells" role="list">${cells}</div>` : '<p>empty captured array</p>'}</div>`;
    }

    function renderRange(state) {
        const range = state && state.range;
        if (!range) return '';
        const array = state.array || {};
        const values = Array.isArray(array.values) ? array.values : [];
        const actualLength = Number.isInteger(array.length) && array.length >= 0 ? array.length : null;
        const drawable = array.status === 'AVAILABLE' && Array.isArray(array.values) && actualLength !== null &&
            (range.status === 'VALID' || range.status === 'EMPTY') && Number.isInteger(range.start) && Number.isInteger(range.endExclusive) &&
            range.start >= 0 && range.endExclusive >= range.start && range.endExclusive <= values.length &&
            (actualLength === null || range.endExclusive <= actualLength);
        return `<p class="unified-range">range ${safeHtml(range.kind)} ${safeHtml(range.status)}${drawable ? ` [${range.start}, ${range.endExclusive})` : ''}</p>`;
    }

    function displayValue(value) {
        if (value === null || value === undefined || typeof value !== 'object') return safeHtml(value);
        try { return safeHtml(JSON.stringify(value)); } catch (error) { return safeHtml(String(value)); }
    }

    function labeledValues(record, names) {
        return names.map(name => `${safeHtml(name)} ${displayValue(record && record[name])}`).join(' · ');
    }

    function renderOperations(operations) {
        if (!Array.isArray(operations) || operations.length === 0) return '<p class="unified-operations">operations: none supplied</p>';
        return `<ol class="unified-operations">${operations.map(operation => {
            const provenance = operation && operation.provenance;
            return `<li>${labeledValues(operation, ['type', 'kind', 'firstIndex', 'secondIndex', 'firstValue', 'secondValue', 'fromIndex', 'toIndex', 'value', 'swapRequired', 'action', 'actionRequired', 'lifecycle', 'sourceStatus'])}<br>provenance ${labeledValues(provenance, ['source', 'field', 'sequence', 'phase', 'snapshotPhase'])}</li>`;
        }).join('')}</ol>`;
    }

    function renderSortedRegion(state) {
        const region = state && state.sortedRegion;
        if (!region) return '';
        const array = state.array || {};
        const drawable = array.status === 'AVAILABLE' && Array.isArray(array.values) &&
            Number.isInteger(array.length) && array.length >= 0 &&
            ['VERIFIED_SORTED', 'NOT_SORTED', 'EMPTY'].includes(region.status) &&
            Number.isInteger(region.start) && Number.isInteger(region.endExclusive) &&
            region.start >= 0 && region.endExclusive >= region.start &&
            region.endExclusive <= array.length && region.endExclusive <= array.values.length;
        return `<p class="unified-region">sorted region ${safeHtml(region.status)} ${safeHtml(region.basis)}${drawable ? ` [${safeHtml(region.start)}, ${safeHtml(region.endExclusive)})` : ''} · ${labeledValues(region, ['violationIndex', 'previousValue', 'currentValue'])}</p>`;
    }

    function renderState(state, timeline) {
        const algorithm = state && state.algorithmId !== null && state.algorithmId !== undefined ? timeline.algorithmsById.get(String(state.algorithmId)) : null;
        const heading = algorithm ? `${algorithm.type} · ${algorithm.visualPlan === undefined ? 'plan missing' : algorithm.visualPlan}` : 'Unknown algorithm metadata';
        const notes = Array.isArray(state && state.notes) ? state.notes.map(safeHtml).join(' · ') : '';
        return `<section class="unified-card" data-state-id="${safeHtml(state && state.id)}"><h3>${safeHtml(heading)}</h3><p>state ${safeHtml(state && state.id)} · status ${safeHtml(state && state.status)} · phase ${safeHtml(state && state.phase)} · snapshot ${safeHtml(state && state.snapshotPhase)}</p>${renderArray(state || {})}<ul class="unified-pointers">${fieldLines('pointers', state && state.pointers)}</ul><ul class="unified-scalars">${fieldLines('scalars', state && state.scalars)}</ul>${renderRange(state || {})}${renderOperations(state && state.operations)}${renderSortedRegion(state || {})}${notes ? `<p class="unified-notes">${notes}</p>` : ''}</section>`;
    }

    function renderChanges(changes, context) {
        const hasContext = context !== null && context !== undefined;
        const contextText = context && typeof context === 'object'
            ? Object.entries(context).map(([key, value]) => `${safeHtml(key)} ${displayValue(value)}`).join(' · ')
            : displayValue(context);
        if ((!Array.isArray(changes) || changes.length === 0) && (!hasContext || !contextText)) return '';
        const rows = Array.isArray(changes) ? changes.map(change => `<li>stateId ${safeHtml(change && change.stateId)} · ${safeHtml(change && change.type)} · ${safeHtml(change && change.target)} · fromSequence ${displayValue(change && change.fromSequence)} · toSequence ${displayValue(change && change.toSequence)} · before ${displayValue(change && change.before)} → after ${displayValue(change && change.after)}</li>`).join('') : '';
        return `<section class="unified-changes"><h3>Current-frame changes (snapshot context only)</h3>${hasContext && contextText ? `<p>context ${contextText}</p>` : ''}<ul>${rows}</ul></section>`;
    }

    function renderFrame(timeline, sequence, selectedAlgorithmId) {
        const empty = { html: '', source: null, rawStep: undefined, stateCount: 0 };
        if (!timeline) return empty;
        const frame = timeline.frames.find(candidate => sequenceKey(candidate && candidate.sequence) === sequenceKey(sequence));
        const rawStep = timeline.rawStepsBySequence.get(sequenceKey(sequence));
        if (!frame) return { ...empty, rawStep };
        if (timeline.mode === 'unsupported') return { html: `<div class="empty">${timeline.message}</div>`, source: rawStep, rawStep, stateCount: 0 };
        if (timeline.mode === 'legacy') return { html: '', source: rawStep, rawStep, stateCount: 0 };
        const states = Array.isArray(frame.states) ? frame.states : [];
        const selected = selectedAlgorithmId === null || selectedAlgorithmId === undefined ? states : states.filter(state => state && String(state.algorithmId) === String(selectedAlgorithmId));
        const content = selected.length ? selected.map(state => renderState(state, timeline)).join('') : '<p class="unified-inactive">No selected algorithm state in this snapshot.</p>';
        return { html: `${content}${renderChanges(frame.changes, frame.context)}`, source: frame.source || null, rawStep, stateCount: selected.length };
    }

    return { createTimeline, responseLookup, renderSelector, renderFrame, safeHtml };
}));
