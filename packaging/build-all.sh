#!/bin/sh
# Builds all supported release archives on macOS without opening a launcher window.
set -eu
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.awt.headless=true"
exec python3 "$(dirname "$0")/package.py" "$@"
