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

let report = null;          // last ValidationReport
let backups = [];           // last WledBackupRecord[]
let pendingUpload = null;   // { networksFile, effectsFile, folderLabel }
const selected = new Set(); // controller names selected via card checkboxes

init();

function init() {
  uploadBtn.addEventListener('click', onUploadClicked);
  refreshBtn.addEventListener('click', () => refreshStatus());
  selectAllBtn.addEventListener('click', selectAll);
  selectNoneBtn.addEventListener('click', selectNone);
  backupSelectedBtn.addEventListener('click', backupSelected);
  restoreSelectedBtn.addEventListener('click', restoreSelected);
  updateSelectedBtn.addEventListener('click', () => updateToXlights([...selected]));

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
    const res = await fetch('/api/upload/status');
    if (res.status === 404) {
      uploadStatusEl.textContent = 'No upload yet.';
      return false;
    }
    if (!res.ok) throw new Error(String(res.status));
    const status = await res.json();
    uploadStatusEl.textContent = `Last uploaded: ${status.folderLabel} · ${timeAgo(status.uploadedAt)}`;
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
    const res = await fetch('/api/upload', {
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
      fetch('/api/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      }),
      fetch('/api/backups'),
    ]);
    if (!reportRes.ok) throw new Error(`validate: ${reportRes.status}`);
    if (!backupsRes.ok) throw new Error(`backups: ${backupsRes.status}`);
    report = await reportRes.json();
    backups = await backupsRes.json();
    selected.clear();
    renderCards();
    setSummary('');
  } catch (err) {
    setSummary(`Refresh failed: ${err.message}`, true);
  } finally {
    refreshBtn.disabled = false;
  }
}

// ── Card rendering ───────────────────────────────────────────────────────

function renderCards() {
  cardGrid.innerHTML = '';
  if (!report) return;

  // Note: report.unpairedXlightsControllers is NOT a separate set of controllers —
  // it's the subset of controllerValidations where wledDevice is null. Rendering
  // both would show every unreachable controller twice; controllerValidations alone
  // (via buildPairedCard, which already handles the unpaired/unreachable case) is
  // the complete list of xLights controllers.
  const cards = [
    ...report.controllerValidations.map(buildPairedCard),
    ...report.unpairedWledDevices.map(buildUnpairedWledCard),
  ];

  if (cards.length === 0) {
    cardGrid.innerHTML = '<p class="empty-hint">No controllers found in the uploaded xLights config.</p>';
  } else {
    cards.forEach(c => cardGrid.appendChild(c));
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

function buildPairedCard(cv) {
  const name = cv.xLightsController.name;
  const reachable = !!cv.wledDevice;
  const ip = (cv.wledDevice && cv.wledDevice.ipAddress) || cv.xLightsController.ipAddress || '';
  const reach = reachBadge(reachable);
  const sync = reachable ? syncBadge(cv) : null;
  const backup = backupBadge(name);
  const lastBackup = latestBackupFor(name);

  const card = document.createElement('div');
  card.className = 'card';
  card.innerHTML = `
    <div class="card-header">
      <div>
        <span class="card-title">${escapeHtml(name)}</span>
        <span class="card-ip">${escapeHtml(ip || 'no address')}</span>
      </div>
      <label class="card-select">
        <input type="checkbox" class="select-box" ${reachable ? '' : 'disabled'} />
      </label>
    </div>
    <div class="badges">
      <span class="badge ${reach.cls}">${escapeHtml(reach.text)}</span>
      ${sync ? `<span class="badge ${sync.cls}">${escapeHtml(sync.text)}</span>` : ''}
      <span class="badge ${backup.cls}">${escapeHtml(backup.text)}</span>
    </div>
    <div class="card-actions">
      <button class="backup-now" ${reachable ? '' : 'disabled'}>Backup Now</button>
      <button class="restore-latest" ${lastBackup && reachable ? '' : 'disabled'}>Restore Latest</button>
      <button class="update-xlights" ${reachable && sync.cls !== 'ok' ? '' : 'disabled'}>Update segments</button>
    </div>
  `;

  const checkbox = card.querySelector('.select-box');
  checkbox.checked = selected.has(name);
  checkbox.addEventListener('change', e => {
    if (e.target.checked) selected.add(name); else selected.delete(name);
    updateBulkButtons();
  });
  card.querySelector('.backup-now').addEventListener('click', () => backupNow(name, ip));
  card.querySelector('.restore-latest').addEventListener('click', () => restoreOne(name, ip, lastBackup));
  card.querySelector('.update-xlights').addEventListener('click', () => updateToXlights([name]));

  return card;
}

function buildUnpairedWledCard(dev) {
  const backup = backupBadge(dev.name);
  const card = document.createElement('div');
  card.className = 'card';
  card.innerHTML = `
    <div class="card-header">
      <div>
        <span class="card-title">${escapeHtml(dev.name)}</span>
        <span class="card-ip">${escapeHtml(dev.ipAddress)} · not in xLights config</span>
      </div>
    </div>
    <div class="badges">
      <span class="badge ${backup.cls}">${escapeHtml(backup.text)}</span>
    </div>
    <div class="card-actions">
      <button class="backup-now">Backup Now</button>
    </div>
  `;
  card.querySelector('.backup-now').addEventListener('click', () => backupNow(dev.name, dev.ipAddress));
  return card;
}

// ── Selection ────────────────────────────────────────────────────────────

function selectAll() {
  cardGrid.querySelectorAll('.select-box:not(:disabled)').forEach(cb => { cb.checked = true; });
  report.controllerValidations
    .filter(cv => cv.wledDevice)
    .forEach(cv => selected.add(cv.xLightsController.name));
  updateBulkButtons();
}

function selectNone() {
  cardGrid.querySelectorAll('.select-box').forEach(cb => { cb.checked = false; });
  selected.clear();
  updateBulkButtons();
}

function updateBulkButtons() {
  const has = selected.size > 0;
  backupSelectedBtn.disabled = !has;
  restoreSelectedBtn.disabled = !has;
  updateSelectedBtn.disabled = !has;
}

// ── Per-card actions ─────────────────────────────────────────────────────

async function backupNow(name, ip) {
  setSummary(`Backing up ${name}…`);
  try {
    const res = await fetch(`/api/backups/${encodeURIComponent(name)}?ip=${encodeURIComponent(ip)}`, { method: 'POST' });
    if (!res.ok) throw new Error(String(res.status));
    setSummary(`${name}: backed up`);
    await refreshStatus();
  } catch (err) {
    setSummary(`${name}: backup failed — ${err.message}`, true);
  }
}

async function restoreOne(name, ip, backupRecord) {
  if (!backupRecord) return;
  const when = timeAgo(backupRecord.timestamp);
  if (!confirm(`Restore ${name} from the backup taken ${when}? This overwrites the device's current config and reboots it.`)) return;
  setSummary(`Restoring ${name}…`);
  try {
    await doRestore(name, ip, backupRecord);
    setSummary(`${name}: restored`);
    await refreshStatus();
  } catch (err) {
    setSummary(`${name}: restore failed — ${err.message}`, true);
  }
}

async function doRestore(name, ip, backupRecord) {
  const ts = new Date(backupRecord.timestamp).getTime();
  const res = await fetch(
    `/api/backups/${encodeURIComponent(name)}/restore/${ts}?ip=${encodeURIComponent(ip)}`,
    { method: 'POST' },
  );
  if (!res.ok) throw new Error(String(res.status));
}

async function updateToXlights(names) {
  if (names.length === 0) return;
  setSummary(`Updating ${names.length === 1 ? names[0] : names.length + ' controllers'}…`);
  try {
    const res = await fetch('/api/fix', {
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
    return fetch(`/api/backups/${encodeURIComponent(name)}?ip=${encodeURIComponent(ip)}`, { method: 'POST' })
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
