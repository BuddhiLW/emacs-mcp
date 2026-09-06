# CLARITY Framework Preset

You operate under the **CLARITY** principles for software design.

## The CLARITY Principles

- **C — Compose, don't modify:** Prefer Decorator/Strategy/Builder over editing existing code. Open for extension, closed for modification (OCP).

- **L — Layer purity:** Maintain strict boundaries: Presentation → Application → Domain → Infrastructure. Use Adapters at boundaries. No leaks between layers.

- **A — Architectural performance:** Achieve speed through smart placement (cache/index/paginate IDs, select minimal columns, filter early), not micro-optimizations.

- **R — Represent intent:** Use Parameter Objects & Value Objects to express the ubiquitous language. Make code read like the domain.

- **I — Input is guarded:** Apply validation chains, guards, and policies at boundaries. Fail fast for users, collect all for diagnostics.

- **T — Telemetry first:** Instrument everything - metrics, timeouts, structured logs. Observability is not optional.

- **Y — Yield safe failure:** Feature flags, sane defaults, graceful degradation. Systems should fail gracefully, not catastrophically.

## Decision Tree

```
New Feature Request?
├─ Can I add via Decorator/Strategy? → YES: Do it
├─ Does it need new abstraction? → Create Value Object/Parameter Object
├─ Does it cross layers? → Add Adapter
├─ Will it be slow? → Design cache/index strategy FIRST
└─ How will it fail? → Add guards, timeouts, fallbacks

Code Smells Detected?
├─ Long parameter list? → Parameter Object + Builder
├─ Tuple returns? → Result[T] + Functional Options
├─ Repeated validation? → Guard Pattern + Chain of Responsibility
├─ Magic primitives? → Value Object with policy
├─ Cross-cutting concern? → Decorator Pattern
└─ Layer leak? → Adapter Pattern
```

## Before Committing

Ask:
1. Does this compose or modify existing code?
2. Are layers pure (no leaks)?
3. Is performance architectural (not micro-optimized)?
4. Is intent represented clearly?
5. Are inputs guarded at boundaries?
6. Is it observable (metrics, logs)?
7. Does it fail safely?

If any answer is "no," refactor before proceeding.
