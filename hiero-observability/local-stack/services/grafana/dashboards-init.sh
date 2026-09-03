#!/bin/sh
# dashboards-init: copies GRAFANA_DASHBOARDS_DIR (mounted read-only at
# /dashboards-src) into the dashboards-out volume Grafana reads from,
# rewriting the ${<name>} datasource placeholders that Grafana's *export for
# sharing externally* option leaves behind. See docs/spec3.md.
#
# Must run to completion before Grafana starts - see docker-compose.yml,
# grafana.depends_on.dashboards-init.condition: service_completed_successfully.
# A non-zero exit here blocks Grafana from starting at all.
set -eu

SRC=/dashboards-src
OUT=/dashboards-out
STAGE=/tmp/dashboards-stage
: "${METRICS_DATASOURCE_NAME:?METRICS_DATASOURCE_NAME must be set}"

rm -rf "$STAGE"
mkdir -p "$STAGE"

FAILED_FLAG=/tmp/dashboards-init.failed
rm -f "$FAILED_FLAG"

# ---------------------------------------------------------------------------
# process_json: a single line-by-line pass over one file that BOTH enforces
# the pluginId guard AND rewrites the ${<name>} placeholder for every
# __inputs entry whose pluginId is "prometheus" - not two separate passes
# over the array.
#
# $1 = source file (under $SRC)   $2 = destination file (already copied,
# byte-identical to the source, under $STAGE)
#
# A missing or empty __inputs array is a pure no-op: the loop below simply
# never finds an entry to act on, and dest is left as the untouched copy
# made by the caller - same code path as the "has inputs" case, no branch.
#
# Not a JSON parser: it assumes Grafana's own pretty-printed export format
# (one structural token, or one key:value pair, per line) - what every one
# of the 22 dashboards audited for issue 3 looks like. The one common
# exception - an empty array collapsed onto one line, e.g.
# `"__inputs": [],` - is handled by the same code path via the heredoc
# below, which splits every {, }, [, ], and , onto its own line before
# scanning, purely for this scan (the copy under $STAGE, the thing that
# actually gets provisioned, is never touched by that normalization).
#
# A __inputs entry may itself contain nested objects with their own "name"
# field (e.g. a library-panel reference under "usage.libraryPanels[]" - this
# is real, present in the audited corpus). depth==2 is what restricts name/
# pluginId capture to the entry's OWN top-level fields, not anything nested
# a level or more deeper inside it.
# ---------------------------------------------------------------------------
process_json() {
  _src="$1"
  _dest="$2"

  # Fast path: most dashboards have no __inputs key at all - skip the
  # line-by-line scan entirely rather than reading the whole file.
  grep -q '"__inputs"' "$_src" || return 0
  _in_inputs=0
  _depth=0
  _cur_name=""
  _cur_plugin=""
  _rc=0
  _seen_inputs=0

  while IFS= read -r _line || [ -n "$_line" ]; do
    _t=$(printf '%s' "$_line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    [ -z "$_t" ] && continue

    # __inputs is a single, early top-level key in a Grafana export - once
    # we have scanned all the way past it, stop reading rather than walking
    # through the rest of a possibly huge panels array.
    [ "$_seen_inputs" -eq 1 ] && break

    if [ "$_in_inputs" -eq 0 ]; then
      case "$_t" in
        '"__inputs"'*)
          _in_inputs=1
          _depth=0
          _cur_name=""
          _cur_plugin=""
          ;;
      esac
      continue
    fi

    case "$_t" in
      '['|'{')
        _depth=$((_depth + 1))
        continue
        ;;
      ']'|'}')
        _old_depth=$_depth
        _depth=$((_depth - 1))
        if [ "$_old_depth" -eq 2 ] && [ "$_depth" -eq 1 ]; then
          # One __inputs entry object just closed.
          if [ -n "$_cur_plugin" ] && [ "$_cur_plugin" != "prometheus" ]; then
            printf 'dashboards-init: %s: __inputs entry has pluginId "%s", only "prometheus" is supported\n' "$_src" "$_cur_plugin" >&2
            _rc=1
          elif [ "$_cur_plugin" = "prometheus" ] && [ -n "$_cur_name" ]; then
            # Escape regex metacharacters that could appear in an
            # attacker- or typo-controlled dashboard's own "name" field -
            # this comes from third-party dashboard JSON, not from our env.
            _esc_name=$(printf '%s' "$_cur_name" | sed -e 's/\\/\\\\/g' -e 's/[.[*^$]/\\&/g')
            # Rewrite immediately - this is the one and only place
            # ${<name>} is acted on for this entry.
            sed -i "s/\${$_esc_name}/$METRICS_DATASOURCE_NAME/g" "$_dest"
          fi
          _cur_name=""
          _cur_plugin=""
        fi
        if [ "$_depth" -le 0 ]; then
          _in_inputs=0
          _seen_inputs=1
        fi
        continue
        ;;
      ',')
        continue
        ;;
    esac

    if [ "$_depth" -eq 2 ]; then
      case "$_t" in
        '"name"'*)
          _v=$(printf '%s\n' "$_t" | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
          [ -n "$_v" ] && _cur_name=$_v
          ;;
        '"pluginId"'*)
          _v=$(printf '%s\n' "$_t" | sed -n 's/.*"pluginId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
          [ -n "$_v" ] && _cur_plugin=$_v
          ;;
      esac
    fi
  done <<EOF
$(sed -e 's/[{}[]/\n&\n/g' -e 's/\]/\n]\n/g' -e 's/,/\n,\n/g' "$_src")
EOF

  return "$_rc"
}

# ---------------------------------------------------------------------------
# Single pass: mirror SRC into STAGE, validating + rewriting .json files as
# we go. Every file is still visited even after a failure elsewhere, so one
# run reports every offending file at once - but STAGE is discarded, not
# promoted to OUT, the moment any file fails, so a bad edit to one dashboard
# never wipes out the previously-good provisioned set.
# ---------------------------------------------------------------------------
find "$SRC" -type f | while IFS= read -r f; do
  rel=${f#"$SRC"/}
  dest="$STAGE/$rel"
  mkdir -p "$(dirname "$dest")"
  cp "$f" "$dest"
  case "$f" in
    *.json)
      process_json "$f" "$dest" || echo 1 >> "$FAILED_FLAG"
      ;;
  esac
done

if [ -f "$FAILED_FLAG" ]; then
  echo "dashboards-init: one or more dashboards failed validation; /dashboards-out left unchanged" >&2
  exit 1
fi

# Promote: OUT should mirror STAGE exactly, including deletions of files
# removed from SRC since the last run.
find "$OUT" -mindepth 1 -delete
cp -a "$STAGE/." "$OUT/"

echo "dashboards-init: provisioned $(find "$SRC" -type f -name '*.json' | wc -l) dashboard(s) from $SRC into $OUT"
