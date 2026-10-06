{
  "attachments": [
    {
      "color": "#777777",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":bell: Hiero Consensus Node - No Build Candidate Promoted",
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
            "text": "*No Build Candidate Promoted. See status below.*"
          },
          "fields": [
            {
              "type": "plain_text",
              "text": "Status:"
            },
            {
              "type": "plain_text",
              "text": {{ getenv "PROMOTE_CANDIDATE_MESSAGE" | required "PROMOTE_CANDIDATE_MESSAGE must be set" | data.ToJSON }}
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
              "type": "plain_text",
              "text": "Date"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*%s*" (getenv "PROMOTE_DATE" | required "PROMOTE_DATE must be set") | data.ToJSON }}
            },
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
