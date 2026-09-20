#!/bin/bash
# Invoked as root by Systems Manager after an operator publishes a release.
set -euo pipefail
cd "$(dirname "$0")"
if [[ $EUID -ne 0 ]]; then echo 'Root privileges required.' >&2; exit 1; fi
if systemctl is-active --quiet apogee-ground.service; then
  curl -fsS http://127.0.0.1:8081/api/state | python3 -c '
import json,sys
run = json.load(sys.stdin).get("run")
if run and run["status"] not in ("COMPLETED", "FAILED", "ABORTED"):
    sys.exit("Resolve the active procedure before deploying an update.")
'
fi
# SSM may become ready before cloud-init's package installation has finished.
cloud-init status --wait >/dev/null
id apogee >/dev/null 2>&1 || useradd --system --home-dir /var/lib/apogee --shell /sbin/nologin apogee
install -d -m 755 /opt/apogee/releases /etc/apogee
install -d -m 700 -o apogee -g apogee /var/lib/apogee
install -m 600 deployment.json /etc/apogee/deployment.json
curl --fail --silent --show-error --proto '=https' --tlsv1.2 \
  https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
  -o /etc/apogee/rds-ca.pem
chmod 644 /etc/apogee/rds-ca.pem
install -m 755 database.py /opt/apogee/database.py
python3 /opt/apogee/database.py --initialize
python3 - <<'PY'
import json
from pathlib import Path
config = json.loads(Path('/etc/apogee/deployment.json').read_text())
host = config['databaseEndpoint']
content = '\n'.join([
    'BIND_ADDRESS=127.0.0.1', 'PORT=8081', 'SIMULATOR_HOST=127.0.0.1',
    'DATABASE_USER=apogee_app',
    f'DATABASE_URL=jdbc:postgresql://{host}:5432/apogee?sslmode=verify-full&sslrootcert=/etc/apogee/rds-ca.pem',
    'SPRING_CONFIG_IMPORT=configtree:/run/apogee-db/', '',
])
Path('/etc/apogee/ground.env').write_text(content)
PY
chmod 644 /etc/apogee/ground.env
release="$(sha256sum apogee.jar | cut -d ' ' -f 1)"
install -d -m 755 "/opt/apogee/releases/$release"
if [[ ! -f "/opt/apogee/releases/$release/apogee.jar" ]]; then
  install -m 644 apogee.jar "/opt/apogee/releases/$release/apogee.jar"
elif ! cmp --silent apogee.jar "/opt/apogee/releases/$release/apogee.jar"; then
  echo 'Existing release differs from its content hash; refusing to overwrite it.' >&2
  exit 1
fi
# Stop both before changing the shared artifact: running JVMs lazily load classes.
systemctl stop apogee-ground.service apogee-simulator.service 2>/dev/null || true
ln -sfn "/opt/apogee/releases/$release" /opt/apogee/current
install -m 644 apogee-ground.service apogee-simulator.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now apogee-simulator.service apogee-ground.service
for attempt in $(seq 1 60); do
  if curl -fs http://127.0.0.1:8081/api/state | python3 -c 'import json,sys; s=json.load(sys.stdin); sys.exit(0 if s["fresh"] else 1)' 2>/dev/null; then
    echo 'Apogee is ready. Open an SSM port-forwarding session to access the console.'
    exit 0
  fi
  sleep 2
done
echo 'Services did not become ready. Inspect journalctl -u apogee-ground -u apogee-simulator on the host.' >&2
exit 1
