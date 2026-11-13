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
const setLookBtn = document.getElementById('setLookBtn');
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
const progressModal = document.getElementById('progressModal');
const progressTitle = document.getElementById('progressTitle');
const progressBarFill = document.getElementById('progressBarFill');
const progressStatusLine = document.getElementById('progressStatusLine');
const progressItemList = document.getElementById('progressItemList');
const progressErrorPanel = document.getElementById('progressErrorPanel');
const progressErrorName = document.getElementById('progressErrorName');
const progressErrorMessage = document.getElementById('progressErrorMessage');
const progressStopBtn = document.getElementById('progressStopBtn');
const progressContinueBtn = document.getElementById('progressContinueBtn');
const progressDoneFooter = document.getElementById('progressDoneFooter');
const progressDoneSummary = document.getElementById('progressDoneSummary');
const progressCloseBtn = document.getElementById('progressCloseBtn');
const BULK_TRIGGER_BUTTONS = [backupSelectedBtn, restoreSelectedBtn, updateSelectedBtn, setLookBtn, clearAllBtn];

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

// Set Look dialog state (see the Set Look section below).
const lookEls = {};
let lookCatalog = null;   // LookCatalog from api/look/catalog
let lookState = null;     // Look being edited: { on, bri, fx, sx, ix, c1, c2, c3, o1, o2, o3, pal, col }
let lookTargets = [];     // [{ name, ip }]
let lookSlot = 0;         // active color slot

// WLED's quick color swatches (wled00/data/index.htm), plus Random.
const LOOK_SWATCHES = ['#ff0000', '#ffa000', '#ffc800', '#ffe0a0', '#ffffff', '#000000',
  '#ff00ff', '#0000ff', '#00ffc8', '#08ff00'];
const LOOK_SLIDER_DEFAULTS = { sx: 128, ix: 128, c1: 128, c2: 128, c3: 16 };
// "* Color" palettes are built from the color slots, so picking one shows those
// slots even when the effect hides them (WLED updateSelectedPalette, ids 2–5).
const LOOK_PALETTE_SLOTS = { '* Color 1': 1, '* Colors 1&2': 2, '* Color Gradient': 3, '* Colors Only': 3 };

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
  setLookBtn.addEventListener('click', openLookModal);
  initLookModal();
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
      syncText: sync ? sync.text : '', syncCls: sync ? sync.cls : '', syncTitle: sync ? sync.title : '', syncCategory: sync ? (sync.cls === 'ok' ? 'in-sync' : 'needs-update') : '',
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
      syncText: '', syncCls: '', syncTitle: '', syncCategory: '',
      backupText: backup.text, backupCls: backup.cls, backupCategory: backup.cls === 'ok' ? 'backed-up' : 'never-backed-up',
    };
  });

  const show = (report.showControllers || []).map(sc => {
    const access = fppAccessBadge(sc);
    return {
      // FPP show controllers: reachability only. They have no WLED segments to
      // sync and no WLED config to back up, so they can't be selected for those.
      kind: 'fpp', raw: sc, name: sc.name, ip: sc.ipAddress || '', reachable: sc.access === 'REACHABLE', selectable: false,
      reachableText: access.text, reachableCls: access.cls, reachableTitle: access.title,
      reachableCategory: sc.access === 'REACHABLE' ? 'reachable' : 'unreachable',
      syncText: 'FPP show controller', syncCls: 'na', syncTitle: sc.version ? `FPP ${sc.version}` : '', syncCategory: 'n/a',
      backupText: 'Backup N/A', backupCls: 'na', backupCategory: 'n/a',
    };
  });

  return [...paired, ...unpaired, ...show];
}

const FPP_ACCESS_BADGE = {
  REACHABLE:     { cls: 'ok',   text: 'Reachable' },
  AUTH_REQUIRED: { cls: 'warn', text: 'Needs credentials', hint: 'FPP asks for a password. Set FPP_PASSWORD (and FPP_USERNAME if not admin) on the server.' },
  AUTH_FAILED:   { cls: 'err',  text: 'Credentials rejected', hint: 'FPP rejected the configured FPP_USERNAME / FPP_PASSWORD.' },
  UNREACHABLE:   { cls: 'err',  text: 'Unreachable' },
};

function fppAccessBadge(sc) {
  const b = FPP_ACCESS_BADGE[sc.access] || FPP_ACCESS_BADGE.UNREACHABLE;
  const title = [b.hint, sc.access === 'REACHABLE' && sc.version ? `FPP ${sc.version}` : sc.error].filter(Boolean).join('\n');
  return { cls: b.cls, text: b.text, title };
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

const SEGMENT_STATUS_TEXT = {
  MISSING: 'missing on WLED',
  RANGE_MISMATCH: 'wrong pixel range',
  NULL_NOT_OFF: 'null model not turned off',
};

// Badge text separates segment problems (which Update fixes) from an LED-count
// mismatch (a WLED LED Preferences setting that Update does not touch). The full
// per-segment breakdown goes in the badge's tooltip.
function syncBadge(cv) {
  const details = [];
  cv.segmentValidations.filter(sv => sv.status !== 'OK').forEach(sv => {
    let line = `${sv.model.name}: ${SEGMENT_STATUS_TEXT[sv.status] || sv.status}`;
    if (sv.status === 'RANGE_MISMATCH' && sv.actualSegment) {
      line += ` (WLED ${sv.actualSegment.start}-${sv.actualSegment.stop}, xLights ${sv.expectedPixelStart}-${sv.expectedPixelStop})`;
    }
    details.push(line);
  });
  (cv.orphanSegments || []).forEach(seg =>
    details.push(`WLED segment ${seg.id}${seg.name ? ` "${seg.name}"` : ''}: no matching xLights model`));
  const segIssues = details.length;

  if (!cv.totalLedsMatch && cv.wledDevice) {
    details.push(`LED count: WLED has ${cv.wledDevice.totalLeds}, xLights expects ${cv.expectedTotalLeds} ` +
      '(change in WLED LED Preferences; Update does not change this)');
  }
  if (cv.segmentLimitWarning) details.push(cv.segmentLimitWarning);

  const parts = [];
  if (segIssues > 0) parts.push(`${segIssues} segment${segIssues === 1 ? '' : 's'} need update`);
  if (!cv.totalLedsMatch) parts.push('LED count differs');
  return parts.length === 0
    ? { cls: 'ok', text: 'In sync', title: '' }
    : { cls: 'warn', text: parts.join(' · '), title: details.join('\n') };
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

// The controller's address, rendered as a link that opens its WLED web UI in
// a new tab. Unreachable controllers still get a link: the address comes from
// the xLights config, and the browser may reach it even when the server can't.
function ipHtml(ip, kind) {
  if (!ip) return 'no address';
  const link = `<a class="ip-link" href="http://${escapeHtml(encodeURI(ip))}/" target="_blank" rel="noopener noreferrer" title="Open the WLED UI for ${escapeHtml(ip)}">${escapeHtml(ip)}</a>`;
  if (kind === 'unpaired') return `${link} · not in xLights config`;
  return link;
}

function buildCard(row) {
  const { name, ip, reachable, reachableText, reachableCls, reachableTitle, syncText, syncCls, syncTitle, backupText, backupCls, kind } = row;
  const card = document.createElement('div');
  card.className = 'card';
  card.innerHTML = `
    <div class="card-header">
      <div>
        <span class="card-title">${escapeHtml(name)}</span>
        <span class="card-ip">${ipHtml(ip, kind)}</span>
      </div>
      <label class="card-select">
        <input type="checkbox" class="select-box" ${row.selectable ? '' : 'disabled'} />
      </label>
    </div>
    <div class="badges">
      ${reachableText ? `<span class="badge ${reachableCls}" title="${escapeHtml(reachableTitle || '')}">${escapeHtml(reachableText)}</span>` : ''}
      ${syncText ? `<span class="badge ${syncCls}" title="${escapeHtml(syncTitle)}">${escapeHtml(syncText)}</span>` : ''}
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
  const { name, reachable, reachableText, reachableCls, reachableTitle, syncText, syncCls, syncTitle, backupText, backupCls, kind, ip } = row;
  const el = document.createElement('div');
  el.className = 'list-row';
  el.innerHTML = `
    <label class="list-select">
      <input type="checkbox" class="select-box" ${row.selectable ? '' : 'disabled'} />
    </label>
    <span class="list-name">${escapeHtml(name)}</span>
    <span class="list-ip">${ipHtml(ip, kind)}</span>
    ${reachableText ? `<span class="badge ${reachableCls}" title="${escapeHtml(reachableTitle || '')}">${escapeHtml(reachableText)}</span>` : '<span></span>'}
    ${syncText ? `<span class="badge ${syncCls}" title="${escapeHtml(syncTitle)}">${escapeHtml(syncText)}</span>` : '<span></span>'}
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
  const blockers = setLookBlockers();
  setLookBtn.disabled = !has || blockers.length > 0;
  setLookBtn.title = has && blockers.length > 0
    ? 'Every selected controller must be paired with xLights and in sync first:\n' + blockers.join('\n')
    : '';
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

function updateToXlights(names) {
  if (names.length === 0) return;
  runSequentialBulk({
    title: 'Updating Controllers',
    items: names,
    action: async name => {
      const res = await fetch('api/fix', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ controllers: [name] }),
      });
      if (!res.ok) throw new Error(String(res.status));
      // /api/fix returns 200 even when the device push fails; per-controller
      // failures are reported in updateErrors.
      const errors = (await res.json()).updateErrors || {};
      if (errors[name]) throw new Error(errors[name]);
    },
  });
}

// ── Bulk actions ─────────────────────────────────────────────────────────

function backupSelected() {
  const names = [...selected];
  if (names.length === 0) return;
  runSequentialBulk({
    title: 'Backing Up Controllers',
    items: names,
    action: async name => {
      const ip = ipForController(name);
      const res = await fetch(`api/backups/${encodeURIComponent(name)}?ip=${encodeURIComponent(ip)}`, { method: 'POST' });
      if (!res.ok) throw new Error(String(res.status));
    },
  });
}

function restoreSelected() {
  const names = [...selected].filter(name => latestBackupFor(name));
  if (names.length === 0) {
    setSummary('None of the selected devices have a backup to restore.', true);
    return;
  }
  if (!confirm(`Restore ${names.length} device(s) from their latest backup?\n\n${names.join(', ')}\n\nThis overwrites each device's current config and reboots it.`)) return;

  runSequentialBulk({
    title: 'Restoring Controllers',
    items: names,
    action: name => doRestore(name, ipForController(name), latestBackupFor(name)),
  });
}

function ipForController(name) {
  const cv = report.controllerValidations.find(c => c.xLightsController.name === name);
  if (cv && cv.wledDevice) return cv.wledDevice.ipAddress;
  const dev = report.unpairedWledDevices.find(d => d.name === name);
  return dev ? dev.ipAddress : '';
}

// ── Bulk operation progress modal ───────────────────────────────────────
//
// Two runners share one modal (#progressModal):
// - runSequentialBulk: processes items one at a time (Backup/Restore/Update
//   Selected), so a failure can genuinely pause the batch — the error panel
//   offers Continue (skip it, keep going) or Stop (abandon the rest).
// - runAtomicBulk: for operations that are already one indivisible backend
//   call (Clear All Controllers) — shows an indeterminate bar while the
//   single request is in flight, then renders the full per-item result at
//   once from the response. There's nothing to "pause" mid-call.

const ITEM_ICON = { pending: '·', active: '◐', success: '✓', failed: '✕', skipped: '−' };

function buildItemRow(name, status, detail) {
  const li = document.createElement('li');
  li.className = status;
  li.innerHTML = `<span class="item-icon">${ITEM_ICON[status] || '·'}</span><span>${escapeHtml(name)}${detail ? ' — ' + escapeHtml(detail) : ''}</span>`;
  return li;
}

// Centre a row in the scrollable item list, leaving the rows just finished
// visible above it. Scrolls only the list, never the page behind the modal.
function scrollItemIntoView(row) {
  if (!row) return;
  const list = progressItemList;
  const target = row.offsetTop - (list.clientHeight - row.offsetHeight) / 2;
  list.scrollTop = Math.max(0, target);
}

function openProgressModal(title) {
  BULK_TRIGGER_BUTTONS.forEach(b => { b.disabled = true; });
  progressTitle.textContent = title;
  progressBarFill.classList.remove('indeterminate');
  progressBarFill.style.width = '0%';
  progressItemList.innerHTML = '';
  progressErrorPanel.hidden = true;
  progressDoneFooter.hidden = true;
  progressModal.hidden = false;
}

function finishProgressModal(summaryText, isError, onClose) {
  progressStatusLine.textContent = '';
  progressDoneSummary.textContent = summaryText;
  progressDoneFooter.hidden = false;
  progressCloseBtn.onclick = () => {
    progressModal.hidden = true;
    updateBulkButtons();       // re-derives backup/restore/update disabled state from `selected`
    clearAllBtn.disabled = false;
    setSummary(summaryText, isError);
    if (onClose) onClose();
  };
}

function runSequentialBulk({ title, items, action }) {
  const statuses = {};
  items.forEach(name => { statuses[name] = 'pending'; });
  let stopped = false;
  let succeeded = 0;
  let failed = 0;

  // Index of the item being worked on (or the one that just failed). The list
  // keeps it in view so long batches show what's running and what's finished.
  let current = 0;

  function render() {
    progressItemList.innerHTML = '';
    items.forEach(name => progressItemList.appendChild(buildItemRow(name, statuses[name])));
    scrollItemIntoView(progressItemList.children[current]);
  }

  function done() {
    const skipped = items.length - succeeded - failed;
    const summary = `${succeeded}/${items.length} succeeded` +
      (failed > 0 ? `, ${failed} failed` : '') +
      (skipped > 0 ? `, ${skipped} skipped` : '');
    finishProgressModal(summary, failed > 0, () => refreshStatus());
  }

  function next(index) {
    if (stopped || index >= items.length) { done(); return; }
    current = index;
    const name = items[index];
    statuses[name] = 'active';
    render();
    progressStatusLine.textContent = `Processing ${name} (${index + 1} of ${items.length})…`;

    Promise.resolve(action(name)).then(() => {
      statuses[name] = 'success';
      succeeded++;
      render();
      progressBarFill.style.width = `${Math.round(((index + 1) / items.length) * 100)}%`;
      next(index + 1);
    }).catch(err => {
      statuses[name] = 'failed';
      failed++;
      render();
      progressBarFill.style.width = `${Math.round(((index + 1) / items.length) * 100)}%`;
      progressErrorName.textContent = name;
      progressErrorMessage.textContent = err.message || 'Unknown error';
      progressErrorPanel.hidden = false;

      progressContinueBtn.onclick = () => {
        progressErrorPanel.hidden = true;
        next(index + 1);
      };
      progressStopBtn.onclick = () => {
        stopped = true;
        progressErrorPanel.hidden = true;
        for (let i = index + 1; i < items.length; i++) statuses[items[i]] = 'skipped';
        render();
        done();
      };
    });
  }

  openProgressModal(title);
  render();
  progressStatusLine.textContent = `0 of ${items.length} complete`;
  next(0);
}

function runAtomicBulk({ title, request, mapResult, onSuccess }) {
  openProgressModal(title);
  progressBarFill.classList.add('indeterminate');
  progressStatusLine.textContent = 'Working…';

  request().then(result => {
    progressBarFill.classList.remove('indeterminate');
    progressBarFill.style.width = '100%';
    const { items, summary } = mapResult(result);
    progressItemList.innerHTML = '';
    items.forEach(({ name, status, detail }) => progressItemList.appendChild(buildItemRow(name, status, detail)));
    finishProgressModal(summary, items.some(i => i.status === 'failed'), onSuccess);
  }).catch(err => {
    progressBarFill.classList.remove('indeterminate');
    finishProgressModal(`Failed: ${err.message}`, true);
  });
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

function onClearAllConfirmed() {
  closeClearAllModal();
  runAtomicBulk({
    title: 'Clearing All Controllers',
    request: async () => {
      const res = await fetch('api/system/reset', { method: 'POST' });
      if (!res.ok) throw new Error(String(res.status));
      return res.json();
    },
    mapResult: result => {
      const items = result.deviceOutcomes.map(o => ({
        name: o.name,
        status: o.success ? 'success' : 'failed',
        detail: o.success ? null : (o.error || 'unknown error'),
      }));
      const succeeded = result.deviceOutcomes.filter(o => o.success).length;
      const parts = [`${succeeded}/${result.deviceOutcomes.length} device(s) reset`];
      parts.push(result.backupsCleared ? 'backups cleared' : 'no backups to clear');
      parts.push(result.configCleared ? 'upload cleared' : 'no upload to clear');
      return { items, summary: parts.join(', ') };
    },
    onSuccess: () => resetUiToPreUploadState(),
  });
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

// ── Set Look ─────────────────────────────────────────────────────────────
//
// Puts one effect/colors/palette on every selected controller at the same
// moment. Meant for outside show hours: the backend never overrides live show
// data. Nothing is sent until Apply, and Apply always sends the whole look.
// Only controllers that are paired with xLights and fully in sync (segments and
// LED count) can be used, since the backend targets segments by xLights model.

function initLookModal() {
  ['lookModal', 'lookTargets', 'lookNotice', 'lookLoading', 'lookBody', 'lookOn', 'lookOnLabel', 'lookBri',
    'lookBriVal', 'lookFxSearch', 'lookFxList', 'lookOpts', 'lookColors', 'lookSlots', 'lookColor',
    'lookSwatches', 'lookColorsUnused', 'lookPalLabel', 'lookPalWrap', 'lookPalSearch', 'lookPalList',
    'lookPalUnused', 'lookRefreshBtn', 'lookCloseBtn', 'lookApplyBtn'].forEach(id => { lookEls[id] = document.getElementById(id); });

  lookEls.lookCloseBtn.addEventListener('click', () => { lookEls.lookModal.hidden = true; });
  lookEls.lookRefreshBtn.addEventListener('click', () => loadLookCatalog(true));
  lookEls.lookApplyBtn.addEventListener('click', applyLook);
  lookEls.lookOn.addEventListener('change', () => { lookState.on = lookEls.lookOn.checked; renderLookPower(); });
  lookEls.lookBri.addEventListener('input', () => { lookState.bri = Number(lookEls.lookBri.value); renderLookPower(); });
  lookEls.lookFxSearch.addEventListener('input', renderLookEffects);
  lookEls.lookPalSearch.addEventListener('input', renderLookPalettes);
  lookEls.lookColor.addEventListener('input', () => setLookColor(hexToRgb(lookEls.lookColor.value)));

  LOOK_SWATCHES.forEach(hex => lookEls.lookSwatches.appendChild(swatchButton(hex, hex, () => setLookColor(hexToRgb(hex)))));
  lookEls.lookSwatches.appendChild(swatchButton('Random', null, () => setLookColor(randomColor()), 'R'));
}

function swatchButton(title, hex, onClick, text) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'look-swatch' + (hex ? '' : ' random');
  b.title = title;
  if (hex) b.style.background = hex;
  if (text) b.textContent = text;
  b.addEventListener('click', onClick);
  return b;
}

// Why each selected controller can't take a look; empty when all of them can.
function setLookBlockers() {
  if (!report) return [];
  const out = [];
  for (const name of selected) {
    const cv = report.controllerValidations.find(c => c.xLightsController.name === name);
    if (!cv) { out.push(`${name}: not paired with an xLights controller`); continue; }
    if (!cv.wledDevice) { out.push(`${name}: unreachable`); continue; }
    const sync = syncBadge(cv);
    if (sync.cls !== 'ok') {
      const fix = cv.totalLedsMatch ? 'run Update Selected' : 'fix the LED count in WLED LED Preferences';
      out.push(`${name}: ${sync.text} (${fix})`);
    }
  }
  return out;
}

function openLookModal() {
  if (selected.size === 0 || setLookBlockers().length > 0) return;
  lookTargets = [...selected].map(name => ({ name, ip: ipForController(name) }));
  lookEls.lookTargets.textContent = `${lookTargets.length} controller${lookTargets.length === 1 ? '' : 's'}: ${lookTargets.map(t => t.name).join(', ')}`;
  lookEls.lookFxSearch.value = '';
  lookEls.lookPalSearch.value = '';
  lookEls.lookModal.hidden = false;
  loadLookCatalog(false);
}

async function loadLookCatalog(refresh) {
  lookEls.lookBody.hidden = true;
  lookEls.lookNotice.hidden = true;
  lookEls.lookLoading.hidden = false;
  lookEls.lookLoading.textContent = 'Loading effects from the controllers…';
  lookEls.lookApplyBtn.disabled = true;
  lookEls.lookRefreshBtn.disabled = true;
  try {
    const ips = lookTargets.map(t => t.ip).filter(Boolean);
    const res = await fetch(`api/look/catalog?ips=${encodeURIComponent(ips.join(','))}${refresh ? '&refresh=true' : ''}`);
    if (!res.ok) throw new Error(String(res.status));
    lookCatalog = await res.json();
  } catch (err) {
    lookEls.lookLoading.textContent = `Could not load effects: ${err.message}`;
    lookEls.lookRefreshBtn.disabled = false;
    return;
  }
  lookEls.lookRefreshBtn.disabled = false;
  renderLookNotice();
  if (lookCatalog.effects.length === 0) {
    lookEls.lookLoading.textContent = 'None of the selected controllers could be reached.';
    return;
  }
  // Keep edits across a Refresh; otherwise start from what the first controller shows now.
  const base = (refresh && lookState) ? lookState : (lookCatalog.current || {});
  const keepPower = refresh && lookState ? lookState.on : true;
  lookState = normalizeLook(base);
  lookState.on = keepPower;
  lookSlot = 0;
  lookEls.lookLoading.hidden = true;
  lookEls.lookBody.hidden = false;
  lookEls.lookApplyBtn.disabled = false;
  renderLook();
}

function normalizeLook(l) {
  const fx = lookCatalog.effects.some(e => e.name === l.fx) ? l.fx : lookCatalog.effects[0].name;
  const pal = lookCatalog.palettes.includes(l.pal) ? l.pal : (lookCatalog.palettes[0] || 'Default');
  const col = [0, 1, 2].map(i => (l.col && l.col[i]) ? l.col[i].slice(0, 3) : [0, 0, 0]);
  return {
    // Default to On: the controller may be off now, but the point here is to show a look.
    on: true, bri: clampInt(l.bri ?? 128, 1, 255), fx,
    sx: l.sx ?? 128, ix: l.ix ?? 128, c1: l.c1 ?? 128, c2: l.c2 ?? 128, c3: l.c3 ?? 16,
    o1: !!l.o1, o2: !!l.o2, o3: !!l.o3, pal, col,
  };
}

function renderLookNotice() {
  const nameFor = ip => (lookTargets.find(t => t.ip === ip) || { name: ip }).name;
  const lines = [];
  (lookCatalog.live || []).forEach(ip =>
    lines.push(`${nameFor(ip)} is playing the show; the look won't show there until the show stops.`));
  Object.entries(lookCatalog.unreachable || {}).forEach(([ip, err]) =>
    lines.push(`Couldn't reach ${nameFor(ip)} (${err}); only effects the reachable controllers share are listed.`));
  lookEls.lookNotice.innerHTML = lines.map(l => `<div>${escapeHtml(l)}</div>`).join('');
  lookEls.lookNotice.hidden = lines.length === 0;
}

function currentEffect() {
  return lookCatalog.effects.find(e => e.name === lookState.fx) || lookCatalog.effects[0];
}

function renderLook() {
  renderLookPower();
  renderLookEffects();
  renderLookOptions();
  renderLookColors();
  renderLookPalettes();
}

function renderLookPower() {
  lookEls.lookOn.checked = lookState.on;
  lookEls.lookOnLabel.textContent = lookState.on ? 'On' : 'Off (the look is set but stays dark until the power is turned on)';
  lookEls.lookBri.value = lookState.bri;
  lookEls.lookBriVal.textContent = lookState.bri;
  lookEls.lookBri.disabled = !lookState.on;
}

function effectIcons(controls) {
  let icons = '';
  if (controls.usesPalette) icons += ' 🎨';
  if (controls.flags.includes('0')) icons += ' •';
  if (controls.flags.includes('v')) icons += ' ♪';
  if (controls.flags.includes('f')) icons += ' ♫';
  return icons;
}

function lookListItem(label, icons, isSelected, onPick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'look-item' + (isSelected ? ' selected' : '');
  b.setAttribute('role', 'option');
  b.setAttribute('aria-selected', String(isSelected));
  b.innerHTML = `<span>${escapeHtml(label)}</span>${icons ? `<span class="look-item-icons">${escapeHtml(icons)}</span>` : ''}`;
  b.addEventListener('click', onPick);
  return b;
}

function filterByQuery(names, query) {
  const q = query.trim().toLowerCase();
  return q ? names.filter(n => n.toLowerCase().includes(q)) : names;
}

function renderLookEffects() {
  const list = lookEls.lookFxList;
  list.innerHTML = '';
  const names = filterByQuery(lookCatalog.effects.map(e => e.name), lookEls.lookFxSearch.value);
  if (names.length === 0) list.innerHTML = '<p class="look-unused">No matching effects</p>';
  names.forEach(name => {
    const fx = lookCatalog.effects.find(e => e.name === name);
    list.appendChild(lookListItem(name, effectIcons(fx.controls), name === lookState.fx, () => pickEffect(fx)));
  });
  const sel = list.querySelector('.selected');
  if (sel) sel.scrollIntoView({ block: 'nearest' });
}

// Like WLED with "effect defaults" on: picking an effect resets its options to
// the firmware defaults, then applies the effect's own defaults from fxdata.
function pickEffect(fx) {
  lookState.fx = fx.name;
  Object.assign(lookState, LOOK_SLIDER_DEFAULTS, { o1: false, o2: false, o3: false });
  Object.entries(fx.controls.defaults || {}).forEach(([k, v]) => {
    if (k in LOOK_SLIDER_DEFAULTS) lookState[k] = v;
    else if (k === 'o1' || k === 'o2' || k === 'o3') lookState[k] = !!v;
  });
  if (fx.defaultPalette && lookCatalog.palettes.includes(fx.defaultPalette)) lookState.pal = fx.defaultPalette;
  renderLook();
}

function renderLookOptions() {
  const { controls } = currentEffect();
  const wrap = lookEls.lookOpts;
  wrap.innerHTML = '';
  if (controls.sliders.length === 0 && controls.checkboxes.length === 0) {
    wrap.innerHTML = '<p class="look-unused">This effect has no options</p>';
    return;
  }
  controls.sliders.forEach(({ key, label }) => {
    const row = document.createElement('label');
    row.className = 'look-slider';
    row.innerHTML = `<span class="look-slider-label">${escapeHtml(label)}</span>` +
      `<input type="range" min="0" max="${key === 'c3' ? 31 : 255}" value="${Number(lookState[key])}" />` +
      `<output class="look-slider-value">${Number(lookState[key])}</output>`;
    const input = row.querySelector('input');
    const out = row.querySelector('output');
    input.addEventListener('input', () => { lookState[key] = Number(input.value); out.textContent = input.value; });
    wrap.appendChild(row);
  });
  controls.checkboxes.forEach(({ key, label }) => {
    const row = document.createElement('label');
    row.className = 'look-check';
    row.innerHTML = `<input type="checkbox" ${lookState[key] ? 'checked' : ''} /> <span>${escapeHtml(label)}</span>`;
    row.querySelector('input').addEventListener('change', e => { lookState[key] = e.target.checked; });
    wrap.appendChild(row);
  });
}

// Slot labels for the current effect, with "* Color" palettes force-showing
// the slots they're built from. null = slot hidden.
function visibleSlots() {
  const { controls } = currentEffect();
  const slots = controls.colors.slice();
  const forced = controls.usesPalette ? (LOOK_PALETTE_SLOTS[lookState.pal] || 0) : 0;
  for (let i = 0; i < forced; i++) if (slots[i] == null) slots[i] = String(i + 1);
  return slots;
}

function renderLookColors() {
  const slots = visibleSlots();
  const any = slots.some(s => s != null);
  lookEls.lookColors.hidden = !any;
  lookEls.lookColorsUnused.hidden = any;
  if (!any) return;
  if (slots[lookSlot] == null) lookSlot = slots.findIndex(s => s != null);

  lookEls.lookSlots.innerHTML = '';
  slots.forEach((label, i) => {
    if (label == null) return;
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'look-slot' + (i === lookSlot ? ' active' : '');
    b.title = `Color slot ${i + 1}`;
    b.innerHTML = `<span class="look-slot-chip" style="background:${rgbToHex(lookState.col[i])}"></span><span>${escapeHtml(label)}</span>`;
    b.addEventListener('click', () => { lookSlot = i; renderLookColors(); });
    lookEls.lookSlots.appendChild(b);
  });
  lookEls.lookColor.value = rgbToHex(lookState.col[lookSlot]);
}

function setLookColor(rgb) {
  lookState.col[lookSlot] = rgb;
  renderLookColors();
}

function renderLookPalettes() {
  const { controls } = currentEffect();
  lookEls.lookPalLabel.textContent = controls.paletteLabel || 'Color palette';
  lookEls.lookPalWrap.hidden = !controls.usesPalette;
  lookEls.lookPalUnused.hidden = controls.usesPalette;
  if (!controls.usesPalette) return;
  const list = lookEls.lookPalList;
  list.innerHTML = '';
  const names = filterByQuery(lookCatalog.palettes, lookEls.lookPalSearch.value);
  if (names.length === 0) list.innerHTML = '<p class="look-unused">No matching palettes</p>';
  names.forEach(name => list.appendChild(lookListItem(name, '', name === lookState.pal, () => {
    lookState.pal = name;
    renderLookPalettes();
    renderLookColors();
  })));
  const sel = list.querySelector('.selected');
  if (sel) sel.scrollIntoView({ block: 'nearest' });
}

function applyLook() {
  const look = { ...lookState, col: lookState.col.map(c => c.slice()) };
  const targets = lookTargets.slice();
  runAtomicBulk({
    title: look.on ? `Setting Look: ${look.fx}` : `Setting Look: ${look.fx} (power off)`,
    request: async () => {
      const res = await fetch('api/look/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ controllers: targets, look }),
      });
      if (!res.ok) throw new Error(String(res.status));
      return res.json();
    },
    mapResult: outcomes => {
      const items = outcomes.map(o => ({
        name: o.name,
        status: o.success ? 'success' : 'failed',
        detail: o.success ? null : (o.error || 'unknown error'),
      }));
      const ok = outcomes.filter(o => o.success).length;
      return { items, summary: `${ok}/${outcomes.length} controller(s) set` };
    },
    // The dialog stays open underneath so the look can be tweaked and re-applied.
  });
}

function clampInt(v, lo, hi) { return Math.max(lo, Math.min(hi, Math.round(Number(v) || 0))); }

function rgbToHex(rgb) {
  return '#' + (rgb || [0, 0, 0]).slice(0, 3).map(v => clampInt(v, 0, 255).toString(16).padStart(2, '0')).join('');
}

function hexToRgb(hex) {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// Same idea as WLED's random swatch: a random fully saturated hue. Picked here,
// once, so every controller gets the same color.
function randomColor() {
  const h = Math.random() * 6;
  const x = Math.round(255 * (1 - Math.abs((h % 2) - 1)));
  return [[255, x, 0], [x, 255, 0], [0, 255, x], [0, x, 255], [x, 0, 255], [255, 0, x]][Math.floor(h)];
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
