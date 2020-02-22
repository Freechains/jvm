#!/usr/bin/env bash

#while : ; do
  ./tests-general.sh || exit 1
  ./tests-sync.sh    || exit 1
  ./tests-shared.sh  || exit 1
  ./tests-pubpvt.sh  || exit 1
#done
