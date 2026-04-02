{
  "attachments": [
    {
      "color": "#FF0000",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":x: XTS - eXtended Test Suite Test Failure Report (%s) Failed" (getenv "XTS_INFO") | data.ToJSON }},
            "emoji": true
          }
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*XTS test failure. See status below.*"
          },
          "fields": [
            {
              "type": "plain_text",
              "text": {{ printf "Fetch XTS Candidate Tag: %s" (getenv "FETCH_XTS_CANDIDATE_RESULT") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": {{ printf "XTS Execution: %s" (getenv "XTS_EXECUTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": {{ printf "Tag as XTS-Passing: %s" (getenv "TAG_FOR_PROMOTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": {{ printf "Failing Test(s): %s" (getenv "FAILED_TESTS") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Workflow and Commit Information*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Source Commit*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "COMMIT_URL") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Commit author*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_AUTHOR" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Slack user*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "SLACK_USER_ID" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run ID*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "WORKFLOW_RUN_ID" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run URL*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "WORKFLOW_RUN_URL") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Commit List*:"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_LIST" | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
