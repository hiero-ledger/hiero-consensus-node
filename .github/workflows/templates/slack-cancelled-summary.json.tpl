{{- $xts_tag_exists := (getenv "XTS_TAG_EXISTS" | required "XTS_TAG_EXISTS must be set") -}}
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
          },
          "text": {
            "type": "mrkdwn",
            "text": {{- if eq $xts_tag_exists "true" -}}{{ "tag exists" }}{{else}}{{"No new XTS Candidate available."}}{{end}}
          }
        }
      ]
    }
  ]
}
