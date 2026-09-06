# Preset: behaviour as data (OCP + DIP)

New behaviour arrives as data added to a table, not as a branch added to a function.

## The two shapes

**Provider-behaviour-as-data (DIP).** A provider is a map of its quirks — endpoints, auth style,
rate limits, error mappings — consumed by one generic driver. Adding a provider means adding an
entry, not editing the driver. If the driver has a `case` on provider name, the abstraction failed.

**Rule chains (OCP).** A policy is an ordered sequence of `{:when pred :then f}` entries evaluated
by a generic runner. Adding a rule means appending to the sequence. Ordering is explicit and
inspectable, which a chain of `cond` branches is not.

## Rules

1. A `case`/`cond` that grows by one branch every time the business adds a thing is the signal to
   convert it to a table.
2. Keep the data declarative — predicates and pure functions, no side effects buried in a rule.
3. The runner is closed for modification: it should not need to change when the table grows. If it
   does, the table is not carrying enough.
4. Registration order must be deliberate and stated, never "whatever the classpath yields."

## Where this bites

Policy that must be tuned per environment or per customer. Hardcoded, every tuning is a deploy;
as data, it is a value someone can read, diff, and test.
