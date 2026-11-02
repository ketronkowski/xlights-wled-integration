// Boundary finder: light one port in measuring colors, nudge each model's start and
// side counts until the colors line up with the real frame, and save the result.
//
// A plan is { controllers: [{ name, ip, ports: [{ port, label, items: [...] }] }] }.
// Each item is { name, kind, sides: [counts in wiring order], sideNames, first, saved }.
// "first" is the port-relative index of the item's first pixel; everything else
// (last pixel, spare pixels before/after) is derived from first + sides.

const COLORS = {
  spare: 'FF2020',
  sideA: '20FF40',
  sideB: '20E0FF',
  corner: 'FFFFFF',
  otherA: '0A4014',
  otherB: '0A3848',
};

const $ = (id) => document.getElementById(id);

let plan = null;
let sel = { c: 0, p: 0, i: 0 };
let session = null;   // { ip, port, busLen } while lit
let paintTimer = null;
let painting = false;
let paintAgain = false;

// ── Derived values ─────────────────────────────────────────────────────────

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

// Ranges to paint for the whole port. The selected item is bright; the others are dim
// so you can still see where they sit. Anything not on a model is lit red: those are
// the spare pixels that become null models in xLights.
function paintRanges(items, current, busLen) {
  const total = busLen ?? Math.max(...items.map(endOf));
  const ranges = [{ start: 0, stop: total, color: COLORS.spare }];
  items.forEach((it, idx) => {
    const bright = idx === current;
    let pos = it.first;
    it.sides.forEach((n, s) => {
      const color = s % 2 === 0
        ? (bright ? COLORS.sideA : COLORS.otherA)
        : (bright ? COLORS.sideB : COLORS.otherB);
      ranges.push({ start: pos, stop: pos + n, color });
      if (bright && n > 0) ranges.push({ start: pos, stop: pos + 1, color: COLORS.corner });
      pos += n;
    });
  });
  return ranges.filter((r) => r.stop > r.start);
}

// ── API ────────────────────────────────────────────────────────────────────

async function api(path, opts = {}) {
  const res = await fetch('api/measure/' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
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
        ranges: paintRanges(port().items, sel.i, session.busLen),
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

function changed() {
  render();
  schedulePaint();
}

async function saveAndNext() {
  const it = item();
  it.saved = true;
  it.savedAt = new Date().toISOString();
  await savePlan();
  if (sel.i < port().items.length - 1) sel.i += 1;
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
    b.onclick = () => { sel.i = idx; changed(); };
    list.appendChild(b);
  });

  const it = item();
  $('itemCard').hidden = false;
  $('itemName').textContent = it.name;
  $('itemSaved').textContent = it.saved ? 'saved' : '';
  $('firstVal').textContent = it.first;
  $('lastVal').textContent = endOf(it) - 1;
  $('lenVal').textContent = lengthOf(it);

  const before = gapBefore(items, sel.i);
  const after = gapAfter(items, sel.i, busLen);
  $('nullBefore').textContent = before;
  $('nullBefore').classList.toggle('warn', before < 0);
  $('nullAfter').textContent = after == null ? '—' : after;
  $('nullAfter').classList.toggle('warn', after != null && after < 0);

  const sides = $('sides');
  sides.innerHTML = '';
  it.sides.forEach((n, s) => {
    const row = document.createElement('div');
    row.className = 'm-adjust';
    const name = it.sideNames?.[s] ?? `side ${s + 1}`;
    row.innerHTML = `<span class="m-label">${name} <b>${n}</b></span>
      <div class="m-btns"><button type="button">&minus;1</button><button type="button">+1</button></div>`;
    const [minus, plus] = row.querySelectorAll('button');
    minus.onclick = () => changeSide(s, -1);
    plus.onclick = () => changeSide(s, 1);
    sides.appendChild(row);
  });

  $('prevBtn').disabled = sel.i === 0;
  renderResults();
}

function resultRows() {
  return plan.controllers.flatMap((c) => c.ports.map((p) => ({
    title: `${c.name} port ${p.port} (${p.label})`,
    rows: p.items.map((it, idx) => ({
      name: it.name,
      first: it.first,
      sides: it.sides.join('/'),
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
        <td>${r.length}</td><td class="${r.after < 0 ? 'warn' : ''}">${after}</td><td>${r.saved ? '✓' : ''}</td>`;
      t.appendChild(tr);
    });
    el.appendChild(t);
  });
}

function markdownTable() {
  const lines = ['| Port | Model | First px | Sides (wiring order) | Length | Null before | Null after | Saved |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |'];
  resultRows().forEach((g) => g.rows.forEach((r) => {
    lines.push(`| ${g.title} | ${r.name} | ${r.first} | ${r.sides} | ${r.length} | ${r.before ?? ''} | ${r.after ?? 'end of string'} | ${r.saved ? 'yes' : 'no'} |`);
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
    render();
    if (wasLit) startSession();
  };
  $('startBtn').onclick = startSession;
  $('stopBtn').onclick = stopSession;
  $('bri').oninput = schedulePaint;
  document.querySelectorAll('[data-shift]').forEach((b) => {
    b.onclick = () => shiftStart(Number(b.dataset.shift));
  });
  $('prevBtn').onclick = () => { if (sel.i > 0) { sel.i -= 1; changed(); } };
  $('saveBtn').onclick = saveAndNext;
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
    renderPortSelect();
    changed();
  };
  // Leaving the page with the lights frozen would keep FPP locked out until reboot.
  window.addEventListener('pagehide', () => {
    if (session) navigator.sendBeacon('api/measure/stop', new Blob([JSON.stringify({ ip: session.ip })], { type: 'application/json' }));
  });
}

init().catch((e) => setStatus('Could not load the plan: ' + e.message, true));
