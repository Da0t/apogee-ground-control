"""Offline checks for the release boundary and secret handling; no AWS credentials needed."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[3]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


deploy = module('apogee_aws', ROOT / 'scripts/aws.py')
database = module('apogee_db', ROOT / 'infra/aws/runtime/database.py')


class DeploymentTests(unittest.TestCase):
    def test_package_is_offline_and_contains_only_release_files(self):
        with tempfile.TemporaryDirectory() as directory:
            jar, archive = Path(directory) / 'app.jar', Path(directory) / 'release.tar.gz'
            with zipfile.ZipFile(jar, 'w') as output:
                output.writestr('BOOT-INF/classes/dev/datnguyen/apogee/Application.class', b'class')
                output.writestr('BOOT-INF/classes/static/index.html', '<html></html>')
            with patch.object(subprocess, 'run', side_effect=AssertionError('External call during packaging')):
                digest = deploy.package(jar, archive)
            self.assertEqual(64, len(digest))
            with tarfile.open(archive) as output:
                self.assertEqual({'apogee.jar', 'install.sh', 'database.py', 'apogee-ground.service',
                                  'apogee-simulator.service'}, set(output.getnames()))

    def test_incomplete_jar_is_rejected_without_creating_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            jar, archive = Path(directory) / 'app.jar', Path(directory) / 'release.tar.gz'
            with zipfile.ZipFile(jar, 'w') as output:
                output.writestr('BOOT-INF/classes/dev/datnguyen/apogee/Application.class', b'class')
            with self.assertRaises(ValueError):
                deploy.package(jar, archive)
            self.assertFalse(archive.exists())

    def test_unexpected_stack_output_is_rejected(self):
        values = {'HostInstanceId': 'i-123abc', 'ArtifactBucket': 'valid-bucket',
                  'DatabaseEndpoint': 'db.example.rds.amazonaws.com; touch /tmp/unsafe',
                  'AdminSecretArn': 'arn:aws:secretsmanager:us-east-2:123456789012:secret:admin-x',
                  'ApplicationSecretArn': 'arn:aws:secretsmanager:us-east-2:123456789012:secret:app-x'}
        response = {'Stacks': [{'StackStatus': 'CREATE_COMPLETE', 'Outputs': [
            {'OutputKey': key, 'OutputValue': value} for key, value in values.items()]}]}
        with patch.object(deploy, 'aws', return_value=response), self.assertRaises(ValueError):
            deploy.stack_outputs('us-east-2', 'apogee')

    def test_initialize_uses_verified_tls_and_no_password_in_process_arguments(self):
        config = {'databaseEndpoint': 'db.example.rds.amazonaws.com', 'region': 'us-east-2',
                  'adminSecretArn': 'secret'}
        with patch.object(database, 'secret', return_value={'username': 'admin', 'password': 'admin-secret'}), \
                patch.object(database.subprocess, 'run') as run:
            database.initialize(config, {'password': 'application-secret'})
        call = run.call_args
        self.assertNotIn('admin-secret', ' '.join(call.args[0]))
        self.assertNotIn('application-secret', call.kwargs['input'])
        self.assertEqual('verify-full', call.kwargs['env']['PGSSLMODE'])
        self.assertTrue(call.kwargs['capture_output'])

    def test_password_file_is_atomically_replaced_and_not_world_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'credentials'
            with patch.object(database, 'PASSWORD_DIR', target), patch.object(database.os, 'chown'), \
                    patch.object(database.pwd, 'getpwnam', return_value=SimpleNamespace(pw_gid=os.getgid())):
                previous = os.umask(0o077)
                try:
                    database.write_password('first-secret')
                    database.write_password('new-secret')
                finally:
                    os.umask(previous)
            password = target / 'spring.datasource.password'
            self.assertEqual('new-secret', password.read_text())
            self.assertEqual(0o640, password.stat().st_mode & 0o777)
            self.assertEqual(0o750, target.stat().st_mode & 0o777)
            self.assertFalse((target / '.password.tmp').exists())

    def test_remote_document_has_checksum_check_before_install(self):
        config = {'region': 'us-east-2', 'databaseEndpoint': 'db.example.rds.amazonaws.com',
                  'adminSecretArn': 'admin', 'applicationSecretArn': 'app'}
        commands = deploy.remote_commands('example-bucket', 'a' * 64, config)
        script = '\n'.join(commands)
        self.assertLess(script.index('sha256sum --check'), script.index('tar --extract'))
        self.assertNotIn('get-secret-value', script)
        self.assertEqual(0, subprocess.run(['bash', '-n'], input=script, text=True).returncode)


if __name__ == '__main__':
    unittest.main()
