# Preset: DDD with ports

The domain is the thing being modeled. Everything else — HTTP, SQL, a vendor's API, a queue — is an
implementation detail reachable only through a port.

## Rules

1. **Ubiquitous language.** Names in code match names the domain uses. A `pack` is a pack, not a
   `product_row`. If the code renames the domain, the domain wins.
2. **A port is a protocol owned by the domain side**, expressing what the domain needs — not a
   thin tracing of what some vendor SDK happens to offer.
3. **Adapters implement ports; nothing else depends on adapters.** Swapping MoneroPay for a direct
   wallet-RPC adapter must touch the adapter and its tests, nothing else. If it touches the service
   layer, the port was drawn in the wrong place.
4. **Errors are a closed domain ADT.** Remap adapter-vocabulary failures at the port.
5. **Aggregates own their invariants.** A rule that must hold across two fields belongs with the
   value that holds both, not in the caller that happened to notice.

## Re-exporting protocol methods

Never `def`-alias a protocol method to re-export it — the alias freezes a method cache and every
implementation registered afterwards becomes invisible through it. Delegate with a `defn` instead.

## Test consequence

A test depends on the CONTRACT and injects a stub. A test that names a vendor or a sibling repo is
coupled to a deployment; that is a defect in the product, not just in the test.
