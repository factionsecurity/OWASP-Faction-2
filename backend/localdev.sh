
# Start LibreOffice headless server in the background so the Java UNO
# connection pool can connect to it for TOC refresh on report generation.
soffice --headless --norestore \
  --accept="socket,host=localhost,port=2002;urp;StarOffice.ServiceManager" &

LO_PID=$!
echo "Started LibreOffice headless server (pid $LO_PID) on port 2002"

# Give LO a moment to finish initializing before the app tries to connect
sleep 3

# SSO_ENCRYPTION_KEY encrypts SMTP/IMAP credentials and report passwords at rest.
#
# Generated per machine rather than hardcoded. The value that used to live here was
# base64(sha256("secret")) — a published example — and shipping that in a public repo
# would hand every self-hoster who copies this script an encryption key whose preimage
# is one dictionary word.
#
# Set your own to keep encrypted data readable across restarts:
#   export SSO_ENCRYPTION_KEY=$(openssl rand -base64 32)
if [ -z "${SSO_ENCRYPTION_KEY:-}" ]; then
  SSO_ENCRYPTION_KEY=$(openssl rand -base64 32)
  echo "SSO_ENCRYPTION_KEY not set — generated an ephemeral one for this run."
  echo "Anything encrypted now will be unreadable after restart. To persist it:"
  echo "  export SSO_ENCRYPTION_KEY=\$(openssl rand -base64 32)"
fi
export SSO_ENCRYPTION_KEY

# Start the Spring Boot application
FRONTEND_URL=http://localhost:3000 BACKEND_URL=http://localhost:3000 mvn spring-boot:run
