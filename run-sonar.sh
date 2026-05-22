#!/bin/bash
echo "=== InsureFlow SonarQube Analysis ==="
echo "Step 1: Running tests + coverage..."
mvn clean test jacoco:report

echo "Step 2: Running SonarQube analysis..."
mvn sonar:sonar \
  -Dsonar.projectKey=insureflow \
  -Dsonar.host.url=http://localhost:9000 \
  -Dsonar.login=$SONAR_TOKEN

echo "=== Done! Open http://localhost:9000 ==="
