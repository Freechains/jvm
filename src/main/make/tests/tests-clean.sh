#!/usr/bin/env sh

freechains host stop --host=localhost:8400 &
freechains host stop --host=localhost:8401 &
freechains host stop --host=localhost:8402 &
for i in $(seq 8411 8450)
do
  freechains host stop --host=localhost:$i &
done
sleep 5

rm -Rf /tmp/freechains/
mkdir /tmp/freechains/

echo
echo "=== ALL TESTS PASSED ==="
echo
