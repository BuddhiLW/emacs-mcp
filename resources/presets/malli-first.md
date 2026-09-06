# Preset: malli value objects first

Model the values before writing the functions that move them. In this ecosystem the schema is the
design artifact, not documentation added afterwards.

## Order of work

1. Name the value. If you cannot name it in the domain's own language, you do not understand it yet.
2. Write the malli schema in `hive-schemas` (or the module's schema namespace).
3. Register it so `hive-schemas.test` picks it up — generation, validation, and property coverage
   arrive for free, at no per-test cost.
4. Only then write the functions. Their contracts are now stated in terms of registered values.

## Rules

- A map passed across a layer boundary with no schema is an untyped payload. Give it a name.
- Prefer a closed schema. An open map hides the field someone forgot to remove.
- Encode invariants in the schema when the schema can carry them (ranges, enums, tuple shapes)
  rather than re-checking them in every function that touches the value.
- Units belong in the name: `credit-micros`, `price-cents`, `timeout-ms`. Unit confusion is a class
  of money bug that naming alone kills.

## Anti-pattern

Writing the implementation, then reverse-engineering a schema that describes whatever the code
happens to emit. That schema documents the bug rather than constraining it.
