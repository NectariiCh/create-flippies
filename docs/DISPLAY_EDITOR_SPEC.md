# Display editor: visual editor for display elements

Mod: Create: Flippies, Forge 1.20.1. Base package `com.nectariich.create_flippies` (below: `cf`).
Decided with the user on 2026-10-25 in a grilling session. Review this before implementation.

## Goal

Replace the number-only element editor with a visual one. Today `cf.client.display.ElementEditor`
is a list plus typed fields, hosted by the screwdriver menu (`ScreenEditorScreen`, a 320 px window
with no preview) and by the workbench Screen tab (`cf.client.design.ui.ScreenTab`). The new editor
shows the real display through a camera, lets you draw, drag and resize elements on it, and adds
new element kinds, gradients, SVG art and progress bars.

## Where it runs

- One editor, two hosts. The screwdriver menu is the reference; the workbench mirrors it exactly.
- Screwdriver: right-click a display (both alphanumeric forms, and designed screens) with a
  screwdriver.
- Workbench: an "Edit layout" button in the Screen tab switches the whole workbench into the editor,
  replacing the workbench tabs until the editor is exited. The workbench edits its local layout
  (`LocalElementTarget`), as today.

## Power

- The editor only opens on a powered display. Right-clicking an unpowered one shows an action-bar
  message ("Display has no power") and opens nothing.
- If the display loses power while the editor is open: flush any pending typed field edit, close
  the editor (camera flies back), show an action-bar message ("Display lost power"). Undo history is
  dropped as on any close.
- The workbench editor has no power check.

## Camera and view

- Opening the editor flies the camera smoothly (eased, wall-clock driven, like `DesignCamera`) into
  an orthographic view. Closing flies back from wherever the camera is.
- World displays: the camera faces the display glass head-on, rolled so the element frame is upright
  on screen (viewport +x is screen right, +y is screen down), whatever way the block faces or is
  rotated.
- Workbench: the camera looks down from the top centre of the bench at the whole design model,
  posed so its glass faces straight up and is centred on the bench. Curved glass still shows curved.
- The near plane sits just in front of the glass, so blocks, entities or the player in front can
  never hide it. HUD and hand are hidden while the editor is open.
- The display is centred on the whole screen, not on the area between sidebars. Resizing,
  collapsing or splitting sidebars never moves the camera.
- No panning.
- Zoom: a minus and a plus button at the bottom-right of the middle area, the zoom percentage
  written above them, hugging the right sidebar's inner edge (it follows that edge as the sidebar
  resizes or collapses). Each press, Ctrl or Cmd with minus or plus, or a wheel notch over it, steps
  through 25, 33, 50, 67, 100, 150, 200, 300, 400, 600, 800, 1200 and 1600%. Zoom is always centred
  on the display.
  It may zoom in past "display fills the screen"; edges then go off-screen and that is accepted.
- On open, the zoom is fitted once so the display fits between the sidebars at their current
  widths. After that it never changes on its own.

## Screen layout

- The editor runs at its own GUI scale while open, put back on close: the largest no larger than
  the game's that leaves it about 860 by 520 scaled pixels, or `displayEditorGuiScale` in the
  client config when that is set above 0.
- Middle: the world view (the display preview).
- Two sidebars, left and right, opaque.
  - Each sidebar can be collapsed to a thin strip at the screen edge with an expand button.
  - Each sidebar's inner edge can be dragged to change its width.
- Menus live in sidebars.
  - Three menus for now: Hierarchy, Inspector, and an empty placeholder menu (shipped to all
    players; a real menu replaces it later).
  - A menu can be dragged by its title bar to the other sidebar.
  - Dropping a menu onto a sidebar that already holds one splits it vertically: dragging toward
    the top or bottom half chooses where it goes. A sidebar can hold several menus stacked.
  - Split lines between stacked menus can be dragged to resize them.
  - A menu can be collapsed to its title bar with a + button to expand it again. The other menus
    in that sidebar take the freed space. Menus cannot be closed.
- The layout (sidebar widths, collapsed states, menu placement and split sizes, menu collapsed
  states) persists per client in a config file, shared by the screwdriver and workbench editors.
- Top bar: Done, Rotate (only for a display that is a block of its own, as today), and the summary
  line (element count, text budget). Everything the current menu has stays.
- Esc and Done both close the editor.

## Tool strip

- Floats at the bottom edge of the screen, as square icon buttons; hovering one names it and its key.
- Tools: Select (V), Text (T), Rectangle (R), Ellipse (E), Line/Arrow (L), Bar (B), Import SVG.
- Drawing a shape: drag on the glass to define its box (or its two endpoints for a line).
- After one shape the tool reverts to Select. Double-clicking a tool button locks it until Select
  is picked or V is pressed.
- Import SVG opens the system file picker (`DesignImporter.browseSvg`, as protruding arrows and
  decals do), then the player drags out the box for it.

## Working on the glass

- Click selects. Shift-click adds to the selection. Ctrl/Cmd-click toggles one element.
- Drag on empty glass with Select draws a selection rectangle.
- Drag a selected element (or the selected group) to move it.
- Resize handles on corners and edges, only when exactly one element is selected. Lines get two
  endpoint handles instead.
- Arrow keys nudge the selection.
- Snapping: whole pixels by default, plus the viewport's edges and centre lines. Hold Alt/Option
  to snap to tenths of a pixel instead.
- Shift while resizing keeps the aspect ratio (draws circles and squares). Shift while drawing or
  dragging a line endpoint snaps the line to 45 degree steps.
- Locked elements cannot be selected on the glass. Hidden elements are not drawn in the editor view.
- Text elements whose text is empty show a dimmed placeholder (for example "#3 text"), so an empty
  box is never invisible. Otherwise text shows the live linked text, or the typed static text.

## Editing commands

- Undo and redo: Ctrl/Cmd+Z and Ctrl/Cmd+Y.
  - One drag, one tool action, one paste, one multi-element move or one menu command is one step.
  - Typing in an inspector field is one step per field, merging keystrokes less than 1 second
    apart (as `PlcCodeEditor` does).
  - Depth 100.
  - Undo sends field-scoped edits restoring old values. Last write wins against other players.
  - Undoing a delete or cut restores the element in the same slot id, so display links pointing at
    it keep working. Needs a new server edit, "restore element at id".
  - History lives only while the editor is open. Closing it (Done, Esc, power loss) discards it.
- Cut, copy, paste: Ctrl/Cmd+X, C, V.
  - Uses the system clipboard, as text, so layouts can be shared outside the game.
  - Paste keeps original positions when they fit, offsets by 1 px when pasting onto the display
    the elements came from, and clamps to the viewport.
  - Pasted elements always get new ids.
  - Paste is refused with a message when it would pass the 128-element cap.
- Delete removes the selection. Ctrl/Cmd+D duplicates it.

## Right-click menus

- On an element: Cut, Copy, Paste, Duplicate, Delete, Bring to front, Raise, Lower, Send to back.
- On empty glass: Paste, plus Add for every element kind (placed at the cursor).
- In the hierarchy, on an element: same as on the glass.
- On a menu title bar: move to the other sidebar, collapse.

## Hierarchy menu

- Lists elements in z-order (drawing order, front at the top).
- Drag rows to reorder. Raise and lower buttons too.
- Each row shows the element's name if it has one, otherwise its id and kind (and a text snippet).
- Elements get an optional name, editable in the inspector or by double-clicking the row. Names
  also appear in the display link's element picker.
- Per-row toggles:
  - Hide (eye): editor view only, never saved.
  - Lock: saved, everyone sees it. A locked element cannot be selected on the glass (still
    selectable in the hierarchy).
- Selection is shared with the glass.

## Inspector menu

- Live numeric and property fields for the selection, updated while dragging. Keeps the current
  editor's live refresh and per-field hold rules.
- With several elements selected, it edits the fields they share. A field whose values differ
  shows blank until typed into.
- Edits apply live, field-scoped, as today.

## Element kinds

All kinds are axis-aligned. Text, Bar and SVG also have quarter turns. Up to 128 elements per
display, all kinds counting.

- Text: as today.
- Rectangle: a Hollow toggle (a hollow rectangle is a frame), stroke width when hollow, and a
  corner radius (rounded rectangles). Frames saved before load as hollow rectangles.
- Ellipse (new): a Hollow toggle, stroke width when hollow. A circle is an ellipse drawn
  with Shift.
- Line (new): two endpoints, thickness. Arrow options:
  - Heads: none, start, end, both.
  - Head style: filled triangle or open V.
  - Head length and width, in pixels.
- SVG (new): see below.
- Bar (new): see below.

## Fills

Filled shapes (Rectangle, rounded Rectangle, filled Ellipse) and the bar's fill choose a fill type:

- Solid.
- Linear gradient: angle, plus stops.
- Radial gradient: centre and radius as fractions of the box, plus stops.
- Stops: colour and position, up to 8, added and removed in the inspector.
- Draggable gradient handles on the glass (a line for linear, centre and radius for radial). They
  show only while the fill section in the inspector is expanded, and are styled differently from
  resize handles.
- Outline shapes (Frame, outline Ellipse, Line) and Text stay solid colour.

## SVG elements

- Imported with the system file picker. The client converts the file with the existing
  `SvgDecalImporter` into `DecalArt` triangles, uploads them to the world's content-addressed
  design store, and the element references them by hash. The element's box scales the art.
- Cap: about 20,000 triangles per SVG. Larger imports are refused with a message.
- Text in the SVG is converted to glyph outlines (as the decal importer already does).
- Real gradients, for display SVGs only: linear and radial, any number of stops, spread methods
  (pad, reflect, repeat), `gradientTransform`, stop opacity. Needs per-vertex colour with enough
  subdivision. Panel decals and protruding arrows keep their current averaged colours.
- Colour toggle on the element: the file's own colours (default), or everything tinted to the
  element's colour.
- Unsupported features warn once on import, as the decal importer already does.

## Bar elements

- A display link target, like text. Bars join the same line sequence as text elements: consecutive
  link lines fill text and bar elements in id order from the chosen element (update
  `LinkRouting` and the link element picker).
- Value parsing as gauges do (`ProceduralGaugeDisplayTarget`): the first number in the text,
  thousands commas stripped. Typed static text is parsed the same way.
- Mapping: (value - min) / (max - min), clamped to the box (0 to 100%). No overshoot, no tremble.
- When the text has no number, the bar holds its last value. With no value ever, it is empty.
- Settings:
  - Min and max.
  - Direction: left to right, right to left, bottom to top, top to bottom.
  - Fill colour (the element colour) and track colour (the highlight field).
  - Easing on or off (needle-style easing toward the target each tick). Default on.
  - Fill type: Solid, Linear, Radial or Bands.
- Linear and Radial gradients on a bar span the whole box; the fill reveals the gradient as it
  grows (green-to-red only reads red near full).
- Bands fill type:
  - The bar fill is one solid colour, chosen by the current value.
  - Each band has a colour, a lower level and an upper level.
  - Levels are in value units or percent of the range, selectable per bar.
  - Up to 8 bands. Default: one band covering the whole range.
  - The first band's lower level is pinned to the minimum and the last band's upper level to the
    maximum. Those fields are greyed out (both, with a single band).
  - Bands never overlap: the inspector clamps a band's lower level to at least the previous band's
    upper level, keeping them sorted.
  - Touching bands switch colour hard at the boundary. A gap between two bands fades smoothly from
    the lower band's colour to the upper band's colour across the gap. No separate option.
  - With easing on, the colour follows the animated value.
  - Bands are edited in the inspector only, with no markers on the glass.
- Editor-only preview-value slider in the inspector, never saved, to see the bar fill while laying
  it out.

## Server and data changes implied

- New element kinds and fields in `ScreenElement` and its save format: corner radius, ellipse fill
  toggle, line endpoints, thickness and arrow options, fill type, gradient data, SVG hash and colour
  toggle, bar settings and bands, name, lock flag. Old saves load unchanged.
- New field bits in the field-scoped edit packet (`EditScreenElementPacket`) for the new fields.
- New edit: restore an element at a given id (for undo of delete and cut).
- Link routing and the link picker include bars, and show element names.
- Content store accepts display SVG art with gradient data.
