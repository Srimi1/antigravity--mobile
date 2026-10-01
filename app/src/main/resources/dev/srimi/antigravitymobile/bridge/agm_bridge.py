"""Paired loopback CLI supervisor. No shell RPC, credential forwarding or uncertain process replay."""
import base64
import contextlib
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
import stat
import subprocess
import threading
import time
import zipfile

MAX_BODY_BYTES = 128 * 1024
MAX_FRAME_BYTES = 192 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_EVENT_BYTES = 80_000
ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_SOURCE_FILE_BYTES = 8 * 1024 * 1024
MAX_ARCHIVE_BYTES = 72 * 1024 * 1024
MAX_SOURCE_FILES = 10_000
EXCLUDED = {".git", ".gradle", ".kotlin", ".signing", ".codex", ".claude", ".gemini", ".agents", ".config",
            ".antigravity", ".agm", "node_modules", "local.properties", "credentials.json", "id_rsa", "id_ed25519", "build"}


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


def source_path(value):
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value or len(value.encode("utf-8")) > 512 or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise ProtocolError()
    parts = value.split("/")
    if len(parts) > 32 or any(p in {"", ".", ".."} or p in EXCLUDED or p == ".env" or p.startswith(".env.") or p.lower().endswith((".pem", ".p12")) for p in parts):
        raise ProtocolError()
    return value


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(32 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@contextlib.contextmanager
def open_source(base, relative):
    """Resolve every component with no-follow openat; a CLI replacement cannot redirect the copy."""
    descriptor = None
    try:
        parts = source_path(relative).split("/")
        descriptor = os.open(str(base), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor); descriptor = child
        file = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=descriptor)
        metadata = os.fstat(file)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_SOURCE_FILE_BYTES:
            os.close(file); raise ProtocolError()
        with os.fdopen(file, "rb") as stream:
            yield stream
    except OSError:
        raise ProtocolError() from None
    finally:
        if descriptor is not None:
            os.close(descriptor)


def source_hash(base, relative):
    digest, size = hashlib.sha256(), 0
    with open_source(base, relative) as stream:
        for chunk in iter(lambda: stream.read(32 * 1024), b""):
            size += len(chunk)
            if size > MAX_SOURCE_FILE_BYTES:
                raise ProtocolError()
            digest.update(chunk)
    return digest.hexdigest(), size


class WorkspaceExchange:
    """Idempotent bounded source transfer. Native accounts and Git internals never enter this workspace."""
    def __init__(self, root):
        self.root = pathlib.Path(root).resolve()
        self.lock = threading.RLock()
        for name in ("uploads", "workspaces", "exports"):
            path = self.root / name
            if path.is_symlink():
                raise ProtocolError()
            path.mkdir(exist_ok=True); os.chmod(path, 0o700)

    def upload(self, task, size, digest):
        task = TaskSupervisor.valid_id(task)
        if type(size) is not int or size < 0 or size > MAX_ARCHIVE_BYTES or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ProtocolError()
        with self.lock:
            state = self.root / "uploads" / (task + ".json")
            if state.is_symlink() or (self.root / ("task-" + task + ".json")).exists():
                raise ProtocolError()
            if state.exists():
                value = parse(state.read_bytes(), 1024)
                if value["size"] != size or value["hash"] != digest:
                    raise ProtocolError()
            else:
                atomic(state, {"size": size, "hash": digest, "ready": False})
            return {"size": size, "hash": digest}

    def chunk(self, task, offset, encoded):
        task = TaskSupervisor.valid_id(task)
        if type(offset) is not int or offset < 0 or not isinstance(encoded, str):
            raise ProtocolError()
        raw = unb64(encoded)
        if not raw or len(raw) > 48 * 1024:
            raise ProtocolError()
        with self.lock:
            state = self.root / "uploads" / (task + ".json")
            path = self.root / "uploads" / (task + ".zip")
            if state.is_symlink() or path.is_symlink():
                raise ProtocolError()
            value = parse(state.read_bytes(), 1024)
            if value["ready"] or offset + len(raw) > value["size"] or offset > (path.stat().st_size if path.exists() else 0):
                raise ProtocolError()
            with path.open("r+b" if path.exists() else "x+b") as output:
                os.chmod(path, 0o600); output.seek(offset)
                existing = output.read(len(raw))
                if existing and existing != raw[:len(existing)]:
                    raise ProtocolError()
                output.seek(offset); output.write(raw); output.flush(); os.fsync(output.fileno())
            return {"received": path.stat().st_size}

    def commit(self, task):
        task = TaskSupervisor.valid_id(task)
        with self.lock:
            state = self.root / "uploads" / (task + ".json")
            archive = self.root / "uploads" / (task + ".zip")
            parent = self.root / "workspaces" / task
            if state.is_symlink() or archive.is_symlink() or parent.is_symlink():
                raise ProtocolError()
            value = parse(state.read_bytes(), 1024)
            source = parent / "source"
            if value["ready"]:
                if not source.is_dir() or source.is_symlink():
                    raise ProtocolError()
                return {"cwd": str(source), "hash": value["hash"]}
            if (self.root / ("task-" + task + ".json")).exists() or not archive.is_file() or archive.stat().st_size != value["size"] or file_hash(archive) != value["hash"] or source.exists():
                raise ProtocolError()
            parent.mkdir(exist_ok=True); os.chmod(parent, 0o700)
            staging = parent / ("staging-" + secrets.token_hex(8))
            staging.mkdir(mode=0o700)
            try:
                total, seen = 0, set()
                with zipfile.ZipFile(archive) as incoming:
                    for entry in incoming.infolist():
                        path = source_path(entry.filename[:-1] if entry.is_dir() else entry.filename)
                        mode = entry.external_attr >> 16
                        if path in seen or len(seen) >= MAX_SOURCE_FILES or stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)) or entry.file_size < 0 or entry.file_size > MAX_SOURCE_FILE_BYTES:
                            raise ProtocolError()
                        seen.add(path); total += entry.file_size
                        if total > MAX_SOURCE_BYTES or entry.flag_bits & 1:
                            raise ProtocolError()
                        target = staging / path
                        if entry.is_dir():
                            if entry.file_size != 0:
                                raise ProtocolError()
                            target.mkdir(parents=True, exist_ok=True)
                            continue
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with incoming.open(entry) as stream, target.open("xb") as output:
                            os.chmod(target, 0o700 if target.name == "gradlew" else 0o600)
                            remaining = entry.file_size
                            while remaining:
                                chunk = stream.read(min(32 * 1024, remaining))
                                if not chunk:
                                    raise ProtocolError()
                                output.write(chunk); remaining -= len(chunk)
                            if stream.read(1):
                                raise ProtocolError()
                            output.flush(); os.fsync(output.fileno())
                staging.rename(source)
                value["ready"] = True; atomic(state, value)
                return {"cwd": str(source), "hash": value["hash"]}
            finally:
                if staging.exists():
                    import shutil
                    shutil.rmtree(staging)

    def capture(self, task):
        task = TaskSupervisor.valid_id(task)
        with self.lock:
            source = self.root / "workspaces" / task / "source"
            if source.is_symlink() or not source.is_dir() or source.resolve() != source.absolute():
                raise ProtocolError()
            def files():
                result, total = {}, 0
                for directory, dirs, names in os.walk(source, followlinks=False):
                    for name in list(dirs):
                        path = pathlib.Path(directory) / name
                        try:
                            source_path(path.relative_to(source).as_posix())
                        except ProtocolError:
                            dirs.remove(name); continue
                        if path.is_symlink():
                            raise ProtocolError()
                    for name in sorted(names):
                        path = pathlib.Path(directory) / name
                        relative = path.relative_to(source).as_posix()
                        try:
                            source_path(relative)
                        except ProtocolError:
                            continue
                        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_SOURCE_FILE_BYTES or len(result) >= MAX_SOURCE_FILES:
                            raise ProtocolError()
                        digest, size = source_hash(source, relative)
                        total += size
                        if total > MAX_SOURCE_BYTES:
                            raise ProtocolError()
                        result[relative] = digest
                return result
            before = files()
            temporary = self.root / "exports" / (task + ".tmp")
            target = self.root / "exports" / (task + ".zip")
            if temporary.is_symlink() or target.is_symlink():
                raise ProtocolError()
            try:
                with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as output:
                    os.chmod(temporary, 0o600)
                    total = 0
                    for relative in sorted(before):
                        entry = zipfile.ZipInfo(relative)
                        entry.compress_type = zipfile.ZIP_DEFLATED
                        entry.external_attr = (stat.S_IFREG | (0o700 if relative.split("/")[-1] == "gradlew" else 0o600)) << 16
                        copied, digest = 0, hashlib.sha256()
                        with open_source(source, relative) as stream, output.open(entry, "w") as destination:
                            for chunk in iter(lambda: stream.read(32 * 1024), b""):
                                copied += len(chunk); total += len(chunk)
                                if copied > MAX_SOURCE_FILE_BYTES or total > MAX_SOURCE_BYTES:
                                    raise ProtocolError()
                                destination.write(chunk); digest.update(chunk)
                        if digest.hexdigest() != before[relative]:
                            raise ProtocolError()
                if temporary.stat().st_size > MAX_ARCHIVE_BYTES or before != files():
                    raise ProtocolError()
                os.replace(temporary, target)
                result = {"size": target.stat().st_size, "hash": file_hash(target)}
                atomic(self.root / "exports" / (task + ".json"), dict(result, modified=target.stat().st_mtime_ns))
                return result
            finally:
                temporary.unlink(missing_ok=True)

    def download(self, task, offset, digest):
        task = TaskSupervisor.valid_id(task)
        if type(offset) is not int or offset < 0:
            raise ProtocolError()
        with self.lock:
            path = self.root / "exports" / (task + ".zip")
            metadata = self.root / "exports" / (task + ".json")
            if metadata.is_symlink():
                raise ProtocolError()
            value = parse(metadata.read_bytes(), 1024)
            if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_ARCHIVE_BYTES or offset > path.stat().st_size or value["hash"] != digest or value["size"] != path.stat().st_size or value["modified"] != path.stat().st_mtime_ns:
                raise ProtocolError()
            with path.open("rb") as stream:
                stream.seek(offset); raw = stream.read(48 * 1024)
            return {"offset": offset, "data": b64(raw), "size": path.stat().st_size, "hash": digest}


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
            process = None
            try:
                process = self.spawn(argv, cwd=str(workspace), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
                self.processes[task] = process
                self.pipe_locks[task] = threading.Lock()
                self.records[task].update(state="RUNNING", pid=process.pid, startTime=start_time(process.pid))
                self._save(task)
            except Exception as error:
                # Popen reports these before execution. Errors after it returns stay uncertain.
                absent = process is None and isinstance(error, (FileNotFoundError, PermissionError))
                self.records[task].update(state="INTERRUPTED", cancellationUnconfirmed=not absent); self._save(task)
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
            self._interrupt(task, process)

    def _interrupt(self, task, process):
        with self.lock:
            record = self.records[task]
            if record["state"] not in {"RUNNING", "CANCEL_REQUESTED"}:
                return
            record.update(state="CANCEL_REQUESTED", cancellationUnconfirmed=True); self._save(task)
        confirmed = stop_owned(record, process)
        with self.lock:
            if record["state"] == "CANCEL_REQUESTED":
                record.update(state="INTERRUPTED", cancellationUnconfirmed=not confirmed); self._save(task)

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
                if self.records[task]["state"] in {"RUNNING", "CANCEL_REQUESTED"}:
                    self.records[task].update(state="INTERRUPTED", cancellationUnconfirmed=True)
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
            self._interrupt(task, process)
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
        self.exchange = WorkspaceExchange(supervisor.root)
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
                elif op == "upload":
                    result = self.server.exchange.upload(task, request.get("size"), request.get("hash"))
                elif op == "upload_chunk":
                    result = self.server.exchange.chunk(task, request.get("offset"), request.get("data"))
                elif op == "upload_commit":
                    result = self.server.exchange.commit(task)
                elif op == "capture":
                    result = self.server.exchange.capture(task)
                elif op == "download":
                    result = self.server.exchange.download(task, request.get("offset"), request.get("hash"))
                else:
                    raise ProtocolError()
                self.wfile.write(session.encode(result).encode("utf-8") + b"\n"); self.wfile.flush()
        except Exception:
            pass  # Fail closed. Never log untrusted input or pairing material.
        finally:
            self.server.connections.release()
