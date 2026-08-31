---
name: skillhub-operator
description: Operate a SkillHub registry through API-key authentication and the official CLI or bundled API client. Use this skill whenever a user or agent needs to validate, publish, update, synchronize, inspect, resolve, download, or delete remote skills; manage a namespace workspace; automate SkillHub from CI; diagnose token scopes or publish failures; or asks how to perform any SkillHub skill lifecycle action. This skill must also be used for destructive remote deletion so the required prechecks and confirmation are not skipped.
license: Apache-2.0
compatibility: Python 3.10+ for the bundled client; Node.js 20+ for the official SkillHub CLI.
---

# SkillHub registry operations

Use API keys for non-interactive Agent and CI workflows. Treat the registry as the source of truth for remote state and the local `SKILL.md` package as the source of truth for the next version.

## Inputs and authentication

Resolve these before doing work:

- Registry URL: `SKILLHUB_REGISTRY` or an explicit `--registry` value.
- API key: `SKILLHUB_TOKEN`; `SKILLHUB_API_KEY` is accepted by the bundled client as an alias.
- Target coordinate: namespace, skill slug, and version when relevant.
- Local package path for validate, publish, or update.

Keep API keys out of prompts, command arguments, logs, files, and git. Pass them through the environment. Never print the token or a request containing it.

Required scopes:

| Operation | Scope |
|---|---|
| whoami, search, list, resolve, download | `skill:read` |
| validate, publish, update, sync push | `skill:publish` |
| remote whole-skill deletion | `skill:delete` |

The platform may additionally enforce namespace membership, ownership, or platform roles.

## Choose the client

Prefer the official CLI for workspace synchronization and local installation:

```bash
skillhub whoami --registry "$SKILLHUB_REGISTRY" --json
skillhub sync status --namespace global --dir ./skills --registry "$SKILLHUB_REGISTRY" --json
```

Use the bundled Python client for deterministic API-only automation, structured JSON, and explicit deletion confirmation:

```bash
python .agents/skills/skillhub-operator/scripts/skillhub_api.py whoami
```

Read [references/cli.md](references/cli.md) for all official client commands and [references/api.md](references/api.md) for the exact HTTP contract.

## Standard workflow

1. Run `whoami` and stop if authentication fails.
2. Read current remote state with `resolve`, `list`, or `search`.
3. Inspect the local `SKILL.md`; confirm `name`, `description`, and intended `version`.
4. Run `validate` or CLI `publish --dry-run` before every upload.
5. Treat validation warnings as a decision point. Continue only when the user or calling workflow explicitly accepts them.
6. Publish the package or push the namespace workspace.
7. Verify the returned namespace, slug, version, and visibility.
8. Resolve the exact published coordinate and, when needed, download it to verify the durable package. If exact resolve fails after upload, report that the version is not yet published and may require review.
9. Report the exact coordinate and request ID for any failure.

Do not call an upload successful when only packaging or validation succeeded.

## Publish a new skill

API client:

```bash
python .agents/skills/skillhub-operator/scripts/skillhub_api.py publish ./skills/my-skill \
  --namespace global \
  --visibility public
```

Official CLI:

```bash
skillhub publish ./skills/my-skill \
  --namespace global \
  --visibility public \
  --registry "$SKILLHUB_REGISTRY" \
  --json
```

The bundled client validates first. Pass `--allow-warnings` only after reviewing the returned warnings.

## Update an existing skill

An update is a new published version of the same normalized `name`/slug. Use an explicit new `version` in `SKILL.md` for reproducible updates.

```bash
python .agents/skills/skillhub-operator/scripts/skillhub_api.py update ./skills/my-skill \
  --namespace global \
  --visibility public
```

The client resolves the current remote version and rejects a missing or unchanged local version. Use `publish`, not `update`, for a skill that does not yet exist.

For a multi-skill workspace:

```bash
skillhub sync diff --namespace global --dir ./skills --registry "$SKILLHUB_REGISTRY" --json
skillhub sync push --all --namespace global --dir ./skills --visibility public \
  --dry-run --registry "$SKILLHUB_REGISTRY" --json
skillhub sync push --all --namespace global --dir ./skills --visibility public \
  --registry "$SKILLHUB_REGISTRY" --json
```

## Inspect, resolve, and download

```bash
python .agents/skills/skillhub-operator/scripts/skillhub_api.py search --query "browser" --limit 20
python .agents/skills/skillhub-operator/scripts/skillhub_api.py list --namespace global
python .agents/skills/skillhub-operator/scripts/skillhub_api.py resolve --namespace global --slug my-skill
python .agents/skills/skillhub-operator/scripts/skillhub_api.py download \
  --namespace global --slug my-skill --output ./my-skill.zip
```

## Delete a remote skill

Remote deletion is a hard delete of the entire skill and all versions. It is not archive, unpublish, or local uninstall.

Before deletion:

1. Resolve the exact namespace and slug.
2. Record the current version and fingerprint.
3. Download a recoverable backup when the source package is not already durable elsewhere.
4. Require the user to confirm the exact `namespace/slug` coordinate.

```bash
python .agents/skills/skillhub-operator/scripts/skillhub_api.py delete \
  --namespace global \
  --slug my-skill \
  --confirm global/my-skill \
  --source-retained
```

Official CLI equivalent:

```bash
skillhub remove global/my-skill --remote --hard \
  --registry "$SKILLHUB_REGISTRY" --json
```

After deletion, verify that `resolve` returns not found. Report that recovery requires republishing from a retained source or backup.

## API-key lifecycle boundary

The current API-token policy supports whole-skill hard deletion but does not expose archive, unarchive, single-version deletion, withdraw-review, rerelease, confirm-publish, or admin moderation to API keys. Those endpoints require an interactive session or a platform change.

Do not work around this boundary with browser cookies, database writes, or undocumented endpoints. Read [references/lifecycle.md](references/lifecycle.md) before handling review, archive, yank, or version-state requests.

## Error handling

- `401`: token missing, invalid, expired, or from the wrong registry.
- `403`: missing scope, namespace permission, ownership, or platform role.
- `404`: wrong namespace/slug/version, or the skill is not visible to this token.
- `409`: conflicting version or lifecycle state; inspect remote state before retrying.
- `413`: package exceeds the configured upload limit.
- `422` or validation response with `valid=false`: fix the package; do not retry unchanged.
- `502`/`503`: registry or dependency unavailable; preserve the request ID and retry with bounded backoff.

Return a concise result containing action, registry, coordinate, version, visibility, and verification evidence. Include lifecycle status only when an API response actually provides it. Never include the API key.
