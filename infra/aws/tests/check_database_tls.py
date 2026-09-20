#!/usr/bin/env python3
"""Real local PostgreSQL/JDBC verification of the AWS connection configuration. No AWS calls."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.request
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def check(jar):
    name = 'apogee-tls-check-' + secrets.token_hex(4)
    admin_password, app_password, root_password = (secrets.token_hex(20) for _ in range(3))
    process = None
    run = subprocess.run
    with tempfile.TemporaryDirectory(prefix='apogee-tls-') as folder:
        directory = Path(folder)
        certificates = directory / 'certs'
        certificates.mkdir()
        (certificates / 'openssl.cnf').write_text(
            '[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n'
            '[dn]\nCN=localhost\n[ext]\nsubjectAltName=DNS:localhost\nbasicConstraints=CA:TRUE\n')
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
             '-keyout', str(certificates / 'server.key'), '-out', str(certificates / 'server.crt'),
             '-config', str(certificates / 'openssl.cnf')], check=True, capture_output=True)
        try:
            environment = os.environ.copy()
            environment['POSTGRES_PASSWORD'] = root_password
            run(['docker', 'create', '--name', name, '-e', 'POSTGRES_PASSWORD',
                 '-p', '127.0.0.1::5432', '--entrypoint', 'sh', 'postgres:17-alpine', '-c',
                 'chown postgres:postgres /certs/server.key; chmod 600 /certs/server.key; '
                 'exec docker-entrypoint.sh postgres -c ssl=on '
                 '-c ssl_cert_file=/certs/server.crt -c ssl_key_file=/certs/server.key'],
                env=environment, check=True, capture_output=True)
            run(['docker', 'cp', str(certificates), name + ':/certs'], check=True, capture_output=True)
            run(['docker', 'start', name], check=True, capture_output=True)
            for _ in range(60):
                # The image briefly runs a Unix-socket-only server during initialization.
                ready = run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], capture_output=True)
                if ready.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise AssertionError('Temporary PostgreSQL did not become ready')
            setup = (f"CREATE ROLE apogee_admin LOGIN CREATEROLE CREATEDB PASSWORD '{admin_password}';\n"
                     'CREATE DATABASE apogee OWNER apogee_admin;\n')
            run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'],
                input=setup, text=True, check=True, capture_output=True)
            spec = importlib.util.spec_from_file_location('database', ROOT / 'infra/aws/runtime/database.py')
            database = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(database)

            def psql(command, **kwargs):
                assert command[0] == 'psql'
                env = kwargs.pop('env')
                env.update({'PGHOST': 'localhost', 'PGPORT': '5432', 'PGSSLROOTCERT': '/certs/server.crt'})
                forwarded = [item for key in env if key.startswith('PG') or key == 'APOGEE_DB_PASSWORD'
                             for item in ('-e', key)]
                return run(['docker', 'exec', '-i', *forwarded, name, *command], env=env, **kwargs)

            config = {'region': 'test', 'adminSecretArn': 'local-test', 'databaseEndpoint': 'localhost'}
            with patch.object(database, 'secret', return_value={'username': 'apogee_admin', 'password': admin_password}), \
                    patch.object(database.subprocess, 'run', side_effect=psql):
                database.initialize(config, {'password': app_password})
                database.initialize(config, {'password': app_password})  # repeatable update
            exposed = run(['docker', 'port', name, '5432/tcp'], check=True, capture_output=True, text=True)
            port = int(exposed.stdout.strip().rsplit(':', 1)[1])
            password_dir = directory / 'password'
            password_dir.mkdir()
            (password_dir / 'spring.datasource.password').write_text(app_password)
            with socket.socket() as temporary_socket:
                temporary_socket.bind(('127.0.0.1', 0))
                http_port = temporary_socket.getsockname()[1]
            environment.update({
                'PORT': str(http_port), 'BIND_ADDRESS': '127.0.0.1', 'DATABASE_USER': 'apogee_app',
                'DATABASE_PASSWORD': 'deliberately-wrong-fallback-password',
                'DATABASE_URL': f'jdbc:postgresql://localhost:{port}/apogee?sslmode=verify-full&sslrootcert={certificates / "server.crt"}',
                'SPRING_CONFIG_IMPORT': f'configtree:{password_dir}/', 'SIMULATOR_PORT': '1',
            })
            environment.pop('SPRING_DATASOURCE_PASSWORD', None)
            with (directory / 'ground.log').open('w') as log:
                process = subprocess.Popen(['java', '-jar', str(jar.resolve())], env=environment,
                                           stdout=log, stderr=subprocess.STDOUT)
                for _ in range(90):
                    if process.poll() is not None:
                        raise AssertionError('Ground service failed with verified TLS and config-tree credentials')
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{http_port}/api/health', timeout=1) as response:
                            if json.load(response)['status'] == 'UP':
                                break
                    except OSError:
                        pass
                    time.sleep(0.5)
                else:
                    raise AssertionError('Ground service readiness timeout')
                query = "SELECT count(*) FROM pg_stat_ssl s JOIN pg_stat_activity a USING (pid) WHERE a.usename='apogee_app' AND s.ssl;"
                result = run(['docker', 'exec', name, 'psql', '-U', 'postgres', '-tAc', query],
                             check=True, capture_output=True, text=True)
                assert int(result.stdout.strip()) > 0, 'JDBC connection was not encrypted'
                roles = run(['docker', 'exec', name, 'psql', '-U', 'postgres', '-tAc',
                             "SELECT rolsuper OR rolcreaterole OR rolcreatedb FROM pg_roles WHERE rolname='apogee_app';"],
                            check=True, capture_output=True, text=True)
                assert roles.stdout.strip() == 'f', 'Application role has administrative privileges'
                # A connection to the wrong hostname must fail even with the trusted certificate.
                negative_env = os.environ.copy()
                negative_env.update({'PGPASSWORD': app_password, 'PGSSLMODE': 'verify-full',
                                     'PGSSLROOTCERT': '/certs/server.crt'})
                negative = run(['docker', 'exec', '-e', 'PGPASSWORD', '-e', 'PGSSLMODE', '-e', 'PGSSLROOTCERT',
                                name, 'psql', '-h', '127.0.0.1', '-U', 'apogee_app', '-d', 'apogee', '-c', 'SELECT 1'],
                               env=negative_env, capture_output=True, text=True)
                assert negative.returncode != 0 and 'certificate' in negative.stderr, 'Hostname mismatch was not rejected'
            print('PASS: repeatable restricted-role setup, Flyway startup, JDBC TLS, config-tree password override, and hostname rejection')
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            run(['docker', 'rm', '-fv', name], capture_output=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/apogee-0.1.0.jar')
    check(parser.parse_args().jar)
