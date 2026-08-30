#!/bin/sh
set -e

# Start LibreOffice headless server in the background so the Java UNO
# connection pool can connect to it for TOC refresh on report generation.
soffice --headless --norestore \
  --accept="socket,host=localhost,port=2002;urp;StarOffice.ServiceManager" &

LO_PID=$!
echo "Started LibreOffice headless server (pid $LO_PID) on port 2002"

# Give LO a moment to finish initializing before the app tries to connect
sleep 3

# Start the Spring Boot application
exec java -jar app.jar
