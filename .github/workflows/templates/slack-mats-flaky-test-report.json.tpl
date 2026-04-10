{{- $tests := getenv "FLAKY_TESTS_JSON" | default "[]" | data.JSONArray -}}
{{- $lines := coll.Slice -}}
{{- range $test := $tests -}}
  {{- $status := "" -}}
  {{- if eq (conv.ToString $test.is_new) "true" -}}{{- $status = " (New)" -}}{{- end -}}
  {{- $lines = coll.Append (printf "• `%s#%s` — <%s|#%v>%s" $test.class $test.method $test.issue_url $test.issue_number $status) $lines -}}
{{- end -}}
{
  "attachments": [
    {
      "color": "#FFA500",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":warning: Hiero Consensus Node - MATS Flaky Test Report",
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
            "text": "*MATS Job Succeeded on `main` but flaky tests were detected.*\nThe following tests failed initially but passed on retry:"
          }
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ conv.Join $lines "\n" | data.ToJSON }}
          }
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
              "text": "*Commit author*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_AUTHOR" | required "COMMIT_AUTHOR must be set" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Slack user*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "SLACK_USER_ID" | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": "*Workflow run ID*:"
            },
            {
              "type": "mrkdwn",
              "text": {{ getenv "WORKFLOW_RUN_ID" | data.ToJSON }}
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
