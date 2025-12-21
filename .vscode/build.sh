#!/usr/bin/env bash
set -euo pipefail

mvn -B -ntp -s ./settings.xml clean package
