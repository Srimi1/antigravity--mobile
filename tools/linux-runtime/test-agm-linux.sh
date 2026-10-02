#!/bin/bash
# shellcheck disable=SC2034  # variables are read inside check() eval strings
# Tests agm-linux.sh against fake proot-distro/pkg in a temporary Termux-like tree. Run: bash tools/linux-runtime/test-agm-linux.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
FAILS=0; PASSES=0
check() { if eval "$2"; then PASSES=$((PASSES+1)); else FAILS=$((FAILS+1)); echo "FAIL: $1"; fi; }

setup() {
  T="$(mktemp -d)"; export HOME="$T/home" PREFIX="$T/usr" AGM_HOME="$T/home/.agm"
  mkdir -p "$HOME" "$PREFIX/bin" "$AGM_HOME"
  export AGM_RUNTIME_DIR="$PREFIX/var/lib/proot-distro" AGM_PD="$T/bin/proot-distro" AGM_PKG="$T/bin/pkg"
  mkdir -p "$T/bin"
  # The user's own data that must survive everything.
  mkdir -p "$AGM_RUNTIME_DIR/containers/debian/rootfs/etc" "$AGM_RUNTIME_DIR/cache/oci_layers"
  echo "user-data" > "$HOME/notes.txt"; echo "12.5" > "$AGM_RUNTIME_DIR/containers/debian/rootfs/etc/debian_version"
  echo "layer" > "$AGM_RUNTIME_DIR/cache/oci_layers/blob"
  cp "$HERE/agm-linux.sh" "$AGM_HOME/agm-linux.sh"
  cat > "$AGM_PKG" <<'P'
#!/bin/bash
echo "pkg $*" >> "$AGM_HOME/calls"; exit 0
P
  chmod +x "$AGM_PKG"
}
fake_pd() { # $1 = oci|legacy, $2 = debian version, $3 = install exit code
  cat > "$AGM_PD" <<P
#!/bin/bash
echo "pd \$*" >> "\$AGM_HOME/calls"
case "\$1" in
  install) if [ "\${2:-}" = --help ]; then [ "$1" = oci ] && echo "  -n, --name NAME"; exit 0; fi
    [ "$3" = 0 ] || exit $3
    mkdir -p "\$AGM_RUNTIME_DIR/containers/\$4/rootfs/etc"; echo "$2" > "\$AGM_RUNTIME_DIR/containers/\$4/rootfs/etc/debian_version" ;;
  login) exit 0 ;;
  remove) rm -rf "\$AGM_RUNTIME_DIR/containers/\$2" ;;
esac
P
  chmod +x "$AGM_PD"
}
helper() { bash "$AGM_HOME/agm-linux.sh" "$@"; }
wait_job() { for _ in $(seq 1 50); do s="$(helper progress "$1" | sed -n 's/^state=//p')"; [ "$s" != running ] && break; sleep 0.1; done; echo "$s"; }
user_data_intact() { [ "$(cat "$HOME/notes.txt")" = user-data ] && [ -f "$AGM_RUNTIME_DIR/containers/debian/rootfs/etc/debian_version" ] && [ -f "$AGM_RUNTIME_DIR/cache/oci_layers/blob" ]; }

# 1. Missing proot-distro and container are reported, nothing created.
setup; rm -f "$AGM_PD"
out="$(helper status)"
check "status reports missing proot-distro" '[[ "$out" == *"proot_distro=none"* && "$out" == *"distribution=missing"* ]]'

# 2. Old proot-distro is not upgraded automatically; install fails clearly.
setup; fake_pd legacy 12.7 0
helper install shell >/dev/null; st="$(wait_job install)"
check "legacy proot-distro refused (got $st)" '[ "$st" = "failed:proot-distro-too-old" ]'
check "legacy: no container created" '[ ! -d "$AGM_RUNTIME_DIR/containers/agm-debian" ]'
check "legacy: pkg not called" '[ ! -f "$AGM_HOME/calls" ] || ! grep -q "^pkg" "$AGM_HOME/calls"'

# 3. Successful install creates only agm-debian from debian:bookworm.
setup; fake_pd oci 12.7 0
helper install shell >/dev/null; st="$(wait_job install)"
check "install done" '[ "$st" = done ]'
check "pinned image and private name" 'grep -q "pd install debian:bookworm --name agm-debian" "$AGM_HOME/calls"'
check "bridge Python installed in Debian base packages" 'grep -Eq "apt-get install .* python3([ ;]|$)" "$AGM_HOME/calls"'
check "status shows Debian 12" '[[ "$(helper status)" == *"debian_version=12.7"* ]]'
check "install keeps user data" 'user_data_intact'

# 4. A non-Debian-12 image is rejected.
setup; fake_pd oci 13.1 0
helper install shell >/dev/null; st="$(wait_job install)"
check "debian 13 rejected (got $st)" '[ "$st" = "failed:not-debian-12" ]'

# 5. Download failure is reported as failed, not done.
setup; fake_pd oci 12.7 7
helper install shell >/dev/null; st="$(wait_job install)"
check "failed download reported (got $st)" '[ "$st" = "failed:debian" ]'

# 6. Interrupted job (process gone) is reported as interrupted.
setup; echo running > "$AGM_HOME/install.state"; echo 999999 > "$AGM_HOME/install.pid"
check "dead job is interrupted" '[ "$(helper progress install | sed -n "s/^state=//p")" = interrupted ]'

# 7. Desktop start without a desktop fails clearly.
setup; fake_pd oci 12.7 0; helper install shell >/dev/null; wait_job install >/dev/null
out="$(helper start desktop)"; code=$?
check "desktop missing reported" '[ $code -ne 0 ] && [[ "$out" == *"error=desktop-missing"* ]]'

# 8. Stop kills only processes recorded by the helper.
setup; sleep 30 & mine=$!; sleep 30 & users=$!
echo $mine > "$AGM_HOME/desktop.pid"
helper stop >/dev/null; sleep 0.3
check "stop kills helper process" '! kill -0 $mine 2>/dev/null'
check "stop leaves user process" 'kill -0 $users 2>/dev/null'
kill $users 2>/dev/null

# 8b. A process for our container without a PID file is still stopped; a user's other container is not.
setup
bash -c 'sleep 30; :' "proot-distro login agm-debian -- x" & orphan=$!
bash -c 'sleep 30; :' "proot-distro login debian -- x" & other=$!
sleep 0.3; helper stop >/dev/null; sleep 0.3
check "orphaned agm process stopped" '! kill -0 $orphan 2>/dev/null'
check "user container process untouched" 'kill -0 $other 2>/dev/null'
kill $other 2>/dev/null

# 9. Cleanup removes only agm-debian content and keeps user data.
setup; fake_pd oci 12.7 0; helper install shell >/dev/null; wait_job install >/dev/null
R="$AGM_RUNTIME_DIR/containers/agm-debian/rootfs"; mkdir -p "$R/root/agm-work/p" "$R/opt/agm/node"
out="$(helper cleanup workspaces cli-installs)"
check "workspaces and CLIs removed" '[ ! -d "$R/root/agm-work" ] && [ ! -d "$R/opt/agm" ] && [ -d "$R/etc" ]'
out="$(helper cleanup distribution)"
check "distribution removed" '[[ "$out" == *"removed=distribution"* ]] && [ ! -d "$AGM_RUNTIME_DIR/containers/agm-debian" ]'
check "cleanup keeps user data, other containers and OCI cache" 'user_data_intact'
check "only agm-debian passed to remove" '! grep -q "pd remove debian$" "$AGM_HOME/calls" && grep -q "pd remove agm-debian" "$AGM_HOME/calls"'

# 10. Unknown commands and tools are rejected.
setup; out="$(helper install-cli rm-rf)"; code=$?
check "unknown CLI rejected" '[ $code -ne 0 ] && [[ "$out" == *"error=unknown-tool"* ]]'

echo "passed=$PASSES failed=$FAILS"
[ $FAILS -eq 0 ]
