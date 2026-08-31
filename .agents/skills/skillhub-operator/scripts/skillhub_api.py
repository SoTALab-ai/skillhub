#!/usr/bin/env python3
"""API-key client for the SkillHub CLI HTTP surface.

The client intentionally exposes only endpoints supported by API tokens. It keeps
credentials in environment variables and emits structured JSON without secrets.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path
from typing import Any


MAX_UPLOAD_BYTES = 100 * 1024 * 1024
SKIPPED_DIRS = {".git", ".skillhub", "__pycache__"}
SKIPPED_FILES = {".DS_Store"}


class SkillHubError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status: int | None = None,
        request_id: str | None = None,
        endpoint: str | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.request_id = request_id
        self.endpoint = endpoint

    def as_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {"ok": False, "error": self.message}
        if self.status is not None:
            result["status"] = self.status
        if self.request_id:
            result["requestId"] = self.request_id
        if self.endpoint:
            result["endpoint"] = self.endpoint
        return result


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def normalize_registry(value: str | None) -> str:
    if not value:
        raise SkillHubError("registry is required; set SKILLHUB_REGISTRY or pass --registry")
    parsed = urllib.parse.urlsplit(value.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise SkillHubError("registry must be an absolute http(s) URL")
    if parsed.username or parsed.password:
        raise SkillHubError("registry URL must not contain credentials")
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def load_token() -> str:
    token = os.environ.get("SKILLHUB_TOKEN") or os.environ.get("SKILLHUB_API_KEY")
    if not token or not token.strip():
        raise SkillHubError("API key is required; set SKILLHUB_TOKEN or SKILLHUB_API_KEY")
    return token.strip()


def json_output(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, sort_keys=True))


def parse_error_body(raw: bytes) -> tuple[str | None, str | None]:
    try:
        body = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, None
    if not isinstance(body, dict):
        return None, None
    message = body.get("msg") or body.get("message") or body.get("error")
    request_id = body.get("requestId") or body.get("request_id")
    return (
        str(message).strip() if message else None,
        str(request_id).strip() if request_id else None,
    )


class SkillHubClient:
    def __init__(self, registry: str, token: str, timeout: float = 60.0) -> None:
        self.registry = normalize_registry(registry)
        self.token = token
        self.timeout = timeout

    def _url(self, path: str) -> str:
        return f"{self.registry}{path}"

    def _headers(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        if extra:
            headers.update(extra)
        return headers

    def request_json(
        self,
        method: str,
        path: str,
        *,
        data: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any:
        request = urllib.request.Request(
            self._url(path),
            data=data,
            headers=self._headers(headers),
            method=method,
        )
        opener = urllib.request.build_opener(NoRedirectHandler())
        try:
            with opener.open(request, timeout=self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            try:
                raw = exc.read()
            finally:
                exc.close()
            message, request_id = parse_error_body(raw)
            raise SkillHubError(
                message or f"registry returned HTTP {exc.code}",
                status=exc.code,
                request_id=request_id,
                endpoint=path,
            ) from None
        except urllib.error.URLError as exc:
            raise SkillHubError(f"registry unreachable: {exc.reason}", endpoint=path) from None

        try:
            envelope = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise SkillHubError("registry returned invalid JSON", endpoint=path) from exc
        if not isinstance(envelope, dict):
            raise SkillHubError("registry returned an invalid response envelope", endpoint=path)
        if "data" in envelope:
            return envelope["data"]
        return envelope

    def whoami(self) -> Any:
        return self.request_json("GET", "/api/cli/v1/auth/whoami")

    def search(self, query: str, limit: int) -> Any:
        params = urllib.parse.urlencode({"q": query, "limit": limit})
        return self.request_json("GET", f"/api/cli/v1/skills/search?{params}")

    def list_namespace(self, namespace: str, limit: int, cursor: str | None) -> Any:
        params: dict[str, Any] = {"limit": limit}
        if cursor:
            params["cursor"] = cursor
        path = f"/api/cli/v1/namespaces/{quote(namespace)}/skills?{urllib.parse.urlencode(params)}"
        return self.request_json("GET", path)

    def resolve(self, namespace: str, slug: str, version: str | None = None) -> Any:
        path = f"/api/cli/v1/skills/{quote(namespace)}/{quote(slug)}/resolve"
        if version:
            path += "?" + urllib.parse.urlencode({"version": version})
        return self.request_json("GET", path)

    def validate(self, namespace: str, package: bytes, filename: str, visibility: str) -> Any:
        body, content_type = multipart_package(package, filename, visibility)
        return self.request_json(
            "POST",
            f"/api/cli/v1/skills/{quote(namespace)}/publish/validate",
            data=body,
            headers={"Content-Type": content_type},
        )

    def publish(self, namespace: str, package: bytes, filename: str, visibility: str) -> Any:
        body, content_type = multipart_package(package, filename, visibility)
        return self.request_json(
            "POST",
            f"/api/cli/v1/skills/{quote(namespace)}/publish",
            data=body,
            headers={"Content-Type": content_type},
        )

    def delete(self, namespace: str, slug: str) -> Any:
        return self.request_json(
            "DELETE",
            f"/api/cli/v1/skills/{quote(namespace)}/{quote(slug)}",
        )

    def download(self, namespace: str, slug: str, version: str | None = None) -> bytes:
        if version:
            path = (
                f"/api/cli/v1/skills/{quote(namespace)}/{quote(slug)}"
                f"/versions/{quote(version)}/download"
            )
        else:
            path = f"/api/cli/v1/skills/{quote(namespace)}/{quote(slug)}/download"
        request = urllib.request.Request(
            self._url(path),
            headers={"Authorization": f"Bearer {self.token}"},
            method="GET",
        )
        opener = urllib.request.build_opener(NoRedirectHandler())
        try:
            with opener.open(request, timeout=self.timeout) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            if exc.code in {301, 302, 303, 307, 308}:
                location = exc.headers.get("Location")
                exc.close()
                if not location:
                    raise SkillHubError("download redirect omitted Location", status=exc.code, endpoint=path)
                target = urllib.parse.urljoin(self._url(path), location)
                parsed = urllib.parse.urlsplit(target)
                if parsed.scheme not in {"http", "https"}:
                    raise SkillHubError("download redirect used an unsafe URL scheme", endpoint=path)
                # Do not forward the registry API key to object storage or another host.
                try:
                    with urllib.request.urlopen(target, timeout=self.timeout) as response:
                        return response.read()
                except urllib.error.HTTPError as redirected_exc:
                    raise SkillHubError(
                        f"download target returned HTTP {redirected_exc.code}",
                        status=redirected_exc.code,
                        endpoint=path,
                    ) from None
            try:
                raw = exc.read()
            finally:
                exc.close()
            message, request_id = parse_error_body(raw)
            raise SkillHubError(
                message or f"download returned HTTP {exc.code}",
                status=exc.code,
                request_id=request_id,
                endpoint=path,
            ) from None
        except urllib.error.URLError as exc:
            raise SkillHubError(f"download failed: {exc.reason}", endpoint=path) from None


def quote(value: str) -> str:
    return urllib.parse.quote(value.strip().lstrip("@"), safe="")


def visibility_value(value: str) -> str:
    normalized = value.strip().upper().replace("-", "_")
    if normalized not in {"PUBLIC", "NAMESPACE_ONLY", "PRIVATE"}:
        raise SkillHubError("visibility must be public, namespace-only, or private")
    return normalized


def package_source(source: Path) -> tuple[bytes, str]:
    source = source.expanduser().resolve()
    if not source.exists():
        raise SkillHubError(f"package path not found: {source}")
    if source.is_file():
        if source.suffix.lower() != ".zip" or not zipfile.is_zipfile(source):
            raise SkillHubError("package file must be a valid .zip archive")
        with zipfile.ZipFile(source) as archive:
            names = archive.namelist()
            if "SKILL.md" not in names:
                raise SkillHubError("zip archive must contain SKILL.md at its root")
            for name in names:
                parts = Path(name).parts
                if name.startswith(("/", "\\")) or ".." in parts:
                    raise SkillHubError(f"zip archive contains an unsafe path: {name}")
        package = source.read_bytes()
        filename = source.name
    elif source.is_dir():
        if not (source / "SKILL.md").is_file():
            raise SkillHubError("skill directory must contain SKILL.md at its root")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(source.rglob("*")):
                relative = path.relative_to(source)
                if any(part in SKIPPED_DIRS for part in relative.parts):
                    continue
                if path.name in SKIPPED_FILES or path.is_dir():
                    continue
                if path.is_symlink():
                    raise SkillHubError(f"symbolic links are not allowed in packages: {relative}")
                archive.write(path, relative.as_posix())
        package = buffer.getvalue()
        filename = f"{source.name}.zip"
    else:
        raise SkillHubError("package path must be a directory or zip file")
    if len(package) > MAX_UPLOAD_BYTES:
        raise SkillHubError(f"package exceeds {MAX_UPLOAD_BYTES} bytes")
    return package, filename


def multipart_package(package: bytes, filename: str, visibility: str) -> tuple[bytes, str]:
    boundary = f"skillhub-{uuid.uuid4().hex}"
    filename = "".join(character if character.isalnum() or character in "._-" else "_" for character in filename)
    newline = b"\r\n"
    chunks = [
        f"--{boundary}".encode(),
        b'Content-Disposition: form-data; name="visibility"',
        b"",
        visibility.encode(),
        f"--{boundary}".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{filename}"'.encode(),
        b"Content-Type: application/zip",
        b"",
        package,
        f"--{boundary}--".encode(),
        b"",
    ]
    return newline.join(chunks), f"multipart/form-data; boundary={boundary}"


def validation_gate(result: Any, allow_warnings: bool) -> dict[str, Any]:
    if not isinstance(result, dict):
        raise SkillHubError("validation returned an invalid response")
    errors = result.get("errors") or []
    warnings = result.get("warnings") or []
    if not result.get("valid", False) or errors:
        raise SkillHubError("validation failed: " + "; ".join(str(item) for item in errors))
    if warnings and not allow_warnings:
        raise SkillHubError(
            "validation produced warnings; review them and rerun with --allow-warnings: "
            + "; ".join(str(item) for item in warnings)
        )
    return result


def verify_publish(client: SkillHubClient, result: dict[str, Any]) -> dict[str, Any]:
    namespace = str(result.get("namespace") or "")
    slug = str(result.get("slug") or "")
    version = str(result.get("version") or "")
    if not namespace or not slug or not version:
        return {"publishedResolve": False, "reason": "publish response omitted coordinate fields"}
    try:
        resolved = client.resolve(namespace, slug, version)
        return {"publishedResolve": True, "resolved": resolved}
    except SkillHubError as exc:
        if exc.status == 404:
            return {
                "publishedResolve": False,
                "reason": "version uploaded but is not currently resolvable as published; inspect review status",
            }
        raise


def write_download(path: Path, data: bytes, force: bool) -> None:
    path = path.expanduser().resolve()
    if path.exists() and not force:
        raise SkillHubError(f"output already exists: {path}; pass --force to overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def add_package_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("path", type=Path)
    parser.add_argument("--namespace", default="global")
    parser.add_argument("--visibility", default="public")
    parser.add_argument("--allow-warnings", action="store_true")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SkillHub API-key lifecycle client")
    parser.add_argument("--registry", default=os.environ.get("SKILLHUB_REGISTRY"))
    parser.add_argument("--timeout", type=float, default=60.0)
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("whoami")

    search = subparsers.add_parser("search")
    search.add_argument("--query", default="")
    search.add_argument("--limit", type=int, default=20)

    list_parser = subparsers.add_parser("list")
    list_parser.add_argument("--namespace", default="global")
    list_parser.add_argument("--limit", type=int, default=100)
    list_parser.add_argument("--cursor")

    resolve = subparsers.add_parser("resolve")
    resolve.add_argument("--namespace", default="global")
    resolve.add_argument("--slug", required=True)
    resolve.add_argument("--version")

    validate = subparsers.add_parser("validate")
    add_package_options(validate)

    publish = subparsers.add_parser("publish")
    add_package_options(publish)

    update = subparsers.add_parser("update")
    add_package_options(update)

    download = subparsers.add_parser("download")
    download.add_argument("--namespace", default="global")
    download.add_argument("--slug", required=True)
    download.add_argument("--version")
    download.add_argument("--output", type=Path, required=True)
    download.add_argument("--force", action="store_true")

    delete = subparsers.add_parser("delete")
    delete.add_argument("--namespace", default="global")
    delete.add_argument("--slug", required=True)
    delete.add_argument("--confirm", required=True, help="must equal namespace/slug")
    recovery = delete.add_mutually_exclusive_group(required=True)
    recovery.add_argument("--backup", type=Path, help="download latest package before deletion")
    recovery.add_argument("--source-retained", action="store_true", help="confirm source is durable elsewhere")
    return parser


def execute(args: argparse.Namespace) -> dict[str, Any]:
    client = SkillHubClient(normalize_registry(args.registry), load_token(), args.timeout)
    command = args.command
    if command == "whoami":
        return {"ok": True, "action": command, "data": client.whoami()}
    if command == "search":
        return {"ok": True, "action": command, "data": client.search(args.query, args.limit)}
    if command == "list":
        return {
            "ok": True,
            "action": command,
            "namespace": args.namespace,
            "data": client.list_namespace(args.namespace, args.limit, args.cursor),
        }
    if command == "resolve":
        return {
            "ok": True,
            "action": command,
            "coordinate": f"{args.namespace}/{args.slug}",
            "data": client.resolve(args.namespace, args.slug, args.version),
        }
    if command in {"validate", "publish", "update"}:
        visibility = visibility_value(args.visibility)
        package, filename = package_source(args.path)
        validation = client.validate(args.namespace, package, filename, visibility)
        validation = validation_gate(validation, args.allow_warnings)
        slug = str(validation.get("resolvedSlug") or "")
        version = str(validation.get("resolvedVersion") or "")
        if command == "validate":
            return {"ok": True, "action": command, "validation": validation}
        if command == "update":
            if not slug or not version:
                raise SkillHubError("update requires validation to resolve both slug and version")
            try:
                current = client.resolve(args.namespace, slug)
            except SkillHubError as exc:
                if exc.status == 404:
                    raise SkillHubError("remote skill does not exist; use publish instead of update") from None
                raise
            if str(current.get("version") or "") == version:
                raise SkillHubError(
                    f"local version {version} matches the current remote version; increment SKILL.md version"
                )
        published = client.publish(args.namespace, package, filename, visibility)
        if not isinstance(published, dict):
            raise SkillHubError("publish returned an invalid response")
        verification = verify_publish(client, published)
        return {
            "ok": True,
            "action": command,
            "validation": validation,
            "published": published,
            "verification": verification,
        }
    if command == "download":
        data = client.download(args.namespace, args.slug, args.version)
        write_download(args.output, data, args.force)
        return {
            "ok": True,
            "action": command,
            "coordinate": f"{args.namespace}/{args.slug}",
            "version": args.version,
            "output": str(args.output.expanduser().resolve()),
            "bytes": len(data),
        }
    if command == "delete":
        coordinate = f"{args.namespace.lstrip('@')}/{args.slug}"
        if args.confirm != coordinate:
            raise SkillHubError(f"--confirm must exactly equal {coordinate}")
        current = client.resolve(args.namespace, args.slug)
        backup: str | None = None
        if args.backup:
            data = client.download(args.namespace, args.slug, current.get("version"))
            write_download(args.backup, data, False)
            backup = str(args.backup.expanduser().resolve())
        deleted = client.delete(args.namespace, args.slug)
        verified_absent = False
        try:
            client.resolve(args.namespace, args.slug)
        except SkillHubError as exc:
            if exc.status == 404:
                verified_absent = True
            else:
                raise
        if not verified_absent:
            raise SkillHubError("delete returned success but the skill still resolves")
        return {
            "ok": True,
            "action": "hard-delete",
            "coordinate": coordinate,
            "previous": current,
            "backup": backup,
            "deleted": deleted,
            "verifiedAbsent": True,
        }
    raise SkillHubError(f"unsupported command: {command}")


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        result = execute(args)
        result.setdefault("registry", normalize_registry(args.registry))
        json_output(result)
        return 0
    except SkillHubError as exc:
        json_output(exc.as_dict())
        if exc.status == 401:
            return 3
        if exc.status == 403:
            return 4
        return 2


if __name__ == "__main__":
    sys.exit(main())
