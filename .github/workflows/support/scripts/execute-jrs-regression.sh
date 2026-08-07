#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Builds JRS arguments, runs the regression with up to 3 retries on GCP
# provisioning failures (exit 12), and maps known exit codes to GitHub
# Actions error annotations.
#
# Expected env vars (set by the calling workflow step):
#   HEDERA_TESTS_ENABLED, REGRESSION_PATH, CONFIG_PATH, USE_ENHANCED_RUNTIME,
#   PLATFORM_REPO_PATH, SLACK_SUMMARY, SLACK_RESULTS, GENERATE_SLACK_CHANNEL,
#   JAVA_VERSION, JRS_BRANCH, JRS_SSH_USER_NAME, JRS_WEB_HOSTNAME,
#   JRS_WEB_PORT, ACTIONS_RUN_URL, SLACK_API_TOKEN, GH_ACTOR, GH_JOB,
#   CG_EXEC, JAVA_OPTS, GITHUB_WORKSPACE

set -x

readonly BRANCH_VERSION_REGEX="([A-Za-z]+)/?[0-9]+\.([0-9]+)\.?[0-9]*-?[A-Za-z]*\.?[0-9]*"

if [[ -z "${GH_ACTOR}" ]]; then
  JRS_USER="swirlds-automation"
else
  JRS_USER="${GH_ACTOR}"
fi

[[ -n "${JRS_BRANCH}" ]] || JRS_BRANCH="${GH_JOB}"

JRS_ARGUMENTS="-po"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -u ${JRS_USER}"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -b ${JRS_BRANCH}"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -sl ${JRS_SSH_USER_NAME}"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -sk ${HOME}/.ssh/jrs-ssh-keyfile"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -wh ${JRS_WEB_HOSTNAME}"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -wp ${JRS_WEB_PORT}"
JRS_ARGUMENTS="${JRS_ARGUMENTS} -fr"
JRS_ARGUMENTS="${JRS_ARGUMENTS} --slack-api-token=${SLACK_API_TOKEN}"

if [[ -n "${GENERATE_SLACK_CHANNEL}" && "${GENERATE_SLACK_CHANNEL}" = true ]]; then
  SLACK_BRANCH="${JRS_BRANCH}"
  if [[ -n "${JRS_BRANCH}" ]]; then
    if [[ "${JRS_BRANCH}" =~ ${BRANCH_VERSION_REGEX} ]]; then
      SLACK_BRANCH="${BASH_REMATCH[1]}-${BASH_REMATCH[2]}"
    fi
  fi

  if [[ -n "${HEDERA_TESTS_ENABLED}" && "${HEDERA_TESTS_ENABLED}" = true ]]; then
    # Override for the main branch
    if [[ "${SLACK_BRANCH}" != "main" ]]; then
      SLACK_SUMMARY="hedera-gcp-${SLACK_BRANCH}-summary"
      SLACK_RESULTS="hedera-gcp-${SLACK_BRANCH}-regression"
    else
      SLACK_SUMMARY="hedera-regression-summary"
      SLACK_RESULTS="hedera-regression"
    fi
  else
    SLACK_SUMMARY="platform-gcp-${SLACK_BRANCH}-summary"
    SLACK_RESULTS="platform-gcp-${SLACK_BRANCH}-regression"
  fi
fi

if [[ -n "${SLACK_SUMMARY}" ]]; then
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -sc ${SLACK_SUMMARY}"
fi

if [[ -n "${SLACK_RESULTS}" ]]; then
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -rc ${SLACK_RESULTS}"
fi

if [[ -n "${JAVA_VERSION}" ]]; then
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -jv ${JAVA_VERSION}"
fi

if [[ -n "${PLATFORM_REPO_PATH}" ]]; then
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -pr ${PLATFORM_REPO_PATH}"
fi

if [[ -n "${HEDERA_TESTS_ENABLED}" && "${HEDERA_TESTS_ENABLED}" = true ]]; then
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -r ${GITHUB_WORKSPACE}"
  JRS_ARGUMENTS="${JRS_ARGUMENTS} -ci ${JRS_USER}_${ACTIONS_RUN_URL}"
fi

if [[ ! -f "${CONFIG_PATH}" ]]; then
  echo
  echo "Configuration File '${CONFIG_PATH}' does not exist......"
  echo
  echo "::error title=JRS Config Error::Configuration file '${CONFIG_PATH}' does not exist."
  exit 20
fi

if [[ -z "${JAVA_OPTS}" ]]; then
  JAVA_OPTS="-Xmx8g"
fi

# Retry up to 3 times on GCP provisioning failures (exit code 12 = RegressionFatalException).
# Any other non-zero exit code is a real test failure and exits without retrying,
# so genuine regressions are never masked by a retry.
EXIT_CODE=0
for attempt in 1 2 3; do
  [[ ${attempt} -gt 1 ]] && echo "::warning title=GCP Provisioning Failure::Attempt $((attempt - 1)) failed with exit code 12. Retrying (attempt ${attempt} of 3)..."

  # Disable exit-on-error so we can capture the exit code and annotate it
  # before re-exiting. Without this, the shell exits immediately on failure
  # and the ::error annotation never runs.
  set +e
  ${CG_EXEC} java ${JAVA_OPTS} \
  -cp "lib/*:regression.jar" \
  -Dlog4j.configurationFile="log4j2-fsts-enhanced.xml" \
  -Dspring.output.ansi.enabled=ALWAYS \
  com.swirlds.fsts.Main ${JRS_ARGUMENTS} -en "Github Actions" "${CONFIG_PATH}"
  EXIT_CODE=$?
  set -e

  echo "Test Exit Code: ${EXIT_CODE} (attempt ${attempt} of 3)"

  # Success or a non-infra failure: stop retrying immediately.
  [[ ${EXIT_CODE} -eq 0 ]] && break
  [[ ${EXIT_CODE} -ne 12 ]] && break

  # Exit code 12 only: clean up any GCP instance groups created during this
  # failed attempt so they don't leave stale process locks for the next attempt.
  if [[ -f "ci-gcp-instance-registry" ]]; then
    echo "Cleaning up GCP resources from failed attempt ${attempt}..."
    while IFS='|' read -r project region instance_group; do
      gcloud compute instance-groups managed delete "${instance_group}" \
        --project "${project}" --zone "${region}" --quiet 2>/dev/null || true
    done < "ci-gcp-instance-registry"
    rm -f "ci-gcp-instance-registry"
  fi

  [[ ${attempt} -lt 3 ]] && sleep 30
done

# Map known JRS exit codes to human-readable GitHub Actions error annotations.
# Exit code 12 = RegressionFatalException (GCP provisioning or SSH/Docker failure).
# Exit code 20 = configuration file not found (also checked above, but JRS can emit it too).
case ${EXIT_CODE} in
  0) ;;
  12) echo "::error title=GCP Provisioning Failure::JRS exited with code 12 after all attempts — GCP nodes failed to provision or SSH/Docker setup failed. Check for stale process locks or GCP quota issues." ;;
  20) echo "::error title=JRS Config Error::JRS exited with code 20 — configuration file not found." ;;
  *) [[ ${EXIT_CODE} -ne 0 ]] && echo "::error title=JRS Test Failure::JRS exited with code ${EXIT_CODE}. See logs above for details." ;;
esac

exit ${EXIT_CODE}
