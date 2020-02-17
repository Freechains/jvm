#!/usr/bin/env sh

echo
echo "=== CLEANING... ==="
echo

for i in $(seq 8400 8450)
do
  freechains host stop --host=localhost:$i &
done
sleep 5

rm -Rf /tmp/freechains/
mkdir /tmp/freechains/

echo
echo "=== CLEAN OK ==="
echo
