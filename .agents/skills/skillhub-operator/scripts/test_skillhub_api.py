#!/usr/bin/env python3

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import tempfile
import threading
import unittest
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("skillhub_api.py")
SPEC = importlib.util.spec_from_file_location("skillhub_api", MODULE_PATH)
assert SPEC and SPEC.loader
skillhub_api = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(skillhub_api)


class FakeRegistryHandler(BaseHTTPRequestHandler):
    deleted = False
    resolved_version = "1.1.0"
    validation_version = "1.1.0"
    request_authorized = True
    multipart_seen = False
    object_storage_authorization = "unset"

    def log_message(self, format, *args):  # noqa: A003, ANN001
        return

    def envelope(self, status: int, data=None, msg="ok") -> None:  # noqa: ANN001
        body = json.dumps({"code": 0 if status < 400 else status, "msg": msg, "data": data}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        ok = self.headers.get("Authorization") == "Bearer test-api-key"
        type(self).request_authorized = type(self).request_authorized and ok
        if not ok:
            self.envelope(401, msg="authentication failed")
        return ok

    def do_GET(self):  # noqa: N802
        if self.path == "/object-storage":
            type(self).object_storage_authorization = self.headers.get("Authorization")
            payload = b"test-zip"
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if not self.authorized():
            return
        if self.path == "/api/cli/v1/auth/whoami":
            self.envelope(200, {"handle": "agent", "displayName": "Agent"})
            return
        if "/resolve" in self.path:
            if type(self).deleted:
                self.envelope(404, msg="not found")
                return
            self.envelope(
                200,
                {
                    "namespace": "global",
                    "slug": "demo-skill",
                    "version": type(self).resolved_version,
                    "versionId": 7,
                    "fingerprint": "sha256:test",
                    "downloadUrl": "/download",
                },
            )
            return
        if self.path.endswith("/download") or "/download?" in self.path:
            self.send_response(302)
            self.send_header("Location", "/object-storage")
            self.end_headers()
            return
        self.envelope(404, msg="not found")

    def do_POST(self):  # noqa: N802
        if not self.authorized():
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        type(self).multipart_seen = (
            "multipart/form-data" in self.headers.get("Content-Type", "")
            and b'SKILL.md' in body
            and b'name="visibility"' in body
        )
        if self.path.endswith("/publish/validate"):
            self.envelope(
                200,
                {
                    "valid": True,
                    "errors": [],
                    "warnings": [],
                    "resolvedSlug": "demo-skill",
                    "resolvedVersion": type(self).validation_version,
                },
            )
            return
        if self.path.endswith("/publish"):
            self.envelope(
                200,
                {
                    "namespace": "global",
                    "slug": "demo-skill",
                    "version": type(self).validation_version,
                    "visibility": "PUBLIC",
                },
            )
            return
        self.envelope(404, msg="not found")

    def do_DELETE(self):  # noqa: N802
        if not self.authorized():
            return
        type(self).deleted = True
        self.envelope(
            200,
            {"ok": True, "scope": "remote", "action": "delete", "namespace": "global", "slug": "demo-skill"},
        )


class SkillHubAPITest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeRegistryHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.registry = f"http://127.0.0.1:{cls.server.server_address[1]}"
        os.environ["SKILLHUB_TOKEN"] = "test-api-key"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)
        os.environ.pop("SKILLHUB_TOKEN", None)

    def setUp(self) -> None:
        FakeRegistryHandler.deleted = False
        FakeRegistryHandler.resolved_version = "1.1.0"
        FakeRegistryHandler.validation_version = "1.1.0"
        FakeRegistryHandler.request_authorized = True
        FakeRegistryHandler.multipart_seen = False
        FakeRegistryHandler.object_storage_authorization = "unset"
        self.tempdir = tempfile.TemporaryDirectory()
        self.skill_dir = Path(self.tempdir.name) / "demo-skill"
        self.skill_dir.mkdir()
        (self.skill_dir / "SKILL.md").write_text(
            '---\nname: "demo-skill"\ndescription: "Demo"\nversion: "1.1.0"\n---\n\n# Demo\n',
            encoding="utf-8",
        )
        (self.skill_dir / ".git").mkdir()
        (self.skill_dir / ".git" / "config").write_text("secret-ish", encoding="utf-8")

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def args(self, command: str, **values):  # noqa: ANN003
        defaults = {
            "registry": self.registry,
            "timeout": 5.0,
            "command": command,
        }
        defaults.update(values)
        return argparse.Namespace(**defaults)

    def test_package_directory_has_root_skill_md_and_excludes_git(self) -> None:
        package, filename = skillhub_api.package_source(self.skill_dir)
        self.assertEqual(filename, "demo-skill.zip")
        with zipfile.ZipFile(io_bytes(package)) as archive:
            self.assertIn("SKILL.md", archive.namelist())
            self.assertNotIn(".git/config", archive.namelist())

    def test_publish_validates_uploads_and_resolves(self) -> None:
        result = skillhub_api.execute(
            self.args(
                "publish",
                path=self.skill_dir,
                namespace="global",
                visibility="public",
                allow_warnings=False,
            )
        )
        self.assertTrue(result["ok"])
        self.assertTrue(result["verification"]["publishedResolve"])
        self.assertTrue(FakeRegistryHandler.multipart_seen)
        self.assertTrue(FakeRegistryHandler.request_authorized)

    def test_update_rejects_unchanged_remote_version(self) -> None:
        FakeRegistryHandler.resolved_version = "1.1.0"
        FakeRegistryHandler.validation_version = "1.1.0"
        with self.assertRaisesRegex(skillhub_api.SkillHubError, "increment SKILL.md version"):
            skillhub_api.execute(
                self.args(
                    "update",
                    path=self.skill_dir,
                    namespace="global",
                    visibility="public",
                    allow_warnings=False,
                )
            )

    def test_validation_warnings_require_explicit_acceptance(self) -> None:
        validation = {
            "valid": True,
            "errors": [],
            "warnings": ["package contains an executable script"],
            "resolvedSlug": "demo-skill",
            "resolvedVersion": "1.1.0",
        }
        with self.assertRaisesRegex(skillhub_api.SkillHubError, "--allow-warnings"):
            skillhub_api.validation_gate(validation, False)
        self.assertEqual(skillhub_api.validation_gate(validation, True), validation)

    def test_download_redirect_does_not_forward_api_key(self) -> None:
        client = skillhub_api.SkillHubClient(self.registry, "test-api-key", timeout=5.0)
        self.assertEqual(client.download("global", "demo-skill"), b"test-zip")
        self.assertIsNone(FakeRegistryHandler.object_storage_authorization)

    def test_delete_requires_exact_confirmation(self) -> None:
        with self.assertRaisesRegex(skillhub_api.SkillHubError, "--confirm must exactly equal"):
            skillhub_api.execute(
                self.args(
                    "delete",
                    namespace="global",
                    slug="demo-skill",
                    confirm="wrong/demo-skill",
                    backup=None,
                    source_retained=True,
                )
            )
        self.assertFalse(FakeRegistryHandler.deleted)

    def test_delete_verifies_absence(self) -> None:
        result = skillhub_api.execute(
            self.args(
                "delete",
                namespace="global",
                slug="demo-skill",
                confirm="global/demo-skill",
                backup=None,
                source_retained=True,
            )
        )
        self.assertTrue(result["verifiedAbsent"])
        self.assertTrue(FakeRegistryHandler.deleted)


def io_bytes(data: bytes):
    import io

    return io.BytesIO(data)


if __name__ == "__main__":
    unittest.main()
