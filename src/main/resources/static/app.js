'use strict';

const folderInputFallback = document.getElementById('folderInputFallback');
const chooseFolderBtn = document.getElementById('chooseFolderBtn');
const folderLabelEl = document.getElementById('folderLabel');
const uploadBtn = document.getElementById('uploadBtn');
const uploadStatusEl = document.getElementById('uploadStatus');
const refreshBtn = document.getElementById('refreshBtn');
const selectAllBtn = document.getElementById('selectAllBtn');
const selectNoneBtn = document.getElementById('selectNoneBtn');
const backupSelectedBtn = document.getElementById('backupSelectedBtn');
const restoreSelectedBtn = document.getElementById('restoreSelectedBtn');
const updateSelectedBtn = document.getElementById('updateSelectedBtn');
const summaryLine = document.getElementById('summaryLine');
const cardGrid = document.getElementById('cardGrid');
const listGrid = document.getElementById('listGrid');
const cardViewBtn = document.getElementById('cardViewBtn');
const listViewBtn = document.getElementById('listViewBtn');
const filterInputs = {
  name: document.getElementById('filterName'),
  host: document.getElementById('filterHost'),
  reachable: document.getElementById('filterReachable'),
  sync: document.getElementById('filterSync'),
  backup: document.getElementById('filterBackup'),
};
const showSelectedOnlyToggle = document.getElementById('showSelectedOnlyToggle');
const selectionSummaryEl = document.getElementById('selectionSummary');
const clearAllBtn = document.getElementById('clearAllBtn');
const clearAllModal = document.getElementById('clearAllModal');
const clearAllOpsList = document.getElementById('clearAllOpsList');
const clearAllConfirmInput = document.getElementById('clearAllConfirmInput');
const clearAllConfirmBtn = document.getElementById('clearAllConfirmBtn');
const clearAllCancelBtn = document.getElementById('clearAllCancelBtn');
const CLEAR_ALL_PHRASE = 'DELETE EVERYTHING';

let report = null;          // last ValidationReport
let backups = [];           // last WledBackupRecord[]
let pendingUpload = null;   // { networksFile, effectsFile, folderLabel }
const selected = new Set(); // controller names selected via card/list checkboxes

// Two kinds of filter, chosen per field based on whether the field's value
// space is closed:
// - name/host are free text, matched as an unanchored, case-insensitive
//   regex against the raw field value — full regex syntax (.*, [...], etc.)
//   is intentional, not escaped, since the value space is unbounded.
// - reachable/sync/backup have a small fixed set of states, so they're
//   selects compared by exact match against a category value on the row
//   (reachableCategory/syncCategory/backupCategory) rather than regex against
//   the rendered badge text — this avoids e.g. "Reachable" as a substring
//   also matching "Unreachable", and stays correct even though the badge
//   text itself contains dynamic data (a segment count, a "N ago" timestamp).
// All non-empty filters AND together.
const filters = { name: '', host: '', reachable: '', sync: '', backup: '' };
const FILTER_KIND = { name: 'regex', host: 'regex', reachable: 'select', sync: 'select', backup: 'select' };
const FILTER_FIELD_KEY = { name: 'name', host: 'ip', reachable: 'reachableCategory', sync: 'syncCategory', backup: 'backupCategory' };

// The last field-filtered row set — recomputed on every render. Used by
// selectAll()/the selection summary so "select all" and "how many of my
// selection are currently hidden" both stay scoped to what's actually
// visible under the active filters.
let visibleRows = [];
let totalRowCount = 0; // unfiltered row count, so "0 matches" can distinguish a narrow filter from truly no data

// Applied as a separate, later display-time restriction — never folded into
// visibleRows itself. visibleRows is also what selectAll() scopes against and
// what the "hidden by filter" count is measured against; if this were folded
// in, turning it on would make Select All a no-op and would corrupt that count.
let showSelectedOnly = false;

const VIEW_STORAGE_KEY = 'xlights-wled-view';
let currentView = localStorage.getItem(VIEW_STORAGE_KEY) === 'list' ? 'list' : 'card';

init();

function init() {
  uploadBtn.addEventListener('click', onUploadClicked);
  refreshBtn.addEventListener('click', () => refreshStatus());
  cardViewBtn.addEventListener('click', () => setView('card'));
  listViewBtn.addEventListener('click', () => setView('list'));
  selectAllBtn.addEventListener('click', selectAll);
  selectNoneBtn.addEventListener('click', selectNone);
  backupSelectedBtn.addEventListener('click', backupSelected);
  restoreSelectedBtn.addEventListener('click', restoreSelected);
  updateSelectedBtn.addEventListener('click', () => updateToXlights([...selected]));
  clearAllBtn.addEventListener('click', openClearAllModal);
  clearAllCancelBtn.addEventListener('click', closeClearAllModal);
  clearAllConfirmInput.addEventListener('input', () => {
    clearAllConfirmBtn.disabled = clearAllConfirmInput.value !== CLEAR_ALL_PHRASE;
  });
  clearAllConfirmBtn.addEventListener('click', onClearAllConfirmed);

  Object.entries(filterInputs).forEach(([field, el]) => {
    // Selects fire 'change', not 'input', in a way that reliably covers
    // keyboard selection too; text inputs use 'input' to filter live per
    // keystroke.
    const eventName = el.tagName === 'SELECT' ? 'change' : 'input';
    el.addEventListener(eventName, () => {
      filters[field] = el.value;
      renderCurrentView();
    });
  });

  showSelectedOnlyToggle.addEventListener('change', e => {
    showSelectedOnly = e.target.checked;
    renderCurrentView();
  });

  setView(currentView);

  // Prefer the File System Access API: it grants access to just the picked
  // folder without eagerly enumerating every nested file, so it doesn't
  // trigger the browser's "Upload N files to this site?" warning the way
  // <input webkitdirectory> does on a real (large) xLights show directory.
  // Fall back to the classic picker on browsers that don't support it (e.g. Safari).
  if (window.showDirectoryPicker) {
    chooseFolderBtn.addEventListener('click', chooseFolderViaFileSystemAccess);
  } else {
    folderInputFallback.addEventListener('change', onFolderChosenFallback);
    chooseFolderBtn.addEventListener('click', () => folderInputFallback.click());
  }

  loadUploadStatus().then(hadUpload => {
    if (hadUpload) refreshStatus();
  });
}

// ── Upload ───────────────────────────────────────────────────────────────

async function loadUploadStatus() {
  try {
    const res = await fetch('api/upload/status');
    if (res.status === 404) {
      uploadStatusEl.textContent = 'No upload yet.';
      folderLabelEl.textContent = '';
      return false;
    }
    if (!res.ok) throw new Error(String(res.status));
    const status = await res.json();
    uploadStatusEl.textContent = `Last uploaded: ${status.folderLabel} · ${timeAgo(status.uploadedAt)}`;
    // Restore the picker's folder label from the persisted upload so a page
    // refresh doesn't make it look like the chosen show folder was forgotten
    // — the underlying config is already on disk, this just reflects that.
    folderLabelEl.textContent = `${status.folderLabel} (uploaded)`;
    return true;
  } catch (err) {
    uploadStatusEl.textContent = `Could not load upload status (${err.message})`;
    return false;
  }
}

// File System Access API: reads only the picked folder's top-level entries
// (no recursion into subfolders like Christmas 2023/, RenderCache/, etc.),
// so it never needs permission for more than the two files it looks for.
async function chooseFolderViaFileSystemAccess() {
  let dirHandle;
  try {
    dirHandle = await window.showDirectoryPicker({ id: 'xlights-show', mode: 'read' });
  } catch (err) {
    if (err.name !== 'AbortError') setSummary(`Could not open folder picker: ${err.message}`, true);
    return;
  }

  let networksFile = null;
  let effectsFile = null;
  for await (const entry of dirHandle.values()) {
    if (entry.kind !== 'file') continue; // top-level only — don't descend into subfolders
    if (entry.name === 'xlights_networks.xml') networksFile = await entry.getFile();
    else if (entry.name === 'xlights_rgbeffects.xml') effectsFile = await entry.getFile();
  }

  applyChosenFiles(networksFile, effectsFile, dirHandle.name);
}

// Fallback for browsers without the File System Access API (e.g. Safari).
// Uses the classic recursive directory input, which will show the browser's
// bulk-file-count warning on a large xLights show directory.
function onFolderChosenFallback() {
  const files = Array.from(folderInputFallback.files);
  const networksFile = files.find(f => f.name === 'xlights_networks.xml') || null;
  const effectsFile = files.find(f => f.name === 'xlights_rgbeffects.xml') || null;
  const folderLabel = (files[0]?.webkitRelativePath || '').split('/')[0] || 'xLights show';
  applyChosenFiles(networksFile, effectsFile, folderLabel);
}

function applyChosenFiles(networksFile, effectsFile, folderLabel) {
  if (!networksFile || !effectsFile) {
    folderLabelEl.textContent = 'Folder must directly contain xlights_networks.xml and xlights_rgbeffects.xml';
    uploadBtn.disabled = true;
    pendingUpload = null;
    return;
  }

  folderLabelEl.textContent = `${folderLabel} (ready to upload)`;
  pendingUpload = { networksFile, effectsFile, folderLabel };
  uploadBtn.disabled = false;
}

async function onUploadClicked() {
  if (!pendingUpload) return;
  uploadBtn.disabled = true;
  setSummary('Uploading…');
  try {
    const [networksXml, effectsXml] = await Promise.all([
      pendingUpload.networksFile.text(),
      pendingUpload.effectsFile.text(),
    ]);
    const res = await fetch('api/upload', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ networksXml, effectsXml, folderLabel: pendingUpload.folderLabel }),
    });
    if (!res.ok) throw new Error(String(res.status));
    await loadUploadStatus();
    await refreshStatus();
  } catch (err) {
    setSummary(`Upload failed: ${err.message}`, true);
  } finally {
    uploadBtn.disabled = false;
  }
}

// ── Refresh (validate + backups) ────────────────────────────────────────

async function refreshStatus() {
  refreshBtn.disabled = true;
  setSummary('Refreshing status…');
  try {
    const [reportRes, backupsRes] = await Promise.all([
      fetch('api/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }),
      fetch('api/backups'),
    ]);
    if (!reportRes.ok) throw new Error(`validate: ${reportRes.status}`);
    if (!backupsRes.ok) throw new Error(`backups: ${backupsRes.status}`);
    report = await reportRes.json();
    backups = await backupsRes.json();
    selected.clear();
    renderCurrentView();
    setSummary('');
  } catch (err) {
    setSummary(`Refresh failed: ${err.message}`, true);
  } finally {
    refreshBtn.disabled = false;
  }
}

// ── View toggle ──────────────────────────────────────────────────────────

function setView(view) {
  currentView = view;
  localStorage.setItem(VIEW_STORAGE_KEY, view);
  cardGrid.hidden = view !== 'card';
  listGrid.hidden = view !== 'list';
  cardViewBtn.classList.toggle('active', view === 'card');
  listViewBtn.classList.toggle('active', view === 'list');
  // Only re-render once we actually have data — otherwise this would wipe
  // out the static "upload a folder" hint markup for the view being shown.
  if (report) renderCurrentView();
}

function renderCurrentView() {
  const allRows = buildRowModel();
  totalRowCount = allRows.length;
  const { rows, invalidFields } = applyFieldFilters(allRows, filters);
  visibleRows = rows;
  updateFilterInputValidity(invalidFields);

  const rowsForDisplay = showSelectedOnly ? rows.filter(r => selected.has(r.name)) : rows;
  if (currentView === 'list') renderList(rowsForDisplay); else renderCards(rowsForDisplay);
}

function noRowsMessage() {
  return totalRowCount > 0
    ? 'No controllers match the current filters.'
    : 'No controllers found in the uploaded xLights config.';
}

// ── Filtering ────────────────────────────────────────────────────────────

function applyFieldFilters(rows, filterValues) {
  const compiled = {};
  const invalidFields = new Set();
  for (const [field, value] of Object.entries(filterValues)) {
    if (!value || FILTER_KIND[field] !== 'regex') continue;
    try {
      compiled[field] = new RegExp(value, 'i');
    } catch {
      // Invalid regex (e.g. an unbalanced paren) — that field matches
      // nothing rather than throwing and breaking the whole render.
      invalidFields.add(field);
    }
  }
  const activeRegexFields = Object.keys(compiled).concat([...invalidFields]);
  const activeSelectFields = Object.entries(filterValues)
    .filter(([field, value]) => FILTER_KIND[field] === 'select' && value);

  const rowsMatching = rows.filter(row =>
    activeRegexFields.every(field => {
      const regex = compiled[field];
      return regex ? regex.test(String(row[FILTER_FIELD_KEY[field]])) : false;
    }) &&
    activeSelectFields.every(([field, value]) => row[FILTER_FIELD_KEY[field]] === value)
  );
  return { rows: rowsMatching, invalidFields };
}

function updateFilterInputValidity(invalidFields) {
  Object.entries(filterInputs).forEach(([field, input]) => {
    input.classList.toggle('invalid', invalidFields.has(field));
  });
}

// ── Unified row model ────────────────────────────────────────────────────
//
// Both views ultimately render the same two data sources (paired xLights/WLED
// controllers + unpaired WLED devices) — this maps them into one common shape
// so filtering/sorting is written once and shared, rather than duplicated per
// view. Field-value logic (what counts as "reachable", how sync/backup text
// is derived) is never reinvented here — it just calls the same badge
// functions every render path already used.
//
// Note: report.unpairedXlightsControllers is NOT a separate set of controllers —
// it's the subset of controllerValidations where wledDevice is null. Including
// both here would show every unreachable controller twice; controllerValidations
// alone (paired.reachable === false covers it) is the complete list.

function buildRowModel() {
  if (!report) return [];

  const paired = report.controllerValidations.map(cv => {
    const name = cv.xLightsController.name;
    const reachable = !!cv.wledDevice;
    const ip = (cv.wledDevice && cv.wledDevice.ipAddress) || cv.xLightsController.ipAddress || '';
    const reach = reachBadge(reachable);
    const sync = reachable ? syncBadge(cv) : null;
    const backup = backupBadge(name);
    return {
      kind: 'paired', raw: cv, name, ip, reachable, selectable: reachable,
      reachableText: reach.text, reachableCls: reach.cls, reachableCategory: reachable ? 'reachable' : 'unreachable',
      syncText: sync ? sync.text : '', syncCls: sync ? sync.cls : '', syncCategory: sync ? (sync.cls === 'ok' ? 'in-sync' : 'needs-update') : '',
      backupText: backup.text, backupCls: backup.cls, backupCategory: backup.cls === 'ok' ? 'backed-up' : 'never-backed-up',
    };
  });

  const unpaired = report.unpairedWledDevices.map(dev => {
    const backup = backupBadge(dev.name);
    return {
      // Always reachable by construction — it only appears here because the
      // backend successfully fetched live data from it.
      kind: 'unpaired', raw: dev, name: dev.name, ip: dev.ipAddress, reachable: true, selectable: true,
      reachableText: '', reachableCls: '', reachableCategory: 'reachable',
      syncText: '', syncCls: '', syncCategory: '',
      backupText: backup.text, backupCls: backup.cls, backupCategory: backup.cls === 'ok' ? 'backed-up' : 'never-backed-up',
    };
  });

  return [...paired, ...unpaired];
}

// ── Card rendering ───────────────────────────────────────────────────────

function renderCards(rows) {
  cardGrid.innerHTML = '';

  if (rows.length === 0) {
    cardGrid.innerHTML = `<p class="empty-hint">${escapeHtml(noRowsMessage())}</p>`;
  } else {
    rows.forEach(row => cardGrid.appendChild(buildCard(row)));
  }
  updateBulkButtons();
}

function reachBadge(reachable) {
  return reachable ? { cls: 'ok', text: 'Reachable' } : { cls: 'err', text: 'Unreachable' };
}

function syncBadge(cv) {
  const issues = cv.segmentValidations.filter(sv => sv.status !== 'OK').length
    + (cv.orphanSegments ? cv.orphanSegments.length : 0)
    + (cv.totalLedsMatch ? 0 : 1);
  return issues === 0
    ? { cls: 'ok', text: 'In sync' }
    : { cls: 'warn', text: `${issues} segment${issues === 1 ? '' : 's'} need update` };
}

function latestBackupFor(controllerName) {
  return backups.find(b => b.controllerName.toLowerCase() === controllerName.toLowerCase()) || null;
}

function backupBadge(controllerName) {
  const b = latestBackupFor(controllerName);
  return b
    ? { cls: 'ok', text: `Backed up ${timeAgo(b.timestamp)}` }
    : { cls: 'warn', text: 'Never backed up' };
}

function buildCard(row) {
  const { name, ip, reachable, reachableText, reachableCls, syncText, syncCls, backupText, backupCls, kind } = row;
  const ipDisplay = kind === 'unpaired' ? `${ip} · not in xLights config` : (ip || 'no address');

  const card = document.createElement('div');
  card.className = 'card';
  card.innerHTML = `
    <div class="card-header">
      <div>
        <span class="card-title">${escapeHtml(name)}</span>
        <span class="card-ip">${escapeHtml(ipDisplay)}</span>
      </div>
      <label class="card-select">
        <input type="checkbox" class="select-box" ${reachable ? '' : 'disabled'} />
      </label>
    </div>
    <div class="badges">
      ${reachableText ? `<span class="badge ${reachableCls}">${escapeHtml(reachableText)}</span>` : ''}
      ${syncText ? `<span class="badge ${syncCls}">${escapeHtml(syncText)}</span>` : ''}
      <span class="badge ${backupCls}">${escapeHtml(backupText)}</span>
    </div>
  `;

  const checkbox = card.querySelector('.select-box');
  checkbox.checked = selected.has(name);
  checkbox.addEventListener('change', e => {
    if (e.target.checked) selected.add(name); else selected.delete(name);
    updateBulkButtons();
  });

  return card;
}

// ── List rendering ───────────────────────────────────────────────────────

const SORT_FIELD_KEY = { name: 'name', ip: 'ip', reachable: 'reachableText', sync: 'syncText', backup: 'backupText' };
const LIST_COLUMNS = [
  { key: null, label: '' },
  { key: 'name', label: 'Name' },
  { key: 'ip', label: 'Host' },
  { key: 'reachable', label: 'Reachable' },
  { key: 'sync', label: 'Sync' },
  { key: 'backup', label: 'Backup' },
];

let sortColumn = null; // one of LIST_COLUMNS' keys, or null for unsorted (upload order)
let sortDir = 'asc';    // 'asc' | 'desc'

function applySort(rows) {
  if (!sortColumn) return rows;
  const field = SORT_FIELD_KEY[sortColumn];
  const dir = sortDir === 'asc' ? 1 : -1;
  return [...rows].sort((a, b) =>
    dir * String(a[field]).localeCompare(String(b[field]), undefined, { sensitivity: 'base', numeric: true }));
}

function buildListHeader() {
  const header = document.createElement('div');
  header.className = 'list-header';
  LIST_COLUMNS.forEach(col => {
    if (!col.key) { header.appendChild(document.createElement('span')); return; }
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'list-sort-btn' + (sortColumn === col.key ? ' active' : '');
    const arrow = sortColumn === col.key ? (sortDir === 'asc' ? '▲' : '▼') : '';
    btn.innerHTML = `${escapeHtml(col.label)}<span class="sort-indicator">${arrow}</span>`;
    btn.addEventListener('click', () => {
      if (sortColumn === col.key) sortDir = sortDir === 'asc' ? 'desc' : 'asc';
      else { sortColumn = col.key; sortDir = 'asc'; }
      renderCurrentView();
    });
    header.appendChild(btn);
  });
  return header;
}

function renderList(rows) {
  listGrid.innerHTML = '';

  const sortedRows = applySort(rows);
  const listRows = sortedRows.map(buildListRow);

  listGrid.appendChild(buildListHeader());
  if (listRows.length === 0) {
    const hint = document.createElement('p');
    hint.className = 'empty-hint';
    hint.textContent = noRowsMessage();
    listGrid.appendChild(hint);
  } else {
    listRows.forEach(row => listGrid.appendChild(row));
  }
  updateBulkButtons();
}

function buildListRow(row) {
  const { name, reachable, reachableText, reachableCls, syncText, syncCls, backupText, backupCls, kind, ip } = row;
  const ipDisplay = kind === 'unpaired' ? `${ip} · not in xLights config` : (ip || 'no address');

  const el = document.createElement('div');
  el.className = 'list-row';
  el.innerHTML = `
    <label class="list-select">
      <input type="checkbox" class="select-box" ${reachable ? '' : 'disabled'} />
    </label>
    <span class="list-name">${escapeHtml(name)}</span>
    <span class="list-ip">${escapeHtml(ipDisplay)}</span>
    ${reachableText ? `<span class="badge ${reachableCls}">${escapeHtml(reachableText)}</span>` : '<span></span>'}
    ${syncText ? `<span class="badge ${syncCls}">${escapeHtml(syncText)}</span>` : '<span></span>'}
    <span class="badge ${backupCls}">${escapeHtml(backupText)}</span>
  `;

  const checkbox = el.querySelector('.select-box');
  checkbox.checked = selected.has(name);
  checkbox.addEventListener('change', e => {
    if (e.target.checked) selected.add(name); else selected.delete(name);
    updateBulkButtons();
  });

  return el;
}

// ── Selection ────────────────────────────────────────────────────────────

// Scoped to visibleRows (the current field-filtered set), never the full
// dataset — checking "Select All" under a filter must only ever select what
// you can actually see. This also merges into the existing selection rather
// than replacing it, so a selection built up across several filter passes
// survives. (Standard guidance for this pattern; see the plan doc for the
// real-world "unscoped select-all deleted 1900 records instead of the 96
// visible" cautionary tale that motivated it — this app's "Restore Selected"
// is exactly the kind of destructive action that makes it worth getting right.)
function selectAll() {
  visibleRows.filter(r => r.selectable).forEach(r => selected.add(r.name));
  renderCurrentView();
}

// Unlike selectAll(), this is a full unscoped reset — simple, predictable,
// always clears everything regardless of what's currently filtered/visible.
function selectNone() {
  selected.clear();
  renderCurrentView();
}

function updateBulkButtons() {
  const has = selected.size > 0;
  backupSelectedBtn.disabled = !has;
  restoreSelectedBtn.disabled = !has;
  updateSelectedBtn.disabled = !has;
  updateSelectionSummary();
}

function updateSelectionSummary() {
  if (selected.size === 0) {
    selectionSummaryEl.textContent = '';
    selectionSummaryEl.classList.remove('has-hidden');
    return;
  }
  const visibleNames = new Set(visibleRows.map(r => r.name));
  const hidden = [...selected].filter(n => !visibleNames.has(n)).length;
  selectionSummaryEl.textContent = hidden > 0
    ? `${selected.size} selected (${hidden} hidden by filter)`
    : `${selected.size} selected`;
  selectionSummaryEl.classList.toggle('has-hidden', hidden > 0);
}

// ── Actions (bulk-only — no per-item buttons remain in either view) ───────

async function doRestore(name, ip, backupRecord) {
  const ts = new Date(backupRecord.timestamp).getTime();
  const res = await fetch(
    `api/backups/${encodeURIComponent(name)}/restore/${ts}?ip=${encodeURIComponent(ip)}`,
    { method: 'POST' },
  );
  if (!res.ok) throw new Error(String(res.status));
}

async function updateToXlights(names) {
  if (names.length === 0) return;
  setSummary(`Updating ${names.length === 1 ? names[0] : names.length + ' controllers'}…`);
  try {
    const res = await fetch('api/fix', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ controllers: names }),
    });
    if (!res.ok) throw new Error(String(res.status));
    setSummary(`Updated ${names.length === 1 ? names[0] : names.length + ' controllers'}`);
    await refreshStatus();
  } catch (err) {
    setSummary(`Update failed — ${err.message}`, true);
  }
}

// ── Bulk actions ─────────────────────────────────────────────────────────

async function backupSelected() {
  const names = [...selected];
  if (names.length === 0) return;
  setSummary(`Backing up ${names.length} device(s)…`);

  const results = await Promise.allSettled(names.map(name => {
    const ip = ipForController(name);
    return fetch(`api/backups/${encodeURIComponent(name)}?ip=${encodeURIComponent(ip)}`, { method: 'POST' })
      .then(res => { if (!res.ok) throw new Error(String(res.status)); });
  }));

  reportBulkOutcome('backed up', names, results);
  await refreshStatus();
}

async function restoreSelected() {
  const names = [...selected].filter(name => latestBackupFor(name));
  if (names.length === 0) {
    setSummary('None of the selected devices have a backup to restore.', true);
    return;
  }
  if (!confirm(`Restore ${names.length} device(s) from their latest backup?\n\n${names.join(', ')}\n\nThis overwrites each device's current config and reboots it.`)) return;

  setSummary(`Restoring ${names.length} device(s)…`);
  const results = await Promise.allSettled(names.map(name => {
    const ip = ipForController(name);
    return doRestore(name, ip, latestBackupFor(name));
  }));

  reportBulkOutcome('restored', names, results);
  await refreshStatus();
}

function ipForController(name) {
  const cv = report.controllerValidations.find(c => c.xLightsController.name === name);
  if (cv && cv.wledDevice) return cv.wledDevice.ipAddress;
  const dev = report.unpairedWledDevices.find(d => d.name === name);
  return dev ? dev.ipAddress : '';
}

function reportBulkOutcome(verb, names, results) {
  const failures = results
    .map((r, i) => ({ r, name: names[i] }))
    .filter(x => x.r.status === 'rejected');

  if (failures.length === 0) {
    setSummary(`${names.length}/${names.length} ${verb}`);
  } else {
    const detail = failures.map(f => `${f.name}: ${f.r.reason?.message || 'failed'}`).join('; ');
    setSummary(`${names.length - failures.length}/${names.length} ${verb} — ${detail}`, true);
  }
}

// ── Clear All Controllers ───────────────────────────────────────────────

function openClearAllModal() {
  const deviceNames = buildRowModel().map(r => r.name); // live report + unpaired devices currently known client-side
  const backupCount = backups.length;
  const hasUpload = uploadStatusEl.textContent.startsWith('Last uploaded');

  clearAllOpsList.innerHTML = `
    <li>Reset ${deviceNames.length ? deviceNames.length + ' known device(s) (' + escapeHtml(deviceNames.join(', ')) + ')' : 'every reachable WLED device found via mDNS'} to WLED's hardware-default segments (best-effort — unreachable devices are skipped)</li>
    <li>Delete ${backupCount > 0 ? 'all ' + backupCount + ' saved backup(s)' : 'all saved backups (none currently exist)'}</li>
    <li>${hasUpload ? 'Delete the uploaded xLights show config' : 'No xLights show is currently uploaded — nothing to delete here'}</li>
  `;
  clearAllConfirmInput.value = '';
  clearAllConfirmBtn.disabled = true;
  clearAllModal.hidden = false;
  clearAllConfirmInput.focus();
}

function closeClearAllModal() {
  clearAllModal.hidden = true;
}

async function onClearAllConfirmed() {
  clearAllConfirmBtn.disabled = true;
  clearAllCancelBtn.disabled = true;
  setSummary('Clearing all controllers…');
  try {
    const res = await fetch('api/system/reset', { method: 'POST' });
    if (!res.ok) throw new Error(String(res.status));
    const result = await res.json();
    closeClearAllModal();
    await resetUiToPreUploadState();
    reportClearAllOutcome(result);
  } catch (err) {
    setSummary(`Clear All failed: ${err.message}`, true);
  } finally {
    clearAllCancelBtn.disabled = false;
  }
}

// Un-does the "uploaded" UI state entirely: clears in-memory report/backups/
// selection, re-fetches upload status (now 404 → "No upload yet."), and
// re-renders both grids back to their static pre-upload hint text (the same
// markup index.html ships with, not noRowsMessage()'s different wording).
async function resetUiToPreUploadState() {
  report = null;
  backups = [];
  selected.clear();
  sortColumn = null;
  await loadUploadStatus();
  folderLabelEl.textContent = '';
  pendingUpload = null;
  uploadBtn.disabled = true;
  const hint = '<p class="empty-hint">Upload an xLights show folder to see your WLED devices here.</p>';
  cardGrid.innerHTML = hint;
  listGrid.innerHTML = hint;
  updateBulkButtons();
}

function reportClearAllOutcome(result) {
  const total = result.deviceOutcomes.length;
  const succeeded = result.deviceOutcomes.filter(o => o.success).length;
  const parts = [`${succeeded}/${total} device(s) reset`];
  parts.push(result.backupsCleared ? 'backups cleared' : 'no backups to clear');
  parts.push(result.configCleared ? 'upload cleared' : 'no upload to clear');
  const failures = result.deviceOutcomes.filter(o => !o.success);
  const isError = failures.length > 0;
  const detail = isError
    ? ` — failed: ${failures.map(f => `${f.name}: ${f.error || 'unknown error'}`).join('; ')}`
    : '';
  setSummary(`Clear All Controllers: ${parts.join(', ')}${detail}`, isError);
}

// ── Helpers ──────────────────────────────────────────────────────────────

function setSummary(text, isError) {
  summaryLine.textContent = text || '';
  summaryLine.style.color = isError ? '#c62828' : '';
}

function timeAgo(isoString) {
  const diffMs = Date.now() - new Date(isoString).getTime();
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  return `${days}d ago`;
}

function escapeHtml(str) {
  return String(str)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}
