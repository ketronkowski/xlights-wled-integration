// Boundary finder: light one port in measuring colors and walk each model step by step:
// find its first frame pixel, then any spare (lead-in) pixels on its own string before
// the frame, then each side's end corner in wiring order, then review and save.
//
// A plan is { controllers: [{ name, ip, ports: [{ port, label, items: [...] }] }] }.
// Each item is { name, kind, sides: [counts in wiring order], sideNames, first, lead,
// corner, dir, saved }. "first" is the port-relative index of the item's first frame
// pixel; "lead" is how many spare pixels right before it belong to this model's string
// (a null before it in xLights). For frames, corner (br/bl/tl/tr) and dir (ccw/cw) give
// the wiring order of the sides, viewed from the front.

const COLORS = {
  spare: 'FF2020',
  active: '20FF40',
  done: '20E0FF',
  marker: 'FFFFFF',
  pending: '0A3020',
  otherA: '0A4014',
  otherB: '0A3848',
  doneDim: '06202C',
  spareDim: '300404',
};
const OFF = '000000';

const $ = (id) => document.getElementById(id);

let plan = null;
let sel = { c: 0, p: 0, i: 0 };
let step = 0;         // see STEP_* below; sides are SIDE0 .. SIDE0+n-1, then review
let session = null;   // { ip, port, busLen } while lit
let paintTimer = null;
let painting = false;
let paintAgain = false;
let blinkTimer = null;
let markerOn = true;

// ── Steps ──────────────────────────────────────────────────────────────────

const STEP_START = 0;
const STEP_LEAD = 1;
const SIDE0 = 2;
const reviewStep = (it) => SIDE0 + it.sides.length;
const sideOf = (it, st) => (st >= SIDE0 && st < reviewStep(it) ? st - SIDE0 : -1);

// ── Orientation ────────────────────────────────────────────────────────────

// Viewed from the front: counter-clockwise goes right side up, top leftwards, left side
// down, bottom rightwards; clockwise is the reverse. The start corner picks the first side.
const CYCLE = { ccw: ['right', 'top', 'left', 'bottom'], cw: ['right', 'bottom', 'left', 'top'] };
const FIRST_SIDE = {
  ccw: { br: 'right', tr: 'top', tl: 'left', bl: 'bottom' },
  cw: { br: 'bottom', bl: 'left', tl: 'top', tr: 'right' },
};
const CORNERS = { br: 'Bottom right', bl: 'Bottom left', tl: 'Top left', tr: 'Top right' };
const DIRS = { ccw: 'Counter-clockwise', cw: 'Clockwise' };

function sideOrder(corner, dir) {
  const cyc = CYCLE[dir];
  const i = cyc.indexOf(FIRST_SIDE[dir][corner]);
  return [...cyc.slice(i), ...cyc.slice(0, i)];
}

const isFrame = (it) => it.kind === 'frame' && Array.isArray(it.sideNames)
  && it.sideNames.every((n) => CYCLE.ccw.includes(n));

// Older plans only have sideNames; work out which corner/direction they describe.
function inferOrientation(it) {
  if (!isFrame(it) || (it.corner && it.dir)) return;
  for (const dir of Object.keys(DIRS)) {
    for (const corner of Object.keys(CORNERS)) {
      const order = sideOrder(corner, dir).filter((n) => it.sideNames.includes(n));
      if (order.join() === it.sideNames.join()) { it.corner = corner; it.dir = dir; return; }
    }
  }
}

// Re-order the sides for a new start corner/direction, keeping each side's count.
function setOrientation(it, corner, dir) {
  const counts = Object.fromEntries(it.sideNames.map((n, k) => [n, it.sides[k]]));
  const order = sideOrder(corner, dir).filter((n) => n in counts);
  it.sideNames = order;
  it.sides = order.map((n) => counts[n]);
  it.corner = corner;
  it.dir = dir;
}

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

// Spare pixels on this model's own string before its frame. For the first model on a
// port every pixel before it is its own lead-in.
const leadOf = (items, idx) => (idx === 0 ? items[0].first : Math.min(items[idx].lead ?? 0, Math.max(0, gapBefore(items, idx))));

// Spare pixels at the end of this model's string: the gap to the next model, minus the
// next model's own lead-in.
function tailOf(items, idx, busLen) {
  const gap = gapAfter(items, idx, busLen);
  if (gap == null || idx === items.length - 1) return gap;
  return gap - leadOf(items, idx + 1);
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
  const cur = items[current];
  const n = cur.sides.length;
  const review = reviewStep(cur);
  const k = sideOf(cur, curStep);
  const spareFrom = current === 0 ? 0 : endOf(items[current - 1]);
  const spareTo = current === items.length - 1 ? total : items[current + 1].first;
  const lead = leadOf(items, current);
  const leadFrom = cur.first - lead;
  const ranges = [];

  if (!focus) ranges.push({ start: 0, stop: total, color: COLORS.spare });
  // Spares before: the whole gap while finding the start; on the lead-in step the
  // previous model's tail goes dim and this model's lead-in stays bright, with the
  // marker on its first pixel. Spares after only show from the last side on, so the
  // corner where the start, the end and dangling spares meet never shows both at once.
  if (curStep === STEP_START || curStep === review) {
    ranges.push({ start: spareFrom, stop: cur.first, color: COLORS.spare });
  } else if (curStep === STEP_LEAD) {
    ranges.push({ start: spareFrom, stop: leadFrom, color: COLORS.spareDim });
    ranges.push({ start: leadFrom, stop: cur.first, color: COLORS.spare });
    if (lead > 0) ranges.push({ start: leadFrom, stop: leadFrom + 1, color: marker });
  } else if (!focus) {
    ranges.push({ start: spareFrom, stop: cur.first, color: COLORS.spareDim });
  }
  if (curStep >= review - 1 && curStep !== STEP_LEAD && curStep !== STEP_START) {
    ranges.push({ start: endOf(cur), stop: spareTo, color: COLORS.spare });
  } else if (!focus) {
    ranges.push({ start: endOf(cur), stop: spareTo, color: COLORS.spareDim });
  }

  items.forEach((it, idx) => {
    let pos = it.first;
    it.sides.forEach((len, s) => {
      const stop = pos + len;
      if (idx !== current) {
        // In focus mode other models stay off (the port starts black).
        if (!focus) ranges.push({ start: pos, stop, color: s % 2 === 0 ? COLORS.otherA : COLORS.otherB });
      } else if (curStep === STEP_START || curStep === STEP_LEAD) {
        // The first side is lit for reference; while finding the start its first pixel is
        // the marker, while counting the lead-in it stays steady.
        const color = s === 0 ? COLORS.active : (focus ? null : COLORS.pending);
        if (color) ranges.push({ start: pos, stop, color });
        if (s === 0 && len > 0 && curStep === STEP_START) ranges.push({ start: pos, stop: pos + 1, color: marker });
      } else if (k >= 0) {
        // Finding the end of side k: its last pixel is the marker.
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

// Compact form for the narrow results table: R29 T16 L29 B17.
function sidesShort(it) {
  if (!isFrame(it)) return it.sides.join('/');
  return it.sides.map((n, s) => `${it.sideNames[s][0].toUpperCase()}${n}`).join(' ');
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
    if (/start one first/.test(e.message)) {
      // The app restarted and forgot the session: take the port over again, then repaint.
      const s0 = session;
      try {
        await api('start', {
          method: 'POST',
          body: JSON.stringify({ ip: s0.ip, port: s0.port, brightness: Number($('bri').value) }),
        });
      } catch (e2) {
        setStatus(e2.message, true);
        return;
      }
      // Done was tapped while we were reconnecting: release the controller again.
      if (!session) {
        await api('stop', { method: 'POST', body: JSON.stringify({ ip: s0.ip, port: s0.port, restoreOn: s0.restoreOn, restoreBri: s0.restoreBri }) }).catch(() => {});
        return;
      }
      paintAgain = true;
      return;
    }
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
    session = { ip: c.ip, port: p.port, busLen: t.busLen, restoreOn: t.originalOn, restoreBri: t.originalBri };
    $('startBtn').disabled = true;
    $('stopBtn').disabled = false;
    startBlink();
    render();
    paintNow();
  } catch (e) {
    clearInterval(blinkTimer);
    $('startBtn').disabled = false;
    $('stopBtn').disabled = true;
    setStatus(e.message, true);
  }
}

async function stopSession() {
  if (!session) return;
  const { ip, port: portNo, restoreOn, restoreBri } = session;
  session = null;
  clearInterval(blinkTimer);
  blinkTimer = null;
  try {
    await api('stop', { method: 'POST', body: JSON.stringify({ ip, port: portNo, restoreOn, restoreBri }) });
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

function changeLead(delta) {
  const items = port().items;
  if (sel.i === 0) return; // the first model's lead-in is simply its start pixel
  const it = item();
  const want = Math.max(0, leadOf(items, sel.i) + delta);
  // If the previous model ends right where this frame starts there is no room for a
  // lead-in, so growing it pushes this model (and, with ripple on, the unsaved ones
  // after it) further along the string to make room.
  const room = Math.max(0, gapBefore(items, sel.i));
  if (want > room) {
    const d = want - room;
    it.first += d;
    ripple(d);
  }
  it.lead = want;
  changed();
}

// The single ± control does whatever the current step measures.
function adjust(delta) {
  const k = sideOf(item(), step);
  if (step === STEP_START) shiftStart(delta);
  else if (step === STEP_LEAD) changeLead(delta);
  else if (k >= 0) changeSide(k, delta);
}

function changed() {
  render();
  schedulePaint();
}

function goToItem(idx) {
  sel.i = idx;
  step = item().saved ? reviewStep(item()) : STEP_START;
  changed();
}

async function next() {
  const it = item();
  if (step < reviewStep(it)) {
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
  else if (sel.i > 0) { sel.i -= 1; step = reviewStep(item()); }
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
  const review = reviewStep(it);
  const k = sideOf(it, step);
  const total = review + 1;
  if (step > review) step = review;
  $('itemCard').hidden = false;
  $('itemName').textContent = it.name;
  $('itemSaved').textContent = it.saved ? 'saved' : '';

  // Start corner and direction, for window frames.
  $('orient').hidden = !isFrame(it);
  if (isFrame(it)) {
    inferOrientation(it);
    $('corner').value = it.corner ?? 'br';
    $('dir').value = it.dir ?? 'ccw';
  }

  const names = ['Start', 'Lead-in', ...it.sides.map((_, s) => cap(it.sideNames?.[s] ?? `Side ${s + 1}`)), 'Review'];
  const stepsEl = $('steps');
  stepsEl.innerHTML = '';
  names.forEach((label, idx) => {
    const li = document.createElement('li');
    li.textContent = label;
    li.className = idx === step ? 'current' : (idx < step || it.saved ? 'done' : '');
    li.onclick = () => { step = idx; changed(); };
    stepsEl.appendChild(li);
  });

  const adjustable = step < review && !(step === STEP_LEAD && sel.i === 0);
  document.querySelectorAll('[data-step]').forEach((b) => { b.disabled = !adjustable; });
  const lead = leadOf(items, sel.i);
  if (step === STEP_START) {
    $('stepTitle').textContent = `Step 1 of ${total}: find the first pixel on the frame`;
    $('stepHelp').textContent = 'Move until the flashing pixel is the first one on the frame itself, at the start corner. Red pixels before it are spare.';
    $('stepLabel').innerHTML = `Start pixel <b>${it.first}</b>`;
  } else if (step === STEP_LEAD) {
    $('stepTitle').textContent = `Step 2 of ${total}: spare pixels before the frame`;
    if (sel.i === 0) {
      $('stepHelp').textContent = 'This is the first model on the port, so every red pixel before the frame is its lead-in. Nothing to adjust.';
      $('stepLabel').innerHTML = `Lead-in spares <b>${lead}</b>`;
    } else {
      $('stepHelp').textContent = 'Bright red pixels are spares on this window\'s own string before the frame; the flashing one is the first. Dim red belongs to the previous model\'s tail. Usually 0: just tap Next. Tapping + past the dim red moves this whole frame along to make room.';
      $('stepLabel').innerHTML = `Lead-in spares <b>${lead}</b>`;
    }
  } else if (k >= 0) {
    const name = it.sideNames?.[k] ?? `side ${k + 1}`;
    $('stepTitle').textContent = `Step ${step + 1} of ${total}: ${name}`;
    $('stepHelp').textContent = n === 1
      ? 'Move until the flashing pixel is the last one on the model.'
      : `Move until the flashing pixel sits on the corner where the ${name} ends. Blue is already measured.`;
    $('stepLabel').innerHTML = `${cap(name)} pixels <b>${it.sides[k]}</b>`;
  } else {
    $('stepTitle').textContent = `Step ${total} of ${total}: check and save`;
    const tail = tailOf(items, sel.i, busLen);
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
  const after = tailOf(items, sel.i, busLen);
  $('nullBefore').textContent = lead;
  $('nullBefore').classList.toggle('warn', before < 0);
  $('nullAfter').textContent = after == null ? '\u2014' : after;
  $('nullAfter').classList.toggle('warn', after != null && after < 0);

  const mm = mismatches(it);
  $('mismatch').hidden = mm.length === 0 || k < 0 && step !== review;
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
      sidesShort: sidesShort(it),
      mismatch: mismatches(it).length > 0,
      length: lengthOf(it),
      after: tailOf(p.items, idx, null),
      before: leadOf(p.items, idx),
      orient: isFrame(it) && it.corner ? `${CORNERS[it.corner]}, ${DIRS[it.dir].toLowerCase()}` : '',
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
    t.innerHTML = '<tr><th>Model</th><th>First</th><th>Sides</th><th>Len</th><th>Null before</th><th>Null after</th><th></th></tr>';
    g.rows.forEach((r) => {
      const tr = document.createElement('tr');
      const after = r.after == null ? 'end' : r.after;
      tr.innerHTML = `<td>${r.name.replace(/^Window - /, '')}</td><td>${r.first}</td><td>${r.sidesShort}</td>
        <td>${r.length}</td><td>${r.before}</td><td class="${r.after < 0 ? 'warn' : ''}">${after}</td><td>${r.saved ? '✓' : ''}${r.mismatch ? ' <span class="warn">≠</span>' : ''}</td>`;
      t.appendChild(tr);
    });
    el.appendChild(t);
  });
}

function markdownTable() {
  const lines = ['| Port | Model | Starts / runs | First px | Sides (wiring order) | Length | Null before | Null after | Sides unequal | Saved |',
    '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |'];
  resultRows().forEach((g) => g.rows.forEach((r) => {
    lines.push(`| ${g.title} | ${r.name} | ${r.orient} | ${r.first} | ${r.sides} | ${r.length} | ${r.before} | ${r.after ?? 'end of string'} | ${r.mismatch ? 'yes' : ''} | ${r.saved ? 'yes' : 'no'} |`);
  }));
  return lines.join('\n');
}

// ── Wiring ─────────────────────────────────────────────────────────────────

async function init() {
  plan = await api('plan');
  plan.controllers.forEach((c) => c.ports.forEach((p) => p.items.forEach(inferOrientation)));
  renderPortSelect();
  render();

  $('portSelect').onchange = async (e) => {
    const [c, p] = e.target.value.split(':').map(Number);
    const wasLit = !!session;
    if (wasLit) await stopSession();
    sel = { c, p, i: 0 };
    step = item().saved ? reviewStep(item()) : STEP_START;
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
  const reorient = () => {
    setOrientation(item(), $('corner').value, $('dir').value);
    step = STEP_START;
    changed();
  };
  $('corner').onchange = reorient;
  $('dir').onchange = reorient;
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
    if (session) navigator.sendBeacon('api/measure/stop', new Blob([JSON.stringify({ ip: session.ip, port: session.port, restoreOn: session.restoreOn, restoreBri: session.restoreBri })], { type: 'application/json' }));
  });
}

init().catch((e) => setStatus('Could not load the plan: ' + e.message, true));
