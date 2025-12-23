#!/usr/bin/env bash

# This script creates a symlink to pcli.sh in /usr/local/bin. This will enable the user to run pcli from any directory
# simply by typing "pcli" in the terminal. This is more robust than an alias, and allows pcli to be used in scripts.

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

PCLI_PATH="${SCRIPT_PATH}/pcli.sh"
PCLI_DESTINATION_PATH="/usr/local/bin/pcli"
ln -s "${PCLI_PATH}" "${PCLI_DESTINATION_PATH}"
