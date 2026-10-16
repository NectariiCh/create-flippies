# DIN-rail PSU — PSU-DCDC-230V-24V-100W-3M

Mod: Create: Flippies (Forge 1.20.1, Create 6.0.7, Power Grid 0.6.0), repo
`C:\Users\nektr\Documents\Create Flippies`, Java 17 at `C:\Program Files\Java\jdk-17\bin`.
Base package `com.nectariich.create_flippies` (`cf`).

## What the user asked for

- Add a DC-DC power supply that clips onto the DIN rail. 230 V in, 24 V out. The model is provided:
  `src/main/resources/assets/create_flippies/models/block/PSU-DCDC-230V-24V-100W-3M.json` with
  texture `textures/block/psu-dcdc-230v-24v-100w-3m.png` (+ the shared `copper_contacts`).
- **DC OK** output is up when the output voltage is **18–27 V** ("as per the standard").
- **The input voltage matters**: the 230 V input must be in range for the output to run.
- Later (NOT now) the user will ask for variants: input 120/230 V × output 5/12/24/48 V ×
  rating 20 W (1 module), 50 W (2M), 100 W (3M), 250 W (4M), 500 W (6M). So build the PSU
  **data-driven** — one `PsuType`-style table entry per variant (input V, output V, watts, modules,
  model id) — and register only this one variant now. Adding a variant later must be: copy the
  model, add one table row, add lang. Do not create any other variant now.

## The model

- Authored plate-down like every DIN device here (y = depth off the plate, x along the rail, z
  across it), x 0..3 = three rail modules, groups `M1`, `M2`, `M3` (one per module). No rail
  section in this export. Elements' names are lowercase-unsafe ("lower base", "V+", "DC OK").
  Breaker exports are authored the same way (x from 0) and are centred with
  `CircuitBreakerModel.shiftX(modules)`; follow `tools/gen_circuit_breaker.py` / `tools/gen_plc.py`.
- Terminals (copper, texture `#2`), on the **sides** of the lower base, not on top:
  - z 10.5–10.75 face (+z side): `VCC` x 0.25–0.75, `GND` x 1.25–1.75 — the **input**.
  - z 5.25–5.5 face (−z side): `V+` x 0.25–0.75, `V-` x 1.25–1.75, `DC OK` x 2.25–2.75 — the
    **output** side. All pins at y 1.25–1.75.
- Write `tools/gen_psu.py` that: lowercases the file name (`psu_dcdc_230v_24v_100w_3m`), shifts x
  by `shiftX(modules)`, fixes texture refs to valid lowercase resource paths (rename the texture
  file to `psu_dcdc_230v_24v_100w_3m.png` if needed — hyphens are legal in ResourceLocations but
  keep the mod's convention; check what the breakers do), drops `groups`/`format_version`/`credit`,
  sets `particle` to the body texture, writes the block partial and an item model (copy the item
  display transforms the breakers use), prints the terminal boxes. Keep the original export in
  place (the user re-exports to it). It must be re-runnable and must work for future variants
  (driven by the table or by a CLI arg / glob over `PSU-*.json`).
- Orientation: keep as authored. (The PLC export turned out to be upside down and is spun 180° in
  `gen_plc.py`; do NOT spin the PSU unless you find evidence — the user will check in game.)

## Terminals (frozen order once shipped)

| idx | element | name shown | role |
|---|---|---|---|
| 0 | VCC | "L / + (230 V in)" or similar | input + |
| 1 | GND | "N / − (230 V in)" | input − |
| 2 | V+ | "V+ (24 V out)" | output + |
| 3 | V- | "V− (24 V out)" | output − |
| 4 | DC OK | "DC OK" | status output |

Grown click boxes outward from the face they sit on (here ±z, not +y), padding under half the gap
to neighbours, explicit Power Grid expand of 0 (see the class comment in `PlcSmallGeometry` for why:
the 6-arg `TerminalBoundingBox` constructor adds 0.1 px per side). Wire origin = centre of the
pin's outer face. Colours: input pair red/blue, outputs V+ red / V− blue (or distinguish them from
the input pair — your call, document it), DC OK a signal colour. Footprint: the body boxes + hooks
+ grown terminal boxes (look at `PlcSmallGeometry.DIN_FOOTPRINT` and the breaker's footprint).
Add a geometry test like `PlcSmallGeometryTest` (no overlaps, each pin resolves to itself).

## Electrical model (Power Grid)

Power Grid is a DC MNA solver; the "230 V" input is treated as a DC magnitude |V(VCC) − V(GND)|
(PG has no AC line here). Available elements (verify with javap against the deobf jar at
`~/.gradle/caches/forge_gradle/deobf_dependencies/maven/modrinth/power-grid/ZHqOfBhb_mapped_official_1.20.1/power-grid-ZHqOfBhb_mapped_official_1.20.1.jar`):
`ElectricWire(R, a, b)` + `setResistance`, `SwitchedWire`, `VoltageSourceCoupling(pos, neg, V[, R])`
+ `setVoltage`/`setResistance`/`getCurrent`, `ProvidedVoltageSourceCoupling` (supplier-driven,
updated in `preSolve`), `CurrentSourceWire`, `TransformerCoupling`. Look at how the existing
controls build circuits (`HostedCircuitBuilder`, `PlcSmallControl`, `CircuitBreakerControl`,
`SquareProceduralGaugeControl`) and at how Power Grid's own blocks use couplings (e.g. its creative
source / battery / transformer block entities) before choosing.

- **Isolation**: the output is galvanically isolated from the input — no element joins an input
  node to an output node. The output's own V− is its reference.
- **Input window** (230 V nominal, values as fractions of nominal so variants reuse them):
  start when |V_in| ≥ 0.85 × V_nom (195.5 V); drop out when |V_in| < 0.75 × V_nom (172.5 V)
  (hysteresis); over-voltage shutdown when |V_in| > 1.20 × V_nom (276 V), restart when back
  ≤ 1.15 × V_nom. Reverse polarity (V_in negative) behaves like an AC-input PSU would on DC: treat
  the magnitude (a bridge rectifier is the front end) — document it.
- **Output**: regulated V_out (24 V) through a small internal resistance (~0.05 Ω) when running.
  When not running, the output must look like an *open* circuit, not a 0 V source (a 0 V source
  would short a second PSU paralleled onto it): disconnect/raise the source resistance to
  effectively open (document how).
- **Current limit**: rated I = P/V_out (100 W / 24 V = 4.17 A). Constant-current limit at
  ~1.1 × rated: when the load would draw more, the output voltage drops so I stays at the limit
  (estimate the load from the previous solve, R_L = V/I, set V = I_lim × R_L, clamp ≤ V_nom). A
  dead short (V_out < ~10 % of nominal at the limit) → hiccup mode: off for ~1 s, retry. Document.
- **Input draw** (what the PSU takes from the 230 V side): P_in = P_out / η + P_noload, with
  η ≈ 0.90 at full load (use a simple load-dependent curve, e.g. η falls toward ~0.80 at 10 % load)
  and P_noload ≈ 0.3 W while running, ~0 when off. Drawn through an input load wire
  R = V_in² / P_in updated each tick (clamped to a sane range; effectively open — e.g. 1 MΩ — when
  off). One-tick lag is acceptable; say so.
- **DC OK**: an output referenced to V−: when the measured output V+ − V− is within 18–27 V *and*
  the PSU is running, DC OK is driven to V+ through a sourcing switch (rated ~50 mA: use a series
  R_on small and protect it — e.g. latch it off above ~0.1 A until the PSU restarts, or limit it;
  document the choice); otherwise open. For variants the 18–27 V window must scale with the output
  rating (= 0.75–1.125 × V_nom; at 24 V that is exactly 18–27 V) — implement as fractions.
- Power and state are server-side, updated once per server tick like the PLC. Persist nothing
  but what must survive a reload (hiccup timer can reset). No `changed()` per tick.

## In the world

- A DIN-rail control like the breaker and the PLC: `ControlType` implementing `DinRailMountable`,
  `modules()` from the table, an item (plain item, like the breaker's/PLC's), creative tab entry,
  renderer drawing the partial with the same transform chain as `PlcSmallControlRenderer`, partial
  registered in `ModPartialModels`, lang names.
- Goggles tooltip (see `goggle-tooltip-style` conventions in existing controls): state
  (Off / Running / Input low / Input high / Overload (CC) / Hiccup), input voltage, output voltage
  and current, load % of rating, DC OK.
- No screwdriver menu (nothing to configure).
- Tests: JUnit for the pure logic (input window hysteresis, CC limit, hiccup, DC OK window,
  efficiency/input-power maths) — keep that logic in a class with no MC imports so it unit-tests
  cleanly — plus the geometry test.

## Rules

- Match the codebase's style (thoughtful Javadoc explaining *why*, 4-space indent, no wildcard
  imports). Don't touch PLC code.
- `gradlew compileJava` / `gradlew test` are allowed. Never run `gradlew runClient`.
