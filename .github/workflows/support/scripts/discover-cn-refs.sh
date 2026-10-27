#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints the consensus node refs to test when the caller gave none, as a comma-separated list: main plus the newest
# release branch (release/X.Y, newest by version number, not by name or date).
#
# Run from inside a git clone with an `origin` remote. Fails if there is no main or no release branch.
set -euo pipefail

heads=$(git ls-remote --heads origin)

if ! grep -q $'\trefs/heads/main$' <<< "${heads}"; then
  echo "::error::origin has no 'main' branch" >&2
  exit 1
fi

latest=$(grep -oE $'\trefs/heads/release/[0-9]+\\.[0-9]+$' <<< "${heads}" | cut -f2 | sed 's#^refs/heads/##' | sort -V | tail -n1 || true)
if [[ -z "${latest}" ]]; then
  echo "::error::origin has no release/X.Y branch" >&2
  exit 1
fi

echo "main,${latest}"
