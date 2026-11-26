# Required local git hooks

This repository requires a `prepare-commit-msg` git hook that auto-appends a DCO `Signed-off-by:` line to non-merge, non-squash commits. The hook lives at `.git/hooks/prepare-commit-msg` (per-clone, not version-controlled).

**On every fresh clone, verify the hook is installed and executable.** If missing, install it with the contents below and `chmod +x` it. Do not commit it to the repo or use `core.hooksPath` — keep it under `.git/hooks/` per local convention.

```bash
#!/bin/bash
# Auto-append DCO Signed-off-by line if not already present.
# Only applies to regular commits (not merges, squashes, or amends with -C).

COMMIT_MSG_FILE="$1"
COMMIT_SOURCE="$2"

case "${COMMIT_SOURCE}" in
  merge|squash|commit) exit 0 ;;
esac

SOB="Signed-off-by: $(git config user.name) <$(git config user.email)>"

if ! grep -qF "${SOB}" "${COMMIT_MSG_FILE}"; then
  echo "" >> "${COMMIT_MSG_FILE}"
  echo "${SOB}" >> "${COMMIT_MSG_FILE}"
fi
```
