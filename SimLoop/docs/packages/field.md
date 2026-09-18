# `field`

`org.horizon36596.simloop.field`

Game pieces and possession, modelled the only way a simulator without contact physics honestly can:
positions, states, and boxes relative to the robot.

## What you use

| Type | What it is |
|---|---|
| `GamePiece` | One piece: an id, a field position, a radius, a possession state. |
| `PossessionState` | `LOOSE`, `HELD`, `SCORED`. |
| `GamePieceTracker` | Owns every piece, enforces the held capacity, logs every transition. |
| `TriggerVolume` | A rectangle fixed to the robot — "is a piece inside my intake's box right now". |

## The model

A piece is `LOOSE` until the robot acquires it, `HELD` while carried, `SCORED` once it is out of play.
Acquisition happens when a piece overlaps a trigger volume and the robot is not already at capacity.
Pieces are scanned in id order, so a partly-full robot fills up reproducibly on a replay.

**Every state change is logged, by construction** — every transition in the tracker goes through one
private method that queues its log line, so "every transition is logged" is true because of the code
shape, not because somebody remembered.

## Units and frames

- Piece positions: **inches**, field frame, FTC-Cartesian — **+X** right, **+Y** forward, origin at the
  centre of the field. **Note this is not the frame the [`plant`](plant.md) package integrates a
  drivetrain pose into**, which is +x forward / +y left; see the warning on that page.
- Piece radius: **inches**, half its diameter.
- Robot heading: **radians**, counter-clockwise-positive; the robot's forward direction is
  `(cos, sin)` of it.
- `TriggerVolume` offsets: **inches**, **robot** frame — forward-positive and left-positive, so a box in
  front of the robot has a positive `forwardOffsetInches`.

## Live views, not snapshots

`getPieces()` hands back an unmodifiable list, but the pieces in it keep changing as the tracker updates
them. A scenario asking "where is piece 3 now" wants *now*. Read what you need in the same tick you got
it; do not cache a position across ticks and assume it is still true.

## What this package will not do

- **It will not decide whether an intake works.** Overlap with a box is a model of intake *logic*, not of
  intaking. A real intake that misses is not modelled.
- **No collisions between pieces, no stacking, no physics of any kind.** A piece has a position and a
  radius; it does not move unless something sets it.
- **No scoring rules.** `SCORED` means "out of play"; what it is worth is your season's business.
- **No field geometry.** Nothing here knows where a wall or a goal is.
