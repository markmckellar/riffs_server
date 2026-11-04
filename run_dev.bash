#!/bin/bash
# Runs the server locally on port 9095 with name-only sign-in (no Google) for testing.
cd "$(dirname "$0")" && exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--rifts.auth.dev-login=true $*"
