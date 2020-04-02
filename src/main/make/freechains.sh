#!/usr/bin/env sh
java -Xmx5M -Xms5M -ea -jar "$(dirname "$0")"/Freechains.jar "$@"
