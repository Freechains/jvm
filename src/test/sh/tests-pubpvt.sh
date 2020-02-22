#!/usr/bin/env bash

echo
echo "=== TESTS-PUBPVT ==="
echo

FC=/tmp/freechains
./tests-clean.sh

# 8400 (public and private keys)
freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
k=`freechains --host=localhost:8400 crypto create pubpvt correct`
freechains --host=localhost:8400 chain join / pubpvt rw $k
#freechains --host=localhost:8400 chain join / pubpvt 4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB 70CFFBAAD1E1B640A77E7784D25C3E535F1E5237264D1B5C38CB2C53A495B3FE4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB

# 8401 (no keys)
freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 chain join /

# 8402 (public key only)
freechains host create $FC/8402 8402
freechains host start $FC/8402 &
sleep 0.5
pub=`echo "$k" | head -n1`
freechains --host=localhost:8402 chain join / pubpvt rw $pub
#freechains --host=localhost:8402 chain join / pubpvt 4EC5AF592D177459D2338D07FFF9A9B64822EF5BE9E9715E8C63965DD2AF6ECB

# get genesis block of each host
g0=`freechains --host=localhost:8400 chain genesis /`
g1=`freechains --host=localhost:8401 chain genesis /`
g2=`freechains --host=localhost:8402 chain genesis /`

# compare them
! diff -q <(echo "$g0") <(echo "$g1") || exit 1
diff <(echo "$g0") <(echo "$g2") || exit 1

# post to 8400, send to 8401 (fail) 8402 (succees)
freechains --host=localhost:8400 chain post / inline utf8 Hello_World
freechains --host=localhost:8400 chain send / localhost:8401  # FAIL
freechains --host=localhost:8400 chain send / localhost:8402  # SUCCESS

# compare them
! diff -q $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
diff $FC/8400/chains/blocks/ $FC/8402/chains/blocks/      || exit 1

# post to 8400, send to 8401 (fail) 8402 (succees, but crypted)
h=`freechains --host=localhost:8400 chain post / inline utf8 Hello_World --encrypt`
freechains --host=localhost:8400 chain send / localhost:8401  # FAIL
freechains --host=localhost:8400 chain send / localhost:8402  # SUCCESS

freechains --host=localhost:8400 chain get / $h > $FC/dec.blk
diff <(jq ".hashable.payload.post" $FC/dec.blk) <(echo '"Hello_World"') || exit 1
freechains --host=localhost:8402 chain get / $h > $FC/enc.blk
diff <(jq ".hashable.payload.encrypted" $FC/enc.blk) <(echo 'true') || exit 1

# stop hosts
freechains host stop --host=localhost:8400 &
freechains host stop --host=localhost:8401 &
freechains host stop --host=localhost:8402 &
sleep 0.5

echo
echo "=== ALL TESTS PASSED ==="
echo