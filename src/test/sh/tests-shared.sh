#!/usr/bin/env bash

echo
echo "=== TESTS-SHARED ==="
echo

FC=/tmp/freechains
./tests-clean.sh

# 8400 (correct password)
freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
k0=`freechains --host=localhost:8400 crypto create shared correct`
freechains --host=localhost:8400 chain join /

# 8401 (wrong password)
freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
k1=`freechains --host=localhost:8401 crypto create shared wrong`
freechains --host=localhost:8401 chain join /

# 8402 (correct password)
freechains host create $FC/8402 8402
freechains host start $FC/8402 &
sleep 0.5
k2=`freechains --host=localhost:8402 crypto create shared correct`
freechains --host=localhost:8402 chain join /

# 8403 (no password)
freechains host create $FC/8403 8403
freechains host start $FC/8403 &
sleep 0.5
freechains --host=localhost:8403 chain join /

# get genesis block of each host
g0=`freechains --host=localhost:8400 chain genesis /`
g1=`freechains --host=localhost:8401 chain genesis /`
g2=`freechains --host=localhost:8402 chain genesis /`
g3=`freechains --host=localhost:8402 chain genesis /`

# compare them
diff <(echo "$g0") <(echo "$g1") || exit 1
diff <(echo "$g0") <(echo "$g2") || exit 1
diff <(echo "$g0") <(echo "$g3") || exit 1

# post to 8400, send to 8401 (fail) 8402 (success)
h1=`freechains --host=localhost:8400 --crypt=$k0 chain post / inline utf8 Hello_World`
h2=`freechains --host=localhost:8400 --crypt=$k0 chain post / inline utf8 Bye_World`
freechains --host=localhost:8400 chain send / localhost:8401
freechains --host=localhost:8400 chain send / localhost:8402
freechains --host=localhost:8400 chain send / localhost:8403

# compare them
diff -I time $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
diff -I time $FC/8400/chains/blocks/ $FC/8402/chains/blocks/ || exit 1
diff -I time $FC/8400/chains/blocks/ $FC/8403/chains/blocks/ || exit 1

freechains --host=localhost:8400 --crypt=$k0 chain get / $h1 > $FC/v01.blk
freechains --host=localhost:8400 --crypt=$k0 chain get / $h2 > $FC/v02.blk
freechains --host=localhost:8401 --crypt=$k1 chain get / $h1 > $FC/v11.blk
freechains --host=localhost:8401 --crypt=$k1 chain get / $h2 > $FC/v12.blk
freechains --host=localhost:8402 --crypt=$k2 chain get / $h1 > $FC/v21.blk
freechains --host=localhost:8402 --crypt=$k2 chain get / $h2 > $FC/v22.blk
freechains --host=localhost:8403 chain get / $h1 > $FC/v31.blk
freechains --host=localhost:8403 chain get / $h2 > $FC/v32.blk

echo '"Hello_World"' > $FC/hello.out
echo '"Bye_World"'   > $FC/bye.out
touch $FC/empty.out
jq ".immut.payload" $FC/8400/chains/blocks/1_*.blk > $FC/enc1.out
jq ".immut.payload" $FC/8400/chains/blocks/2_*.blk > $FC/enc2.out

jq ".immut.payload" $FC/v01.blk > $FC/v01.out
jq ".immut.payload" $FC/v02.blk > $FC/v02.out
jq ".immut.payload" $FC/v11.blk > $FC/v11.out
jq ".immut.payload" $FC/v12.blk > $FC/v12.out
jq ".immut.payload" $FC/v21.blk > $FC/v21.out
jq ".immut.payload" $FC/v22.blk > $FC/v22.out
jq ".immut.payload" $FC/v31.blk > $FC/v31.out
jq ".immut.payload" $FC/v32.blk > $FC/v32.out

diff $FC/hello.out $FC/v01.out || exit 1
diff $FC/empty.out $FC/v11.out || exit 1
diff $FC/hello.out $FC/v21.out || exit 1
diff $FC/enc1.out  $FC/v31.out || exit 1

diff $FC/bye.out   $FC/v02.out || exit 1
diff $FC/empty.out $FC/v12.out || exit 1
diff $FC/bye.out   $FC/v22.out || exit 1
diff $FC/enc2.out  $FC/v32.out || exit 1

# stop hosts
freechains host stop --host=localhost:8400 &
freechains host stop --host=localhost:8401 &
freechains host stop --host=localhost:8402 &
sleep 0.5

echo
echo "=== ALL TESTS PASSED ==="
echo