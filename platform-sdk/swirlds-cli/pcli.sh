#!/usr/bin/env bash
set -o pipefail

#
# Copyright 2016-2022 Hedera Hashgraph, LLC
#
# This software is the confidential and proprietary information of
# Hedera Hashgraph, LLC. ("Confidential Information"). You shall not
# disclose such Confidential Information and shall use it only in
# accordance with the terms of the license agreement you entered into
# with Hedera Hashgraph.
#
# HEDERA HASHGRAPH MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
# THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
# TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
# PARTICULAR PURPOSE, OR NON-INFRINGEMENT. HEDERA HASHGRAPH SHALL NOT BE LIABLE FOR
# ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
# DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
#

# This script provides a convenient wrapper for launching the platform CLI.

JVM_CLASSPATH=''

# This function attempts to add a jar file to the classpath, or if given a directory attempts to
# add all jarfiles inside that directory to the classpath.
add_to_classpath() {
  PATH_TO_ADD=$1
  PATH_TO_ADD=$(readlink -f "${PATH_TO_ADD}")
  if [[ -e "$PATH_TO_ADD" ]]; then
    # The file exists.
    if [[ -d $PATH_TO_ADD ]]; then
      # If the path is a directory, then add all files in that directory tree to the classpath.
      # shellcheck disable=SC2044
      for FILE in $(find "${PATH_TO_ADD}" -name '*.jar'); do
        FILE=$(readlink -f "${FILE}")
        if [[ "$JVM_CLASSPATH" = '' ]]; then
          JVM_CLASSPATH="${FILE}"
        else
          JVM_CLASSPATH="${JVM_CLASSPATH}:${FILE}"
        fi
      done
    else
      # Path is not a directory.
      if [[ $PATH_TO_ADD = *.jar ]]; then
        # File is a jar file.
        if [[ "$JVM_CLASSPATH" = '' ]]; then
          JVM_CLASSPATH="${PATH_TO_ADD}"
        else
          JVM_CLASSPATH="${JVM_CLASSPATH}:${PATH_TO_ADD}"
        fi
      else
        echo "invalid classpath file, ${PATH_TO_ADD} is not a jar file"
        exit 1
      fi
    fi
  else
    echo "invalid classpath file, ${PATH_TO_ADD} does not exist"
    exit 1
  fi
}

# The location were this script can be found.
SCRIPT_PATH="$(dirname "$(readlink -f "$0")")"

# The entrypoint into the platform CLI (i.e. where the main() method is)
MAIN_CLASS_NAME='org.hiero.consensus.pcli.Pcli'

# Iterate over arguments and strip out the classpath arguments and JVM arguments.
# This needs to be handled by this bash script and not by the java program,
# since we need to pass this data directly to the JVM.
PROGRAM_ARGS=()
JVM_ARGS=()
SHOW_BANNER=false
SHOW_HELP=false
for ((CURRENT_INDEX=1; CURRENT_INDEX<=$#; CURRENT_INDEX++)); do

  # The current argument we are considering.
  ARG="${!CURRENT_INDEX}"
  # The argument after the current argument.
  NEXT_INDEX=$((CURRENT_INDEX+1))
  NEXT_ARG="${!NEXT_INDEX}"

  if [[ "$ARG" = '--help' ]] || [[ "$ARG" = '-h' ]]; then
    # Detect help flag
    SHOW_HELP=true
    PROGRAM_ARGS+=("${ARG}")
  elif [[ "$ARG" = '--banner' ]] || [[ "$ARG" = '-B' ]]; then
    # Enable ASCII banner display
    SHOW_BANNER=true
  elif [[ "$ARG" = '--load' ]] || [[ "$ARG" = '-L' ]] || [[ "$ARG" = '--cp' ]]; then
    # We have found an argument that needs to be handled in this bash script.

    # Skip the next argument in the next loop cycle.
    CURRENT_INDEX=$NEXT_INDEX

   add_to_classpath $NEXT_ARG

  elif [[ "$ARG" = '--jvm' ]] || [[ "$ARG" = '-J' ]]; then
    # We have found an argument that needs to be handled in this bash script.

    # Skip the next argument in the next loop cycle.
    CURRENT_INDEX=$NEXT_INDEX

    JVM_ARGS+=("${NEXT_ARG}")

  elif [[ "$ARG" = '--debug' ]] || [[ "$ARG" = '-D' ]]; then
    # We have found an argument that needs to be handled in this bash script.
    JVM_ARGS+=('-agentlib:jdwp=transport=dt_socket,address=8888,server=y,suspend=y')
  elif [[ "$ARG" = '--memory' ]] || [[ "$ARG" = '-M' ]]; then
      # We have found an argument that needs to be handled in this bash script.

      # Skip the next argument in the next loop cycle.
      CURRENT_INDEX=$NEXT_INDEX

      JVM_ARGS+=("-Xmx${NEXT_ARG}g")
  else
    # The argument should be passed to the PCLI java process.
    PROGRAM_ARGS+=("${ARG}")
  fi
done

# Add the main jar
MAIN_JAR_PATH="${SCRIPT_PATH}/../sdk/swirlds-cli.jar"
if [[ -e "$MAIN_JAR_PATH" ]]; then
  add_to_classpath "${MAIN_JAR_PATH}"
fi

# In a development environment, this is the location where jarfiles are compiled to. If this directory
# exists then add it to the classpath automatically.
DEFAULT_LIB_PATH="${SCRIPT_PATH}/../sdk/data/lib"
if [[ -e "$DEFAULT_LIB_PATH" ]]; then
  add_to_classpath "${DEFAULT_LIB_PATH}"
fi

if [[ "$JVM_CLASSPATH" = '' ]]; then
  echo 'ERROR: the JVM classpath is empty!'
  echo 'Try adding jar or directories containing jarfiles to the classpath via the "--load /path/to/my/jars" argument.'
  exit 1
fi

# Override any internal log4j configs to force console output
LOG4J_CONFIG="${SCRIPT_PATH}/log4j2-stdout.xml"
if [[ -e "$LOG4J_CONFIG" ]]; then
  JVM_ARGS+=("-Dlog4j.configurationFile=${LOG4J_CONFIG}")
fi

# Pass banner flag to Java program (disabled by default, enabled with --banner/-B)
JVM_ARGS+=("-Dpcli.showBanner=${SHOW_BANNER}")

# Run the CLI
java "${JVM_ARGS[@]}" -cp "${JVM_CLASSPATH}" $MAIN_CLASS_NAME "${PROGRAM_ARGS[@]}"
EXIT_CODE=$?

# If help was requested OR if picocli showed usage due to invalid arguments (exit code 2),
# append bash script options
if [[ "$SHOW_HELP" = true ]] || [[ $EXIT_CODE -eq 2 ]]; then
    echo ""
    echo "Additional bash script options (handled before JVM starts):"
    echo "  -L, --load <path>    Load jar files from path"
    echo "  -J, --jvm <arg>      Pass argument to JVM"
    echo "  -D, --debug          Enable remote debugging on port 8888"
    echo "  -M, --memory <GB>    Set JVM max heap size in GB"
fi

exit $EXIT_CODE
