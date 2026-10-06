#!/usr/bin/env bash
set -eo pipefail

echo "Running script to find commit author in Slack with email '${EMAIL}'."

: "${EMAIL:?EMAIL environment variable must be set}"
: "${SLACK_BOT_TOKEN:?SLACK_BOT_TOKEN environment variable must be set}"

SLACK_USER_ID=$(curl -s -X GET "https://slack.com/api/users.list" \
  -H "Authorization: Bearer ${SLACK_BOT_TOKEN}" | jq -r --arg email "${EMAIL}" \
  '.members[] | select((.profile.email // "" | ascii_downcase) == ($email | ascii_downcase)) | .name')

if [[ -z "${SLACK_USER_ID}" || "${SLACK_USER_ID}" == "null" ]]; then
  echo "No Slack user found for email: ${EMAIL}"
  SLACK_USER_ID="No matching slack user found"
else
  echo "Found slack user for email: ${EMAIL}"
  SLACK_USER_ID="<@${SLACK_USER_ID}>"
fi
echo "Found the Slack user ${SLACK_USER_ID}"
