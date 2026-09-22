{{- $version := getenv "VERSION" | required "VERSION must be set" -}}
{{- $serverUrl := getenv "SERVER_URL" | required "SERVER_URL must be set" -}}
{{- $repository := getenv "REPOSITORY" | required "REPOSITORY must be set" -}}
{
  "attachments": [
    {
      "color": "#b7f350",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":dvd: Node Software Release v%s" $version | data.ToJSON }},
            "emoji": true
          }
        },
        {
          "type": "section",
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Deployment Channel:*"
            },
            {
              "type": "mrkdwn",
              "text": "*Deployment Status Check:*"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "ARTIFACT_REGISTRY" | required "ARTIFACT_REGISTRY must be set" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s|%s>" (getenv "ARTIFACT_URL" | required "ARTIFACT_URL must be set") (getenv "ARTIFACT_NAME" | required "ARTIFACT_NAME must be set") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "section",
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Source Branch:*"
            },
            {
              "type": "mrkdwn",
              "text": "*Short Commit ID:*"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s/%s/tree/%s|%s>" $serverUrl $repository (getenv "REF_NAME" | required "REF_NAME must be set") (getenv "REF_NAME") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "`%s`" (getenv "COMMIT_ID_SHORT" | required "COMMIT_ID_SHORT must be set") | data.ToJSON }}
            }
          ]
        },
        {
          "type": "section",
          "fields": [
            {
              "type": "mrkdwn",
              "text": "*Gradle Version Number:*"
            },
            {
              "type": "mrkdwn",
              "text": "*Release Notes:*"
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "`%s`" $version | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "<%s/%s/releases/tag/v%s|v%s>" $serverUrl $repository $version $version | data.ToJSON }}
            }
          ]
        },
        {
          "type": "divider"
        },
        {
          "type": "context",
          "elements": [
            {
              "type": "mrkdwn",
              "text": ":warning: Artifacts may not be immediately available in Maven Central. Please verify existence of the artifacts using the link provided above."
            }
          ]
        }
      ]
    }
  ]
}
