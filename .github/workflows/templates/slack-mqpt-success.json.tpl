{
  "attachments": [
    {
      "color": "#00FF00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ getenv "HEADER_TEXT" | required "HEADER_TEXT must be set" | data.ToJSON }},
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
            "text": {{ getenv "STATUS_TEXT" | required "STATUS_TEXT must be set" | data.ToJSON }}
          },
          "fields": [
            {
              "type": "plain_text",
              "text": {{ getenv "RUN_TEST_LABEL" | required "RUN_TEST_LABEL must be set" | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": {{ getenv "RUN_TEST_RESULT" | required "RUN_TEST_RESULT must be set" | data.ToJSON }}
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
            "text": {{ getenv "INFO_SECTION_TEXT" | required "INFO_SECTION_TEXT must be set" | data.ToJSON }}
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Source Tag*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s>" (getenv "SOURCE_TAG_URL" | required "SOURCE_TAG_URL must be set") | data.ToJSON }}
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
