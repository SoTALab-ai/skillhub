# Lifecycle and governance boundary

## Package and container states

Publishing affects both a skill container and a version.

- Container: `ACTIVE`, `ARCHIVED`, plus a governance `hidden` overlay.
- Version: `DRAFT`, `SCANNING`, `SCAN_FAILED`, `UPLOADED`, `PENDING_REVIEW`, `PUBLISHED`, `REJECTED`, or `YANKED`.
- `latestVersionId` points only to a `PUBLISHED` version.

## Visibility

- `PUBLIC`: visible according to registry access policy after publish/review.
- `NAMESPACE_ONLY`: visible to the namespace audience.
- `PRIVATE`: owner/governance-restricted and normally not submitted for public review.

A platform administrator may auto-publish. Other publishers can receive `PENDING_REVIEW` or `UPLOADED`; always inspect the returned status.

## API-key-safe operations

API keys can:

- read/search/resolve/download visible skills;
- validate packages;
- publish a new skill or new version;
- delete an entire remote skill when granted `skill:delete`.

API keys currently cannot:

- archive or unarchive a skill;
- delete one version while retaining the skill;
- withdraw or submit review through the lifecycle controller;
- confirm warnings through the browser confirmation flow;
- approve/reject, yank, hide, restore, or perform admin moderation.

If the user requests an unsupported action, report the boundary and ask whether to use the interactive UI or extend the platform's API-token policies. Do not substitute hard deletion for archive, unpublish, or single-version deletion.

## Safe update sequence

1. Resolve the current remote version and fingerprint.
2. Update local content.
3. Increment the explicit version in `SKILL.md`.
4. Validate the package.
5. Review warnings and intended visibility.
6. Publish.
7. Verify returned status and resolve the exact version.
8. Keep source and commit provenance outside SkillHub when the current server does not store it.

## Safe deletion sequence

1. Resolve the exact coordinate.
2. Download or locate a durable source backup.
3. Record version and fingerprint.
4. Confirm the exact coordinate.
5. Delete with `skill:delete`.
6. Verify `resolve` no longer succeeds.
7. State that recovery is a republish, not an undelete.
