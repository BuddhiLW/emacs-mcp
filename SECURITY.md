# Security Policy

## Supported versions

Fixes land on the latest released minor version. There is no long-term support
branch before 1.0.0.

## Reporting a vulnerability

Report privately, not as a public issue:

- GitHub: open a draft advisory at
  <https://github.com/hive-agi/hive-mcp/security/advisories/new>
- Email: pedrogbranquinho@gmail.com

Please include what an attacker gains, the smallest reproduction you have, and
the version or commit you tested. You will get an acknowledgement within a few
days. A fix is released before the advisory is published.

## What is in scope

hive-mcp runs as a local process with the privileges of the user who starts it.
It executes tools, evaluates Clojure over nREPL, spawns terminals and reads
secrets from the configured store, all by design. Reports that reduce to "the
server can run code as its own user" are not vulnerabilities.

In scope:

- An MCP client, addon or workspace file reaching capabilities the running
  configuration did not grant it.
- Secrets, credentials or the contents of the memory store leaking to a log, a
  tool response, or a network destination that should not receive them.
- A network listener (nREPL, the WebSocket transports, the legacy channel)
  accepting a connection from beyond its intended boundary, or accepting one
  without the authentication its configuration claims.
- An addon mounted from the classpath escaping the entitlement or trust class
  the mount manifest declares.

## Addons and dependencies

An addon runs in the host JVM with full host privileges. Mounting one is a
trust decision equivalent to adding a dependency. Report vulnerabilities in a
published hive-agi addon here; report ones in third-party libraries upstream
first.
