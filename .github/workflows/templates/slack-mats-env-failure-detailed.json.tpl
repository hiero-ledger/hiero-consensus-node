{{- $tests := getenv "FLAKY_TESTS_JSON" | default "[]" | data.JSONArray -}}
{{- $flaky_lines := coll.Slice -}}
{{- range $test := $tests -}}
  {{- $status := "" -}}
  {{- if eq (conv.ToString $test.is_new) "true" -}}{{- $status = " (New)" -}}{{- end -}}
  {{- $flaky_lines = coll.Append (printf "• `%s#%s` — <%s|#%v>%s" $test.class $test.method $test.issue_url $test.issue_number $status) $flaky_lines -}}
{{- end -}}
{
  "attachments": [
    {
      "color": "#FF8C00",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": ":warning: Hiero Consensus Node - MATS Environment Failure Report",
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
            "text": "*Environment issue detected on `main`.*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*MATS Tests*: %s" (getenv "MATS_TESTS_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Deploy CI Triggers*: %s" (getenv "DEPLOY_CI_TRIGGER_RESULT") | data.ToJSON }}
            }
          ]
        }
{{- if gt (len $tests) 0 }},
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ conv.Join $flaky_lines "\n" | data.ToJSON }}
          }
        }
{{- end }},
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Workflow run URL*:"
          },
          "fields": [
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
