#!/bin/sh
# Interactive front end for import-logs.sh: prompts for its two mandatory
# inputs (re-prompting until each is non-empty) and its two optional ones
# (showing the same defaults import-logs.sh itself would use, accepting a
# blank line to keep them), then delegates to it unchanged. After each
# import it asks whether there's another source to bring in, so a multi-node
# backfill doesn't need re-running the script by hand for every node - it
# loops until you answer "no" (or stops immediately if an import fails). See
# import-logs.sh's own usage comment for what each input means and why a
# historical import needs them; see README.md, "Configure logs ingestion" ->
# "Historical", for when to reach for either script.
#
# import-logs.sh itself stays fully scriptable (no prompts, no TTY
# requirement) for CI/automation - this wizard is a purely additive,
# interactive alternative for a human running one or more imports by hand.
#
# Usage: ./scripts/import-logs-wizard.sh
#   (no arguments - everything is gathered interactively, once per source)
set -eu

if [ ! -t 0 ]; then
	echo "error: import-logs-wizard.sh needs an interactive terminal to prompt for input." >&2
	echo "Run ./scripts/import-logs.sh directly instead - see its usage comment for arguments." >&2
	exit 1
fi

DEFAULT_GLOB="/logs/**/*.log"
DEFAULT_WAIT="30"
IMPORT_LOGS_SH="$(dirname "$0")/import-logs.sh"

while true; do
	SRC=""
	while [ -z "$SRC" ]; do
		printf 'Source directory (host path, required): '
		read -r SRC
	done

	LABELS=""
	while [ -z "$LABELS" ]; do
		printf 'Labels JSON, e.g. {"node_id":"1"} (required): '
		read -r LABELS
	done

	printf 'Glob (container-side path) [%s]: ' "$DEFAULT_GLOB"
	read -r GLOB
	GLOB="${GLOB:-$DEFAULT_GLOB}"

	printf 'Wait seconds [%s]: ' "$DEFAULT_WAIT"
	read -r WAIT
	WAIT="${WAIT:-$DEFAULT_WAIT}"

	echo
	echo "Running: import-logs.sh '$SRC' '$LABELS' '$GLOB' '$WAIT'"
	echo

	# Not exec'd (unlike a single-shot run) - the wizard needs to keep going
	# to ask about further sources. set -eu means a failed import stops the
	# loop right here instead of going on to ask "another source?".
	"$IMPORT_LOGS_SH" "$SRC" "$LABELS" "$GLOB" "$WAIT"

	while :; do
		printf '\nImport another source? (yes/no): '
		read -r MORE
		case "$MORE" in
		yes | Yes | YES) break ;;
		no | No | NO) exit 0 ;;
		*) echo "Please answer 'yes' or 'no'." ;;
		esac
	done
done
