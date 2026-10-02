#!/data/data/com.termux/files/usr/bin/bash
# Antigravity Mobile — phone-local Linux helper. Runs inside the user's Termux (no root).
#
# Installed by the app to ~/.agm/agm-linux.sh and run through Termux's RUN_COMMAND service.
# Everything it creates lives in ONE proot-distro container named agm-debian (Debian 12,
# image debian:bookworm) and in ~/.agm. It never removes Termux itself, other containers,
# the user's Termux packages, the shared OCI image cache or files outside those two places.
#
# Output is line-oriented key=value so the app can parse it. Long jobs (install, CLI installs)
# run detached and write progress to ~/.agm/<job>.log plus a state file the app polls.
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
AGM_HOME="${AGM_HOME:-$HOME/.agm}"
NAME="agm-debian"
IMAGE="debian:bookworm"
PD="${AGM_PD:-proot-distro}"
PKG="${AGM_PKG:-pkg}"
RUNTIME="${AGM_RUNTIME_DIR:-$PREFIX/var/lib/proot-distro}"
ROOTFS="$RUNTIME/containers/$NAME/rootfs"
SELF="$AGM_HOME/agm-linux.sh"
CLAUDE_KEY_FPR="31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE"

mkdir -p "$AGM_HOME"

kv() { printf '%s=%s\n' "$1" "$2"; }
die() { kv error "$1"; exit "${2:-1}"; }
# Atomic write: a reader never sees a half-written state file.
finish() { echo "$2" > "$AGM_HOME/$1.state.tmp" && mv -f "$AGM_HOME/$1.state.tmp" "$AGM_HOME/$1.state"; }

# Removes a path only when it is strictly inside the agm container rootfs or ~/.agm.
safe_rm() {
  local target="$1"
  case "$target" in
    "$ROOTFS"/?*|"$AGM_HOME"/?*) ;;
    *) die "refused to remove $target" 3 ;;
  esac
  case "$target" in *..*) die "refused to remove $target" 3 ;; esac
  rm -rf -- "$target"
}

pd_kind() {
  command -v "$PD" >/dev/null 2>&1 || { echo none; return; }
  if "$PD" install --help 2>&1 | grep -q -- '--name'; then echo oci; else echo legacy; fi
}
installed() { [ -d "$ROOTFS" ]; }
# Plain login: --shared-tmp would expose proot's own temp files (in Termux's tmp) to apt and break it.
in_debian() { "$PD" login "$NAME" -- "$@"; }
alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }
size_kb() { if [ -e "$1" ]; then du -sk "$1" 2>/dev/null | cut -f1; else echo 0; fi; }

cli_path() {
  case "$1" in
    codex) echo /opt/agm/node/bin/codex ;;
    gemini) echo /opt/agm/node/bin/gemini ;;
    claude-code) echo /usr/bin/claude ;;
    antigravity) echo /root/.local/bin/agy ;;
    *) return 1 ;;
  esac
}

cmd_status() {
  kv helper 1
  kv proot_distro "$(pd_kind)"
  if installed; then
    kv distribution installed
    kv debian_version "$(cat "$ROOTFS/etc/debian_version" 2>/dev/null || echo unknown)"
  else
    kv distribution missing
  fi
  if [ -x "$ROOTFS/usr/bin/startxfce4" ]; then kv desktop installed; else kv desktop missing; fi
  if command -v termux-x11 >/dev/null 2>&1; then kv x11_package installed; else kv x11_package missing; fi
  if alive "$AGM_HOME/desktop.pid"; then kv desktop_running yes; else kv desktop_running no; fi
  for job in install cli-codex cli-gemini cli-claude-code cli-antigravity; do
    [ -f "$AGM_HOME/$job.state" ] && kv "job_$job" "$(job_state "$job")"
  done
  return 0
}

job_state() {
  local state; state="$(cat "$AGM_HOME/$1.state" 2>/dev/null || echo idle)"
  # A job whose process is gone without recording a result was interrupted (for example the phone restarted).
  if [ "$state" = running ] && ! alive "$AGM_HOME/$1.pid"; then
    # Re-read: the job may have recorded its result and exited between the two checks.
    state="$(cat "$AGM_HOME/$1.state" 2>/dev/null || echo idle)"
    [ "$state" = running ] && state=interrupted
  fi
  echo "$state"
}

cmd_progress() {
  local job="$1"
  kv state "$(job_state "$job")"
  [ -f "$AGM_HOME/$job.log" ] && grep '^step=' "$AGM_HOME/$job.log" | tail -n 1
  [ -f "$AGM_HOME/$job.log" ] && tail -n 5 "$AGM_HOME/$job.log" | sed 's/^/log=/'
  return 0
}

# Starts a detached job so it survives the app being closed.
detach() {
  local job="$1"; shift
  if [ "$(cat "$AGM_HOME/$job.state" 2>/dev/null)" = running ] && alive "$AGM_HOME/$job.pid"; then kv state running; return 0; fi
  finish "$job" running
  : > "$AGM_HOME/$job.log"
  # A new session keeps the job alive after Termux ends the RUN_COMMAND invocation that started it.
  if command -v setsid >/dev/null 2>&1; then
    setsid nohup bash "$SELF" "$@" >> "$AGM_HOME/$job.log" 2>&1 < /dev/null &
  else
    nohup bash "$SELF" "$@" >> "$AGM_HOME/$job.log" 2>&1 < /dev/null &
  fi
  echo $! > "$AGM_HOME/$job.pid"
  kv state running
}


run_install() {
  local desktop="$1"
  echo "step=checking proot-distro"
  case "$(pd_kind)" in
    none) echo "step=installing proot-distro"; yes | "$PKG" install -y proot-distro || { finish install "failed:proot-distro"; return 1; } ;;
    legacy) echo "proot-distro in Termux is too old for pinned images. Run: pkg upgrade proot-distro"; finish install "failed:proot-distro-too-old"; return 1 ;;
  esac
  if ! installed; then
    echo "step=downloading Debian 12"
    "$PD" install "$IMAGE" --name "$NAME" || { finish install "failed:debian"; return 1; }
  fi
  case "$(cat "$ROOTFS/etc/debian_version" 2>/dev/null)" in
    12*) ;;
    *) echo "The agm-debian container is not Debian 12"; finish install "failed:not-debian-12"; return 1 ;;
  esac
  echo "step=installing base packages"
  in_debian env DEBIAN_FRONTEND=noninteractive sh -c 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl xz-utils gnupg git procps python3' \
    || { finish install "failed:base-packages"; return 1; }
  if [ "$desktop" = desktop ]; then
    echo "step=installing Termux:X11 support"
    { yes | "$PKG" install -y x11-repo && yes | "$PKG" install -y termux-x11-nightly; } || { finish install "failed:termux-x11"; return 1; }
    echo "step=installing XFCE desktop"
    in_debian env DEBIAN_FRONTEND=noninteractive sh -c 'apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11' \
      || { finish install "failed:xfce"; return 1; }
  fi
  echo "step=done"
  finish install "done"
}

run_cli() {
  local tool="$1" job="cli-$1"
  echo "step=installing $tool"
  case "$tool" in
    codex|gemini)
      if [ ! -x "$ROOTFS/opt/agm/node/bin/node" ]; then
        echo "step=downloading Node.js 24 LTS (checksum verified)"
        in_debian sh -ec '
          cd /tmp && rm -rf agm-node && mkdir agm-node && cd agm-node
          curl -fsSLO https://nodejs.org/dist/latest-v24.x/SHASUMS256.txt
          file=$(grep -o "node-v[0-9.]*-linux-arm64.tar.xz" SHASUMS256.txt | head -n 1)
          [ -n "$file" ]
          curl -fsSLO "https://nodejs.org/dist/latest-v24.x/$file"
          grep " $file\$" SHASUMS256.txt | sha256sum -c -
          mkdir -p /opt/agm/node && tar -xJf "$file" -C /opt/agm/node --strip-components=1
          cd /tmp && rm -rf agm-node' || { finish "$job" "failed:node"; return 1; }
      fi
      local package="@openai/codex"; [ "$tool" = gemini ] && package="@google/gemini-cli"
      in_debian env PATH=/opt/agm/node/bin:/usr/bin:/bin /opt/agm/node/bin/npm install -g "$package" || { finish "$job" "failed:npm"; return 1; } ;;
    claude-code)
      # Anthropic's signed apt repository; the key fingerprint is checked before it is trusted.
      in_debian env DEBIAN_FRONTEND=noninteractive FPR="$CLAUDE_KEY_FPR" sh -ec '
        install -d -m 0755 /etc/apt/keyrings
        curl -fsSL https://downloads.claude.ai/keys/claude-code.asc -o /tmp/claude-code.asc
        gpg --show-keys --with-colons /tmp/claude-code.asc | grep -q "^fpr:::::::::$FPR:"
        mv /tmp/claude-code.asc /etc/apt/keyrings/claude-code.asc
        echo "deb [signed-by=/etc/apt/keyrings/claude-code.asc] https://downloads.claude.ai/claude-code/apt/stable stable main" > /etc/apt/sources.list.d/claude-code.list
        apt-get update && apt-get install -y claude-code' || { finish "$job" "failed:claude-code"; return 1; } ;;
    antigravity)
      # Google's documented installer (verifies its download checksum), saved to a file first, not piped into a shell.
      # Its real options are only --dir/--help; the default target is /root/.local/bin/agy.
      in_debian sh -ec '
        curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh
        bash /tmp/agy-install.sh
        rm -f /tmp/agy-install.sh' || { finish "$job" "failed:antigravity"; return 1; } ;;
    *) finish "$job" "failed:unknown-tool"; return 1 ;;
  esac
  # Login shells in the terminal find every installed CLI.
  in_debian sh -c 'echo "export PATH=/opt/agm/node/bin:/root/.local/bin:\$PATH" > /etc/profile.d/agm-cli.sh' || true
  echo "step=done"
  finish "$job" "done"
}

cmd_clis() {
  local tool path
  for tool in codex gemini claude-code antigravity; do
    path="$(cli_path "$tool")"
    if [ -e "$ROOTFS$path" ]; then
      kv "cli_$tool" "$path"
      kv "version_$tool" "$(in_debian env PATH=/opt/agm/node/bin:/root/.local/bin:/usr/bin:/bin "$path" --version 2>/dev/null | head -n 1 | tr -d '\r')"
    else
      kv "cli_$tool" missing
    fi
  done
}

cmd_start() {
  installed || die "distribution-missing" 4
  if [ "${1:-}" != desktop ]; then in_debian true && kv started shell; return; fi
  [ -x "$ROOTFS/usr/bin/startxfce4" ] || die "desktop-missing" 4
  command -v termux-x11 >/dev/null 2>&1 || die "x11-package-missing" 4
  alive "$AGM_HOME/desktop.pid" && { kv started already; return 0; }
  # Termux:X11's documented form: the X server starts and owns the session (-xstartup), so they live and stop together.
  setsid nohup termux-x11 :1 -xstartup "$PD login $NAME --shared-x11 -- env DISPLAY=:1 dbus-launch --exit-with-session startxfce4" \
    > "$AGM_HOME/desktop.log" 2>&1 < /dev/null &
  echo $! > "$AGM_HOME/desktop.pid"
  rm -f "$AGM_HOME/x11.pid"
  sleep 3
  alive "$AGM_HOME/desktop.pid" || die "desktop-exited: $(tail -n 3 "$AGM_HOME/desktop.log" | tr '\n' ' ')" 5
  kv started desktop
}

# Stops only processes this helper started (recorded pid files).
cmd_stop() {
  local f
  for f in desktop x11; do
    if alive "$AGM_HOME/$f.pid"; then
      # The recorded PID leads its own session/process group (setsid), so the group holds exactly what we started.
      kill -TERM -- "-$(cat "$AGM_HOME/$f.pid")" 2>/dev/null
      pkill -TERM -P "$(cat "$AGM_HOME/$f.pid")" 2>/dev/null
      kill -TERM "$(cat "$AGM_HOME/$f.pid")" 2>/dev/null
    fi
    rm -f "$AGM_HOME/$f.pid"
  done
  # Also catch anything left from a lost PID file: only command lines naming our own container match.
  pkill -TERM -f "proot-distro login $NAME( |$)" 2>/dev/null
  pkill -TERM -f "containers/$NAME/rootfs" 2>/dev/null
  sleep 1
  pkill -KILL -f "proot-distro login $NAME( |$)" 2>/dev/null
  pkill -KILL -f "containers/$NAME/rootfs" 2>/dev/null
  kv stopped yes
}

cmd_storage() {
  kv distribution_kb "$(size_kb "$ROOTFS")"
  kv package_cache_kb "$(size_kb "$ROOTFS/var/cache/apt")"
  kv cli_kb "$(( $(size_kb "$ROOTFS/opt/agm") + $(size_kb "$ROOTFS/root/.local/share/antigravity") ))"
  kv workspace_kb "$(size_kb "$ROOTFS/root/agm-work")"
}

cmd_cleanup() {
  local item
  for item in "$@"; do
    case "$item" in
      package-cache)
        if installed && in_debian apt-get clean; then kv removed "$item"; else kv failed "$item"; fi ;;
      workspaces) safe_rm "$ROOTFS/root/agm-work"; kv removed "$item" ;;
      cli-installs)
        safe_rm "$ROOTFS/opt/agm"
        safe_rm "$ROOTFS/root/.local/bin/agy"
        installed && in_debian sh -c 'apt-get purge -y claude-code >/dev/null 2>&1; rm -f /etc/apt/sources.list.d/claude-code.list /etc/apt/keyrings/claude-code.asc /etc/profile.d/agm-cli.sh'
        rm -f "$AGM_HOME"/cli-*.state "$AGM_HOME"/cli-*.log
        kv removed "$item" ;;
      desktop)
        if installed && in_debian env DEBIAN_FRONTEND=noninteractive sh -c 'apt-get purge -y xfce4 xfce4-terminal dbus-x11 && apt-get autoremove -y'; then kv removed "$item"; else kv failed "$item"; fi ;;
      distribution)
        cmd_stop >/dev/null
        if ! installed || "$PD" remove "$NAME"; then rm -f "$AGM_HOME"/*.state "$AGM_HOME"/*.log; kv removed "$item"; else kv failed "$item"; fi ;;
      *) kv failed "$item" ;;
    esac
  done
}

case "${1:-status}" in
  status) cmd_status ;;
  progress) cmd_progress "${2:-install}" ;;
  install) detach install _install "${2:-shell}" ;;
  _install) run_install "${2:-shell}" ;;
  install-cli) cli_path "${2:-}" >/dev/null || die "unknown-tool" 2; detach "cli-$2" _cli "$2" ;;
  _cli) run_cli "$2" ;;
  clis) cmd_clis ;;
  start) cmd_start "${2:-shell}" ;;
  stop) cmd_stop ;;
  storage) cmd_storage ;;
  cleanup) shift; cmd_cleanup "$@" ;;
  *) die "unknown-command" 2 ;;
esac
