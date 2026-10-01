"""Paired loopback CLI supervisor. No shell RPC, credential forwarding or uncertain process replay."""
import base64
import hashlib
import hmac
import io
import json
import os
import pathlib
import re
import secrets
import select
import signal
import socketserver
import subprocess
import threading
import time

MAX_BODY_BYTES = 128 * 1024
MAX_FRAME_BYTES = 192 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_EVENT_BYTES = 80_000
ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")


class ProtocolError(Exception):
    def __init__(self):
        super().__init__("Local CLI bridge protocol or authentication failed")


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def unb64(value):
    try:
        result = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
        if b64(result) != value:
            raise ProtocolError()
        return result
    except (ValueError, TypeError, UnicodeError):
        raise ProtocolError() from None


def dump(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def parse(value, limit=MAX_FRAME_BYTES):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ProtocolError()
            result[key] = item
        return result

    def invalid(_):
        raise ProtocolError()

    try:
        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="strict")
        if len(value.encode("utf-8", errors="strict")) > limit:
            raise ProtocolError()
        result = json.loads(value, object_pairs_hook=unique, parse_constant=invalid)
        if not isinstance(result, dict):
            raise ProtocolError()
        return result
    except (ValueError, TypeError, UnicodeError):
        raise ProtocolError() from None


def read_line(stream, limit=MAX_FRAME_BYTES):
    line = stream.readline(limit + 1)
    if not line or len(line) > limit or not line.endswith(b"\n"):
        raise ProtocolError()
    return line[:-1].decode("utf-8", errors="strict")


def mac(key, value):
    return hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()


def proof(key, role, pair, server, client):
    return mac(key, "agm-bridge-v1|%s|%s|%s|%s" % (role, pair, server, client))


def nonce(value):
    if not isinstance(value, str) or len(unb64(value)) != 32:
        raise ProtocolError()
    return value


def verify(expected, actual):
    decoded = unb64(actual)
    if len(decoded) != 32 or not hmac.compare_digest(expected, decoded):
        raise ProtocolError()


class Session:
    def __init__(self, key, send_role, receive_role):
        self.key, self.send_role, self.receive_role = key, send_role, receive_role
        self.sent = self.received = 0

    def encode(self, value):
        raw = dump(value).encode("utf-8")
        if len(raw) > MAX_BODY_BYTES or self.sent >= 2**63 - 1:
            raise ProtocolError()
        body, sequence = b64(raw), self.sent + 1
        tag = b64(mac(self.key, "agm-frame-v1|%s|%s|%s" % (self.send_role, sequence, body)))
        result = dump({"seq": sequence, "body": body, "tag": tag})
        self.sent = sequence
        return result

    def decode(self, frame):
        value = parse(frame)
        if set(value) != {"seq", "body", "tag"} or type(value["seq"]) is not int or value["seq"] != self.received + 1:
            raise ProtocolError()
        if value["seq"] >= 2**63:
            raise ProtocolError()
        verify(mac(self.key, "agm-frame-v1|%s|%s|%s" % (self.receive_role, value["seq"], value["body"])), value["tag"])
        result = parse(unb64(value["body"]), MAX_BODY_BYTES)
        self.received = value["seq"]
        return result


class ServerHandshake:
    def __init__(self, pair, key):
        if not ID.fullmatch(pair) or len(key) != 32:
            raise ProtocolError()
        self.pair, self.key, self.server = pair, key, b64(secrets.token_bytes(32))
        self.used = False
        self.challenge = dump({"type": "challenge", "v": 1, "pairId": pair, "nonce": self.server})

    def accept(self, hello):
        value = parse(hello, 1024)
        if self.used or set(value) != {"type", "v", "pairId", "nonce", "proof"} or value["type"] != "hello" or type(value["v"]) is not int or value["v"] != 1 or value["pairId"] != self.pair:
            raise ProtocolError()
        client = nonce(value["nonce"])
        verify(proof(self.key, "client", self.pair, self.server, client), value["proof"])
        self.used = True
        reply = dump({"type": "paired", "v": 1, "proof": b64(proof(self.key, "server", self.pair, self.server, client))})
        return reply, Session(proof(self.key, "session", self.pair, self.server, client), "server", "client")


class ClientHandshake:
    def __init__(self, pair, key, challenge):
        value = parse(challenge, 1024)
        if not ID.fullmatch(pair) or len(key) != 32 or set(value) != {"type", "v", "pairId", "nonce"} or value["type"] != "challenge" or type(value["v"]) is not int or value["v"] != 1 or value["pairId"] != pair:
            raise ProtocolError()
        self.pair, self.key, self.server, self.client = pair, key, nonce(value["nonce"]), b64(secrets.token_bytes(32))
        self.used = False
        self.hello = dump({"type": "hello", "v": 1, "pairId": pair, "nonce": self.client, "proof": b64(proof(key, "client", pair, self.server, self.client))})

    def finish(self, reply):
        value = parse(reply, 1024)
        if self.used or set(value) != {"type", "v", "proof"} or value["type"] != "paired" or type(value["v"]) is not int or value["v"] != 1:
            raise ProtocolError()
        verify(proof(self.key, "server", self.pair, self.server, self.client), value["proof"])
        self.used = True
        return Session(proof(self.key, "session", self.pair, self.server, self.client), "client", "server")


def atomic(path, value):
    target = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    with target.open("x", encoding="utf-8") as output:
        os.chmod(target, 0o600)
        output.write(dump(value)); output.flush(); os.fsync(output.fileno())
    os.replace(target, path)
    directory = os.open(str(path.parent), os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def start_time(pid):
    try:
        # The process name may contain spaces; field 22 follows the final ')' delimiter.
        return pathlib.Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, ValueError, IndexError, TypeError):
        return None


def group_members(group):
    """Birth stamps prevent signalling a reused PID or unrelated process group."""
    root = pathlib.Path("/proc")
    if not (root / "self/stat").exists():
        return None
    result = {}
    try:
        for path in root.iterdir():
            if not path.name.isdigit():
                continue
            try:
                fields = (path / "stat").read_text().rsplit(")", 1)[1].split()
                if int(fields[2]) == group and fields[0] != "Z":
                    result[int(path.name)] = (fields[0], fields[19])
            except (OSError, ValueError, IndexError):
                continue
        return result
    except OSError:
        return None


def stop_owned(record, process=None):
    pid, stamp = record.get("pid"), record.get("startTime")
    if pid is None and process is not None:  # In-memory fixture process, never Popen in production.
        process.terminate(); process.wait(timeout=5)
        return True
    members = group_members(pid) if type(pid) is int else None
    if members is None:
        if process is not None:
            process.terminate()
        return False  # Cannot prove descendants stopped; do not report cancellation confirmed.
    if not members:
        return True
    if stamp is None or members.get(pid, (None, None))[1] != stamp:
        return False
    original = dict(members)
    try:
        os.killpg(pid, signal.SIGTERM)
        deadline = time.monotonic() + 0.25
        while time.monotonic() < deadline:
            remaining = group_members(pid)
            if remaining is None:
                return False
            if not remaining:
                return True
            time.sleep(0.02)
        remaining = group_members(pid)
        # A surviving birth-matched member proves this is still the original group.
        if remaining is None or not any(remaining.get(member, (None, None))[1] == birth[1] for member, birth in original.items()):
            return remaining == {}
        os.killpg(pid, signal.SIGKILL)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            remaining = group_members(pid)
            if remaining == {}:
                return True
            if remaining is None:
                return False
            time.sleep(0.02)
    except OSError:
        return group_members(pid) == {}
    return False


class TaskSupervisor:
    def __init__(self, root, installations, spawn=subprocess.Popen):
        requested_root = pathlib.Path(root)
        if requested_root.is_symlink():
            raise ProtocolError()
        self.root = requested_root.resolve()
        self.root.mkdir(parents=True, exist_ok=True); os.chmod(self.root, 0o700)
        self.installations, self.spawn = dict(installations), spawn
        self.lock = threading.RLock()
        self.records, self.processes, self.threads, self.pipe_locks = {}, {}, [], {}
        for path in self.root.glob("task-*.json"):
            if path.is_symlink() or path.stat().st_size > MAX_BODY_BYTES:
                raise ProtocolError()
            record = parse(path.read_bytes(), MAX_BODY_BYTES)
            task = self.valid_id(record["id"])
            if record["state"] in {"STARTING", "RUNNING", "CANCEL_REQUESTED"}:
                record["cancellationUnconfirmed"] = not stop_owned(record)
                record["state"] = "INTERRUPTED"
            self.records[task] = record
            self._save(task)

    @staticmethod
    def valid_id(value):
        if not isinstance(value, str) or not ID.fullmatch(value):
            raise ProtocolError()
        return value

    def _save(self, task):
        if len(dump(self.records[task]).encode("utf-8")) > MAX_BODY_BYTES:
            raise ProtocolError()
        atomic(self.root / ("task-" + task + ".json"), self.records[task])

    def _workspace(self, task):
        root = self.root / "workspaces" / task / "source"
        if root.is_symlink() or not root.is_dir() or root.resolve() != root.absolute():
            raise ProtocolError()
        return root

    def start(self, task, backend, conversation=None):
        task = self.valid_id(task)
        with self.lock:
            if task in self.records:
                if self.records[task]["backend"] != backend:
                    raise ProtocolError()
                return False
            if any(r["state"] in {"STARTING", "RUNNING", "CANCEL_REQUESTED"} or r.get("cancellationUnconfirmed", False) for r in self.records.values()):
                raise ProtocolError()
            binary = self.installations.get(backend)
            if backend not in {"codex", "antigravity"} or not isinstance(binary, str) or not binary.startswith("/") or "\x00" in binary:
                raise ProtocolError()
            workspace = self._workspace(task)
            argv = [binary, "app-server"] if backend == "codex" else [binary, "--input-format", "stream-json", "--output-format", "stream-json", "--sandbox"]
            if conversation is not None and backend == "antigravity":
                if not isinstance(conversation, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", conversation):
                    raise ProtocolError()
                argv += ["--conversation", conversation]
            self.records[task] = {"id": task, "backend": backend, "state": "STARTING", "writes": {}, "events": 0, "requests": {}}
            self._save(task)  # This claim survives a crash before/after Popen. Never repeat it.
            try:
                process = self.spawn(argv, cwd=str(workspace), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                self.processes[task] = process
                self.pipe_locks[task] = threading.Lock()
                self.records[task].update(state="RUNNING", pid=process.pid, startTime=start_time(process.pid))
                self._save(task)
            except Exception:
                # A spawn/save error cannot prove that no child exists.
                self.records[task].update(state="INTERRUPTED", cancellationUnconfirmed=True); self._save(task)
                return False
            readers = []
            for stream, kind in ((process.stdout, "cli"), (process.stderr, "diagnostic")):
                thread = threading.Thread(target=self._read, args=(task, process, stream, kind), daemon=True)
                readers.append(thread); self.threads.append(thread); thread.start()
            thread = threading.Thread(target=self._wait, args=(task, process, readers), daemon=True)
            self.threads.append(thread); thread.start()
            return True

    def _event(self, task, event):
        with self.lock:
            record = self.records[task]
            path = self.root / ("events-" + task + ".jsonl")
            if path.is_symlink() or (path.exists() and path.stat().st_size >= MAX_LOG_BYTES) or record["events"] >= 5000:
                raise ProtocolError()
            sequence = record["events"] + 1
            raw = dump(dict(event, sequence=sequence)).encode("utf-8") + b"\n"
            if len(raw) > MAX_EVENT_BYTES or (path.stat().st_size if path.exists() else 0) + len(raw) > MAX_LOG_BYTES:
                raise ProtocolError()
            with path.open("ab") as output:
                os.chmod(path, 0o600); output.write(raw); output.flush(); os.fsync(output.fileno())
            record["events"] = sequence; self._save(task)

    def _read(self, task, process, stream, kind):
        try:
            while True:
                raw = stream.readline(MAX_BODY_BYTES + 1)
                if not raw:
                    break
                if len(raw) > MAX_BODY_BYTES or not raw.endswith(b"\n"):
                    raise ProtocolError()
                if kind == "diagnostic":
                    text = raw.decode("utf-8", errors="replace").lower()
                    if "soft-denied" in text or (("permission" in text or "approval" in text) and any(x in text for x in ("denied", "cannot", "unavailable"))):
                        self._event(task, {"kind": "permission_unavailable"})
                    continue  # CLI authentication/diagnostic text is never copied to native logs.
                message = parse(raw, MAX_BODY_BYTES)
                method = message.get("method", "")
                if method.startswith("account/"):
                    continue
                with self.lock:
                    if method in {"item/commandExecution/requestApproval", "item/fileChange/requestApproval"}:
                        request = message.get("id")
                        if type(request) not in (str, int) or len(str(request)) > 128 or len(self.records[task]["requests"]) >= 32:
                            raise ProtocolError()
                        params = message["params"]
                        workspace = str(self._workspace(task))
                        safe = all(params.get(field) is None for field in ("networkApprovalContext", "additionalPermissions", "proposedExecpolicyAmendment")) and params.get("grantRoot") in (None, workspace)
                        if method == "item/commandExecution/requestApproval":
                            safe = safe and params.get("cwd") == workspace
                        # Preview remains in the bounded event journal; control metadata stores no command text.
                        self.records[task]["requests"][dump(request)] = {"safe": safe}
                self._event(task, {"kind": "cli", "message": message})
        except Exception:
            with self.lock:
                if self.records[task]["state"] == "RUNNING":
                    self.records[task].update(state="CANCEL_REQUESTED", cancellationUnconfirmed=True); self._save(task)
            confirmed = stop_owned(self.records[task], process)
            with self.lock:
                self.records[task].update(state="INTERRUPTED", cancellationUnconfirmed=not confirmed); self._save(task)

    def _wait(self, task, process, readers):
        try:
            code = process.wait()
            for reader in readers:
                reader.join(timeout=5)
            descendants_stopped = stop_owned(self.records[task], process)
            with self.lock:
                record = self.records[task]
                if record["state"] == "RUNNING":
                    record["state"] = "INTERRUPTED" if any(reader.is_alive() for reader in readers) else "EXITED"
                    record["cancellationUnconfirmed"] = not descendants_stopped
                record["exitCode"] = code; self._save(task)
                self._event(task, {"kind": "exit", "code": code})
        except Exception:
            with self.lock:
                self.records[task]["state"] = "INTERRUPTED"
                try:
                    self._save(task)
                except OSError:
                    pass

    def _allowed(self, task, message):
        backend = self.records[task]["backend"]
        if backend == "antigravity":
            if set(message) != {"event", "message"} or message["event"] != "user" or not isinstance(message["message"].get("content"), str) or message["message"]["content"].lstrip().startswith("/"):
                raise ProtocolError()
            return
        method, params = message.get("method"), message.get("params", {})
        workspace = str(self._workspace(task))
        if method == "initialize":
            if params.get("capabilities", {}).get("experimentalApi", False) is not False:
                raise ProtocolError()
        elif method == "initialized":
            pass
        elif method in {"thread/start", "thread/resume"}:
            if params.get("cwd") != workspace or params.get("sandbox") != "workspace-write" or params.get("approvalPolicy") != "on-request" or params.get("approvalsReviewer") != "user":
                raise ProtocolError()
        elif method == "turn/start":
            policy = params.get("sandboxPolicy", {})
            if params.get("cwd") != workspace or params.get("approvalPolicy") != "on-request" or params.get("approvalsReviewer") != "user" or policy.get("type") != "workspaceWrite" or policy.get("writableRoots") != [workspace] or policy.get("networkAccess") is not False or policy.get("excludeSlashTmp") is not True or policy.get("excludeTmpdirEnvVar") is not True:
                raise ProtocolError()
        elif method == "turn/interrupt":
            if not isinstance(params.get("threadId"), str) or not isinstance(params.get("turnId"), str):
                raise ProtocolError()
        elif method is None:
            request = self.records[task]["requests"].get(dump(message.get("id")))
            decision = message.get("result", {}).get("decision")
            if request is None or decision not in {"accept", "decline", "cancel"}:
                raise ProtocolError()
            if decision == "accept" and not request["safe"]:
                raise ProtocolError()
        else:
            raise ProtocolError()

    def send(self, task, command, message):
        task, command = self.valid_id(task), self.valid_id(command)
        with self.lock:
            record, process = self.records.get(task), self.processes.get(task)
            if record is None or process is None or record["state"] != "RUNNING":
                raise ProtocolError()
            raw = dump(message).encode("utf-8")
            if len(raw) > MAX_BODY_BYTES:
                raise ProtocolError()
            digest = hashlib.sha256(raw).hexdigest()
            if command in record["writes"]:
                if record["writes"][command] != digest:
                    raise ProtocolError()
                return False
            self._allowed(task, message)
            if len(record["writes"]) >= 512:
                raise ProtocolError()
            record["writes"][command] = digest
            if "method" not in message:
                record["requests"].pop(dump(message.get("id")), None)
            self._save(task)
        # Pipe backpressure must never hold the state/cancellation lock.
        try:
            with self.pipe_locks[task]:
                self._write_pipe(task, process, raw + b"\n")
            return True
        except Exception:
            with self.lock:
                if record["state"] == "RUNNING":
                    record["state"] = "INTERRUPTED"; self._save(task)
            stop_owned(record, process)
            raise ProtocolError() from None

    def _write_pipe(self, task, process, raw):
        if isinstance(process.stdin, io.BytesIO):
            process.stdin.write(raw); process.stdin.flush()
            return
        descriptor = process.stdin.fileno()
        os.set_blocking(descriptor, False)
        offset, deadline = 0, time.monotonic() + 10
        while offset < len(raw):
            with self.lock:
                if self.records[task]["state"] != "RUNNING":
                    raise ProtocolError()
            if time.monotonic() >= deadline:
                raise ProtocolError()
            if not select.select([], [descriptor], [], 0.2)[1]:
                continue
            try:
                offset += os.write(descriptor, raw[offset:offset + 4096])
            except BlockingIOError:
                continue

    def cancel(self, task):
        task = self.valid_id(task)
        with self.lock:
            record = self.records.get(task)
            if record is None or (record["state"] not in {"STARTING", "RUNNING", "CANCEL_REQUESTED"} and not record.get("cancellationUnconfirmed", False)):
                return
            record["state"] = "CANCEL_REQUESTED"; self._save(task)
            process = self.processes.get(task)
        confirmed = stop_owned(record, process)
        with self.lock:
            record["state"] = "CANCELLED" if confirmed else "INTERRUPTED"
            record["cancellationUnconfirmed"] = not confirmed; self._save(task)

    def status(self, task):
        task = self.valid_id(task)
        with self.lock:
            record = self.records.get(task)
            return {"id": task, "state": record["state"] if record else "NOT_FOUND", "events": record["events"] if record else 0, "exitCode": record.get("exitCode") if record else None, "cancellationUnconfirmed": record.get("cancellationUnconfirmed", False) if record else False}

    def observe(self, task, after):
        task = self.valid_id(task)
        if type(after) is not int or after < 0:
            raise ProtocolError()
        result, size = [], 0
        path = self.root / ("events-" + task + ".jsonl")
        with self.lock:
            if task not in self.records or after > self.records[task]["events"] or path.is_symlink():
                raise ProtocolError()
            if path.exists():
                with path.open("rb") as stream:
                    for line in stream:
                        event = parse(line, MAX_BODY_BYTES)
                        if event["sequence"] <= after:
                            continue
                        size += len(line)
                        if size > 90_000 or len(result) >= 100:
                            break
                        result.append(event)
            return {"status": self.status(task), "events": result}


class LoopbackServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True

    def __init__(self, port, pair, key, supervisor):
        self.pair, self.key, self.supervisor = pair, key, supervisor
        self.connections = threading.BoundedSemaphore(4)
        super().__init__(("127.0.0.1", port), BridgeHandler)


class BridgeHandler(socketserver.StreamRequestHandler):
    def handle(self):
        if self.client_address[0] != "127.0.0.1" or not self.server.connections.acquire(blocking=False):
            return
        try:
            self.request.settimeout(5)
            handshake = ServerHandshake(self.server.pair, self.server.key)
            self.wfile.write(handshake.challenge.encode("utf-8") + b"\n"); self.wfile.flush()
            reply, session = handshake.accept(read_line(self.rfile, 1024))
            self.wfile.write(reply.encode("utf-8") + b"\n"); self.wfile.flush()
            self.request.settimeout(30)
            while True:
                request = session.decode(read_line(self.rfile))
                op, task = request.get("op"), request.get("taskId")
                if op == "status":
                    result = self.server.supervisor.status(task)
                elif op == "start":
                    result = {"started": self.server.supervisor.start(task, request.get("backend"), request.get("conversationId"))}
                elif op == "send":
                    result = {"sent": self.server.supervisor.send(task, request.get("commandId"), request.get("message"))}
                elif op == "observe":
                    result = self.server.supervisor.observe(task, request.get("after"))
                elif op == "cancel":
                    self.server.supervisor.cancel(task); result = self.server.supervisor.status(task)
                else:
                    raise ProtocolError()
                self.wfile.write(session.encode(result).encode("utf-8") + b"\n"); self.wfile.flush()
        except Exception:
            pass  # Fail closed. Never log untrusted input or pairing material.
        finally:
            self.server.connections.release()
