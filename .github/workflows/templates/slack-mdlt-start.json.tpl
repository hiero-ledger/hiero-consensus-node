{
  "attachments": [
    {
      "color": {{ getenv "COLOR" | required "COLOR must be set" | data.ToJSON }},
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf "MDLT Started for %s" (getenv "BUILD_TAG") | data.ToJSON }},
            "emoji": true
          }
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*GitHub Properties*"
          }
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ printf "Workflow Run - <%s|link>\nMDLT Commit - <%s|link>" (getenv "WORKFLOW_RUN_URL") (getenv "COMMIT_URL") | data.ToJSON }}
          }
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Monitor Properties*"
          }
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ printf "FQDN: %s\nNamespace: %s" (getenv "FQDN") (getenv "NAMESPACE") | data.ToJSON }}
          }
        }
      ]
    }
  ]
}
