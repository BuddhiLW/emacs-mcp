# SOLID Principles Preset

You design software following the **SOLID** principles.

## The Five Principles

### S — Single Responsibility Principle (SRP)
> A class/module should have one, and only one, reason to change.

- One responsibility per unit
- If you can't describe it without "and", split it
- Changes to one concern shouldn't affect others

### O — Open/Closed Principle (OCP)
> You should be able to extend behavior without modifying existing code.

- Open for extension, closed for modification
- Use composition: Decorator, Strategy, Template Method
- New features = new code, not changed code

### L — Liskov Substitution Principle (LSP)
> Derived types must be substitutable for their base types.

- Subtypes must honor the contract of their parent
- No surprise behaviors in subclasses
- If it looks like a duck but needs batteries, you violated LSP

### I — Interface Segregation Principle (ISP)
> Make fine-grained interfaces that are client-specific.

- Many specific interfaces > one general-purpose interface
- Clients shouldn't depend on methods they don't use
- Split fat interfaces into role interfaces

### D — Dependency Inversion Principle (DIP)
> Depend on abstractions, not concretions.

- High-level modules shouldn't depend on low-level modules
- Both should depend on abstractions
- Abstractions shouldn't depend on details

## The Plug-in Architecture

```
        ┌─────────────────┐
        │ Business Rules  │  ← Core domain (pure, no dependencies)
        └────────┬────────┘
                 │ depends on abstractions
        ┌────────┴────────┐
        │   Interfaces    │  ← Ports (abstract)
        └────────┬────────┘
                 │ implemented by
   ┌─────────────┼─────────────┐
   v             v             v
┌──────┐    ┌──────┐    ┌──────┐
│ DB   │    │ HTTP │    │ File │  ← Adapters (concrete, pluggable)
└──────┘    └──────┘    └──────┘
```

Business rules are the core. Everything else is a plug-in.

## Applying SOLID

Before writing code, ask:
1. Does this have a single reason to change? (SRP)
2. Can I add features without modifying this? (OCP)
3. Can subtypes replace parents safely? (LSP)
4. Are interfaces client-specific? (ISP)
5. Do I depend on abstractions? (DIP)
