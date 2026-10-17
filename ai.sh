#!/usr/bin/env sh
# AI tooling CLI: ./ai.sh generate TC-103 | gate TC-103 | approve TC-103 Your Name | triage | status | app
# Compiles first (quietly), then runs the CLI with the test classpath.
cd "$(dirname "$0")" && exec ./mvnw -q -B test-compile exec:exec -Dcli="$*"
