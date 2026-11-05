#!/bin/sh
# Interactive front end for import-metrics.sh: prompts for its two mandatory
# inputs (re-prompting until each is non-empty) and its optional one (showing
# the same default import-metrics.sh itself would use, accepting a blank line
# to keep it), then delegates to it unchanged. After each import it asks
# whether there's another source to bring in, so a multi-node backfill
# doesn't need re-running the script by hand for every node - it loops until
# you answer "no" (or stops immediately if an import fails). The previous
# source directory is offered as the default for the next round, since a
# multi-node dump usually sits in one directory. See import-metrics.sh's own
# usage comment for what each input means; see README.md, "Configure metrics
# scraping" -> "Historical", for when to reach for either script.
#
# import-metrics.sh itself stays fully scriptable (no prompts, no TTY
# requirement) for CI/automation - this wizard is a purely additive,
# interactive alternative for a human running one or more imports by hand.
#
# Usage: ./scripts/import-metrics-wizard.sh
#   (no arguments - everything is gathered interactively, once per source)
set -eu

if [ ! -t 0 ]; then
	echo "error: import-metrics-wizard.sh needs an interactive terminal to prompt for input." >&2
	echo "Run ./scripts/import-metrics.sh directly instead - see its usage comment for arguments." >&2
	exit 1
fi

DEFAULT_PATTERN="*.txt"
IMPORT_METRICS_SH="$(dirname "$0")/import-metrics.sh"
PREV_SRC=""

while true; do
	SRC=""
	while [ -z "$SRC" ]; do
		if [ -n "$PREV_SRC" ]; then
			printf 'Source directory (host path) [%s]: ' "$PREV_SRC"
		else
			printf 'Source directory (host path, required): '
		fi
		read -r SRC
		SRC="${SRC:-$PREV_SRC}"
	done
	PREV_SRC="$SRC"

	LABELS=""
	while [ -z "$LABELS" ]; do
		printf 'Labels JSON, e.g. {"environment":"localhost","node_id":"1"} (required): '
		read -r LABELS
	done

	printf 'File name pattern, e.g. metrics-node-1.txt [%s]: ' "$DEFAULT_PATTERN"
	read -r PATTERN
	PATTERN="${PATTERN:-$DEFAULT_PATTERN}"

	echo
	echo "Running: import-metrics.sh '$SRC' '$LABELS' '$PATTERN'"
	echo

	# Not exec'd (unlike a single-shot run) - the wizard needs to keep going
	# to ask about further sources. set -eu means a failed import stops the
	# loop right here instead of going on to ask "another source?".
	"$IMPORT_METRICS_SH" "$SRC" "$LABELS" "$PATTERN"

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
