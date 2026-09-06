# Preset: hive-test trifecta

A covered unit has three tests, and they answer three different questions.

| Test     | Question it answers                                        |
|----------|------------------------------------------------------------|
| golden   | Does it produce the right answer for the cases we know?     |
| property | Does the invariant hold across generated input?             |
| mutation | Would this suite actually NOTICE if the code were wrong?    |

## Mutation is the point

Golden and property tests can both pass against code that is subtly wrong. Mutation testing asks
whether the suite has teeth: introduce the specific defect you fear, and require the suite to fail.

Name the mutants after the real bug, not the syntax: "price-cents used as credit-micros" is a mutant
worth writing; "changed + to -" usually is not.

A mutant that survives is a coverage hole. Report the surviving mutant — the score alone hides it.

## Rules

1. Malli value objects first; the property facets come free from `hive-schemas.test`.
2. A test depends on CONTRACTS and injects stubs. It may not `:require` or `with-redefs` a concrete
   backend or a sibling implementation repo.
3. `^:integration` is for tests whose SUBJECT is a specific deployed system. It is never a way to
   quiet a unit test that is coupled to a vendor.
4. Check the module's test convention before writing — some require an isolated JVM.
