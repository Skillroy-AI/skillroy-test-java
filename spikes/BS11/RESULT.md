# BS11 result: `PAINT` capture determinism (2026-10-10)

**Verdict: passed with conditions.** Within one environment the built `swing.capture` is pixel-deterministic: 100 of
100 captures of the desk's main window hash identically (ARGB raster and PNG bytes) on a Java 25 and on a Java 8
target in the arm64 image, and the same holds across five fresh launches, after a field was typed and cleared, after
a dialog was opened and closed, while the window was covered by another process's window and after it was moved,
for two dialogs and for the toy. The one exception is time-dependent painting: the blinking text caret of a focused
text field (a 1 x 24 pixel column, 24 of 786,432 pixels, per-channel difference 204) gives a focused window two
alternating variants (50/50 of 100 on both targets in the recorded runs; 46/54 and 48/52 in an earlier run), and that is the same size as a real one-character change (28 to 50
pixels), so it must be masked, not tolerated. Across environments the differences are environment-bound and large:
the architecture (arm64 against emulated amd64, same JDK build and libraries), the Xvfb depth (16 against 24),
`-Dswing.aatext=true` and `-Dawt.useSystemAAFontSettings=off` change nothing; the JDK line (8 against 25) changes
antialiased shapes and the table header (0.30 % of the window, max 187), text antialiasing on or LCD changes the
glyphs (0.14 % to 0.82 %, max 111 to 180), installing two font packages changes the default sans face (3.3 % to
4.7 %, max 238), and macOS against Linux changes everything (7.9 % to 14.7 %: look and feel, fonts, decorations).
No per-channel tolerance with a pixel budget separates that noise from the real changes (a one-character label change
is 28 pixels; the cross-environment noise inside that same label is up to 1,056 pixels at max 154). The conditions:
(1) carets (and any other time-dependent painting) are masked on both sides of a comparison; (2) the focus owner and
whether its window is active are part of the captured state (under Aqua, losing the focus repaints the field's focus
ring: 9,625 pixels); (3) a baseline or a pixel anchor is bound to an environment fingerprint that carries the rendering
inputs listed below, and within the fingerprint the comparison is exact on the decoded raster (after the masks);
(4) the hash is taken over the decoded raster, never the PNG bytes (identical rasters encode to different PNG bytes on
Java 8 and Java 25).

## What was run

`spikes/BS11/` (see `README.md`): the sample lending desk (seed A) launched with the built agent home plus the sample's
agent module through the real `TargetLauncher`; `swing.capture {target: <window ref>}` called over the control channel
with the real `AgentClient` of the built CLI distribution; every capture decoded with `ImageIO` and hashed (SHA-256
over width, height and every ARGB pixel; and SHA-256 of the PNG bytes). State changes through the agent's own
`swing.type` and `swing.dismiss`, waits on the desk's events and `swing.settle` (no sleeps). Built from `main` at
`763e154` (the reactor's artifacts on disk, built from `579af9c`; `main` differs by docs only).

| Environment | Image / host | Target Java | Notes |
|---|---|---|---|
| `arm64-j25` | `skillroy-spike-bs11:tools` (the arm64 test image plus `xdotool`), Xvfb 1024x768x24, no window manager | Temurin 25.0.4.1+1 | the baseline: 100 captures per main series, 5 launches |
| `arm64-j8` | the same | Temurin 1.8.0_504-b01 | 100 captures, 5 launches |
| `arm64-j25-base` | the unmodified `skillroy-test-java-ci:bs8-arm64` | 25.0.4.1 | control: the derived image renders the same |
| `amd64-j25`, `amd64-j8` | `skillroy-test-java-ci:local` (emulated) | 25.0.4.1; 1.8.0_492-b09 | 30 captures, 2 launches, no move tool |
| `arm64-j25-aa-on`, `-aa-off`, `-aa-lcd`; `arm64-j8-aa-lcd` | tools image | 25 / 8 | `-Dawt.useSystemAAFontSettings=on/off/lcd` |
| `arm64-j25-aatext` | tools image | 25 | `-Dswing.aatext=true` |
| `arm64-j25-depth16` | tools image, Xvfb 1024x768x16 (verified: the default configuration's colour model is RGB 565) | 25 | |
| `arm64-j25-fonts`, `arm64-j8-fonts` | `skillroy-spike-bs11:fonts`: plus `fonts-liberation2`, `fonts-noto-core` (`fc-match sans` becomes Noto Sans) | 25 / 8 | |
| `arm64-j25-fontpref` | `skillroy-spike-bs11:fontpref`: plus a `local.conf` preferring Liberation (`fc-match sans` = Liberation Sans) | 25 | |
| `mac-j25` | macOS 26.6.2 (arm64) on this machine's desktop, native, under the display lock | OpenJDK 25.0.1+8-27 | 100 captures, 3 launches, no move tool |

Both Linux images carry freetype 2.14.2 and fontconfig 2.17.1 (Ubuntu 26.04). One full environment takes 15 to 34 s
(the emulated amd64 ones too). A 1024 x 768 capture takes 18 to 33 ms end to end natively (Java 25 24 ms, Java 8 33 ms,
macOS 18 ms; 53 to 55 ms emulated), PNG encoding, base64 and HTTP included.

## Within one environment

Captures per state / distinct raster hashes (distinct PNG hashes were equal to distinct raster hashes in every state of
every run: the PNG encoding is deterministic for a given raster within one JDK).

| State | arm64-j25 | arm64-j8 | amd64-j25 | amd64-j8 | mac-j25 |
|---|---|---|---|---|---|
| main window, focused field (`desk-signed-off`) | 100 / 2 (caret) | 100 / 2 (caret) | 30 / 2 | 30 / 2 | 100 / 2 (caret, 1 x 25) |
| main window, no focus owner (`desk-signed-off-uncovered`) | **100 / 1** | **100 / 1** | 10 / 1 | 10 / 1 | 10 / 2 (macOS gives the focus back) |
| typed `12345`, then `12346`, then cleared | 10 / 2, 10 / 2, 10 / 2; cleared = start | the same | the same | the same | the same |
| after the unknown-staff dialog closed | 10 / 1, one of the start's variants | the same | the same | the same | the same |
| covered by another process's window | 10 / 1, one of the start's variants | the same | the same | the same | **10 / 1, not one of them** (Aqua: focus ring, 9,625 px) |
| moved by `xdotool` to (137, 91), and back | 10 / 1 and 5 / 1, the start's variants | the same | not run | not run | not run |
| one item on loan (`desk-loan`), and after the induction dialog was answered No | 20 / 1; after = same | the same | the same | the same | 20 / 2; after = same |
| fresh launches 2-5: start and loan | the start's variants; loan differs from launch 1 by the caret only | the same | the same (1 launch) | the same (1 launch) | the start's variants (2 launches) |
| unknown-staff dialog; induction dialog | 20 / 1; 20 / 1 | 20 / 1; 20 / 1 | 20 / 1; 20 / 1 | 20 / 1; 20 / 1 | 20 / 1; 20 / 1 |
| toy, and each change's reset | 20 / 1; every reset = base | the same | the same | the same | the same |

Every variant pair of the Linux runs differs only in the caret column: `14,658 1x24 (24 px)` inside
`EntryField[entry]`, per-channel difference 204 (`Compare` regions and owners; checked for the 100-capture series, the
typed states and every restart's loan against launch 1, whose loan was captured unfocused because nothing returns the
focus after the covering window closes on a display without a window manager). Masking that rectangle makes every
state of every Linux run a single hash. The same states also hash identically in the other eleven Linux environments
(20 captures each, 2 launches).

## Across environments

Same state, closest caret variant on each side; pixels differing (any channel), the largest per-channel difference,
pixels above a tolerance, and where (the deepest component of the first run's tree under each differing pixel; "text
share" is the share inside labels, fields, tables, text panes and buttons with text).

| Pair | What changed | Main window (`desk-signed-off`) | Loan window | Dialogs | Toy | Where |
|---|---|---|---|---|---|---|
| arm64-j25 / arm64-j25-base | `xdotool` installed | 0 | 0 | 0 | 0 | - |
| arm64-j25 / amd64-j25; arm64-j8 / amd64-j8 | architecture (same JDK build on Java 25; 1.8.0_504 against 1.8.0_492 on Java 8) | 0 (raster and PNG bytes identical) | 0 | 0 | 0 | - |
| arm64-j25 / arm64-j25-depth16 | Xvfb depth 24 / 16 | 0 | 0 | 0 | 0 | - |
| arm64-j25 / -aatext, / -aa-off | `swing.aatext=true`; `useSystemAAFontSettings=off` | 0 | 0 | 0 | 0 | the Xvfb default is already no text antialiasing; `swing.aatext` is ignored |
| arm64-j25 / arm64-j8 (amd64 the same) | JDK line 25 / 8 | 2,359 (0.300 %), max 187; >32: 644 | the same 2,359 | 69 and 138 (0.08 %, 0.16 %), max 28; >32: 0 | 0 | the icon keys (antialiased shapes, 208-416 px each), the table header (626 px), the dialog buttons' rounded corners (6x6 at each corner); plain text identical |
| arm64-j25 / -aa-on | text antialiasing on | 1,128 (0.143 %), max 111 | 2,783 (0.354 %), max 119 | 0 | 1,149 (0.748 %), max 115 | glyphs (text share 0.94-1.00) |
| arm64-j25 / -aa-lcd (Java 8 the same) | LCD text | 2,661 (0.338 %), max 164 | 6,438 (0.819 %), max 180 | 0 | 2,841 (1.850 %), max 165 | glyphs (0.89-1.00) |
| arm64-j25 / -fonts (Java 8 the same) | two font packages installed | 25,970 (3.302 %), max 238 | 31,380 (3.990 %) | 1,525 and 4,108 (1.8 %, 4.7 %) | 7,122 (4.637 %) | text (0.72-1.00) and every component whose size follows the text |
| arm64-j25 / -fontpref | Liberation preferred | 7,789 (0.990 %), max 238 | 12,536 (1.594 %) | 1,568 and 5,164 | 7,300 (4.753 %) | text (0.64-1.00) and layout |
| arm64-j25 / mac-j25 | macOS, Aqua, CoreText, a 28 px title bar inside the 1024x768 frame | 61,902 (7.871 %), max 255 | 68,479 (8.708 %) | 1,558 and 4,117 (1.8 %, 4.7 %) | 22,557 (14.686 %) | everything: the root pane starts at y = 28 and is 740 high, so the layout differs |

The dialogs keep their pixels under every antialiasing switch because their text pane and buttons set their own
rendering hints; an application's own hints win over the JVM's switches. The Java 8 / Java 25 difference is not in the
glyphs (with antialiasing off they are identical) but in antialiased shape rasterisation and the table header; the toy,
which has neither, is identical on both lines (its PNG bytes are not: 2,419 against 3,536 bytes for the same raster).

## Tolerance study

Real changes, within one run (the same numbers on arm64-j25 and arm64-j8):

| Change | Pixels differing | Max channel | > 8 | > 16 | > 32 | > 64 |
|---|---|---|---|---|---|---|
| toy label 3.50 to 3.60 (one character) | 28 (0.018 %) | 187 | 28 | 28 | 28 | 28 |
| desk entry field `12345` to `12346` (one character) | 50 (0.006 %) | 204 | 50 | 50 | 50 | 50 |
| toy button disabled | 1,725 (1.123 %) | 102 | 1,725 | 927 | 453 | 118 |
| toy swatch green to red | 3,264 (2.125 %) | 152 | 3,264 | 3,264 | 3,264 | 3,264 |
| toy swatch blue +6 (subtle) | 3,264 (2.125 %) | 6 | 0 | 0 | 0 | 0 |
| (noise) the caret blink | 24 (0.003 %) | 204 | 24 | 24 | 24 | 24 |

Against the environment noise of the table above:

- **Identical pairs** (architecture, depth, the two no-op switches): an exact hash compares them equal and catches all
  five changes, the subtle one included.
- **Java 8 / Java 25**: the dialogs compare equal at t = 32 (max 28) and every change but the tint is still caught
  there; the main window needs t >= 187 (628 noise pixels are still above 64), which also hides the one-character
  label change (max 187) and leaves at most 118 of the disabled button's pixels; or a budget of 2,359 pixels, 47 to 84
  times the size of the one-character changes.
- **Antialiasing on / LCD, fonts, macOS**: the noise is 1,128 to 68,479 pixels at max 111 to 255, mostly in the very
  labels a guard crop would cover (the label `Deposit due 3.50` alone: up to 1,056 pixels of noise against a 28-pixel
  change). No (t, budget) makes these pairs equal and still sees a one-character change.
- **Crops** (leaf components of the same size in both trees, over all noise pairs): text components carry 0 to 4,901
  differing pixels, the icon keys 208 to 416 at max 32 to 78; within one environment every crop is identical. A crop of
  a component's bounds can include a neighbour's pixels where the bounds exceed the parent's clip (the toy's swatch).
- **The caret** is 24 pixels at 204: the same size class as a one-character change. A tolerance or budget loose enough
  to absorb it (t >= 204 or 24 pixels) would also absorb the toy's label change; it has to be masked.

So a tolerance is not a way to make a baseline portable; it is at most a safety margin inside one fingerprint, where
the measured noise is zero. A subtle colour change (6 levels) is visible only to an exact comparison.

## Rendering inputs (candidates for the fingerprint)

Change pixels (measured): the JDK line (8 / 25: shapes and table header), text antialiasing
(`awt.useSystemAAFontSettings` on, lcd; and the desktop's own font settings that this property overrides), the font
set as fontconfig resolves it (installing a package changed the default sans face; a `local.conf` changed it again),
the operating system and its look and feel (Aqua against Metal: the desk sets no look and feel, so the platform default
applies), the window geometry (the macOS frame keeps 28 px for the title bar inside the same frame size), the focus
owner and whether its window is active (caret; under Aqua the focus ring and the scroll pane's border), and any
time-dependent painting (the caret blink).

Did not change pixels here (measured): the CPU architecture (with the same JDK build and the same freetype and
fontconfig versions), the JDK 8 update (1.8.0_492 against 1.8.0_504), the X screen depth (PAINT draws into an RGB
`BufferedImage`), `swing.aatext`, whether the window was covered or moved (on Metal), a process restart, a state change
and its reversal.

Not measured: a JDK update within the 25 line on Linux (only 25.0.4.1 in the images; macOS had 25.0.1 but differs in
everything else), Windows, HiDPI scale factors other than 1 (the macOS capture was 1024 x 768 user-space pixels; which
of the machine's two displays the window opened on, and its scale, was not recorded; `sun.java2d.uiScale` or a GDK
scale was not tried), other look and feels set explicitly, freetype versions.

## Proposal for the image guard and `expectImage`

1. **Compare decoded rasters, exactly, after masks.** The guard's crop and `expectImage`'s baseline are compared on
   the ARGB raster: an exact hash first, then (on a mismatch) a pixel difference for the report. Default tolerance 0
   and budget 0 pixels; a scenario may allow a small tolerance per expectation, but the measured noise inside one
   fingerprint is zero, so a loose default would only hide real changes (a one-character change is 28-50 pixels; a
   subtle colour change is 6 levels).
2. **Mask time-dependent painting at the source.** The capture result gains `carets: [{ref, rect}]`: for every showing
   focused `JTextComponent` in the captured window whose caret is visible, the caret's rectangle (from `modelToView` of
   the dot, widened by the caret's width), computed in the same EDT read as `printAll`. Both sides of a comparison mask
   the union of their caret rectangles. Not recommended: hiding the caret during the paint (`Caret.setVisible(false)`
   and back), which changes the host's state. A pack may name further components to mask (a clock, a spinner, an
   animated progress bar) through the same mask list as personal data.
3. **Record the focus state with the image.** The capture result reports the focus owner's ref and whether its window
   is active; the guard and `expectImage` compare only captures with the same focus state, else they mask the focus
   owner's bounds and say so (on Metal the focus owner differs only by its caret; on Aqua by its focus ring and its
   scroll pane's border).
4. **The fingerprint's rendering inputs**, recorded with every baseline and anchor and compared before any pixel
   comparison: the JDK vendor and version (line and update), the OS name and version, the look and feel class, the
   effective text antialiasing hint (`Toolkit.getDesktopProperty("awt.font.desktophints")` plus the
   `awt.useSystemAAFontSettings` property), the font resolution of the logical fonts the window uses (on Linux the
   `fc-match` answers for sans, serif and monospace and their files; elsewhere the family and file the JDK resolves),
   the screen scale (`GraphicsConfiguration.getDefaultTransform()`), and the captured window's size and insets. The CPU
   architecture and the X depth may be recorded for information; they did not change pixels. As a tripwire, a
   **probe render**: a fixed off-screen Swing panel (a label plain and bold, a text field, a table header, a button, an
   antialiased shape) painted by `printAll` in the target with the application's look and feel; its raster hash goes
   into the fingerprint. (The toy alone would not do: it renders identically on Java 8 and Java 25 while the desk does
   not, so the probe needs an antialiased shape and a table header.)
5. **A baseline is environment-bound whenever any of those inputs differs**; a mismatch of the fingerprint is
   `REBIND_NEEDED` for the anchor (with both images) and "baseline not applicable" for `expectImage`, never an application
   failure. Baselines are kept per fingerprint (the platform in the file name, as Playwright does), and a project that
   runs CI in one image needs one baseline per expectation.

## Surprises

- The caret: the only non-determinism, and as large as a one-character change.
- `xdotool` without a window manager moves the window and Java reports the new bounds; the capture does not change.
- Without a window manager nothing returns the focus after another window closes, so the desk captured after the cover
  had no caret for the rest of that launch; with a window manager (macOS) the focus came back. A guard recorded with one
  focus state and replayed with another would fail on the caret alone.
- Installing `fonts-noto-core` alone moves fontconfig's default sans from DejaVu to Noto: a font package in a CI image
  is a rendering input even when nobody configures it.
- The desk's dialogs ignore the antialiasing switches (their components set their own hints), so one window can be
  invariant to a switch that changes another.

## Not verified

Windows; a HiDPI scale factor; a JDK update within the 25 line; an embedded web view (a heavyweight surface is
`skipped` by the capture, Phase 4); a window larger than 4096 pixels (scaled, so the scale is part of the result);
captures under memory or CPU pressure (only idle containers); a desktop session on Linux with a window manager and a
compositor.
