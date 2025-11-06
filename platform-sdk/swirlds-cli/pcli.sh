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
MAIN_CLASS_NAME='com.swirlds.cli.PlatformCli'

PYTHON_INSTALLED=true
python3 --version >/dev/null 2>&1 || PYTHON_INSTALLED=false

SQUELCH_SPAM=false
SQUELCH_SPAM_PATH="${SCRIPT_PATH}/squelch-spam.py"
if [[ -e "$SQUELCH_SPAM_PATH" ]]; then
  if [[ "$PYTHON_INSTALLED" = true ]]; then
    # The squelch-spam.py script exists and can be executed, enable squelching.
    SQUELCH_SPAM=true
  fi
fi

# Iterate over arguments and strip out the classpath arguments and JVM arguments.
# This needs to be handled by this bash script and not by the java program,
# since we need to pass this data directly to the JVM.
PROGRAM_ARGS=()
JVM_ARGS=()
LOG4J_SET=false
IGNORE_JARS=false
COLOR=true
NO_PIPING=false
for ((CURRENT_INDEX=1; CURRENT_INDEX<=$#; CURRENT_INDEX++)); do

  # The current argument we are considering.
  ARG="${!CURRENT_INDEX}"
  # The argument after the current argument.
  NEXT_INDEX=$((CURRENT_INDEX+1))
  NEXT_ARG="${!NEXT_INDEX}"

  if [[ "$ARG" = '--load' ]] || [[ "$ARG" = '-L' ]] || [[ "$ARG" = '--cp' ]]; then
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
  elif [[ "$ARG" = '--ignore-jars' ]] || [[ "$ARG" = '-I' ]]; then
    # We have found an argument that needs to be handled in this bash script.
    IGNORE_JARS=true
  elif [[ "$ARG" = '--memory' ]] || [[ "$ARG" = '-M' ]]; then
      # We have found an argument that needs to be handled in this bash script.

      # Skip the next argument in the next loop cycle.
      CURRENT_INDEX=$NEXT_INDEX

      JVM_ARGS+=("-Xmx${NEXT_ARG}g")
  elif [[ "$ARG" = 'jtr' ]]; then
    # The jtr suite of commands does not want its output passed through the log colorizer or spam squelcher.
    # Piping output streams disrupts dynamic loading bars.
    NO_PIPING=true
    PROGRAM_ARGS+=("${ARG}")
  else
    # The argument should be passed to the PCLI java process.

    if [[ "$ARG" = '--log4j' ]]; then
        # If the user has specified a log4j path then we don't want to attempt to override it with the default.
        LOG4J_SET=true
    elif [[ "$ARG" = '--no-color' ]]; then
        # A boring person doesn't want log coloration.
        COLOR=false
    fi;

    PROGRAM_ARGS+=("${ARG}")
  fi
done

if [[ "$IGNORE_JARS" = false ]]; then
  # In a development environment, this is the location where jarfiles are compiled to. If this directory
  # exists then add it to the classpath automatically.
  DEFAULT_LIB_PATH="${SCRIPT_PATH}/../sdk/data/lib"
  if [[ -e "$DEFAULT_LIB_PATH" ]]; then
    add_to_classpath "${DEFAULT_LIB_PATH}"
  fi
fi

if [[ "$LOG4J_SET" = false ]]; then
  # The user hasn't specified a log4j path.
  DEFAULT_LOG4J_PATH="${SCRIPT_PATH}/log4j2-stdout.xml"
  if [[ -e "$DEFAULT_LOG4J_PATH" ]]; then
    # There is a log4j configuration file at the default location.
    PROGRAM_ARGS+=('--log4j')
    PROGRAM_ARGS+=("${DEFAULT_LOG4J_PATH}")
  fi
fi

if [[ "$JVM_CLASSPATH" = '' ]]; then
  echo 'ERROR: the JVM classpath is empty!'
  echo 'Try adding jar or directories containing jarfiles to the classpath via the "--load /path/to/my/jars" argument.'
  exit 1
fi

if [[ "$NO_PIPING" = true ]]; then
  COLOR=false
  SQUELCH_SPAM=false
fi

run () {
  java "${JVM_ARGS[@]}" -cp "${JVM_CLASSPATH}" $MAIN_CLASS_NAME "${PROGRAM_ARGS[@]}"
}

colorize () {
  java -cp "${JVM_CLASSPATH}" com.swirlds.cli.logging.StdInOutColorize "${PROGRAM_ARGS[@]}"
}

if [[ "$COLOR" = true ]]; then
  if [[ "$SQUELCH_SPAM" = true ]]; then
    run 2>&1 | $SQUELCH_SPAM_PATH | colorize
  else
    run | colorize
  fi
else
  if [[ "$SQUELCH_SPAM" = true ]]; then
    run 2>&1 | $SQUELCH_SPAM_PATH
  else
    run
  fi
fi
