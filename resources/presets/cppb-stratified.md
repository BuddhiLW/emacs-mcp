# Preset: CPPB stratified design

Every subsystem is stratified into four altitudes. Each layer speaks only to the one below it, and
each layer's vocabulary is its own.

## The altitudes

| Layer         | Owns                                                        | May depend on |
|---------------|-------------------------------------------------------------|---------------|
| **Collect**   | gathering raw inputs; no interpretation                     | Boundary      |
| **Promote**   | raw input → domain value; validation, enrichment, folding   | Collect       |
| **Pipeline**  | orchestration of the aggregate lifecycle; sequencing, retry | Promote       |
| **Boundary**  | adapters: DB, HTTP, filesystem, external services           | —             |

## Rules

1. **Promote enriches, Pipeline orchestrates.** If a fold, a defaulting rule, or a derivation is
   sitting in the Pipeline layer, it is misplaced.
2. **Domain types never leak downward and adapter types never leak upward.** An adapter's error
   vocabulary is remapped onto the closed domain ADT *at the port*, not by callers downstream.
3. **A layer that needs something two levels down is a design smell**, not a shortcut to take.
4. **Altitude is about vocabulary, not file count.** Splitting one namespace into three that all
   speak the adapter's language buys nothing.

## Applying it to an existing namespace

State each existing function's altitude before moving anything. A function whose altitude you cannot
name is the one actually causing the trouble — that is where the work is.
