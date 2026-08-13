#!/usr/bin/env sh
# SPDX-License-Identifier: Apache-2.0
# Recursively copy a remote path from a Teleport SSH node to a local path via `tsh scp`.
# Usage: scpR.sh <remote-path> <local-path>
# Requires the following environment variables:
#   TELEPORT_IDENTITY - identity file produced by teleport-actions/auth
#   TELEPORT_LOGIN    - SSH login (OS user) on the Teleport node
#   TELEPORT_HOST     - Teleport SSH node that holds the remote path
tsh -i "${TELEPORT_IDENTITY}" scp -r "${TELEPORT_LOGIN}@${TELEPORT_HOST}:$1" "$2"
