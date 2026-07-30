{{- $xts_proceed := (getenv "XTS_PROCEED" | required "XTS_PROCEED must be set") -}}
{
  "attachments": [
    {
      "color": "#808080",
      "blocks": [
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ getenv "SLACK_SUMMARY_TEXT" | required "SLACK_SUMMARY_TEXT must be set" | data.ToJSON }}
          }
        }{{ if ne $xts_proceed "true" }},
        {
          "type": "context",
          "elements": [
            {
              "type": "mrkdwn",
              "text": {{ "No new XTS Candidate available." | data.ToJSON }}
            }
          ]
        }{{ end }}
      ]
    }
  ]
}
