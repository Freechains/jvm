#!/usr/bin/env sh

while : ; do
  ./tests-general.sh || exit 1
  ./tests-shared.sh  || exit 1
  ./tests-pubpvt.sh  || exit 1
done

echo
echo "=== ALL TESTS PASSED ==="
echo
