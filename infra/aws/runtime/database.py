#!/usr/bin/env python3
"""Root-only database initialization and password delivery; never print secrets."""
import json
import os
from pathlib import Path
import pwd
import subprocess
import sys

CONFIG = Path('/etc/apogee/deployment.json')
PASSWORD_DIR = Path('/run/apogee-db')
CA = '/etc/apogee/rds-ca.pem'


def secret(arn, region):
    result = subprocess.run(
        ['aws', 'secretsmanager', 'get-secret-value', '--region', region,
         '--secret-id', arn, '--query', 'SecretString', '--output', 'text'],
        check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)


def initialize(config, application):
    admin = secret(config['adminSecretArn'], config['region'])
    environment = os.environ.copy()
    environment.update({
        'PGHOST': config['databaseEndpoint'], 'PGPORT': '5432', 'PGDATABASE': 'apogee',
        'PGUSER': admin['username'], 'PGPASSWORD': admin['password'],
        'PGSSLMODE': 'verify-full', 'PGSSLROOTCERT': CA,
        'PGCONNECT_TIMEOUT': '10', 'APOGEE_DB_PASSWORD': application['password'],
    })
    sql = r"""
\getenv app_password APOGEE_DB_PASSWORD
SELECT 'CREATE ROLE apogee_app LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'apogee_app') \gexec
ALTER ROLE apogee_app PASSWORD :'app_password';
GRANT CONNECT ON DATABASE apogee TO apogee_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO apogee_app;
"""
    subprocess.run(['psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-f', '-'],
                   input=sql, env=environment, text=True, capture_output=True, check=True)


def write_password(password):
    if not password or '\n' in password or '\r' in password:
        raise ValueError('Invalid password format')
    account = pwd.getpwnam('apogee')
    PASSWORD_DIR.mkdir(mode=0o750, parents=True, exist_ok=True)
    # systemd uses UMask=0077, so explicitly restore group traversal for the JVM.
    os.chmod(PASSWORD_DIR, 0o750)
    os.chown(PASSWORD_DIR, 0, account.pw_gid)
    temporary = PASSWORD_DIR / '.password.tmp'
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o640)
    with os.fdopen(fd, 'w') as output:
        output.write(password)
    os.chmod(temporary, 0o640)
    os.chown(temporary, 0, account.pw_gid)
    temporary.replace(PASSWORD_DIR / 'spring.datasource.password')


def main():
    if os.geteuid() != 0:
        raise RuntimeError('Run through the root deployment service')
    config = json.loads(CONFIG.read_text())
    application = secret(config['applicationSecretArn'], config['region'])
    if application['username'] != 'apogee_app':
        raise ValueError('Unexpected database role')
    if '--initialize' in sys.argv:
        initialize(config, application)
    write_password(application['password'])


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # AWS/psql errors may include sensitive values. Do not forward captured output.
        print('Database configuration failed. Check IAM, secret access and RDS connectivity.', file=sys.stderr)
        sys.exit(1)
