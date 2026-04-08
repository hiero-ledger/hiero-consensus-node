{{- $tests := getenv "FLAKY_TESTS_JSON" | default "[]" | data.JSON -}}
{{- $flaky_lines := coll.Slice -}}
{{- range $test := $tests -}}
  {{- $status := "" -}}
  {{- if eq (conv.ToString $test.is_new) "true" -}}{{- $status = " (New)" -}}{{- end -}}
  {{- $flaky_lines = coll.Append (printf "• `%s#%s` — <%s|#%v>%s" $test.class $test.method $test.issue_url $test.issue_number $status) $flaky_lines -}}
{{- end -}}
{
  "attachments": [
    {
      "color": "#FF0000",
      "blocks": [
        {
          "type": "header",
          "text": {
            "type": "plain_text",
            "text": {{ printf ":x: XTS - eXtended Test Suite Test Failure Report (%s) Failed" (getenv "XTS_INFO" | required "XTS_INFO must be set") | data.ToJSON }},
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
            "text": "*XTS test failure. See status below.*"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ printf "*Fetch XTS Candidate Tag*: %s" (getenv "FETCH_XTS_CANDIDATE_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*XTS Execution*: %s" (getenv "XTS_EXECUTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Tag as XTS-Passing*: %s" (getenv "TAG_FOR_PROMOTION_RESULT") | data.ToJSON }}
            },
            {
              "type": "mrkdwn",
              "text": {{ printf "*Failing Test(s)*: %s" (getenv "FAILED_TESTS" | required "FAILED_TESTS must be set") | data.ToJSON }}
            }
          ]
        },
{{- if gt (len $tests) 0 }},
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": {{ strings.Join $flaky_lines "\n" | data.ToJSON }}
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
        },
        {
          "type": "divider"
        },
        {
          "type": "section",
          "text": {
            "type": "mrkdwn",
            "text": "*Commit List*:"
          },
          "fields": [
            {
              "type": "mrkdwn",
              "text": {{ getenv "COMMIT_LIST" | required "COMMIT_LIST must be set" | data.ToJSON }}
            }
          ]
        }
      ]
    }
  ]
}
