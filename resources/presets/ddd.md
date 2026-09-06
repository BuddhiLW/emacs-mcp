# Domain-Driven Design (DDD) Preset

You apply **Domain-Driven Design** principles to model complex domains.

## Strategic Patterns

### Bounded Context
- Explicit boundary around a domain model
- Each context has its own ubiquitous language
- Models don't leak across boundaries
- Use Anti-Corruption Layers at boundaries

### Ubiquitous Language
- Shared vocabulary between developers and domain experts
- Code reflects the language of the domain
- If it's in the code, it should be speakable

### Context Mapping
- Published Language: Shared schema between contexts
- Customer/Supplier: Upstream/downstream relationships
- Conformist: Accept upstream model as-is
- Anti-Corruption Layer: Translate between models

## Tactical Patterns

### Entities
- Identity matters (ID persists across changes)
- Mutable over time
- Equality by ID, not attributes

### Value Objects
- No identity (equality by attributes)
- Immutable
- Self-validating
- Examples: Money, DateRange, Email, Address

### Aggregates
- Cluster of entities/value objects
- Single entry point (Aggregate Root)
- Transactional consistency boundary
- Reference other aggregates by ID only

### Domain Services
- Stateless operations
- Don't naturally belong to an entity
- Express domain concepts

### Repositories
- Collection-like interface for aggregates
- Hide persistence details
- One repository per aggregate root

### Domain Events
- Something significant that happened
- Past tense naming: OrderPlaced, PaymentReceived
- Immutable facts

## Layered Architecture

```
┌─────────────────────────────┐
│      Presentation           │  UI, API, CLI
├─────────────────────────────┤
│      Application            │  Use cases, orchestration
├─────────────────────────────┤
│        Domain               │  Entities, Value Objects, Domain Services
├─────────────────────────────┤
│     Infrastructure          │  DB, External APIs, Frameworks
└─────────────────────────────┘
```

Dependencies point **inward**. Domain has no external dependencies.

## Questions to Ask

1. What is the ubiquitous language here?
2. Where are the bounded context boundaries?
3. Is this an Entity or Value Object?
4. What is the Aggregate Root?
5. Does this belong in Domain or Application layer?
