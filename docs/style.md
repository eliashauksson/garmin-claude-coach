# Coach style guide (bento)

This guide describes the app's visual style precisely enough to rebuild it in another system and
get the same result. It replaces the original bento implementation brief, which is not in git
(it contained personal data) and is out of date in places (see §11).

The live source of truth is `resources/public/css/app.css`, with chart styling in
`src/cljs/coach/charts.cljs` and small components in `src/cljs/coach/ui.cljs`. If this file and the
code disagree, the code wins and this file should be updated.

---

## 1. Principles

- **Bento grid.** Every piece of content sits in a rounded card on a pale grey page. Cards wrap
  in flex rows with a 14px gap. There are no borders around cards and no drop shadows on them.
- **Colour by tint.** A card for one metric gets a pale tint of that metric's accent colour
  (green for readiness, blue for body battery, purple for sleep…). Neutral content sits on white.
- **One big number per card.** Large, heavy, tabular numerals carry the card; everything else is
  small and quiet.
- **Recessive charts.** No axis lines, no ticks, hairline grid, no symbols, no animation.
- **Status is never colour alone.** Every coloured dot or bar also has a label or a value.
- **Two themes from one set of tokens.** Light and dark use the same token names; only the values
  change. Nothing may hard-code a colour that should follow the theme.
- **No outside requests** except OpenStreetMap tiles: the font is self-hosted and there is no CSS
  framework or icon font.

---

## 2. Design tokens

Define these as CSS custom properties on `:root`. The dark set replaces them on
`:root[data-theme="dark"]` (see §10 for how the theme is chosen).

### 2.1 Light (default)

| Token | Value | Use |
|---|---|---|
| `--font` | `"Plus Jakarta Sans", system-ui, sans-serif` | everything |
| `--page` | `#eef0f4` | page background, sidebar background |
| `--surface` | `#ffffff` | white cards, active nav link, inputs |
| `--surface-2` | `#f2f3f6` | pills and inset boxes on white cards |
| `--pill-on-tint` | `rgba(255,255,255,.75)` | pills, inputs and `<code>` on tinted cards |
| `--text` | `#16181d` | primary text, big numbers |
| `--text-2` | `#4b5160` | labels, sub text, units |
| `--text-3` | `#5b6170` | axis labels, table headers, dates |
| `--line` | `#eef0f4` | chart grid, dividers, table header border |
| `--row-line` | `#f4f5f8` | table row borders, list separators |
| `--row-hover` | `#f6f7fa` | row and item hover |
| `--ink` | `#16181d` | primary buttons, active segment, logo, user chat bubble |
| `--on-ink` | `#ffffff` | text on `--ink` |
| `--blue` | `#2577d9` | accent |
| `--red` | `#e0473a` | accent, errors |
| `--amber` | `#e08a12` | accent, warnings |
| `--green` | `#22a35a` | accent |
| `--green-ink` | `#1a7a43` | dark green lines and text (TSB line, done items) |
| `--green-text` | `#1f5e3a` | green sub text on the green tint |
| `--purple` | `#6d55e0` | accent |
| `--purple-2` | `#9c8cf2` | light sleep stage, mid sleep score |
| `--deep` | `#3b2fa6` | deep sleep stage |
| `--pink` | `#e07ab8` | REM sleep, ski family |
| `--tint-green` | `#dff5e8` | card tint |
| `--tint-green-2` | `#e2f6e5` | second green card tint (Steps) |
| `--tint-blue` | `#e3f0fc` | card tint |
| `--tint-purple` | `#ece8fd` | card tint |
| `--tint-red` | `#fde9e7` | card tint, critical banner |
| `--tint-amber` | `#fff2dc` | card tint, warning banner |
| `--shadow-nav` | `0 1px 2px rgba(22,24,29,.06)` | active nav link |
| `--track` | `rgba(255,255,255,.8)` | progress bar track |
| `--tooltip-shadow` | `0 6px 24px rgba(22,24,29,.12)` | chart tooltip |

Sport families (chip background / chip text / series colour):

| Family | `--chip-*-bg` | `--chip-*-fg` | `--fam-*` |
|---|---|---|---|
| run | `#e3f0fc` | `#1b5fae` | `var(--blue)` |
| bike | `#fff2dc` | `#9a5a00` | `var(--amber)` |
| swim | `#e0f4f6` | `#0e6a75` | `#0e9aa7` |
| hike | `#dff5e8` | `#1a7a43` | `var(--green)` |
| ski | `#fbe7f2` | `#a3346f` | `var(--pink)` |
| other | `#f2f3f6` | `#4b5160` | `#8a90a0` |

### 2.2 Dark

Also set `color-scheme: dark` (light sets `color-scheme: light`).

| Token | Value |
|---|---|
| `--page` | `#0f1115` |
| `--surface` | `#171a21` |
| `--surface-2` | `#222632` |
| `--pill-on-tint` | `rgba(255,255,255,.08)` |
| `--text` | `#f2f3f5` |
| `--text-2` | `#a3a9b5` |
| `--text-3` | `#90969f` (lightened from `#8a909c` to reach 4.5:1 on the green and amber tints) |
| `--line` | `#222632` |
| `--row-line` | `#222632` |
| `--row-hover` | `#1d212a` |
| `--ink` | `#f2f3f5` |
| `--on-ink` | `#0f1115` |
| `--blue` | `#4c8dff` |
| `--red` | `#ff6b5f` |
| `--amber` | `#ffb547` |
| `--green` | `#4cd18a` |
| `--green-ink` | `#4cd18a` |
| `--green-text` | `#8fe0b0` |
| `--purple` | `#9b8cff` |
| `--purple-2` | `#9b8cff` |
| `--deep` | `#5b6cff` |
| `--pink` | `#f08cc8` |
| `--tint-green`, `--tint-green-2` | `#14301f` |
| `--tint-blue` | `#16263a` |
| `--tint-purple` | `#221d3d` |
| `--tint-red` | `#3a1c1a` |
| `--tint-amber` | `#3a2a12` |
| `--shadow-nav` | `none` |
| `--track` | `rgba(255,255,255,.1)` |
| `--tooltip-shadow` | `0 6px 24px rgba(0,0,0,.45)` |

Sport families in dark:

| Family | `--chip-*-bg` | `--chip-*-fg` | `--fam-*` |
|---|---|---|---|
| run | `rgba(27,95,174,.18)` | `#4c8dff` | `var(--blue)` |
| bike | `rgba(154,90,0,.18)` | `#ffb547` | `var(--amber)` |
| swim | `rgba(14,106,117,.18)` | `#4fd1db` | `#2bb8c4` |
| hike | `rgba(26,122,67,.18)` | `#4cd18a` | `var(--green)` |
| ski | `rgba(163,52,111,.18)` | `#f08cc8` | `var(--pink)` |
| other | `rgba(75,81,96,.18)` | `#a3a9b5` | `#8a909c` |

### 2.3 Scales used across the app

- **Radius:** 999px (pills, chips) · 22px (cards, banners) · 14px (nav links, segment container,
  buttons, selects, inputs, inset boxes, chat bubbles) · 12px (tooltip) ·
  10px (segment buttons) · 9px (icon chip) · 8px (logo, calendar items, small inputs) ·
  7px (stage bar segments) · 6px (`<code>`) · 5px (progress track) · 4px (small bars).
- **Spacing:** 14px between cards and page sections · 10px inside cards · 4/6/8/10/12/16px for
  small gaps. Card padding is 20px 22px; a flush card (one holding a table) is 10px 12px.

---

## 3. Typography

Font: **Plus Jakarta Sans**, weights 400, 500, 600, 700 and 800, self-hosted as woff2 (latin
subset, from `@fontsource/plus-jakarta-sans`), each declared with `@font-face` and
`font-display: swap`.

| Role | Size / weight | Other |
|---|---|---|
| Body | 14px / 400, line-height 1.45 | `--text` |
| Page title `h1` | 30px / 800 | letter-spacing −0.02em, margin 0 |
| `h2` | 13px / 600 | `--text-2` |
| Card label (`.lab`) | 13px / 600 | `--text-2` |
| Sub text (`.sub`) | 13px | `--text-2` |
| Big number (`.big`) | 44px / 800 | line-height 1, letter-spacing −0.02em, tabular numerals |
| — `.big.xl` | 72px | readiness score |
| — `.big.md` | 38px | KPI cards on Activities and Training load |
| — `.big.sm` | 34px | 7-night cards, activity detail tiles |
| Unit inside a big number (`<small>`) | 18px / 600 | `--text-2`, 4px left margin, letter-spacing 0 |
| Pill | 12px / 600 | |
| Sport chip | 12px / 700 | |
| Table header | 12px / 600 | `--text-3` |
| Stat label | 12px / 600 | `--text-3` |
| Stat value | 18px / 800 | tabular; unit 12px / 600 `--text-2`, 3px left margin |
| Activity / week title | 20px / 800 | letter-spacing −0.01em |
| Month title | 16px / 800 | |
| Axis and weekday labels | 11px / 500 | `--text-3` |
| Eyebrow (uppercase) | 11px / 800 | letter-spacing .06em, uppercase, `--text-2` |
| Code | `ui-monospace, monospace` 12px | `--pill-on-tint` background, padding 2px 6px, radius 6px |

Every number that can change uses `font-variant-numeric: tabular-nums`. Missing values show as
an em dash "—".

---

## 4. Layout

### Shell

- `.shell`: flex row that wraps, `min-height: 100vh`. `html, body` hide horizontal overflow;
  `* { box-sizing: border-box }`.
- **Sidebar** `nav.side`: `flex: 1 1 200px`, max-width 200px, padding 24px 14px, no border, on
  `--page`.
  - Brand: flex, 10px gap, 18px / 800, padding 0 14px 20px. A 26×26 square, radius 8, filled with
    `--ink`, then the word "Coach".
  - Links: flex, 10px gap, padding 10px 14px, radius 14px, no underline, `--text-2`, weight 500;
    hover `--text`. Active: `--surface` background, `--text`, weight 700, `--shadow-nav`.
  - Theme toggle: a button below the links, margin-top 18px, padding 8px 14px, radius 14px, no
    background or border, 13px, `--text-3`. Hover: `--text` on `--surface`. It shows a 16px moon
    icon and "Dark theme" in light mode, a sun and "Light theme" in dark mode.
- **Main** `main`: `flex: 999 1 560px`, `min-width: 0`, padding 24px 28px 48px, max-width 1100px
  (1600px with `.wide`, used by the calendar), a flex column with a 14px gap. While loading it
  fades to opacity .55 (0.15s).
- **≤ 760px:** the sidebar becomes a full-width top bar (padding 16px 16px 0) with its links in a
  wrapping row (4px gap); the brand takes its own line (padding 0 0 8px). Main padding becomes
  16px 16px 40px. Nothing may scroll sideways.

### Page header

`.page-head`: flex row that wraps, 12px gap, centred. The title block (`.title`, `flex: 1`,
min-width 200px) holds an optional `.sub` line above the `h1`; the controls (segmented control,
select, buttons) follow on the right.

### Card rows

`.row`: flex, wrap, 14px gap. Each card sets its own `flex`, e.g. `1 1 260px` for a third,
`2 1 460px` for a double-width card, `1 1 200px` for a KPI tile, `1 1 420px` for a half. Cards
then wrap cleanly on narrow screens without media queries.

---

## 5. Components

### Card

- `.card`: radius 22px, padding 20px 22px, `--surface`, flex column with 10px gap, `min-width: 0`.
- Tints: `.tint-green`, `.tint-green-2`, `.tint-blue`, `.tint-purple`, `.tint-red`, `.tint-amber`
  set the background to the matching `--tint-*` token.
- `.card.flush`: padding 10px 12px (tables).
- **KPI tile:** a card with a label, a big number with an optional `<small>` unit, and a sub line.

### Card label row

`.lab`: flex, centred, 8px gap, 13px / 600, `--text-2`. Order: icon chip, label text, a spacer
(`.sp`, `flex: 1`), then optional right-hand controls.

### Icon chip

`.ic`: 28×28, radius 9px, inline-flex centred. It holds a 16×16 inline SVG (24×24 viewBox,
`fill: none`, stroke-width 2.4, round caps and joins, `aria-hidden="true"`).

- On a **tinted** card: chip background = the accent (`--green`, `--blue`…), icon stroke white.
- On a **white** card ("soft"): chip background = the tint (`--tint-blue`…), icon stroke = the
  accent.

Icon paths (24×24):

| Icon | SVG |
|---|---|
| bolt | `<path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/>` |
| battery | `<rect x="2" y="7" width="18" height="10" rx="2"/><path d="M22 11v2"/>` |
| moon | `<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>` |
| sun | `<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>` |
| heart | `<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z"/>` |
| pulse | `<path d="M3 12h4l3-8 4 16 3-8h4"/>` |
| footprints | `<path d="M4 16v-2.4C4 11.5 3 10.5 3 8c0-2.7 1.5-6 4.5-6C9.4 2 10 3.8 10 5.5c0 3.1-2 5.7-2 8.7V16a2 2 0 1 1-4 0z"/><path d="M20 20v-2.4c0-2.1 1-3.1 1-5.6 0-2.7-1.5-6-4.5-6-1.9 0-2.5 1.8-2.5 3.5 0 3.1 2 5.7 2 8.7V20a2 2 0 1 0 4 0z"/>` |
| mountain | `<path d="m8 3 4 8 5-5 5 15H2L8 3z"/>` |

The sidebar theme toggle uses the same moon/sun paths at stroke-width 2.2 in `currentColor`.

### Pills

- `.pills`: flex, wrap, 8px gap.
- `.pill`: inline-flex, centred, 6px gap, 12px / 600, padding 5px 10px, radius 999px,
  `--surface-2` background, `--text`. Inside any tinted card the background is `--pill-on-tint`.
- Legend and status pills start with an 8px round `.dot` in the series or status colour.
- Status pill (e.g. Garmin login): `--surface` background; dot `--green` ok, `--amber` warn,
  `--red` otherwise.
- `.pill.missed`: `--tint-red` background, `--red` text.
- Pills used as links or buttons have no underline or border and keep the pill font.
- Legends are always pill rows; there is no separate legend component.

### Segmented control

- `.seg`: inline-flex, wrap, 4px gap, padding 4px, radius 14px, `--surface`. Inside a white
  card use `.seg.soft` (`--surface-2`).
- Buttons: no border or background, radius 10px, padding 7px 12px, 13px / 600, `--text-2`;
  hover `--text`. Active (`.on`): `--ink` background, `--on-ink` text.

### Select and inputs

- `select`: inherit font, 13px / 600, `--text` on `--surface`, no border, radius 14px, padding
  10px 14px. It keeps a visually hidden `<label>`.
- Text input (`input.note`): 13px, padding 10px 14px, radius 14px, no border; background
  `--pill-on-tint` on tinted cards, `--surface-2` on white cards.
- Small inline inputs (plan edits): width 70px, padding 4px 6px, radius 8px, 1px `--line` border,
  `--surface` background.

### Primary button

`.btn-ink`: inherit font, weight 700, `--on-ink` on `--ink`, no border, radius 14px, padding
10px 18px. Disabled: opacity .6, default cursor. While working it shows a 12px spinner (2px
`currentColor` ring with a transparent right side, 0.8s linear spin; 2.4s with
`prefers-reduced-motion`) and the label changes, e.g. "Sync now" → "Syncing…". Small variant
inside lists: padding 6px 12px, 13px.

### Progress bar and stage bar

- `.track`: 10px tall, radius 5, `--track` background, overflow hidden; the fill child is 10px,
  radius 5, in the card's accent.
- `.stages` (sleep): flex, 4px gap, 14px tall. Segments have radius 7, min-width 4px and `flex`
  equal to their duration. Colours: deep `--deep`, light `--purple-2`, REM `--pink`, awake
  `--amber` (only when > 0).

### Tables

- `table`: full width, `border-collapse: separate`, spacing 0.
- `th, td`: left-aligned, padding 11px 10px, no wrapping.
- `th`: 12px / 600, `--text-3`, bottom border 1px `--line`.
- `td`: bottom border 1px `--row-line`. Row hover turns cells `--row-hover`. Clickable rows get a
  pointer cursor.
- Numeric columns (`.r`): right-aligned, tabular. Name column 700; date column `--text-3`.
- Tables sit in a `.card.flush` inside `.table-wrap` (horizontal scroll, max-height 70vh with
  vertical scroll).

### Sport chip

`.chip`: inline-flex, 6px gap, 12px / 700, padding 4px 10px, radius 999px, with a 7px dot in
`currentColor` first. Background and text come from the family's `--chip-*-bg` / `--chip-*-fg`.
Family is chosen by matching the Garmin sport type:

| Family | Label | Matches |
|---|---|---|
| run | Run | `running` |
| bike | Bike | `cycling`, `biking`, `ride` |
| swim | Swim | `swimming` |
| hike | Hike/walk | `hiking`, `walking`, `mountaineering` |
| ski | Ski/snow | `ski`, `snowboard` |
| other | Other | anything else |

Colours belong to the family, never to a rank or position.

### Banner

`.banner`: flex, 10px gap, top-aligned, radius 22px, padding 16px 22px, no border.
`.warn` uses `--tint-amber`, `.critical` uses `--tint-red`. It starts with a 22px round icon
holding a bold "!" (13px / 800): amber background with `#16181d` text for warn, red background
with white text for critical.

### Divider, empty and error states

- `.divider`: 1px `--line` top border, 10px padding-top, pushed to the bottom of the card
  (`margin-top: auto`), a flex column with a 4px gap.
- `.empty`: 13px, `--text-2`, padding 12px 0.
- `.error`: `--red`, weight 600.
- `.muted`: `--text-3`.

### Activity feed card

- Feed: CSS grid, `repeat(auto-fill, minmax(min(420px, 100%), 1fr))`, 14px gap, grouped into
  months (16px / 800 month title, 10px between cards, 10px between months).
- `.card.activity`: pointer cursor; on hover a `0 6px 24px rgba(22,24,29,.08)` shadow (0.15s).
  Head: sport chip + date sub line. Title: 20px / 800 link with no underline.
- Body: a stats grid (3 columns, 12px × 14px gap, `flex: 1 1 220px`) next to a 160×110 route box
  (radius 14, background = the family's `--chip-*-bg`).
- Route thumbnail: an SVG polyline in `--fam-*`, stroke 2.5, round joins; projected with
  cos(latitude) so it isn't stretched, fitted with 10px padding. A start dot in `--green` and an
  end dot in `--red`, radius 4, with a 2px `--surface` outline.
- ≤ 480px: the route box goes full width at 140px tall, and stats drop to 2 columns.

### Calendar

- A `.card.cal` with padding 14px and a 6px gap.
- Grid: a 190px week summary column, then 7 equal day columns, 6px gap. Week rows are at least
  118px tall. The header row shows weekday names (12px / 600, `--text-3`).
- Week summary: `--surface-2`, radius 14, padding 10px 12px. Date range 12px / 700 `--text-2`;
  total 22px / 800; family breakdown at 11px / 600 with 7px dots; load line at the bottom
  (11px / 600, `--text-3`).
- Day cell: 1px `--line` border, radius 14, padding 6px. Future days: dashed border, transparent.
  Today: 2px solid `--ink` border. Date 12px / 700 `--text-2`.
- Item: `--surface-2`, radius 8, padding 5px 7px, 4px left border in the family colour; hover
  `--row-hover`. Name 12px / 700 with ellipsis; meta and TSS 11px `--text-2` (TSS bold).
- Planned item: transparent with a 1.5px dashed `--text-3` border and the 4px family left border.
  Skipped: opacity .5 and line-through. Missed: red border and " · missed" appended in red.
- ≤ 900px: the header hides; each week becomes a single column list separated by `--line`; empty
  days hide (today stays); days lay out as rows with a 64px date column and the weekday shown.

### Plan page

- Week title 20px / 800.
- Plan rows: grid `110px | 1fr | auto`, 12px gap, padding 10px 0, 1px `--row-line` between rows.
  When: 13px / 700. What: flex column, 3px gap. Actions: right-aligned wrapping row of small
  buttons and pills. Done items: name in `--green-ink`. Skipped or rejected: opacity .5,
  line-through. ≤ 640px: one column, actions left-aligned.
- Proposal block: 1px `--line` top border, 12px padding-top, 10px gap.
- Coach signals: a dot (8px; red high, amber warn, blue info) and text, padding 6px 0.

### Athlete card

- Overview: race block (eyebrow, big race name without tabular numerals) beside a 2-column grid
  of stat boxes (`--surface-2`, radius 14, padding 10px 14px).
- Focus pill: radius 999, padding 8px 14px, `--tint-blue`, 600, with an uppercase eyebrow.
- Chat: bubbles max 85% wide, padding 10px 14px, radius 14, `pre-wrap`. User: right, `--ink` /
  `--on-ink`. Assistant: left, `--surface-2`. Typing indicator: spinner + `--text-2` text.
- Proposed change: 1px `--row-line` border, radius 14, padding 12px 14px; `del` in `--text-2`,
  `ins` without underline, weight 600.

---

## 6. Charts (ECharts, SVG renderer)

Charts are drawn by ECharts with the **SVG renderer**. Colours are read from the CSS tokens with
`getComputedStyle` every time a chart renders, so a theme change only needs a re-render.

Base option for every chart:

- `animation: false`; `textStyle.fontFamily` = the app font.
- Grid: left 44 (adjustable), right 14, top 12, bottom 24.
- X axis: no axis line, no ticks, no split lines; labels 11px / 500 `--text-3`, overlapping
  labels hidden.
- Y axis: value axis with `scale: true`, no line, no ticks; labels and name 11px / 500 `--text-3`;
  split lines 1px `--line`.
- Tooltip: trigger on the axis, confined to the chart. Pointer: 1px `--text-3` line at 50%
  opacity. Box: `--surface` background, no border, radius 12, padding 10,
  `box-shadow: var(--tooltip-shadow)`, text 12px `--text`.
- Tooltip content: a bold heading (weight 600, 4px below), then one row per series with a
  12×2px colour swatch, the **value first in bold**, then the label at 70% opacity.
- Charts in the same group share a linked crosshair (e.g. all Training load charts, all
  activity stream charts).

Series:

| Kind | Style |
|---|---|
| Line | width 2 (2.5–3 for headline lines), no symbols, round cap and join, LTTB sampling, gaps not connected, hover emphasis off |
| Area | a line plus `areaStyle` opacity 0.18 (heart rate, CTL); 0.25 for body battery |
| Normal-range band | two stacked invisible lines; the band fill is the accent at 0.12 opacity, no tooltip |
| Bars | `barMaxWidth` 24, radius 4 (small 24-hour / hourly bars); radius 6–10 for 7-night and weekly bars; `barMaxWidth` 14 for daily TSS, 40 for 7-night bars |
| Stacked bars | radius 6 on every segment, 3px border in the card colour to separate segments |

Chart heights (class on the chart div, width 100%): default 220px; `h90` 90, `h120` 120,
`h150` 150, `h170` 170, `h240` 240, `h280` 280, `tall` 300, `short` 170.

Custom axis rows under 24-hour charts (`.ax`): flex `space-between`, 11px / 500 `--text-3`,
labels 00:00 · 06:00 · 12:00 · 18:00 · 24:00 (or 00 · 06 · 12 · 18 · 24).

Colour by meaning:

| Series | Colour |
|---|---|
| Heart rate | `--red` |
| Body battery | `--blue` |
| Stress rest (0–25) / active (26–100) | `--blue` / `--amber` |
| Steps | `--green` |
| Sleep score ≥ 80 / 60–79 / < 60 | `--green` / `--purple-2` / `--amber` |
| HRV in range / out of range; baseline band | `--blue` / `--amber`; `--tint-green` |
| CTL (fitness) | `--blue`, area |
| ATL (fatigue) | `--red` |
| TSB (form) | `--green-ink`, zero line at 50% opacity |
| Daily TSS | `--amber` |
| Weekly volume | family colours (`--fam-*`) |
| HR zones | `--blue` at 25 / 40 / 55 / 70 / 85% strength |
| Coach signal severity high / warn / info | `--red` / `--amber` / `--blue` |

To use a token at partial opacity, convert its hex value to `rgba()` at render time (hex tokens
only).

---

## 7. Page compositions

### Today

- Header: sub line with the full date ("Thursday, 29 October"), `h1` greeting by hour (Good
  morning < 12, Good afternoon < 18, Good evening), then the login status pill and **Sync now**.
  A banner sits below when the Garmin login needs attention.
- Row 1 (`1 1 260px` each):
  - **Training readiness** — green tint, bolt. Score at 72px; sub in `--green-text` / 600
    ("Moderate · this morning"); progress bar at score %.
  - **Body battery** — blue tint, battery. Current value, sub "high N"; 90px area chart over the
    whole day; pills "+N charged", "−N drained".
  - **Sleep** — purple tint, moon. Score, sub "7h 32m asleep"; stage bar; stage duration pills.
- Row 2: **Heart rate** — red tint, heart, full width. Resting HR with `<small>bpm resting`,
  pills Low / High / 7-day resting; 170px area chart; hour axis row.
- Row 3:
  - **Stress** — amber tint, pulse, `2 1 460px`. Average with `<small>average`; pills Peak and
    time at rest; 120px bar chart, one bar per 3-minute sample; hour axis; pills
    Rest / Low / Medium / High / Unmeasured %.
  - **Steps** — green tint 2, footprints. Total; sub "% of goal · km · floors"; 24 hourly bars
    (90px, radius 4, non-zero bars at least 4px).
- Row 4 (`1 1 300px`), soft icon chips (tint background, accent icon) on all three: Sleep on the purple tint, HRV on the blue tint, Last activity on white.
  - **Sleep · 7 nights** — 34px average; seven bars (radius 10) coloured by score; score
    (12px / 700) and weekday under each.
  - **HRV · 7 nights** — weekly average in ms; sub "status · normal low–high"; 14px-wide bars
    (radius 7) over a `--tint-green` baseline band (radius 8); value under each bar.
  - **Last activity** — mountain chip (accent `--text`, tint `--surface-2`); name link at
    20px / 800; date; pills for km, moving time, avg bpm; then below a divider, what is planned
    today.

### Activities

- Header: `h1` "Activities", range segmented control (30 days / 90 days / 1 year / 2 years / All),
  sport select.
- Four KPI cards (`1 1 200px`, 38px numbers): count (blue; sub "5 runs · 6 hikes · 1 ride"),
  moving time (purple; `31<small>h</small> 54<small>m</small>`), distance (green; km), training
  stress (amber; TSS).
- Then the activity feed by month (§5).

### Activity detail

- Small tinted KPI tiles (34px, `1 1 160px`) rotating blue, purple, green, amber, red.
- Stream charts (170px, linked crosshair), zones and peaks in white cards.

### Training load

- Header: `h1` "Training load" and the range control.
- KPI cards (38px): Fitness · CTL (blue, "42-day load"), Fatigue · ATL (red, "7-day load"),
  Form · TSB (green, "fitness − fatigue"), Ramp rate (amber, "CTL change / 7 days").
- Fitness and fatigue: white card, legend pills, 280px chart (CTL area blue width 3 / fill 0.18,
  ATL red line width 2.5).
- Two cards (`1 1 420px`): Form · TSB (green tint, 150px line in `--green-ink`, note in
  `--green-text`: "Above 0: fresh. −10 to −30: building fitness. Below −30: high strain.") and
  Daily stress · TSS (amber tint, 150px amber bars).
- Weekly volume: white card, a `.seg.soft` Hours / Distance / TSS switch in the label row, family
  legend pills, 240px stacked bars.

### Calendar and Plan

- Calendar uses `main.wide` (1600px) and the week grid in §5.
- Plan: athlete card, coach signals card (amber pulse chip, soft), week cards with plan rows and
  proposals.

---

## 8. Copy and number formatting

- Sentence case everywhere; titles use "·" as a separator ("Form · TSB", "Sleep · 7 nights").
- Dates: long "Thu 29 Oct 2026"; short "29 Oct 2026"; times "06:42".
- Durations: "6h 32m" (or "45m"); clock "1:02:15" / "4:58".
- Distance: km with 2 decimals under 10 km, 1 decimal above.
- Pace "4:58 /km" for runs; speed "24.3 km/h" for everything else.
- Thousands with commas ("12,345").
- Units go in a `<small>` after the number, never in the label.

---

## 9. Accessibility and motion

- Text on tints must reach 4.5:1 (this is why dark `--text-3` is `#90969f`).
- Decorative icons have `aria-hidden="true"`; route thumbnails have `role="img"` and a label.
- Selects keep a visually hidden label (`.visually-hidden`: 1px clipped box).
- The only animations are the spinner, the loading fade (0.15s) and the activity card shadow
  (0.15s). Spinners slow to 2.4s with `prefers-reduced-motion: reduce`.

---

## 10. Theme switching

- The theme is an attribute: `<html data-theme="light|dark">`.
- A small inline script in `<head>`, before any stylesheet paints, sets it from
  `localStorage.theme` if that is "light" or "dark", otherwise from
  `prefers-color-scheme: dark`. This avoids a flash of the wrong theme.
- The sidebar toggle saves the choice to `localStorage` and re-renders, so charts re-read their
  colours. Without a saved choice, the app follows the system setting.

---

## 11. Differences from the original bento brief

- The per-chart **Table** toggle has been removed. Do not add it back.
- Dark mode is chosen by `data-theme` (toggle or system setting), not only by
  `@media (prefers-color-scheme)`.
- Dark `--text-3` is `#90969f`, not `#8a909c`.
- Added since the brief: activity feed cards (replacing the table on Activities), the calendar,
  the Plan page and athlete card and the theme toggle.

