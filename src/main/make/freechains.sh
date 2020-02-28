#!/usr/bin/env sh
java -Xmx10M -Xms10M -ea -jar "$(dirname "$0")"/Freechains.jar "$@"