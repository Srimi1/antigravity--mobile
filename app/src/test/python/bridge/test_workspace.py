import hashlib
import io
import pathlib
import tempfile
import unittest
import zipfile
from unittest import mock
from test_bridge import bridge


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.directory.name).resolve()
        self.exchange = bridge.WorkspaceExchange(self.root)

    def tearDown(self):
        self.directory.cleanup()

    def zip(self, files):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, content in files:
                archive.writestr(name, content)
        return output.getvalue()

    def upload(self, raw):
        digest = hashlib.sha256(raw).hexdigest()
        self.exchange.upload("task-1", len(raw), digest)
        self.exchange.chunk("task-1", 0, bridge.b64(raw))
        return digest

    def test_duplicate_chunks_and_commit_do_not_replace_workspace(self):
        raw = self.zip([("src/Game.kt", "before"), ("gradlew", "script")])
        digest = self.upload(raw)
        self.exchange.chunk("task-1", 0, bridge.b64(raw))
        result = self.exchange.commit("task-1")
        self.assertEqual(digest, result["hash"])
        source = pathlib.Path(result["cwd"])
        self.assertEqual("before", (source / "src/Game.kt").read_text())
        (source / "src/Game.kt").write_text("CLI edit")
        self.assertEqual(result, self.exchange.commit("task-1"))
        self.assertEqual("CLI edit", (source / "src/Game.kt").read_text())
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.chunk("task-1", 0, bridge.b64(raw))

    def interrupted_commit(self, raw):
        digest = self.upload(raw)
        original = bridge.atomic

        def fail_checkpoint(path, value):
            if path == self.root / "uploads/task-1.json" and value.get("ready"):
                raise OSError("simulated metadata write failure after workspace rename")
            return original(path, value)

        with mock.patch.object(bridge, "atomic", fail_checkpoint):
            with self.assertRaises(OSError):
                self.exchange.commit("task-1")
        return digest, self.root / "workspaces/task-1/source"

    def test_retry_recovers_published_workspace_after_commit_checkpoint_failure(self):
        raw = self.zip([("src/Game.kt", "before"), ("gradlew", "script")])
        digest, source = self.interrupted_commit(raw)
        modified = (source / "src/Game.kt").stat().st_mtime_ns
        # A restarted helper must finish the upload without replacing the published directory.
        restarted = bridge.WorkspaceExchange(self.root)
        self.assertFalse(restarted.upload("task-1", len(raw), digest)["ready"])
        restarted.chunk("task-1", 0, bridge.b64(raw))
        self.assertEqual({"cwd": str(source), "hash": digest}, restarted.commit("task-1"))
        self.assertEqual(modified, (source / "src/Game.kt").stat().st_mtime_ns)
        self.assertTrue(restarted.upload("task-1", len(raw), digest)["ready"])

    def test_commit_recovery_refuses_changed_workspace_without_overwriting_it(self):
        _, source = self.interrupted_commit(self.zip([("Game.kt", "before")]))
        (source / "Game.kt").write_text("uncommitted edit")
        with self.assertRaises(bridge.ProtocolError):
            bridge.WorkspaceExchange(self.root).commit("task-1")
        self.assertEqual("uncommitted edit", (source / "Game.kt").read_text())

    def test_forbidden_links_and_traversal_never_extract(self):
        for name in ("../outside", ".git/config", ".codex/auth.json", ".agents/mcp_config.json"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                exchange = bridge.WorkspaceExchange(directory)
                raw = self.zip([(name, "data")])
                exchange.upload("task-1", len(raw), hashlib.sha256(raw).hexdigest())
                exchange.chunk("task-1", 0, bridge.b64(raw))
                with self.assertRaises(bridge.ProtocolError):
                    exchange.commit("task-1")
                self.assertFalse((pathlib.Path(directory) / "workspaces/task-1/source").exists())
        entry = zipfile.ZipInfo("linked")
        entry.create_system = 3; entry.external_attr = (0o120777 << 16)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr(entry, "../../account")
        self.upload(output.getvalue())
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.commit("task-1")

    def test_capture_returns_bounded_private_files_and_digest_checked_download(self):
        self.upload(self.zip([("Game.kt", "before"), ("removed.txt", "old")]))
        source = pathlib.Path(self.exchange.commit("task-1")["cwd"])
        (source / "Game.kt").write_text("after")
        (source / "removed.txt").unlink()
        (source / "added.txt").write_text("new")
        (source / ".env").write_text("excluded fixture")
        meta = self.exchange.capture("task-1")
        reply = self.exchange.download("task-1", 0, meta["hash"])
        with zipfile.ZipFile(io.BytesIO(bridge.unb64(reply["data"]))) as archive:
            self.assertEqual({"Game.kt", "added.txt"}, set(archive.namelist()))
            self.assertEqual(b"after", archive.read("Game.kt"))
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.download("task-1", 0, "0" * 64)
        (source / "link").symlink_to(self.root / "outside")
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.capture("task-1")

    def test_inconsistent_chunk_or_archive_hash_cannot_be_committed(self):
        raw = self.zip([("Game.kt", "before")])
        self.upload(raw)
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.chunk("task-1", 0, bridge.b64(b"other"))
        path = self.root / "uploads/task-1.zip"
        path.write_bytes(b"corrupted")
        with self.assertRaises(bridge.ProtocolError):
            self.exchange.commit("task-1")

    def test_copy_time_symlink_or_growth_cannot_bypass_snapshot_validation(self):
        self.upload(self.zip([("Game.kt", "ok")]))
        source = pathlib.Path(self.exchange.commit("task-1")["cwd"])
        file = source / "Game.kt"
        outside = self.root / "outside.txt"; outside.write_text("outside fixture")
        original_enter, original_exit = zipfile.ZipFile.__enter__, zipfile.ZipFile.__exit__
        for attack in ("symlink", "growth"):
            def enter(archive):
                result = original_enter(archive)
                if archive.mode == "w":
                    file.unlink()
                    if attack == "symlink":
                        file.symlink_to(outside)
                    else:
                        file.write_text("12345")
                return result
            def leave(archive, *arguments):
                result = original_exit(archive, *arguments)
                if archive.mode == "w":
                    file.unlink(); file.write_text("ok")
                return result
            with self.subTest(attack=attack), mock.patch.object(zipfile.ZipFile, "__enter__", enter), mock.patch.object(zipfile.ZipFile, "__exit__", leave), mock.patch.object(bridge, "MAX_SOURCE_FILE_BYTES", 4):
                with self.assertRaises(bridge.ProtocolError):
                    self.exchange.capture("task-1")
            self.assertFalse((self.root / "exports/task-1.zip").exists())


if __name__ == "__main__":
    unittest.main()
