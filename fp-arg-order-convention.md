# FP Arg-Order Convention for Threading Macros

Pure transform helpers consumed inside `->>` threading must place data LAST. This ensures seamless composition where the threaded value flows naturally as the final argument. Functions should be designed with the primary transformed value as their last parameter.

**BAD**: `(defn transform [data config] ...)` – data first breaks threading
**GOOD**: `(defn transform [config data] ...)` – data last works with `->>`

When using `->>`, each form receives the threaded value as its final argument. Pure transform functions should follow this convention: configuration/parameters first, data last. This enables clean pipeline composition: `(->> data (transform config) (filter pred) (map f))`.