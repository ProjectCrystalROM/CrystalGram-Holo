#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
classes=$(mktemp -d)
trap 'rm -rf -- "$classes"' EXIT
src="$root/app/src/main/java"
javac -d "$classes" "$src/org/drinkless/tdlib/TdApi.java" \
    "$src/ai/crystalgram/holo/ChatScope.java" "$root/tests/ForumRoutingTest.java"
java -cp "$classes" ai.crystalgram.holo.ForumRoutingTest
