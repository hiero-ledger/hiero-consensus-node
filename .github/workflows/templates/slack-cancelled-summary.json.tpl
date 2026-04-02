{
  "attachments": [
    {
      "color": "#808080",
      "blocks": [
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ getenv "SLACK_SUMMARY_TEXT" | data.ToJSON }}
          }
        }
      ]
    }
  ]
}
