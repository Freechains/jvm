#!/usr/bin/env bash

echo
echo "=== TESTS-GENERAL ==="
echo

FC=/tmp/freechains
./clean.sh

PVT=6F99999751DE615705B9B1A987D8422D75D16F5D55AF43520765FA8C5329F7053CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322
PUB=3CCAF4839B1FDDF406552AF175613D7A247C5703683AEC6DBDF0BB3932DD8322

###############################################################################
echo "#### 1"

freechains host create $FC/8400 8400
freechains host start $FC/8400 &
sleep 0.5
freechains --host=localhost:8400 chains join / owner-only $PUB
freechains --host=localhost:8400 host now 0
g=`freechains --host=localhost:8400 chain genesis /`
h=`freechains --host=localhost:8400 --sign=$PVT chain post / inline Hello_World`
freechains --host=localhost:8400 chain get / block "$h" > $FC/freechains-tests-get-1.out
freechains --host=localhost:8400 chain get / block 0_B5E21297B8EBEE0CFA0FA5AD30F21B8AE9AE9BBF25F2729989FE5A092B86B129 > $FC/freechains-tests-get-0.out
hs=`freechains --host=localhost:8400 chain heads / linked`
freechains --host=localhost:8400 chain get / block "$g" > $FC/freechains-tests-gen.out
freechains --host=localhost:8400 chain get / block "$hs" > $FC/freechains-tests-heads.out

diff -I 1_ $FC/freechains-tests-gen.out   out/freechains-tests-get-0.out || exit 1
diff -I 1_ $FC/freechains-tests-get-0.out out/freechains-tests-get-0.out || exit 1
diff -I time -I hash $FC/freechains-tests-get-1.out out/freechains-tests-get-1.out || exit 1
diff -I time -I hash $FC/freechains-tests-heads.out out/freechains-tests-get-1.out || exit 1

uuencode /bin/cat cat > /tmp/cat.uu
h=`freechains --host=localhost:8400 --sign=$PVT chain post / file /tmp/cat.uu`
echo $h
freechains --host=localhost:8400 chain get / payload "$h" | uudecode -o /tmp/cat
diff /tmp/cat /bin/cat || exit 1

###############################################################################
echo "#### 2"

freechains host create $FC/8401 8401
freechains host start $FC/8401 &
sleep 0.5
freechains --host=localhost:8401 host now 0
freechains --host=localhost:8401 chains join / owner-only $PUB
echo 111 | freechains --host=localhost:8400 chain --sign=$PVT post / -
freechains --host=localhost:8400 chain --sign=$PVT post / inline 222
freechains --host=localhost:8400 peer send localhost:8401 /

diff $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
ret=`ls $FC/8400/chains/blocks/ | wc`
if [ "$ret" != "     10      10     710" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 3"

rm -Rf $FC/8402
freechains host create $FC/8402 8402
freechains host start $FC/8402 &
sleep 0.5
freechains --host=localhost:8402 host now 0
freechains --host=localhost:8402 chains join / owner-only $PUB
freechains --host=localhost:8402 chain recv / localhost:8400 &
P1=$!
freechains --host=localhost:8401 peer send localhost:8402 / &
P2=$!
wait $P1 $P2
#sleep 10

diff $FC/8401/chains/blocks/ $FC/8402/chains/blocks/ || exit 1
ret=`ls $FC/8401/chains/blocks/ | wc`
if [ "$ret" != "     10      10     710" ]; then
  echo "$ret"
  exit 1
fi

#exit 0

###############################################################################
###############################################################################
echo "#### 4"

for i in $(seq 1 50)
do
  freechains --host=localhost:8400 --sign=$PVT chain post / inline $i
done
freechains --host=localhost:8400 peer send localhost:8401 /
freechains --host=localhost:8400 peer send localhost:8401 /
freechains --host=localhost:8400 peer send localhost:8402 /
freechains --host=localhost:8400 peer send localhost:8402 /

diff $FC/8400/chains/blocks/ $FC/8401/chains/blocks/ || exit 1
diff $FC/8401/chains/blocks/ $FC/8402/chains/blocks/ || exit 1
ret=`ls $FC/8401/chains/blocks/ | wc`
if [ "$ret" != "    110     110    7900" ]; then
  echo "$ret"
  exit 1
fi

###############################################################################
echo "#### 5"

for i in $(seq 8411 8430)
do
  freechains host create $FC/$i $i
  freechains host start $FC/$i &
  sleep 0.5
  freechains --host=localhost:$i host now 0
  freechains --host=localhost:$i chains join / owner-only $PUB
done

echo "#### 5.1"

for i in $(seq 8411 8420)
do
  freechains --host=localhost:8400 peer send localhost:$i / &
done

echo "#### 5.2"

sleep 90

for i in $(seq 8411 8420)
do
  echo ">>> $i"
  diff $FC/8400/chains/blocks/ $FC/$i/chains/blocks/ || exit 1
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:$(($i+10)) chain recv / localhost:$i &
done
sleep 20

echo "#### 5.3"

for i in $(seq 8421 8425)
do
  freechains --host=localhost:$i peer send localhost:$(($i+5)) / &
  freechains --host=localhost:$i peer send localhost:$(($i+10)) / &
done
sleep 20

for i in $(seq 8421 8430)
do
  diff $FC/8400/chains/blocks/ $FC/$i/chains/blocks/ || exit 1
done

###############################################################################

echo
echo "=== ALL TESTS PASSED ==="
echo
