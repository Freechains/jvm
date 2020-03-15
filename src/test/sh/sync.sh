#!/usr/bin/env bash

echo
echo "=== TESTS-SYNC ==="
echo

FC=/tmp/freechains
./clean.sh

PVT0=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB0=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
S0=--sign=$PVT0

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

freechains --host=localhost:8400 host now 0
freechains --host=localhost:8401 host now 0

freechains --host=localhost:8400 $S0 chain post / inline utf8 zero
freechains --host=localhost:8400 chain send / localhost:8401

freechains --host=localhost:8400 chain post / inline utf8 111
freechains --host=localhost:8401 chain post / inline utf8 aaa

freechains --host=localhost:8400 chain send / localhost:8401
freechains --host=localhost:8401 chain send / localhost:8400

! diff -q -I localTime $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1

freechains --host=localhost:8400 $S0 chain like post / + 1000 `freechains --host=localhost:8400 chain heads rejected /`
freechains --host=localhost:8401 $S0 chain like post / + 1000 `freechains --host=localhost:8401 chain heads rejected /`

freechains --host=localhost:8400 host now 8000000
freechains --host=localhost:8401 host now 8000000

freechains --host=localhost:8400 chain send / localhost:8401
freechains --host=localhost:8401 chain send / localhost:8400

diff -I localTime $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo