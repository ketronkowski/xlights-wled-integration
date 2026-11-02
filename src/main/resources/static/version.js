// Fills any element with id="appVersion" with the app version and the commit it was built from.
(async () => {
  const el = document.getElementById('appVersion');
  if (!el) return;
  try {
    const res = await fetch('api/system/version');
    if (!res.ok) return;
    const v = await res.json();
    el.textContent = `v${v.version} · ${v.sha}`;
    el.title = `Built from commit ${v.sha}`;
  } catch { /* leave it blank */ }
})();
