#!/usr/bin/env sh

#while : ; do

rm -Rf /tmp/freechains/

###############################################################################
echo "#### 1"

freechains host create /tmp/freechains/8400 8400
jq ".timestamp=false" /tmp/freechains/8400/host > /tmp/host.tmp && mv /tmp/host.tmp /tmp/freechains/8400/host
freechains host start /tmp/freechains/8400 &
sleep 0.5
freechains --host=localhost:8400 chain create /0
g=`freechains --host=localhost:8400 chain genesis /0`
h=`freechains --host=localhost:8400 chain put /0 text Hello_World`
freechains --host=localhost:8400 chain get /0 "$h" > /tmp/freechains/freechains-tests-get-1.out
freechains --host=localhost:8400 chain get /0 0_6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d > /tmp/freechains/freechains-tests-get-0.out
hs=`freechains --host=localhost:8400 chain heads /0`
freechains --host=localhost:8400 chain get /0 "$g" > /tmp/freechains/freechains-tests-gen.out
freechains --host=localhost:8400 chain get /0 "$hs" > /tmp/freechains/freechains-tests-heads.out

set -e
diff /tmp/freechains/freechains-tests-gen.out   tests/freechains-tests-get-0.out
diff /tmp/freechains/freechains-tests-get-0.out tests/freechains-tests-get-0.out
diff /tmp/freechains/freechains-tests-get-1.out tests/freechains-tests-get-1.out
diff /tmp/freechains/freechains-tests-heads.out tests/freechains-tests-get-1.out
set +e

###############################################################################
echo "#### 2"

freechains host create /tmp/freechains/8401 8401
freechains host start /tmp/freechains/8401 &
sleep 0.5
freechains --host=localhost:8401 chain create /0
freechains --host=localhost:8400 chain put /0 text 111
freechains --host=localhost:8400 chain put /0 text 222
freechains --host=localhost:8400 chain send /0 localhost:8401

set -e
diff /tmp/freechains/8400/chains/0/ /tmp/freechains/8401/chains/0/
ret=`ls /tmp/freechains/8400/chains/0/ | wc`
if [ "$ret" != "      4       4     288" ]; then
  echo "$ret"
  exit 1
fi
set +e

###############################################################################
echo "#### 3"

while :
do
  rm -Rf /tmp/freechains/8402
  freechains host create /tmp/freechains/8402 8402
  freechains host start /tmp/freechains/8402 &
  sleep 0.5
  freechains --host=localhost:8402 chain create /0
  freechains --host=localhost:8400 chain send /0 localhost:8402 &
  P1=$!
  freechains --host=localhost:8401 chain send /0 localhost:8402 &
  P2=$!
  wait $P1 $P2

  set -e
  diff /tmp/freechains/8401/chains/0/ /tmp/freechains/8402/chains/0/
  ret=`ls /tmp/freechains/8401/chains/0/ | wc`
  if [ "$ret" != "      4       4     288" ]; then
    echo "$ret"
    exit 1
  fi
  set +e
  break
done

###############################################################################
###############################################################################
echo "#### 4"

for i in $(seq 1 50)
do
  freechains --host=localhost:8400 chain put /0 text $i
done
freechains --host=localhost:8400 chain send /0 localhost:8401 &
P1=$!
freechains --host=localhost:8400 chain send /0 localhost:8402 &
P2=$!
wait $P1 $P2

set -e
diff /tmp/freechains/8400/chains/0/ /tmp/freechains/8401/chains/0/
diff /tmp/freechains/8401/chains/0/ /tmp/freechains/8402/chains/0/
ret=`ls /tmp/freechains/8401/chains/0/ | wc`
if [ "$ret" != "     54      54    3932" ]; then
  echo "$ret"
  exit 1
fi
set +e

###############################################################################
echo "#### 5"

for i in $(seq 8411 8450)
do
  freechains host create /tmp/freechains/$i $i
  freechains host start /tmp/freechains/$i &
  sleep 0.5
  freechains --host=localhost:$i chain create /0
done

for i in $(seq 8411 8420)
do
  freechains --host=localhost:8400 chain send /0 localhost:$i &
done
sleep 10

set -e
for i in $(seq 8411 8420)
do
  diff /tmp/freechains/8400/chains/0/ /tmp/freechains/$i/chains/0/
done
set +e

for i in $(seq 8411 8420)
do
  freechains --host=localhost:$i chain send /0 localhost:$(($i+10)) &
done
sleep 10
for i in $(seq 8421 8430)
do
  freechains --host=localhost:$i chain send /0 localhost:$(($i+10)) &
  freechains --host=localhost:$i chain send /0 localhost:$(($i+20)) &
done
sleep 10

for i in $(seq 8411 8450)
do
  freechains host stop --host=localhost:$i
done

set -e
for i in $(seq 8421 8450)
do
  diff /tmp/freechains/8400/chains/0/ /tmp/freechains/$i/chains/0/
done
set +e

###############################################################################

#done

echo
echo "=== ALL TESTS PASSED ==="
echo
