#!/bin/bash

# Use default if no argument is provided
LOGFILE="${1:-build/turtle/otter.log}"

# Exit if file doesn't exist
if [ ! -f "$LOGFILE" ]; then
  echo "File not found: $LOGFILE"
  exit 1
fi

# Clean up existing output files
rm -f log-node-*.log

# Process the log file
while IFS= read -r line; do
  if [[ "$line" =~ \[\{nodeId=([0-9]+)\}\] ]]; then
    node_id="${BASH_REMATCH[1]}"
    echo "$line" >> "log-node-${node_id}.log"
  else
    echo "$line" >> "log-node-unknown.log"
  fi
done < "$LOGFILE"