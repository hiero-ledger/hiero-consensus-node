#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints commits as a Slack mrkdwn list for 226's XTS Slack reports: one "• <url|sha: subject>" line per
# commit, newest first. Slack rejects a whole message when one text object is too long, and the reports post with
# errors off, so an over-long list would drop the report silently. The list is cut to fit within <max-chars>, and
# the commits left out are counted in a last line pointing to the workflow summary, which lists them all.
#
# Usage: slack-commit-list.sh <repository-url> <max-chars> <git log revision arguments...>
#   e.g. slack-commit-list.sh https://github.com/owner/repo 2900 abc123..def456
#        slack-commit-list.sh https://github.com/owner/repo 2900 -n 1 def456
#
# Length is measured in bytes, which is never less than Slack's character count. Subjects have &, < and >
# escaped, as Slack's mrkdwn requires.
# Exit status: 0 printed (possibly nothing, when the range is empty), 2 usage error.
set -eo pipefail
export LC_ALL=C

if [[ $# -lt 3 || ! "${2}" =~ ^[0-9]+$ ]]; then
  echo "Usage: ${0} <repository-url> <max-chars> <git log revision arguments...>" >&2
  exit 2
fi

REPOSITORY_URL="${1%/}"
MAX_CHARS="${2}"
shift 2

# The last line, when commits are left out, is at most this long.
TRAILER_RESERVE=80

COMMITS=()
while IFS= read -r entry; do
  [[ -n "${entry}" ]] && COMMITS+=("${entry}")
done < <(git log --pretty=format:'%h%x1f%H%x1f%s%n' "$@")

LIST=""
SHOWN=0
for entry in "${COMMITS[@]}"; do
  IFS=$'\x1f' read -r SHORT FULL SUBJECT <<< "${entry}"
  SUBJECT="${SUBJECT//&/&amp;}"
  SUBJECT="${SUBJECT//</&lt;}"
  SUBJECT="${SUBJECT//>/&gt;}"
  LINE="• <${REPOSITORY_URL}/commit/${FULL}|${SHORT}: ${SUBJECT}>"
  if [[ ${SHOWN} -gt 0 ]]; then
    CANDIDATE="${LIST}"$'\n'"${LINE}"
  else
    CANDIDATE="${LINE}"
  fi
  # Keep room for the trailer unless this is the last commit.
  if (( SHOWN + 1 < ${#COMMITS[@]} )); then
    LIMIT=$(( MAX_CHARS - TRAILER_RESERVE ))
  else
    LIMIT=${MAX_CHARS}
  fi
  if (( ${#CANDIDATE} > LIMIT )); then
    break
  fi
  LIST="${CANDIDATE}"
  SHOWN=$(( SHOWN + 1 ))
done

LEFT_OUT=$(( ${#COMMITS[@]} - SHOWN ))
if (( LEFT_OUT > 0 )); then
  TRAILER="…and ${LEFT_OUT} more; see the workflow summary for the full list."
  if [[ -n "${LIST}" ]]; then
    LIST="${LIST}"$'\n'"${TRAILER}"
  else
    LIST="${TRAILER}"
  fi
fi

printf '%s\n' "${LIST}"
