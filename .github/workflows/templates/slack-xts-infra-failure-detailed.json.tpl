{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":warning: XTS - eXtended Test Suite Infrastructure Failure Report (%s)" (getenv "XTS_INFO") | data.ToJSON }},
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
            "text": "*Infrastructure issue detected — not a test failure.*"
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
