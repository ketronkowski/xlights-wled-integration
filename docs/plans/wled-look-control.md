# Plan: "Set Look" for selected WLED controllers

Status: **decisions made (see bottom), ready to build**. No code yet.

This is for controlling the lights **outside show hours**. It never overrides a
running show.

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
| Effect | Searchable list of effect names (no icons) | `seg.fx` |
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
- `flags`: `1` 1D, `2` 2D, `v`/`f` audio-reactive. **Effects whose flags
  contain `2` but not `1` (2D-only) are hidden** (same test as WLED's UI uses
  when there's no matrix). Audio-reactive effects (flags `v`/`f`) are hidden
  too (Kevin, 2026-11-13).
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
- which controllers are currently receiving live show data (`/json/info`
  `live: true`), so the dialog can warn "Octa5 is playing the show; the look
  won't show until the show stops",
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

1. Reads `/json/state` to get its segments.
2. Picks the target segments (see "Which segments" below).
3. Maps `fx` and `pal` names to that controller's ids.
4. Builds one `POST /json/state` body (it never includes `lor`, so a running
   show always wins):

```json
{
  "on": true, "bri": 180, "tt": 0, "tb": 0,
  "udpn": { "nn": true },
  "seg": [
    { "id": 0, "on": true, "fx": 54, "sx": 128, "ix": 128, "pal": 4, "col": [[255,0,0],[255,255,255],[0,255,0]], ... },
    { "id": 1, ... same ... },
    { "id": 2, "on": false }
  ]
}
```

(segment 2 here is a null model.)

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

### Which segments

Only segments that match a **non-null** xLights model get the look. Every
other segment is turned off, the same as nulls.
"Update Selected" already names each WLED segment after its xLights model, and
the parser already knows which models are nulls (`LayoutGroup="Nulls"` or a
name starting with "Null"). So for each segment on the controller:

| Segment | What Apply sends |
|---|---|
| Name matches a non-null xLights model | the full look, plus `on: true` |
| Anything else (null model, or no matching model) | `on: false` only |

Because the dialog only opens for in-sync controllers (below), the "no matching
model" row shouldn't happen in practice; the backend still turns such a
segment off rather than guessing.

The whole look is always sent (effect, every option, all 3 colors, palette),
never just the fields you changed, so every controller ends up identical.

### Frontend flow

1. New toolbar button **Set Look…**, between Update Selected and Clear All.
   It is enabled only when something is selected **and every selected
   controller is paired with xLights and its segments are in sync** (no
   segment needs an update and there are no orphan segments). Otherwise the
   button stays disabled with a tooltip naming the controllers to fix with
   Update Selected first. Unpaired WLED devices and FPP controllers can't be
   used. An "LED count differs" warning also blocks it: fix the pixel count in
   WLED's LED Preferences (Update Selected can't) before setting a look. The
   backend re-checks the same condition on
   Apply and rejects a controller that has drifted.
2. Opens the dialog, calls the catalog endpoint, pre-fills from the first
   controller's main segment.
3. **Apply** calls `/api/look/apply` and shows results in the existing progress
   modal (`runAtomicBulk`), one row per controller.
4. Dialog stays open after Apply so you can tweak and re-apply.

## Out of scope for v1

- Saving presets or building playlists on the controllers (a follow-on
  feature). Looks are temporary and revert to the boot preset on reboot.
- Overriding a running show (`lor`).
- Different looks per segment inside one controller.
- 2D effects.
- Palette gradient previews.
- Live preview while dragging sliders; changes go out only on Apply.
- Scheduling, groups, saved looks in this app.

## Files touched (estimate)

- `wled/WledApiClient.kt`: `fetchEffects`, `fetchPalettes`, `fetchFxData` (+ a
  small `LookService.kt` for catalog merge and parallel apply).
- `web/LookController.kt`: the two endpoints.
- `static/index.html`, `static/app.js`, `static/style.css`: button, dialog,
  fxdata parsing.
- Tests: fxdata parser cases taken from real WLED strings; `LookService` body
  building (segment ids, name → id mapping, off payload).

## Decisions (Kevin, 2026-11-13)

1. **Live show data:** leave the show alone. This is for outside show hours, so
   no `lor` is sent; the dialog only warns when a controller is live.
2. **Segments:** only segments matching a non-null xLights model get the look;
   everything else (nulls included) is turned off. Every selected controller
   must be fully in sync with xLights before the dialog opens, including
   matching LED count.
3. **Whole look:** Apply always sends the full look.
4. **Presets:** temporary change for now; presets come in a follow-on feature.
5. **2D effects:** hidden.
6. **Apply button:** nothing goes out until Apply is pressed.

## Sources

- WLED `wled00/json.cpp`: `deserializeState` (`tt`, `tb`, `udpn.nn`, `lor`,
  `seg` object vs array), `deserializeSegment` (`fxdef`), `respondModeData`.
- WLED `wled00/data/index.js`: `setEffectParameters`, `updateSelectedPalette`.
- WLED `wled00/data/index.htm`: quick color swatches.
- Prior attempt `~/git/ketronkowski/wled-controller`: `WledService.kt`,
  `GroupService.kt`, `parseFxData.ts`, `ColorPicker.tsx`, `PaletteSelector.tsx`,
  `e2e/fixtures/wled-reference.json`.
