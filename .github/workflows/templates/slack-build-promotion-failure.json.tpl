{
  "attachments": [
    {
      "color": "#FF0000",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":rotating_light: Hiero Consensus Node - Build Candidate Promotion Error Report",
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
            "text": "*Build Candidate Promotion Job Resulted in failure. See status below.*"
          },
          "fields": [
            {
              "type": "plain_text",
              "text": "Date"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*%s*" (getenv "PROMOTE_DATE" | required "PROMOTE_DATE must be set") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": "Fetch Latest Build Candidate"
            },
            {
              "type": "plain_text",
              "text": {{ getenv "DETERMINE_RESULT" | required "DETERMINE_RESULT must be set" | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": "Promote Build Candidate"
            },
            {
              "type": "plain_text",
              "text": {{ getenv "PROMOTE_RESULT" | required "PROMOTE_RESULT must be set" | data.ToJSON }}
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
            "text": "*Additional Information:*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Source Commit*: \n<%s>" (getenv "COMMIT_URL" | required "COMMIT_URL must be set") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Workflow Run*: \n<%s>" (getenv "WORKFLOW_RUN_URL" | required "WORKFLOW_RUN_URL must be set") | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
