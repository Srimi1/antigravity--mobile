import concurrent.futures
import importlib.util
import io
import json
import pathlib
import secrets
import socket
import tempfile
import threading
import unittest
from unittest import mock

SOURCE = pathlib.Path(__file__).resolve().parents[3] / "main/resources/dev/srimi/antigravitymobile/bridge/agm_bridge.py"
spec = importlib.util.spec_from_file_location("agm_bridge", SOURCE)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class SecurityTest(unittest.TestCase):
    def test_unpaired_forged_and_replayed_frames_fail(self):
        key = secrets.token_bytes(32)
        server = bridge.ServerHandshake("pair-1", key)
        client = bridge.ClientHandshake("pair-1", key, server.challenge)
        reply, incoming = server.accept(client.hello)
        outgoing = client.finish(reply)
        frame = outgoing.encode({"op": "observe", "taskId": "task-1"})
        self.assertEqual("task-1", incoming.decode(frame)["taskId"])
        with self.assertRaises(bridge.ProtocolError):
            incoming.decode(frame)
        stranger = bridge.ClientHandshake("pair-1", secrets.token_bytes(32), server.challenge)
        with self.assertRaises(bridge.ProtocolError):
            server.accept(stranger.hello)
        altered = json.loads(outgoing.encode({"op": "cancel"}))
        altered["body"] = bridge.b64(b'{"op":"start"}')
        with self.assertRaises(bridge.ProtocolError):
            incoming.decode(json.dumps(altered))

    def test_duplicate_keys_invalid_sequences_and_oversize_are_rejected(self):
        with self.assertRaises(bridge.ProtocolError):
            bridge.parse('{"op":"cancel","op":"start"}')
        with self.assertRaises(bridge.ProtocolError):
            bridge.parse('{"number":NaN}')
        with self.assertRaises(bridge.ProtocolError):
            bridge.read_line(io.BytesIO(b"x" * (bridge.MAX_FRAME_BYTES + 1)))
        with self.assertRaises(bridge.ProtocolError):
            bridge.TaskSupervisor.valid_id("../../other")

    def test_loopback_server_rejects_unpaired_client_before_dispatch(self):
        with tempfile.TemporaryDirectory() as directory:
            supervisor = bridge.TaskSupervisor(directory, {}, spawn=lambda *a, **k: self.fail("unpaired dispatch"))
            key = secrets.token_bytes(32)
            with bridge.LoopbackServer(0, "pair-1", key, supervisor) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
                try:
                    with socket.create_connection(server.server_address, timeout=2) as client:
                        stream = client.makefile("rwb", buffering=0)
                        challenge = bridge.read_line(stream, 1024)
                        wrong = bridge.ClientHandshake("pair-1", secrets.token_bytes(32), challenge)
                        stream.write((wrong.hello + "\n").encode())
                        self.assertEqual(b"", stream.readline(1024))
                        stream.close()
                    self.assertFalse(supervisor.records)
                finally:
                    server.shutdown(); thread.join(2)


class FakeProcess:
    pid = None

    def __init__(self):
        self.stdin = io.BytesIO()
        self.stdout = io.BytesIO()
        self.stderr = io.BytesIO()
        self.done = threading.Event()

    def poll(self):
        return 0 if self.done.is_set() else None

    def wait(self, timeout=None):
        if not self.done.wait(timeout):
            raise TimeoutError()
        return 0

    def terminate(self):
        self.done.set()

    def kill(self):
        self.done.set()


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.directory.name)
        self.spawned = []

        def spawn(argv, **kwargs):
            process = FakeProcess()
            self.spawned.append((argv, process))
            return process

        self.runner = bridge.TaskSupervisor(self.root, {"codex": "/usr/local/bin/codex", "antigravity": "/usr/local/bin/agy"}, spawn=spawn)
        (self.root / "workspaces/task-1/source").mkdir(parents=True)

    def tearDown(self):
        for _, process in self.spawned:
            process.kill()
        for thread in self.runner.threads:
            thread.join(timeout=2)
        self.directory.cleanup()

    def test_duplicate_start_and_send_claim_before_process_write(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(lambda _: self.runner.start("task-1", "codex"), range(20)))
        self.assertEqual(1, len(self.spawned))
        message = {"id": "agm-init", "method": "initialize", "params": {"capabilities": {"experimentalApi": False}}}
        self.assertTrue(self.runner.send("task-1", "write-1", message))
        self.assertFalse(self.runner.send("task-1", "write-1", message))
        self.assertEqual(1, self.spawned[0][1].stdin.getvalue().count(b'"initialize"'))
        with self.assertRaises(bridge.ProtocolError):
            self.runner.send("task-1", "write-1", {"method": "process/spawn"})

    def test_unsafe_rpc_other_workspace_and_second_active_task_fail_closed(self):
        self.runner.start("task-1", "codex")
        with self.assertRaises(bridge.ProtocolError):
            self.runner.send("task-1", "bad", {"method": "process/spawn", "id": 2})
        with self.assertRaises(bridge.ProtocolError):
            self.runner.send("task-1", "bad", {"method": "thread/start", "id": 3, "params": {"cwd": "/root", "sandbox": "danger-full-access"}})
        (self.root / "workspaces/task-2/source").mkdir(parents=True)
        self.assertFalse(self.runner.start("task-2", "codex"), "busy slot refuses definitively")
        self.assertEqual(1, len(self.spawned))

    def test_restart_marks_uncertain_start_interrupted_without_spawning(self):
        # Persisted claim at the crash boundary before/after Popen, with no surviving pipe owner.
        bridge.atomic(self.root / "task-task-1.json", {"id": "task-1", "backend": "codex", "state": "STARTING", "writes": {}, "events": 0, "requests": {}})
        recovered = bridge.TaskSupervisor(self.root, {"codex": "/usr/local/bin/codex"}, spawn=lambda *a, **k: self.fail("uncertain task replayed"))
        self.assertFalse(recovered.start("task-1", "codex"))
        self.assertEqual("INTERRUPTED", recovered.status("task-1")["state"])

    def test_cancellation_is_recorded_and_only_owned_process_stops(self):
        self.runner.start("task-1", "antigravity")
        self.runner.cancel("task-1")
        self.assertTrue(self.spawned[0][1].done.is_set())
        self.assertEqual("CANCELLED", self.runner.status("task-1")["state"])
        self.assertEqual(["/usr/local/bin/agy", "--input-format", "stream-json", "--output-format", "stream-json", "--sandbox"], self.spawned[0][0])

    def test_first_large_event_is_delivered_or_rejected_before_journaling(self):
        self.runner.start("task-1", "codex")
        try:
            self.runner._event("task-1", {"kind": "cli", "message": {"text": "x" * 95_000}})
        except bridge.ProtocolError:
            return
        self.assertEqual(1, len(self.runner.observe("task-1", 0)["events"]))

    def test_blocked_pipe_does_not_hold_cancellation_lock(self):
        self.runner.start("task-1", "codex")
        process = self.spawned[0][1]
        entered, release, cancelled = threading.Event(), threading.Event(), threading.Event()

        class Blocked(io.BytesIO):
            def write(self, value):
                entered.set(); release.wait(2)
                return super().write(value)

        process.stdin = Blocked()
        def send():
            try:
                self.runner.send("task-1", "write-1", {"id": "agm-init", "method": "initialize", "params": {}})
            except bridge.ProtocolError:
                pass
        writer = threading.Thread(target=send, daemon=True); writer.start()
        self.assertTrue(entered.wait(1))
        def cancel():
            self.runner.cancel("task-1"); cancelled.set()
        stopper = threading.Thread(target=cancel, daemon=True); stopper.start()
        try:
            self.assertTrue(cancelled.wait(0.5), "blocked stdin held cancellation lock")
        finally:
            release.set(); writer.join(2); stopper.join(2)

    def test_confirmed_group_cancellation_stops_child_after_leader_exit(self):
        self.runner.start("task-1", "codex")
        process = self.spawned[0][1]
        process.pid = 42
        self.runner.records["task-1"].update(pid=42, startTime="leader")
        alive = {42: ("S", "leader"), 43: ("S", "child")}
        signals = []
        def kill_group(group, value):
            self.assertEqual(42, group); signals.append(value)
            alive.pop(42, None); process.done.set()
            if value == bridge.signal.SIGKILL:
                alive.clear()
        with mock.patch.object(bridge, "group_members", create=True, side_effect=lambda _: dict(alive)), mock.patch.object(bridge.os, "killpg", side_effect=kill_group):
            self.runner.cancel("task-1")
        self.assertFalse(alive, "CLI child kept running after leader exited")
        self.assertIn(bridge.signal.SIGKILL, signals)
        self.assertEqual("CANCELLED", self.runner.status("task-1")["state"])

    def test_approval_previews_do_not_expand_state_and_resolved_requests_are_pruned(self):
        self.runner.start("task-1", "codex")
        process = self.spawned[0][1]
        self.runner.records["task-1"].update(thread="thread-1", turn="turn-1")
        messages = [{"id": number, "method": "item/commandExecution/requestApproval", "params": {
            "threadId": "thread-1", "turnId": "turn-1", "itemId": "command-%d" % number,
            "command": "x" * 55_000, "cwd": str(self.runner._workspace("task-1"))}} for number in (8, 9, 10)]
        stream = io.BytesIO(b"".join((bridge.dump(message) + "\n").encode() for message in messages))
        self.runner._read("task-1", process, stream, "cli")
        self.assertLess((self.root / "task-task-1.json").stat().st_size, bridge.MAX_BODY_BYTES)
        self.runner.send("task-1", "answer-1", {"id": 8, "result": {"decision": "cancel"}})
        self.assertNotIn("8", self.runner.records["task-1"]["requests"])
        self.assertFalse(self.runner.send("task-1", "answer-1", {"id": 8, "result": {"decision": "cancel"}}))

    def test_unconfirmed_cancellation_keeps_single_task_slot_on_restart(self):
        self.runner.start("task-1", "codex")
        (self.root / "workspaces/task-2/source").mkdir(parents=True)
        with mock.patch.object(bridge, "stop_owned", return_value=False):
            self.runner.cancel("task-1")
            self.assertTrue(self.runner.status("task-1")["cancellationUnconfirmed"])
            self.assertFalse(self.runner.start("task-2", "codex"), "busy slot refuses definitively")
            recovered = bridge.TaskSupervisor(self.root, {"codex": "/usr/local/bin/codex"}, spawn=lambda *a, **k: self.fail("unconfirmed process released slot"))
            self.assertFalse(recovered.start("task-2", "codex"), "busy slot refuses definitively")
        with mock.patch.object(bridge, "stop_owned", return_value=True):
            self.runner.cancel("task-1")
        self.assertFalse(self.runner.status("task-1")["cancellationUnconfirmed"])
        self.assertTrue(self.runner.start("task-2", "codex"))

    def test_pipe_failure_cannot_release_unknown_process_or_change_completed_stop(self):
        self.runner.start("task-1", "codex")
        (self.root / "workspaces/task-2/source").mkdir(parents=True)
        with mock.patch.object(self.runner, "_write_pipe", side_effect=OSError()), mock.patch.object(bridge, "stop_owned", return_value=False):
            with self.assertRaises(bridge.ProtocolError):
                self.runner.send("task-1", "write-1", {"id":"agm-init", "method":"initialize", "params":{}})
            self.assertFalse(self.runner.start("task-2", "codex"), "busy slot refuses definitively")
        with mock.patch.object(bridge, "stop_owned", return_value=True):
            self.runner.cancel("task-1")
            self.runner._interrupt("task-1", self.spawned[0][1])
        self.assertEqual("CANCELLED", self.runner.status("task-1")["state"])

    def test_known_pre_exec_failure_allows_fresh_task_but_never_replays_old_id(self):
        self.runner.spawn = mock.Mock(side_effect=FileNotFoundError())
        self.assertFalse(self.runner.start("task-1", "codex"))
        self.assertFalse(self.runner.status("task-1")["cancellationUnconfirmed"])
        self.assertFalse(self.runner.start("task-1", "codex"))
        self.assertEqual(1, self.runner.spawn.call_count)
        (self.root / "workspaces/task-2/source").mkdir(parents=True)
        process = FakeProcess(); self.spawned.append(([], process))
        self.runner.spawn = lambda *a, **k: process
        self.assertTrue(self.runner.start("task-2", "codex"))

    def test_drained_is_reported_only_after_trailing_diagnostics_and_exit_event(self):
        read_fd, write_fd = bridge.os.pipe()
        process = FakeProcess(); process.stderr = bridge.os.fdopen(read_fd, "rb")
        self.runner.spawn = lambda *a, **k: process
        self.assertTrue(self.runner.start("task-1", "codex"))
        self.assertFalse(self.runner.status("task-1")["drained"])
        seen_during_stop = []
        def late_diagnostic():
            while self.runner.status("task-1")["state"] == "RUNNING":
                bridge.time.sleep(0.01)
            seen_during_stop.append(self.runner.status("task-1")["drained"])
            with bridge.os.fdopen(write_fd, "wb") as late:
                late.write(b"approval denied: soft-denied by sandbox\n")
        writer = threading.Thread(target=late_diagnostic); writer.start()
        self.runner.cancel("task-1")
        writer.join(5)
        self.assertEqual([False], seen_during_stop, "helper claimed drained while the stderr reader could still append")
        status = self.runner.status("task-1")
        self.assertEqual("CANCELLED", status["state"])
        self.assertTrue(status["drained"], "cancel returns the final drained state"); self.assertFalse(status["cancellationUnconfirmed"])
        events = self.runner.observe("task-1", 0)["events"]
        self.assertEqual(["permission_unavailable", "exit"], [event["kind"] for event in events])
        self.assertEqual(status["events"], len(events))

    def probe_with(self, sandbox_writes):
        calls = []
        def run(argv, cwd=None, env=None, **kwargs):
            calls.append(argv)
            self.assertTrue(env["PATH"].startswith("/usr/local/bin:"), "CLI directory first on PATH for its node shebang")
            if argv[-1] == "--version":
                return mock.Mock(returncode=0, stdout=b"codex-cli 0.159.3\n\x1b[0m")
            for target, text in sandbox_writes:
                (pathlib.Path(cwd) / target).write_text(text)
            return mock.Mock(returncode=0, stdout=b"")
        return self.runner.probe("codex", run=run), calls

    def test_probe_confirms_only_a_sandbox_that_refuses_writes_outside_the_workspace(self):
        confirmed, calls = self.probe_with([("inside", "ok\n")])
        self.assertEqual("confirmed", confirmed["sandbox"]); self.assertEqual("codex-cli 0.159.3", confirmed["version"])
        self.assertEqual(["/usr/local/bin/codex", "sandbox", "--permission-profile", ":workspace", "-C"], calls[1][:5])
        escaped, _ = self.probe_with([("inside", "ok\n"), ("../outside", "escaped\n")])
        self.assertEqual("escaped", escaped["sandbox"])
        broken, _ = self.probe_with([])
        self.assertEqual("unavailable", broken["sandbox"])
        self.assertFalse((self.root / "probe").exists() and any((self.root / "probe").iterdir()))

    def test_probe_refuses_unknown_backend_and_active_task(self):
        with self.assertRaises(bridge.ProtocolError):
            self.runner.probe("bash")
        self.runner.start("task-1", "codex")
        with self.assertRaises(bridge.ProtocolError):
            self.runner.probe("codex", run=lambda *a, **k: self.fail("probe ran beside an active task"))

    def mcp_session(self, *messages):
        stdin = io.BytesIO(b"".join((bridge.dump(m) + "\n").encode() for m in messages))
        stdout = io.BytesIO()
        thread = threading.Thread(target=bridge.mcp, args=(str(self.root), "task-1", stdin, stdout), daemon=True)
        thread.start()
        return thread, stdout

    def test_codex_gets_native_mcp_server_by_per_process_override(self):
        self.runner.start("task-1", "codex")
        argv = self.spawned[0][0]
        self.assertEqual("app-server", argv[-1])
        self.assertIn('mcp_servers.agm_native.args=["%s","mcp","%s","task-1"]' % (self.root.resolve() / "agm_bridge.py", self.root.resolve()), argv)

    def test_native_build_request_round_trips_once_through_journal(self):
        self.runner.start("task-1", "codex")
        call = {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "build_project", "arguments": {"tasks": ":app:assembleDebug"}}}
        thread, stdout = self.mcp_session({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                                          {"jsonrpc": "2.0", "method": "notifications/initialized"},
                                          {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, call)
        deadline = bridge.time.monotonic() + 5
        events = []
        while not events and bridge.time.monotonic() < deadline:
            events = [e for e in self.runner.observe("task-1", 0)["events"] if e["kind"] == "native_request"]
            bridge.time.sleep(0.01)
        self.assertEqual({"kind": "native_request", "requestId": 1, "tool": "build_project", "arguments": {"tasks": ":app:assembleDebug"}},
                         {k: v for k, v in events[0].items() if k != "sequence"})
        self.assertEqual({"answered": True}, self.runner.native_answer("task-1", 1, "Build abc COMPLETED", False))
        with self.assertRaises(bridge.ProtocolError):
            self.runner.native_answer("task-1", 1, "again", False)
        thread.join(5)
        replies = [json.loads(line) for line in stdout.getvalue().splitlines()]
        self.assertEqual([1, 2, 3], [r["id"] for r in replies])
        self.assertEqual(["build_project", "install_apk"], [tool["name"] for tool in replies[1]["result"]["tools"]])
        self.assertEqual({"content": [{"type": "text", "text": "Build abc COMPLETED"}], "isError": False}, replies[2]["result"])

    def test_native_requests_reject_unknown_tools_and_end_when_task_stops(self):
        self.runner.start("task-1", "codex")
        thread, stdout = self.mcp_session({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "run_shell", "arguments": {"cmd": "id"}}},
                                          {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "install_apk", "arguments": {"path": "/x"}}},
                                          {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "install_apk", "arguments": {}}})
        deadline = bridge.time.monotonic() + 5
        while not any(e["kind"] == "native_request" for e in self.runner.observe("task-1", 0)["events"]) and bridge.time.monotonic() < deadline:
            bridge.time.sleep(0.01)
        self.runner.cancel("task-1")
        thread.join(5)
        replies = [json.loads(line)["result"] for line in stdout.getvalue().splitlines()]
        self.assertTrue(all(reply["isError"] for reply in replies))
        self.assertIn("stopped", replies[2]["content"][0]["text"])
        requests = [e for e in self.runner.observe("task-1", 0)["events"] if e["kind"] == "native_request"]
        self.assertEqual(["install_apk"], [e["tool"] for e in requests], "invalid requests must not reach the journal")

    def test_replies_to_other_codex_requests_cannot_grant_access(self):
        self.runner.start("task-1", "codex")
        process = self.spawned[0][1]
        workspace = str(self.runner._workspace("task-1"))
        requests = [{"id": 30, "method": "item/permissions/requestApproval", "params": {"threadId": "t", "turnId": "u", "itemId": "i", "cwd": workspace, "permissions": {}, "startedAtMs": 1}},
                    {"id": 31, "method": "mcpServer/elicitation/request", "params": {"serverName": "agm_native", "threadId": "t", "mode": "form", "message": "m", "requestedSchema": {}}},
                    {"id": 32, "method": "mcpServer/elicitation/request", "params": {"serverName": "other", "threadId": "t", "mode": "form", "message": "m", "requestedSchema": {}}},
                    {"id": 33, "method": "attestation/generate", "params": {}}]
        self.runner._read("task-1", process, io.BytesIO(b"".join((bridge.dump(m) + "\n").encode() for m in requests)), "cli")
        def refused(message):
            with self.assertRaises(bridge.ProtocolError):
                self.runner.send("task-1", "bad-%d" % message["id"], message)
        refused({"id": 30, "result": {"permissions": {"network": {"enabled": True}}}})
        refused({"id": 32, "result": {"action": "accept", "content": {}}})
        refused({"id": 31, "result": {"action": "accept", "content": {"secret": "x"}}})
        refused({"id": 33, "result": {}})
        self.assertTrue(self.runner.send("task-1", "ok-30", {"id": 30, "result": {"permissions": {}}}))
        self.assertTrue(self.runner.send("task-1", "ok-31", {"id": 31, "result": {"action": "accept", "content": {}}}))
        self.assertTrue(self.runner.send("task-1", "ok-32", {"id": 32, "result": {"action": "decline"}}))
        self.assertTrue(self.runner.send("task-1", "ok-33", {"id": 33, "error": {"code": -32601, "message": "Not supported by Antigravity Mobile"}}))


if __name__ == "__main__":
    unittest.main()
