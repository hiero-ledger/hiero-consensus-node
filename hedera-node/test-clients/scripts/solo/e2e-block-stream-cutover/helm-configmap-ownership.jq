# SPDX-License-Identifier: Apache-2.0
# Helm 4 applies as manager=helm, operation=Apply. Solo's Update writes can claim
# the same data fields under node-fetch or helm/Update. Release only those data
# claims; retain metadata, other fields, and every unrelated manager's ownership.
(.metadata.managedFields // []) as $before
| ($before | map(
    if .manager == "node-fetch" or (.manager == "helm" and .operation == "Update")
    then .fieldsV1 |= del(."f:data")
    else . end
  ) | map(select(.fieldsV1 != {}))) as $after
| if $before == $after then [] else [
    {op: "test", path: "/metadata/resourceVersion", value: .metadata.resourceVersion},
    {op: "replace", path: "/metadata/managedFields", value: $after}
  ] end
