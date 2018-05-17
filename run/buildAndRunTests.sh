#!/usr/bin/env bash
set -e
export REDIS_HOST=red
export REDIS_PORT=6379

export PROXY_HOST=proxy
export PROXY_PORT=55555

export PROXY_CAPACITY=500
export PROXY_EXPIRY=3000
export PROXY_PARALLELISM=11

export TEST_PARALLELISM=7
export TEST_SLEEP_TIME=10
export TEST_OPS=10000
export TEST_KEYS=1000

docker run -it --rm --name mavenBuild -v "$(pwd)":/usr/src/mymaven -w /usr/src/mymaven maven:3.3-jdk-8 mvn clean install

cp target/redis-proxy-1.0-SNAPSHOT.jar run/

cd run

docker-compose up -d
docker-compose logs -f -t proxy > proxy.log &
docker-compose logs -f -t tester > tester.log &

docker wait tester
docker container stop proxy
docker exec -it red redis-cli FLUSHALL
docker container stop red

echo "************************************PROXY STATS************************************"
tail -n $(($(($((PROXY_PARALLELISM + 1)) * 11)) + 2)) proxy.log

echo "************************************TESTR STATS************************************"
tail -n 5 tester.log
