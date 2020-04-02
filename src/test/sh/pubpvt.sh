#!/usr/bin/env bash

echo
echo "=== TESTS-PUBPVT ==="
echo

FC=/tmp/freechains
./clean.sh

# 8400 (public and private keys)
freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
KEYS=`freechains --host=localhost:8400 crypto create pubpvt correct`
PUB=`echo $KEYS | cut -d ' ' -f 1`
PVT=`echo $KEYS | cut -d ' ' -f 2`
freechains --host=localhost:8400 chain join / $PUB

# 8401 (no keys)
freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain join /

# 8402 (public key only)
freechains host create $FC/8402 8402
freechains host start $FC/8402 &
sleep 0.5
freechains --host=localhost:8402 chain join / $PUB

# get genesis block of each host
g0=`freechains --host=localhost:8400 chain genesis /`
g1=`freechains --host=localhost:8401 chain genesis /`
g2=`freechains --host=localhost:8402 chain genesis /`

# compare them
! diff -q <(echo "$g0") <(echo "$g1") || exit 1
diff <(echo "$g0") <(echo "$g2") || exit 1

# post to 8400, send to 8401 (fail) 8402 (succees)
freechains --host=localhost:8400 --sign=$PVT chain post / inline utf8 Hello_World
freechains --host=localhost:8400 chain send / localhost:8401  # FAIL
freechains --host=localhost:8400 chain send / localhost:8402  # SUCCESS

# compare them
! diff -q -I tineTime $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
diff -I tineTime $FC/8400/chains/blocks/ $FC/8402/chains/blocks/      || exit 1

# post to 8400, send to 8401 (fail) 8402 (succees, but crypted)
h=`freechains --host=localhost:8400 --sign=$PVT --crypt=$PVT chain post / inline utf8 Hello_World`
freechains --host=localhost:8400 chain send / localhost:8401  # FAIL
freechains --host=localhost:8400 chain send / localhost:8402  # SUCCESS

freechains --host=localhost:8400 --crypt=$PVT chain get / $h > $FC/dec.blk
diff <(jq ".immut.payload" $FC/dec.blk) <(echo '"Hello_World"') || exit 1
freechains --host=localhost:8402 chain get / $h > $FC/enc.blk
diff <(jq ".immut.crypt" $FC/enc.blk) <(echo 'true') || exit 1

# stop hosts
freechains host stop --host=localhost:8400 &
freechains host stop --host=localhost:8401 &
freechains host stop --host=localhost:8402 &
sleep 0.5

echo
echo "=== ALL TESTS PASSED ==="
echo