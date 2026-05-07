

# There is a slack api token, a report webhook, and a details token.
#
#* according to the citr-test-config.md file, the ZXC: Regression workflow posts to  the 'regression-test' slack channel.
#* according to the flaky-test-plugin-guide.md the flaky tests workflow will send a message to slack confirming the run passed, but noting that flaky tests were detected.
#    * also a warning message is posted to #continuous-integration-test-operations on slack.
#
#* node-flow-build-application.yaml:135 the job 'report-flaky-success' has a step 'Find Commit Author in Slack'
#    It uses the SLACK_CITR_BOT_TOKEN and emails to EMAIL: ${{ steps.fetch-commit-info.outputs.commit-email }}
#    The actual run is an inline bash script to get the list of users from slack with curl,  use JQ to parse out
#    the emails, then select the user that matches the email. Then it echos to github output
#        "slack-user-id=${SLACK_USER_ID}" >> "${GITHUB_OUTPUT}"
#    Then in the build report summary it outputs a summary of the MATS test along with the commit author and slack
#    user id to the github output.
#
#    then the step "Report flaky tests (slack citr-operations)" seems to be the part that actually posts to slack.
#
#    It looks up the slack user again on line 287.
#
#    The actual slack posting use this github action: slackapi/slack-github-action@b0fa283ad8fea605de13dc3f449259339835fc52 # v2.1.0
#
#* node-flow-deploy-release-artifact.yaml also has a step to find the commit author
#
#every place this is called:
#          SLACK_USER_ID=$(curl -s -X GET "https://slack.com/api/users.list" \
#
#Notes:
#
#* in 210-flow-merge-queue-controller.yaml line 66 the slack-citr-details-token
#    is assigned to a SLACK_CITR_DETAILED_REPORTS_WEBHOOK secret.
#
#

echo "JOSH: Running find commit author slack."
echo "JOSH: looking up email '${EMAIL}'."
SLACK_USER_ID=$(curl -s -X GET "https://slack.com/api/users.list" \
  -H "Authorization: Bearer ${SLACK_BOT_TOKEN}" | jq -r --arg email "${EMAIL}" \
  '.members[] | select((.profile.email // "" | ascii_downcase) == ($email | ascii_downcase)) | .name')

if [[ -z "${SLACK_USER_ID}" || "${SLACK_USER_ID}" == "null" ]]; then
  echo "No Slack user found for email: ${EMAIL}"
  SLACK_USER_ID="No matching slack user found"
  echo "JOSH: set slack user id branch 1"
else
  echo "Found slack user for email: ${EMAIL}"
  SLACK_USER_ID="<@${SLACK_USER_ID}>"
  echo "JOSH: set slack user id branch 2"
fi
echo "JOSH: got the slack user ${SLACK_USER_ID}"
echo "JOSH: done."
#echo "slack-user-id=${SLACK_USER_ID}" >> "${GITHUB_OUTPUT}"
