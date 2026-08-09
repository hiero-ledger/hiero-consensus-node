{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":warning: Hiero Consensus Node - MATS Environment Failure Report",
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
            "text": {{ printf "*Environment issue detected on `%s`. See status below.* " (getenv "REF_NAME" | required "REF_NAME must be set") (getenv "REF_NAME") | data.ToJSON }}
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*MATS Tests*: %s" (getenv "MATS_TESTS_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Deploy CI Triggers*: %s" (getenv "DEPLOY_CI_TRIGGER_RESULT") | data.ToJSON }}
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
            "text": "*Workflow run URL*:"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Run attempt*: %s" (getenv "RUN_ATTEMPT") | data.ToJSON }}
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
