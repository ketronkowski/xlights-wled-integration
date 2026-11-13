# Plan: "Set Look" for selected WLED controllers

Status: **draft, open questions at the bottom**. No code yet.

## Goal

Add one new bulk action, next to Backup / Restore / Update Selected, that opens a
dialog where you pick power, an effect, its options and colors, then press
**Apply** and every selected controller gets the same look at the same moment.

Deliberately much smaller than the prior `wled-controller` project: no groups,
no Mongo, no snapshots, no discovery, no React. It lives in the existing
vanilla JS page and the existing Spring Boot `WledApiClient`.

## What the dialog has

Laid out like the WLED UI in the screenshots, top to bottom:

| Section | Controls | WLED field |
|---|---|---|
| Power | On / Off toggle, Brightness slider (1–255) | `on`, `bri` |
| Effect | Searchable list of effect names, with WLED's icons (🎨 uses palette, • uses colors) | `seg.fx` |
| Effect options | Only the sliders/checkboxes the chosen effect uses, labelled with the effect's own names (e.g. "Speed", "Fade rate") | `sx`, `ix`, `c1`, `c2`, `c3`, `o1`, `o2`, `o3` |
| Colors | Up to 3 slot buttons (1/2/3, or Fx/Bg/Cs), a color picker for the active slot, and WLED's 11 quick swatches (red, orange, amber, warm white, white, black, magenta, blue, aqua, green, random) | `seg.col[0..2]` |
| Palette | Searchable list: Default, the `* Color 1` / `* Colors 1&2` / `* Color Gradient` / `* Colors Only` / `* Random Cycle` specials, then the named palettes. Shows "Color palette not used" when the effect ignores it | `seg.pal` |

The color picker is a native `<input type="color">` (no new JS dependency).
The quick swatch hex values are copied from WLED's `index.htm`:
`#ff0000 #ffa000 #ffc800 #ffe0a0 #ffffff #000000 #ff00ff #0000ff #00ffc8 #08ff00` + random.

### Which controls show (fxdata rules)

`GET /json/fxdata` returns one string per effect id, **with the name and `@`
already stripped** by WLED (`respondModeData` in `json.cpp`). Format:

```
<sliders>;<colors>;<palette>;<flags>;<defaults>
e.g. "!,Fade rate,,,,Smear;!,!;!;1;sx=24,pal=50"
```

- `sliders`: up to 8 comma-separated entries: `sx, ix, c1, c2, c3, o1, o2, o3`.
  Empty = hidden, `!` = default label, anything else = custom label.
- `colors`: 3 entries for the color slots, same empty/`!`/label rule
  (default labels Fx, Bg, Cs).
- `palette`: empty = palette not used; `!` = used.
- `flags`: `1` 1D, `2` 2D, `v`/`f` audio-reactive.
- `defaults`: `sx=24,pal=50`-style values to pre-fill when the effect is picked.
- An effect with an empty string (e.g. Solid on some builds) shows speed +
  intensity for ids < 128, all 3 color slots labelled 1/2/3, and the palette.
- Picking palette 2–5 force-shows color slots: 2 → slot 1, 3 → slots 1–2,
  4/5 → slots 1–3 (WLED `updateSelectedPalette`).

These match WLED's own `setEffectParameters()` in `wled00/data/index.js`.

> The prior project's `parseFxData.ts` looked for `@` in the fxdata string, which
> `/json/fxdata` never contains, so it always took its "old format" branch and
> mislabelled sliders and color slots. Port the logic from `index.js` instead.

## How it talks to the controllers

### Catalog (effects / palettes / fxdata)

New endpoint `GET /api/look/catalog?ips=a,b,c`. The backend fetches
`/json/effects`, `/json/palettes` and `/json/fxdata` from each selected
controller in parallel and returns:

- the effect and palette names common to all reachable controllers,
- per controller, the name → id map (ids can differ between firmware builds,
  so the frontend always works in names and the backend maps back to ids),
- the fxdata string for each common effect (from the first controller),
- the current state of the first reachable controller, to pre-fill the dialog,
- the list of controllers that could not be reached.

Cached in memory per IP for the life of the process (effects only change with a
firmware update). A "Refresh" in the dialog clears it.

Palette gradient previews (`/json/palx?page=N`) are a nice-to-have for a later
pass; the first version shows names only.

### Apply

New endpoint `POST /api/look/apply`:

```json
{
  "controllers": [{ "name": "Octa5", "ip": "192.168.6.25" }, ...],
  "look": {
    "on": true, "bri": 180,
    "fx": "Chase 3", "sx": 128, "ix": 128, "c1": 128, "c2": 128, "c3": 16,
    "o1": false, "o2": false, "o3": false,
    "pal": "* Color Gradient",
    "col": [[255,0,0],[255,255,255],[0,255,0]]
  }
}
```

For each controller the backend:

1. Reads `/json/state` to get its segment ids.
2. Maps `fx` and `pal` names to that controller's ids.
3. Builds one `POST /json/state` body:

```json
{
  "on": true, "bri": 180, "tt": 0, "tb": 0,
  "udpn": { "nn": true },
  "seg": [
    { "id": 0, "fx": 54, "sx": 128, "ix": 128, "pal": 4, "col": [[255,0,0],[255,255,255],[0,255,0]], ... },
    { "id": 1, ... same ... }
  ]
}
```

Then fires all controllers' POSTs **in parallel** (virtual threads), so they
land within a few ms of each other, and returns per-controller success or the
error message.

Why each field:

- **`seg` as an array with every segment's `id`**. WLED treats an array element
  without `id` as segment 0 by position, so the prior project's
  `seg: [{fx, pal, ...}]` only ever changed the first segment. A bare object
  `seg: {...}` hits only segments marked "selected", which is unreliable here
  because "Update Selected" rewrites segments to match xLights models.
- **`tb: 0`** resets each controller's effect timebase as the request lands, so
  effects start in phase across controllers. Sync is as good as the network
  jitter between parallel requests (typically a few ms on the LAN).
- **`tt: 0`** applies instantly instead of with the default 700 ms crossfade, so
  controllers don't drift during the fade.
- **`udpn.nn: true`** stops WLED's UDP sync from re-broadcasting the change to
  controllers that weren't selected.
- Errors surface: unlike the prior project's `setState()`, which caught every
  exception and returned `null`, failures propagate so the progress modal shows
  them.

**Off** is just `{"on": false, "tt": 0, "udpn": {"nn": true}}`.

### Frontend flow

1. New toolbar button **Set Look…** (enabled when something is selected and
   reachable), between Update Selected and Clear All.
2. Opens the dialog, calls the catalog endpoint, pre-fills from the first
   controller's main segment.
3. **Apply** calls `/api/look/apply` and shows results in the existing progress
   modal (`runAtomicBulk`), one row per controller.
4. Dialog stays open after Apply so you can tweak and re-apply.

## Out of scope for v1

- Saving presets or building playlists on the controllers.
- Per-segment (per xLights model) looks inside one controller.
- Palette gradient previews.
- Live preview while dragging sliders.
- Scheduling, groups, saved looks in this app.

## Files touched (estimate)

- `wled/WledApiClient.kt`: `fetchEffects`, `fetchPalettes`, `fetchFxData` (+ a
  small `LookService.kt` for catalog merge and parallel apply).
- `web/LookController.kt`: the two endpoints.
- `static/index.html`, `static/app.js`, `static/style.css`: button, dialog,
  fxdata parsing.
- Tests: fxdata parser cases taken from real WLED strings; `LookService` body
  building (segment ids, name → id mapping, off payload).

## Open questions

1. **Live show data.** When FPP or xLights is streaming DDP to a controller, WLED
   shows that and ignores effects. Should Apply (a) leave it alone, so the look
   only appears when nothing is streaming, (b) send `lor: 1`, overriding until
   the stream stops and restarts, or (c) send `lor: 2`, overriding until reboot
   (what the boundary finder does)? Recommended: (b) as an "Override live show
   data" checkbox, on by default.
2. **Whole controller vs per segment.** Plan applies the look to every segment on
   each controller. Is that right, or do you ever want, say, only the Mega Tree
   model on a controller?
3. **Full look vs changed fields.** Plan sends the whole look (effect, options,
   colors, palette) so every controller matches exactly. Do you instead want to
   change only what you touched (e.g. just colors, leaving each controller's
   effect alone)?
4. **Presets.** Plan is transient: nothing is saved, and the controller reverts
   to its boot preset after a reboot. Do you want a "Save as preset N" option, or
   is transient fine for v1?
5. **2D and audio effects.** Should 2D-only effects (flag `2`) be hidden? Are any
   of your controllers (Mega Tree?) set up as a 2D matrix in WLED, or with an
   audio-reactive build?
6. **Apply vs live.** Plan uses an Apply button. Do you want changes to go out
   live as you drag sliders and click colors instead?

## Sources

- WLED `wled00/json.cpp`: `deserializeState` (`tt`, `tb`, `udpn.nn`, `lor`,
  `seg` object vs array), `deserializeSegment` (`fxdef`), `respondModeData`.
- WLED `wled00/data/index.js`: `setEffectParameters`, `updateSelectedPalette`.
- WLED `wled00/data/index.htm`: quick color swatches.
- Prior attempt `~/git/ketronkowski/wled-controller`: `WledService.kt`,
  `GroupService.kt`, `parseFxData.ts`, `ColorPicker.tsx`, `PaletteSelector.tsx`,
  `e2e/fixtures/wled-reference.json`.
