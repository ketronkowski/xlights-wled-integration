// Boundary finder: light one port in measuring colors and walk each model step by step:
// first find its start pixel (which also gives the spare pixels before it), then each
// side's end corner in wiring order, then review the whole frame and save.
//
// A plan is { controllers: [{ name, ip, ports: [{ port, label, items: [...] }] }] }.
// Each item is { name, kind, sides: [counts in wiring order], sideNames, first, saved }.
// "first" is the port-relative index of the item's first pixel; everything else
// (last pixel, spare pixels before/after) is derived from first + sides.

const COLORS = {
  spare: 'FF2020',
  active: '20FF40',
  done: '20E0FF',
  marker: 'FFFFFF',
  pending: '0A3020',
  otherA: '0A4014',
  otherB: '0A3848',
  doneDim: '06202C',
};
const OFF = '000000';

const $ = (id) => document.getElementById(id);

let plan = null;
let sel = { c: 0, p: 0, i: 0 };
let step = 0;         // 0 = start pixel, 1..n = side n, n+1 = review
let session = null;   // { ip, port, busLen } while lit
let paintTimer = null;
let painting = false;
let paintAgain = false;
let blinkTimer = null;
let markerOn = true;

// ── Derived values ─────────────────────────────────────────────────────────

const cap = (t) => t.charAt(0).toUpperCase() + t.slice(1);
const lengthOf = (item) => item.sides.reduce((a, b) => a + b, 0);
const endOf = (item) => item.first + lengthOf(item);          // exclusive
const port = () => plan.controllers[sel.c].ports[sel.p];
const controller = () => plan.controllers[sel.c];
const item = () => port().items[sel.i];

function gapBefore(items, idx) {
  return idx === 0 ? items[0].first : items[idx].first - endOf(items[idx - 1]);
}

function gapAfter(items, idx, busLen) {
  if (idx < items.length - 1) return items[idx + 1].first - endOf(items[idx]);
  return busLen == null ? null : busLen - endOf(items[idx]);
}

// Ranges to paint for the whole port. Spare pixels (which become null models in
// xLights) are red. The current model is lit according to the step you're on.
//
// opts.focus lights only what is being measured: the current model's spares before and
// after it, its finished sides dimly, and the side being measured; everything else is
// off. That matters at a corner where the start, the end and the dangling spares meet.
// opts.markerOn is false on the "off" half of a blink, so the marker flashes.
function paintRanges(items, current, curStep, busLen, opts = {}) {
  const focus = !!opts.focus;
  const marker = opts.markerOn === false ? OFF : COLORS.marker;
  const total = busLen ?? Math.max(...items.map(endOf));
  const ranges = [];
  if (focus) {
    const cur = items[current];
    const spareFrom = current === 0 ? 0 : endOf(items[current - 1]);
    const spareTo = current === items.length - 1 ? total : items[current + 1].first;
    const n = cur.sides.length;
    // Spares before only while finding the start, spares after only from the last side
    // on, so the corner where both meet never shows both at once (except in review).
    if (curStep === 0 || curStep > n) ranges.push({ start: spareFrom, stop: cur.first, color: COLORS.spare });
    if (curStep >= n) ranges.push({ start: endOf(cur), stop: spareTo, color: COLORS.spare });
  } else {
    ranges.push({ start: 0, stop: total, color: COLORS.spare });
  }
  items.forEach((it, idx) => {
    let pos = it.first;
    const n = it.sides.length;
    it.sides.forEach((len, s) => {
      const stop = pos + len;
      if (idx !== current) {
        // In focus mode other models stay off (the port starts black).
        if (!focus) ranges.push({ start: pos, stop, color: s % 2 === 0 ? COLORS.otherA : COLORS.otherB });
      } else if (curStep === 0) {
        // Finding the start: the first side is lit, its first pixel is the marker.
        const color = s === 0 ? (focus ? COLORS.active : COLORS.pending) : (focus ? null : COLORS.pending);
        if (color) ranges.push({ start: pos, stop, color });
        if (s === 0 && len > 0) ranges.push({ start: pos, stop: pos + 1, color: marker });
      } else if (curStep <= n) {
        // Finding the end of side (curStep - 1): its last pixel is the marker.
        const k = curStep - 1;
        const color = s < k ? (focus ? COLORS.doneDim : COLORS.done)
          : s === k ? COLORS.active
          : (focus ? null : COLORS.pending);
        if (color) ranges.push({ start: pos, stop, color });
        if (s === k && len > 0) ranges.push({ start: stop - 1, stop, color: marker });
      } else {
        // Review: sides alternate colors, first pixel of each side is the marker.
        ranges.push({ start: pos, stop, color: s % 2 === 0 ? COLORS.active : COLORS.done });
        if (len > 0) ranges.push({ start: pos, stop: pos + 1, color: marker });
      }
      pos = stop;
    });
  });
  return ranges.filter((r) => r.stop > r.start);
}

// Left/right and top/bottom should usually match. xLights' Window Frame model has one
// count for both sides, so unequal sides need attention; top and bottom are separate.
function mismatches(it) {
  if (it.kind !== 'frame' || !it.sideNames) return [];
  const get = (name) => {
    const i = it.sideNames.indexOf(name);
    return i < 0 ? null : it.sides[i];
  };
  const out = [];
  const [l, r, t, b] = ['left', 'right', 'top', 'bottom'].map(get);
  if (l != null && r != null && l !== r) out.push(`Left ${l} and right ${r} differ. xLights' Window Frame uses one side count, so this window needs a custom model or an adjusted submodel.`);
  if (t != null && b != null && t !== b) out.push(`Top ${t} and bottom ${b} differ. Window Frame has separate top and bottom counts, so set each one.`);
  return out;
}

function sidesText(it) {
  return it.sides.map((n, s) => `${it.sideNames?.[s] ?? 'side ' + (s + 1)} ${n}`).join(', ');
}

// ── API ────────────────────────────────────────────────────────────────────

async function api(path, opts = {}) {
  const res = await fetch('api/measure/' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // Not JSON: usually the ingress answering because the app is restarting.
    throw new Error(res.ok
      ? 'Unexpected reply from the app; reload the page.'
      : `The app isn't answering (HTTP ${res.status}: ${text.trim().slice(0, 60)}). It may be restarting; try again in a minute.`);
  }
  if (!res.ok) throw new Error(body?.error || `HTTP ${res.status}`);
  return body;
}

function setStatus(msg, isErr = false) {
  const el = $('status');
  el.textContent = msg;
  el.classList.toggle('err', isErr);
}

async function savePlan() {
  try {
    await api('plan', { method: 'PUT', body: JSON.stringify(plan) });
  } catch (e) {
    setStatus('Could not save: ' + e.message, true);
  }
}

// ── Painting (debounced, one request in flight) ────────────────────────────

function schedulePaint() {
  if (!session) return;
  clearTimeout(paintTimer);
  paintTimer = setTimeout(paintNow, 120);
}

async function paintNow() {
  if (!session) return;
  if (painting) { paintAgain = true; return; }
  painting = true;
  try {
    await api('paint', {
      method: 'POST',
      body: JSON.stringify({
        ip: session.ip,
        brightness: Number($('bri').value),
        ranges: paintRanges(port().items, sel.i, step, session.busLen, {
          focus: $('focus').checked,
          markerOn: !$('blink').checked || markerOn,
        }),
      }),
    });
    setStatus(`Lit ${controller().name} port ${session.port}`);
  } catch (e) {
    setStatus(e.message, true);
  } finally {
    painting = false;
    if (paintAgain) { paintAgain = false; paintNow(); }
  }
}

async function startSession() {
  const c = controller();
  const p = port();
  setStatus('Connecting…');
  try {
    const t = await api('start', {
      method: 'POST',
      body: JSON.stringify({ ip: c.ip, port: p.port, brightness: Number($('bri').value) }),
    });
    session = { ip: c.ip, port: p.port, busLen: t.busLen };
    $('startBtn').disabled = true;
    $('stopBtn').disabled = false;
    startBlink();
    render();
    paintNow();
  } catch (e) {
    setStatus(e.message, true);
  }
}

async function stopSession() {
  if (!session) return;
  const ip = session.ip;
  session = null;
  clearInterval(blinkTimer);
  blinkTimer = null;
  try {
    await api('stop', { method: 'POST', body: JSON.stringify({ ip }) });
    setStatus('Controller restored. Live data from FPP/xLights is accepted again.');
  } catch (e) {
    setStatus('Restore failed: ' + e.message + ' (a reboot of the controller also restores it)', true);
  }
  $('startBtn').disabled = false;
  $('stopBtn').disabled = true;
  render();
}

// The marker flashes about once a second, which is far easier to spot from the
// street than one steady white pixel. A blink tick is skipped while a paint is in flight.
function startBlink() {
  clearInterval(blinkTimer);
  blinkTimer = setInterval(() => {
    if (!session || !$('blink').checked || painting) return;
    markerOn = !markerOn;
    paintNow();
  }, 550);
}

// ── Editing ────────────────────────────────────────────────────────────────

// Moving the end of an item moves every unsaved item after it by the same amount,
// since they are chained on the same physical string.
function ripple(delta) {
  if (!$('ripple').checked || delta === 0) return;
  port().items.slice(sel.i + 1).forEach((it) => { if (!it.saved) it.first += delta; });
}

function shiftStart(delta) {
  const it = item();
  const next = Math.max(0, it.first + delta);
  const d = next - it.first;
  it.first = next;
  ripple(d);
  changed();
}

function changeSide(s, delta) {
  const it = item();
  const next = Math.max(0, it.sides[s] + delta);
  const d = next - it.sides[s];
  it.sides[s] = next;
  ripple(d);
  changed();
}

// The single ± control does whatever the current step measures.
function adjust(delta) {
  if (step === 0) shiftStart(delta);
  else if (step <= item().sides.length) changeSide(step - 1, delta);
}

function changed() {
  render();
  schedulePaint();
}

function goToItem(idx) {
  sel.i = idx;
  step = item().saved ? item().sides.length + 1 : 0;
  changed();
}

async function next() {
  const it = item();
  if (step <= it.sides.length) {
    step += 1;
    changed();
    return;
  }
  it.saved = true;
  it.savedAt = new Date().toISOString();
  await savePlan();
  if (sel.i < port().items.length - 1) goToItem(sel.i + 1);
  else { setStatus('Last model on this port saved. Pick the next port above.'); changed(); }
}

function back() {
  if (step > 0) step -= 1;
  else if (sel.i > 0) { sel.i -= 1; step = item().sides.length + 1; }
  changed();
}

// ── Rendering ──────────────────────────────────────────────────────────────

function renderPortSelect() {
  const s = $('portSelect');
  s.innerHTML = '';
  plan.controllers.forEach((c, ci) => c.ports.forEach((p, pi) => {
    const o = document.createElement('option');
    o.value = `${ci}:${pi}`;
    o.textContent = `${c.name} port ${p.port} · ${p.label}`;
    if (ci === sel.c && pi === sel.p) o.selected = true;
    s.appendChild(o);
  }));
}

function render() {
  const items = port().items;
  const busLen = session?.busLen ?? null;

  const list = $('itemList');
  list.innerHTML = '';
  items.forEach((it, idx) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = it.name.replace(/^Window - /, '');
    b.className = (idx === sel.i ? 'current ' : '') + (it.saved ? 'saved' : '');
    b.onclick = () => goToItem(idx);
    list.appendChild(b);
  });

  const it = item();
  const n = it.sides.length;
  if (step > n + 1) step = n + 1;
  $('itemCard').hidden = false;
  $('itemName').textContent = it.name;
  $('itemSaved').textContent = it.saved ? 'saved' : '';

  const names = ['Start', ...it.sides.map((_, s) => cap(it.sideNames?.[s] ?? `Side ${s + 1}`)), 'Review'];
  const stepsEl = $('steps');
  stepsEl.innerHTML = '';
  names.forEach((label, k) => {
    const li = document.createElement('li');
    li.textContent = label;
    li.className = k === step ? 'current' : (k < step || it.saved ? 'done' : '');
    li.onclick = () => { step = k; changed(); };
    stepsEl.appendChild(li);
  });

  const adjustable = step <= n;
  document.querySelectorAll('[data-step]').forEach((b) => { b.disabled = !adjustable; });
  if (step === 0) {
    $('stepTitle').textContent = `Step 1 of ${n + 2}: find the first pixel`;
    $('stepHelp').textContent = 'Move until the flashing pixel is the first one on the frame (where the string first touches it). Red pixels before it are spare.';
    $('stepLabel').innerHTML = `Start pixel <b>${it.first}</b>`;
  } else if (adjustable) {
    const name = it.sideNames?.[step - 1] ?? `side ${step}`;
    $('stepTitle').textContent = `Step ${step + 1} of ${n + 2}: ${name}`;
    $('stepHelp').textContent = n === 1
      ? 'Move until the flashing pixel is the last one on the model.'
      : `Move until the flashing pixel sits on the corner where the ${name} ends. Blue is already measured.`;
    $('stepLabel').innerHTML = `${cap(name)} pixels <b>${it.sides[step - 1]}</b>`;
  } else {
    $('stepTitle').textContent = `Step ${n + 2} of ${n + 2}: check and save`;
    const tail = gapAfter(items, sel.i, busLen);
    $('stepHelp').textContent = 'Each side alternates green and blue, with a white pixel at each corner. '
      + (tail == null ? 'Red after the frame is spare.' : `Red after the frame is spare: count the red pixels hanging off the end, there should be ${tail}.`);
    $('stepLabel').textContent = '';
  }
  $('nextBtn').textContent = adjustable ? 'Next \u2192' : (sel.i < items.length - 1 ? 'Save & next model' : 'Save');
  $('backBtn').disabled = step === 0 && sel.i === 0;

  $('firstVal').textContent = it.first;
  $('lastVal').textContent = endOf(it) - 1;
  $('sidesVal').textContent = `${sidesText(it)} (${lengthOf(it)} total)`;

  const before = gapBefore(items, sel.i);
  const after = gapAfter(items, sel.i, busLen);
  $('nullBefore').textContent = before;
  $('nullBefore').classList.toggle('warn', before < 0);
  $('nullAfter').textContent = after == null ? '\u2014' : after;
  $('nullAfter').classList.toggle('warn', after != null && after < 0);

  const mm = mismatches(it);
  $('mismatch').hidden = mm.length === 0 || step <= 0;
  $('mismatch').textContent = mm.join(' ');

  renderResults();
}

function resultRows() {
  return plan.controllers.flatMap((c) => c.ports.map((p) => ({
    title: `${c.name} port ${p.port} (${p.label})`,
    rows: p.items.map((it, idx) => ({
      name: it.name,
      first: it.first,
      sides: sidesText(it),
      mismatch: mismatches(it).length > 0,
      length: lengthOf(it),
      after: gapAfter(p.items, idx, null),
      before: idx === 0 ? it.first : null,
      saved: it.saved,
    })),
  })));
}

function renderResults() {
  const el = $('results');
  el.innerHTML = '';
  resultRows().forEach((g) => {
    const h = document.createElement('h3');
    h.textContent = g.title;
    el.appendChild(h);
    const t = document.createElement('table');
    t.innerHTML = '<tr><th>Model</th><th>First</th><th>Sides</th><th>Len</th><th>Null after</th><th></th></tr>';
    g.rows.forEach((r) => {
      const tr = document.createElement('tr');
      const after = r.after == null ? 'end' : r.after;
      tr.innerHTML = `<td>${r.name.replace(/^Window - /, '')}</td><td>${r.first}</td><td>${r.sides}</td>
        <td>${r.length}</td><td class="${r.after < 0 ? 'warn' : ''}">${after}</td><td>${r.saved ? '✓' : ''}${r.mismatch ? ' <span class="warn">≠</span>' : ''}</td>`;
      t.appendChild(tr);
    });
    el.appendChild(t);
  });
}

function markdownTable() {
  const lines = ['| Port | Model | First px | Sides (wiring order) | Length | Null before | Null after | Sides unequal | Saved |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- |'];
  resultRows().forEach((g) => g.rows.forEach((r) => {
    lines.push(`| ${g.title} | ${r.name} | ${r.first} | ${r.sides} | ${r.length} | ${r.before ?? ''} | ${r.after ?? 'end of string'} | ${r.mismatch ? 'yes' : ''} | ${r.saved ? 'yes' : 'no'} |`);
  }));
  return lines.join('\n');
}

// ── Wiring ─────────────────────────────────────────────────────────────────

async function init() {
  plan = await api('plan');
  renderPortSelect();
  render();

  $('portSelect').onchange = async (e) => {
    const [c, p] = e.target.value.split(':').map(Number);
    const wasLit = !!session;
    if (wasLit) await stopSession();
    sel = { c, p, i: 0 };
    step = item().saved ? item().sides.length + 1 : 0;
    render();
    if (wasLit) startSession();
  };
  $('startBtn').onclick = startSession;
  $('stopBtn').onclick = stopSession;
  $('bri').oninput = schedulePaint;
  $('focus').onchange = schedulePaint;
  $('blink').onchange = () => { markerOn = true; schedulePaint(); };
  document.querySelectorAll('[data-step]').forEach((b) => {
    b.onclick = () => adjust(Number(b.dataset.step));
  });
  $('backBtn').onclick = back;
  $('nextBtn').onclick = next;
  $('copyBtn').onclick = async () => {
    try {
      await navigator.clipboard.writeText(markdownTable());
      setStatus('Results copied as a Markdown table.');
    } catch {
      setStatus('Clipboard blocked; long-press the results table to copy it instead.', true);
    }
  };
  $('resetBtn').onclick = async () => {
    if (!confirm('Throw away all measurements and go back to the starting guesses?')) return;
    plan = await api('plan', { method: 'DELETE' });
    sel = { c: 0, p: 0, i: 0 };
    step = 0;
    renderPortSelect();
    changed();
  };
  // Leaving the page with the lights frozen would keep FPP locked out until reboot.
  window.addEventListener('pagehide', () => {
    if (session) navigator.sendBeacon('api/measure/stop', new Blob([JSON.stringify({ ip: session.ip })], { type: 'application/json' }));
  });
}

init().catch((e) => setStatus('Could not load the plan: ' + e.message, true));
