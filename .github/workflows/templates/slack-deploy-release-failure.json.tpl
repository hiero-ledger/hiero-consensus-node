{
  "attachments": [
    {
      "color": "#FF0000",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":exclamation: Hiero Consensus Node - %s Failure Report" (getenv "REPORT_TITLE" | required "REPORT_TITLE must be set") | data.ToJSON }},
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
            "text": {{ printf "*%s job resulted in failure on `%s`. See status below.* " (getenv "REPORT_TITLE") (getenv "REF_NAME" | required "REF_NAME must be set") | data.ToJSON }}
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ getenv "JOB_RESULTS" | required "JOB_RESULTS must be set" | data.ToJSON }}
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
              "text": {{ printf "<%s>" (getenv "COMMIT_URL" | required "COMMIT_URL must be set") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Commit author*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_AUTHOR" | required "COMMIT_AUTHOR must be set" | data.ToJSON }}
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
              "text": {{ printf " %s" (getenv "WORKFLOW_RUN_ID") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run URL*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "WORKFLOW_RUN_URL" | required "WORKFLOW_RUN_URL must be set") | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
