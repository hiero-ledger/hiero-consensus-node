{{- $result := getenv "RESULT" | required "RESULT must be set" -}}
{{- $color := cond (eq $result "success") "#00FF00" (cond (eq $result "cancelled") "#555555" "#FF0000") -}}
{
  "attachments": [
    {
      "color": {{ $color | data.ToJSON }},
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
              "text": {{ getenv "PANEL_LABEL" | required "PANEL_LABEL must be set" | data.ToJSON }}
            },
            {
              "type": "plain_text",
              "text": {{ getenv "RESULT" | required "RESULT must be set" | data.ToJSON }}
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
