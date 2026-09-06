# Swarm Presets

Presets are markdown files that configure slave Claude instances with specialized behaviors, constraints, and guidelines.

## Built-in Presets

| Preset | Description | Use Case |
|--------|-------------|----------|
| `clarity` | CLARITY framework (Compose, Layer, Architectural, Represent, Input, Telemetry, Yield) | Architecture decisions |
| `solid` | SOLID principles (SRP, OCP, LSP, ISP, DIP) | Class/module design |
| `ddd` | Domain-Driven Design patterns | Complex domain modeling |
| `tdd` | Test-Driven Development (Red-Green-Refactor) | Writing tests first |
| `tester` | Test execution specialist | Running test suites |
| `reviewer` | Code review specialist | PR reviews |
| `documenter` | Documentation writer | Writing docs |
| `refactorer` | Refactoring specialist | Code restructuring |
| `researcher` | Codebase researcher | Understanding code |
| `fixer` | Bug fixing specialist | Debugging |
| `minimal` | No special constraints | General tasks |
| `task-coordinator` | Breaks complex tasks into parallel subtasks | Orchestrating parallel work |
| `ling` | Focused worker agent for single tasks | Leaf node execution |

## Predefined Roles

Roles combine multiple presets:

| Role | Presets Applied |
|------|-----------------|
| `tester` | tester, tdd |
| `reviewer` | reviewer, solid, clarity |
| `documenter` | documenter |
| `refactorer` | refactorer, solid, clarity |
| `researcher` | researcher |
| `fixer` | fixer, tdd |
| `clarity-dev` | clarity, solid, ddd, tdd |
| `coordinator` | task-coordinator |
| `ling` | ling, minimal |
| `worker` | ling |

## Using Presets

```elisp
;; Spawn with specific presets
(emacs-mcp-swarm-spawn "my-tester" :presets '("tdd" "clarity"))

;; Spawn with a role (expands to presets)
(emacs-mcp-swarm-spawn "code-reviewer" :role "reviewer")

;; Combine multiple presets
(emacs-mcp-swarm-spawn "full-stack" :presets '("clarity" "solid" "ddd" "tdd"))
```

## Custom Presets

### Adding a Custom Directory

```elisp
;; Add your presets directory
(add-to-list 'emacs-mcp-swarm-custom-presets-dirs "~/my-presets/")

;; Or interactively
M-x emacs-mcp-swarm-add-custom-presets-dir
```

### Writing a Custom Preset

Create a `.md` file in your presets directory:

```markdown
# My Custom Preset

You are a specialist in [DOMAIN].

## Guidelines

- Guideline 1
- Guideline 2

## Constraints

- Must always do X
- Never do Y

## Output Format

- How to structure responses
```

The filename (without `.md`) becomes the preset name.

### Directory Structure

```
my-presets/
├── team-conventions.md
├── project-specific.md
└── domain/
    ├── healthcare.md
    └── finance.md
```

All `.md` files are loaded recursively. Nested files use just the filename as the preset name.

## Combining Presets

When multiple presets are applied, they are concatenated with `---` separators. The combined prompt is passed to the slave via `--system-prompt`.

Order matters: later presets can override earlier ones if they contain conflicting instructions.

## Best Practices

1. **Be specific**: Clear, actionable guidelines
2. **Include examples**: Show expected behavior
3. **Define output format**: Consistent responses
4. **Keep focused**: One concern per preset
5. **Test your presets**: Verify they work as expected
