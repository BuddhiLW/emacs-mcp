# Preset: crystallize into hive memory

Rationale lives in hive memory. Code carries contract only.

## The split

- **Docstrings and comments**: WHAT it does, args, return, gotchas needed to call it correctly.
- **Hive memory**: WHY it is this way — the tradeoff, the incident, the thing that bit us, the
  decision that was live at the time.

A comment beginning "because", "the reason", or narrating a bug is misplaced rationale. Move it to
memory and, if it earns one, leave a single-line pointer to the memory id.

## When to write

Same turn as the discovery. A decision, a correction, or a piece of feedback that is still only in
the conversation is already half-lost.

## Types

`decision` · `convention` · `axiom` · `note` · `plan`

Link with KG edges — `kg_supersedes`, `kg_refines`, `kg_depends_on`, `kg_implements` — so the entry
is reachable from the things it touches rather than sitting alone in a search index.

## Rules

1. Convert relative dates to absolute. "Last week" is meaningless to the agent that reads it later.
2. Superseding an entry means recording the supersedes edge, not silently writing a contradiction.
3. Report the memory ids you created. They are part of the deliverable, not a side effect.
