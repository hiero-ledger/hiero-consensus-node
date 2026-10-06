{
  "attachments": [
    {
      "color": {{ getenv "STATUS_COLOR" | required "STATUS_COLOR must be set" | data.ToJSON }},
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":vertical_traffic_light: Hiero Consensus Node - Migration Testing Report",
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
            "text": "*Migration Testing Panel was executed. See status below.*"
          },
          "fields": [
            {
              "type": "plain_text",
              "text": "Migration Testing Panel Result"
            },
            {
              "type": "plain_text",
              "text": {{ getenv "STATUS" | required "STATUS must be set" | data.ToJSON }}
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
              "text": "*Workflow run ID*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "WORKFLOW_RUN_ID" | required "WORKFLOW_RUN_ID must be set" | data.ToJSON }}
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
