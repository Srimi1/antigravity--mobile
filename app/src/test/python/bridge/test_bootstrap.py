import hashlib
import os
import pathlib
import signal
import sys
import tempfile
import time
import unittest
from test_bridge import bridge


def stop_daemon(pid, timeout=10):
    """bootstrap() starts a reaper thread, so either it or this test may collect the exit status."""
    os.kill(pid, signal.SIGTERM)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if os.waitpid(pid, os.WNOHANG)[0] == pid:
                return
        except ChildProcessError:
            pass  # Already reaped by the bootstrap thread.
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.01)
    raise AssertionError("bridge daemon did not exit")


class BootstrapTest(unittest.TestCase):
    def test_bootstrap_reuses_paired_helper_without_persisting_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory).resolve()
            source = pathlib.Path(bridge.__file__).read_bytes()
            config = {"pairId": "fixture-pair", "secret": bridge.b64(bytes(range(32))),
                      "helperHash": hashlib.sha256(source).hexdigest(), "installations": {"codex": "/fixture/codex"}}
            endpoint = None
            try:
                endpoint = bridge.bootstrap(config, source, str(root), sys.executable)
                again = bridge.bootstrap(config, source, str(root), sys.executable)
                self.assertEqual(endpoint, again)
                self.assertTrue(1024 <= endpoint["port"] <= 65535)
                self.assertFalse(any(config["secret"] in file.read_text() for file in root.iterdir() if file.is_file()))
                with self.assertRaises(bridge.ProtocolError):
                    bridge.bootstrap(dict(config, pairId="unpaired"), source, str(root), sys.executable)
            finally:
                if endpoint is not None:
                    stop_daemon(endpoint["pid"])

    def test_changed_source_and_linked_root_are_rejected_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory).resolve()
            source = b"not a helper"
            config = {"pairId": "fixture-pair", "secret": bridge.b64(bytes(range(32))),
                      "helperHash": "0" * 64, "installations": {"codex": "/fixture/codex"}}
            with self.assertRaises(bridge.ProtocolError):
                bridge.bootstrap(config, source, str(root), sys.executable)
            linked = root / "linked"; linked.symlink_to(root, target_is_directory=True)
            with self.assertRaises(bridge.ProtocolError):
                bridge.private_root(str(linked))


if __name__ == "__main__":
    unittest.main()
