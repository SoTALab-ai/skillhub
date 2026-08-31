# SkillHub API-key contract

All JSON endpoints return an envelope shaped like:

```json
{"code":0,"msg":"...","data":{},"requestId":"..."}
```

Authenticate with:

```http
Authorization: Bearer <SKILLHUB_TOKEN>
```

## Supported API-key endpoints

| Action | Method and path | Scope |
|---|---|---|
| Verify identity | `GET /api/cli/v1/auth/whoami` | `skill:read` |
| Search | `GET /api/cli/v1/skills/search?q=&limit=` | `skill:read` |
| List namespace | `GET /api/cli/v1/namespaces/{namespace}/skills?limit=&cursor=` | `skill:read` |
| Resolve | `GET /api/cli/v1/skills/{namespace}/{slug}/resolve?version=` | `skill:read` |
| Download latest | `GET /api/cli/v1/skills/{namespace}/{slug}/download` | `skill:read` |
| Download version | `GET /api/cli/v1/skills/{namespace}/{slug}/versions/{version}/download` | `skill:read` |
| Validate package | `POST /api/cli/v1/skills/{namespace}/publish/validate` | `skill:publish` |
| Publish package | `POST /api/cli/v1/skills/{namespace}/publish` | `skill:publish` |
| Delete whole skill | `DELETE /api/cli/v1/skills/{namespace}/{slug}` | `skill:delete` |

URL-encode namespace, slug, version, query, and cursor values.

## Publish multipart form

Use `multipart/form-data` with:

- `file`: ZIP archive containing `SKILL.md` at its root.
- `visibility`: `PUBLIC`, `NAMESPACE_ONLY`, or `PRIVATE`.

The validation response data contains:

```json
{
  "valid": true,
  "errors": [],
  "warnings": [],
  "resolvedSlug": "my-skill",
  "resolvedVersion": "1.2.0"
}
```

The current CLI publish response data contains namespace, slug, version, and visibility; it does not expose lifecycle status. A successful HTTP upload is not necessarily public: non-admin public or namespace-only versions may enter review. Resolve the exact returned version to verify that it is currently published and distributable.

## Update semantics

There is no separate update endpoint. Publish the same normalized slug with a new version. A published version is immutable; reuse of the same published version is rejected. Packages without a version may receive an automatic timestamp version, but explicit versions are safer for CI and rollback.

## Deletion semantics

`DELETE /api/cli/v1/skills/{namespace}/{slug}` invokes hard deletion. It removes the skill container, versions, files, search index records, and related lifecycle data. There is no API-key restore endpoint.

## Unsupported API-key endpoints

The following browser lifecycle routes currently have no API-token policy and return an unsupported-endpoint denial for API keys:

- `POST /api/v1/skills/{namespace}/{slug}/archive`
- `POST /api/v1/skills/{namespace}/{slug}/unarchive`
- `DELETE /api/v1/skills/{namespace}/{slug}/versions/{version}`
- withdraw-review, rerelease, confirm-publish, review, yank, hide, and admin routes

Do not treat an authenticated browser-session example as proof that an API key can use the same route.
