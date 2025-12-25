#!/usr/bin/env bash

# This script creates a symlink to pcli.sh in /usr/local/bin. This will enable the user to run pcli from any directory
# simply by typing "pcli" in the terminal. This is more robust than an alias, and allows pcli to be used in scripts.
#
# Usage:
#   ./install.sh           - Install pcli
#   ./install.sh --uninstall - Remove pcli installation

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

PCLI_PATH="${SCRIPT_PATH}/pcli.sh"
PCLI_DESTINATION_PATH="/usr/local/bin/pcli"

# Check for uninstall flag
if [[ "$1" = "--uninstall" ]] || [[ "$1" = "-u" ]]; then
    if [[ -L "$PCLI_DESTINATION_PATH" ]] || [[ -f "$PCLI_DESTINATION_PATH" ]]; then
        echo "Removing pcli from ${PCLI_DESTINATION_PATH}..."
        rm -f "${PCLI_DESTINATION_PATH}"
        if [[ $? -eq 0 ]]; then
            echo "✓ pcli uninstalled successfully!"
        else
            echo "✗ Failed to uninstall pcli. You may need to run with sudo:"
            echo "  sudo $0 --uninstall"
            exit 1
        fi
    else
        echo "pcli is not installed at ${PCLI_DESTINATION_PATH}"
    fi
    exit 0
fi

# Check if pcli is already installed
if [[ -L "$PCLI_DESTINATION_PATH" ]] || [[ -f "$PCLI_DESTINATION_PATH" ]]; then
    echo "Existing pcli installation found at ${PCLI_DESTINATION_PATH}"
    echo "Removing old version..."
    rm -f "${PCLI_DESTINATION_PATH}"
fi

# Create symlink
echo "Installing pcli to ${PCLI_DESTINATION_PATH}..."
ln -s "${PCLI_PATH}" "${PCLI_DESTINATION_PATH}"

if [[ $? -eq 0 ]]; then
    echo "✓ pcli installed successfully!"
    echo "You can now run 'pcli' from any directory."
else
    echo "✗ Failed to install pcli. You may need to run with sudo:"
    echo "  sudo $0"
    exit 1
fi
