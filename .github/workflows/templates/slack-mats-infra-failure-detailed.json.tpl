{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":warning: Hiero Consensus Node - MATS Infrastructure Failure Report",
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
            "text": "*Infrastructure issue detected on `main` — not a test failure.*"
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
              "text": {{ printf "<%s>" (getenv "WORKFLOW_RUN_URL") | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
