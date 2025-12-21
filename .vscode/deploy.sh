#!/usr/bin/env bash
set -euo pipefail

# --- CONFIG ---
JENKINS_URL="${JENKINS_URL:-http://localhost:8080}"
JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_TOKEN="${JENKINS_TOKEN:?Missing JENKINS_TOKEN}"
PLUGIN_SHORTNAME="job-backup"

HPI_PATH=$(ls -1 target/*.hpi | head -n 1)

echo "Deploying: $HPI_PATH"

CLI_JAR=".vscode/jenkins-cli.jar"
mkdir -p .vscode

if [ ! -f "$CLI_JAR" ]; then
  echo "Downloading jenkins-cli.jar..."
  curl -fsSL "$JENKINS_URL/jnlpJars/jenkins-cli.jar" -o "$CLI_JAR"
fi

java -jar "$CLI_JAR" \
  -s "$JENKINS_URL" \
  -auth "$JENKINS_USER:$JENKINS_TOKEN" \
  install-plugin "$HPI_PATH" -deploy

java -jar "$CLI_JAR" \
  -s "$JENKINS_URL" \
  -auth "$JENKINS_USER:$JENKINS_TOKEN" \
  safe-restart

echo "Waiting for Jenkins..."
until curl -fsS "$JENKINS_URL/login" >/dev/null; do
  sleep 2
done

echo "Jenkins is back."
