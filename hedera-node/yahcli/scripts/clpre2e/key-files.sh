#!/usr/bin/env bash
# Helpers for writing private-key files without ever destroying one. Source this file:
#   . "$(dirname "${BASH_SOURCE[0]}")/key-files.sh"
#
#   kf_backup_existing <path>        move an existing file aside to <path>.<epoch>.bak (0600)
#   kf_write_key_file <path> <hex>   write "0x<hex>\n" to <path> with mode 0600
#
# kf_write_key_file leaves a file that already holds the identical key untouched (it only
# re-tightens the mode), so repeated e2e runs do not pile up identical backups. Any other
# existing content is preserved as a backup first. Symlinks are refused. Backups are never pruned.

kf_backup_existing() {
    local f="$1" ts bak
    if [ -L "$f" ]; then
        echo "Refusing to write through symlink: $f" >&2
        return 1
    fi
    [ -e "$f" ] || return 0
    ts="$(date +%s)"
    bak="${f}.${ts}.bak"
    while [ -e "$bak" ]; do ts=$((ts + 1)); bak="${f}.${ts}.bak"; done
    mv "$f" "$bak"
    chmod 600 "$bak"
    echo "Existing $f preserved as $bak" >&2
}

kf_write_key_file() {
    local f="$1" hex="${2#0x}" content
    content="0x${hex}"
    if [ -L "$f" ]; then
        echo "Refusing to write through symlink: $f" >&2
        return 1
    fi
    if [ -f "$f" ] && [ "$(cat "$f")" = "$content" ]; then
        chmod 600 "$f"
        return 0
    fi
    kf_backup_existing "$f" || return 1
    (umask 177 && printf '%s\n' "$content" > "$f")
}

# kf_mode <path>: octal permission bits, portable across macOS/BSD and GNU stat.
kf_mode() { stat -f %Lp "$1" 2>/dev/null || stat -c %a "$1"; }
kf_owner() { stat -f %u "$1" 2>/dev/null || stat -c %u "$1"; }

# kf_check_key_file <path>: refuse a symlink / non-regular file, and (unless ALLOW_LOOSE_KEY_FILE=1)
# a file owned by someone else or accessible by group/others. Skipped on non-POSIX hosts.
kf_check_key_file() {
    local f="$1" mode
    case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) return 0 ;; esac
    if [ -L "$f" ] || [ ! -f "$f" ]; then
        echo "key file must be an existing regular file (not a symlink): $f" >&2
        return 1
    fi
    [ "${ALLOW_LOOSE_KEY_FILE:-0}" = "1" ] && return 0
    if [ "$(kf_owner "$f")" != "$(id -u)" ]; then
        echo "key file $f is not owned by you (set ALLOW_LOOSE_KEY_FILE=1 to override)" >&2
        return 1
    fi
    mode="$(kf_mode "$f")"
    if [ $(( 8#${mode} & 8#077 )) -ne 0 ]; then
        echo "key file $f has mode ${mode}; it must not be accessible by group/others. Run: chmod 600 $f (or set ALLOW_LOOSE_KEY_FILE=1)" >&2
        return 1
    fi
}

# kf_read_key_file <path>: validate the file and print its key as 0x<64 hex>.
kf_read_key_file() {
    local f="$1" hex
    kf_check_key_file "$f" || return 1
    hex="$(tr -d '[:space:]' < "$f")"
    hex="${hex#0x}"
    if ! printf '%s' "$hex" | grep -Eq '^[0-9a-fA-F]{64}$'; then
        echo "key file $f must contain a 32-byte hex key (64 hex chars)" >&2
        return 1
    fi
    printf '0x%s\n' "$hex"
}
