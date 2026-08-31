# Official SkillHub CLI

Use an installed `skillhub` binary when available. For reproducible CI, pin a tested `@astron-team/skillhub` package version.

## Authentication

Prefer environment injection for agents:

```bash
export SKILLHUB_REGISTRY="https://skillhub.example.com"
export SKILLHUB_TOKEN="<api-key>"
skillhub whoami --json
```

`skillhub login --token ...` persists credentials locally and is better suited to an interactive workstation than ephemeral Agent or CI execution.

## Discovery and installation

```bash
skillhub search "browser" --limit 20 --json
skillhub install global/my-skill --version 1.2.0 --agent codex --json
skillhub list --json
```

## Publish

```bash
skillhub publish ./my-skill --namespace global --visibility public --dry-run --json
skillhub publish ./my-skill --namespace global --visibility public --json
```

## Namespace workspace synchronization

```bash
skillhub sync pull --namespace team --dir ./skills --check --json
skillhub sync pull --namespace team --dir ./skills --json
skillhub sync status --namespace team --dir ./skills --json
skillhub sync diff --namespace team --dir ./skills --json
skillhub sync push ./skills/my-skill --namespace team --visibility namespace-only --dry-run --json
skillhub sync push ./skills/my-skill --namespace team --visibility namespace-only --json
skillhub sync push --all --namespace team --dir ./skills --visibility namespace-only --json
```

Use `--submit-review` only for a public or namespace-only version when the registry requires review.

## Remote deletion

```bash
skillhub remove global/my-skill --remote --hard --json
```

`--hard` skips the interactive confirmation. Agents must perform the explicit coordinate confirmation and backup steps from `SKILL.md` before using it.

`skillhub remove` without `--remote` only uninstalls a local copy; it does not change the registry.
