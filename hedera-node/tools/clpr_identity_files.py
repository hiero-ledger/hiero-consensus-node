"""
Key-file helpers shared by sign-clpr-channel-identity.py and sign-clpr-connector-identity.py
(loaded by file path, the same way build-besu-qbft-config-payload.py loads its common module).

  _check_key_file   refuse symlinked/special key files and, on POSIX, loose or foreign-owned ones
  _backup_existing  move an existing file to <name>.<epochMillis>.bak (0600); never destroys data
  _write_secure     create a new 0600 file (O_EXCL|O_NOFOLLOW), backing up any existing file first
"""

from __future__ import annotations  # PEP 604/585 annotations on Python 3.9

import os
import stat
import sys
import time
from pathlib import Path


def _check_key_file(path: Path, allow_loose: bool) -> None:
    """Refuse a key file that is not a regular, non-symlink file readable only by its owner (POSIX)."""
    if os.name != "posix":
        return
    try:
        st = os.lstat(str(path))
    except OSError as e:
        raise ValueError("cannot stat key file {}: {}".format(path, e)) from e
    if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode):
        raise ValueError("key file must be a regular file, not a symlink or special file: {}".format(path))
    if allow_loose:
        return
    if st.st_uid != os.geteuid():
        raise ValueError(
            "key file {} is not owned by you; pass --allow-loose-key-file to override".format(path))
    if st.st_mode & 0o077:
        raise ValueError(
            "key file {} has mode {:o}; it must not be accessible by group/others. "
            "Run: chmod 600 {}  (or pass --allow-loose-key-file to override)".format(
                path, stat.S_IMODE(st.st_mode), path))


def _backup_existing(path: Path) -> Path | None:
    """Move an existing regular file to <name>.<epochMillis>.bak (0600); never destroys data."""
    if path.is_symlink():
        raise OSError("refusing to write through symlink: {}".format(path))
    if not path.exists():
        return None
    if not path.is_file():
        raise OSError("refusing to replace non-regular file: {}".format(path))
    epoch = int(time.time() * 1000)
    backup = path.with_name("{}.{}.bak".format(path.name, epoch))
    while backup.exists():
        epoch += 1
        backup = path.with_name("{}.{}.bak".format(path.name, epoch))
    os.rename(str(path), str(backup))
    os.chmod(str(backup), 0o600)
    return backup


def _write_secure(path: Path, content: str) -> None:
    """Write content to a new file at path with mode 0o600, first backing up any existing file."""
    backup = _backup_existing(path)
    if backup is not None:
        print("existing {} preserved as {}".format(path, backup), file=sys.stderr)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(str(path), flags, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(content.encode())
    os.chmod(str(path), 0o600)
