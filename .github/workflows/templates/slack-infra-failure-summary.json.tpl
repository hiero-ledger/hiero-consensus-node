{
  "attachments": [
    {
      "color": "#FF8C00",
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
