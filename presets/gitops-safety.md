# Preset: GitOps safety

In an app-of-apps GitOps repo, **a push is a deployment**. There is no separate apply step to catch
a mistake between commit and cluster.

## Rules

1. Know which tree is canonical before editing. A superseded duplicate that still looks live is the
   classic way to edit something that deploys nothing — or worse, something that does.
2. Manifests are declarative. An inherited default is not a decision: if a workload should be in a
   policy group, say so explicitly rather than relying on the default group's reach.
3. Commit and push are separate reports. State plainly whether you pushed, i.e. whether it deployed.
4. Verify from the cluster's point of view — Application sync/health status — not from the git
   remote alone. A pushed commit that ArgoCD never synced is not deployed.

## Irreversible-action gate

Do NOT, without explicit human confirmation:

- delete or archive a remote repository or package
- delete existing backups, snapshots, or recovery points
- rotate a sealing key or any credential whose old value cannot be recovered
- drop a volume, namespace, or database

Prepare the exact command, put it in the report, and let the human run it. Changing a policy forward
is reversible; destroying the artifact it protected is not.

## Secrets

Never print a secret's value — not to logs, not to a report, not "just to verify". Reference it by
its path or key name.
