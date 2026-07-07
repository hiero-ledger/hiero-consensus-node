{
  "attachments": [
    {
      "color": "#00FF00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":tada: Hiero Consensus Node - Build Candidate Promoted (%s)" (getenv "BUILD_CANDIDATE_TAG" | required "BUILD_CANDIDATE_TAG must be set") | data.ToJSON }},
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
            "text": "*Build Candidate Promotion Succeeded. See details below.*"
          },
          "fields": [
            {
              "type": "plain_text",
              "text": "Build Candidate Commit"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "BUILD_CANDIDATE_COMMIT_URL" | required "BUILD_CANDIDATE_COMMIT_URL must be set") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": "Promoted Build Tag"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "PROMOTED_TAG_URL" | required "PROMOTED_TAG_URL must be set") | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": "Tag Date"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*%s*" (getenv "PROMOTE_DATE" | required "PROMOTE_DATE must be set") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "Workflow Run"
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
