#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Prints, as a compact JSON array, the test configuration of every consensus node ref to regression-test.
# Each element holds the ref plus the versions that ref pins for itself (read with `git show`, no checkout):
#   {"cn-ref","cn-slug","solo-version","java-version","bn-mirror-node-version","tck-version","js-sdk-version","bn-version-override"}
# "bn-version-override" is true when the ref's BlockNodeContainer reads the BLOCK_NODE_VERSION env var; on refs
# without it the BN communication suite would silently run the pinned Block Node image whatever version is requested.
#
# Run from inside a git clone with an `origin` remote.
# env:
#   CN_REFS  comma-separated branches, tags or SHAs of the consensus node repository (required)
#
# Each ref is fetched (depth 1) and must contain .github/workflows/support/citr/.citr-env and
# gradle/toolchain-versions.properties with every key used below; a missing key or a Solo version below 0.44.0
# (required by the Block Node regression panel) fails the script. Duplicate refs are dropped; distinct refs whose
# artifact slugs collide (e.g. release/0.72 and release-0.72) fail the script.
set -euo pipefail

CITR_ENV=".github/workflows/support/citr/.citr-env"
TOOLCHAIN="gradle/toolchain-versions.properties"
BN_CONTAINER="hedera-node/test-clients/src/main/java/com/hedera/services/bdd/junit/hedera/containers/BlockNodeContainer.java"
# no leading '-': git would read the ref as an option (e.g. --tags)
REF_RE='^[A-Za-z0-9._][A-Za-z0-9._/-]*$'
# git cannot fetch an abbreviated SHA by name
SHORT_SHA_RE='^[0-9a-f]{7,39}$'

: "${CN_REFS:?CN_REFS must be set}"

# value of KEY from KEY=VALUE text on stdin; fails if absent or empty
get() { # key file-label
  local v
  v=$(grep -E "^$1=" | head -n1 | cut -d= -f2- | tr -d '[:space:]') || true
  if [[ -z "${v}" ]]; then
    echo "::error::'$1' is missing or empty in $2 of ref '${ref}'" >&2
    exit 1
  fi
  echo "${v}"
}

refs=$(echo "${CN_REFS}" | tr ',' '\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | awk 'NF && !seen[$0]++')
if [[ -z "${refs}" ]]; then
  echo "::error::No consensus node refs given" >&2
  exit 1
fi

configs="[]"
while IFS= read -r ref; do
  if [[ ! "${ref}" =~ ${REF_RE} ]]; then
    echo "::error::Invalid consensus node ref '${ref}' (expected ${REF_RE})" >&2
    exit 1
  fi
  if [[ "${ref}" =~ ${SHORT_SHA_RE} ]]; then
    echo "::error::Consensus node ref '${ref}' looks like an abbreviated SHA; use the full 40-character SHA" >&2
    exit 1
  fi
  git fetch --quiet --depth=1 origin "${ref}" >&2
  citr=$(git show "FETCH_HEAD:${CITR_ENV}")
  toolchain=$(git show "FETCH_HEAD:${TOOLCHAIN}")

  solo=$(echo "${citr}" | get citr-solo-version "${CITR_ENV}")
  if [[ "$(printf '%s\n' "${solo#v}" "0.44.0" | sort -V | head -n1)" != "0.44.0" ]]; then
    echo "::error::Block Node regression requires Solo >= 0.44.0 but ref '${ref}' pins '${solo}'" >&2
    exit 1
  fi

  # plain assignments so that a missing key aborts the script (a failing $(...) inside an argument would not)
  java=$(echo "${toolchain}" | get jdk "${TOOLCHAIN}")
  mn=$(echo "${citr}" | get bn-mirror-node-version "${CITR_ENV}")
  tck=$(echo "${citr}" | get tck-version "${CITR_ENV}")
  sdk=$(echo "${citr}" | get js-sdk-version "${CITR_ENV}")

  # a missing BlockNodeContainer fails git show (and the script); only a present file without the env var means "no"
  bn_container=$(git show "FETCH_HEAD:${BN_CONTAINER}")
  # the quoted env var name: release branches have a constant that is also called BLOCK_NODE_VERSION but is hard-coded
  if grep -q '"BLOCK_NODE_VERSION"' <<< "${bn_container}"; then override=true; else override=false; fi

  # the slug is the only part of the ref in artifact names, so two refs sharing one (release/0.72, release-0.72) would
  # make the second leg's upload fail with a duplicate name
  slug="${ref//[^A-Za-z0-9._-]/-}"
  if [[ -n "$(echo "${configs}" | jq -r --arg slug "${slug}" '.[] | select(."cn-slug" == $slug) | ."cn-ref"')" ]]; then
    echo "::error::Consensus node ref '${ref}' has the same artifact slug '${slug}' as an earlier ref" >&2
    exit 1
  fi

  configs=$(echo "${configs}" | jq -c \
    --arg ref "${ref}" --arg slug "${slug}" --arg solo "${solo}" --arg java "${java}" \
    --arg mn "${mn}" --arg tck "${tck}" --arg sdk "${sdk}" --argjson override "${override}" \
    '. + [{"cn-ref": $ref, "cn-slug": $slug, "solo-version": $solo, "java-version": $java,
           "bn-mirror-node-version": $mn, "tck-version": $tck, "js-sdk-version": $sdk,
           "bn-version-override": $override}]')
done <<< "${refs}"

echo "${configs}"
