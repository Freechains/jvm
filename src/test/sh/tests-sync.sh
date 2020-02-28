#!/usr/bin/env bash

echo
echo "=== TESTS-SYNC ==="
echo

FC=/tmp/freechains
./tests-clean.sh

###############################################################################
echo "#### 1"

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 chain join /

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain join /

freechains --host=localhost:8400 --time=0 chain post / inline utf8 zero
freechains --host=localhost:8401 --time=0 chain post / inline utf8 zero

freechains --host=localhost:8400 chain post / inline utf8 111
freechains --host=localhost:8401 chain post / inline utf8 aaa

freechains --host=localhost:8400 chain send / localhost:8401
freechains --host=localhost:8401 chain send / localhost:8400

diff $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo