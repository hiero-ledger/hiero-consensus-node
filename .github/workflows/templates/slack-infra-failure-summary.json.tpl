{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ getenv "SLACK_SUMMARY_TEXT" | required "SLACK_SUMMARY_TEXT must be set" | data.ToJSON }}
          }
        }
      ]
    }
  ]
}
