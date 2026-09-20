#!/usr/bin/env python3
"""Package locally; publish or connect only when explicitly selected. Never provisions a stack."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def package(jar, destination):
    jar = Path(jar).resolve()
    destination = Path(destination).resolve()
    with zipfile.ZipFile(jar) as archive:
        required = {'BOOT-INF/classes/dev/datnguyen/apogee/Application.class',
                    'BOOT-INF/classes/static/index.html'}
        if not required.issubset(archive.namelist()):
            raise ValueError('Build the complete application and console with scripts/build.sh first')
    if jar == destination:
        raise ValueError('Archive must not overwrite the application JAR')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, 'w:gz') as archive:
        archive.add(jar, arcname='apogee.jar')
        for name in ('install.sh', 'database.py', 'apogee-ground.service', 'apogee-simulator.service'):
            archive.add(ROOT / 'infra/aws/runtime' / name, arcname=name)
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def aws(region, *arguments):
    result = subprocess.run(['aws', '--region', region, '--no-cli-pager', *arguments],
                            check=True, capture_output=True, text=True)
    return json.loads(result.stdout) if result.stdout.strip() else {}


def stack_outputs(region, stack):
    response = aws(region, 'cloudformation', 'describe-stacks', '--stack-name', stack)
    record = response['Stacks'][0]
    if record['StackStatus'] not in ('CREATE_COMPLETE', 'UPDATE_COMPLETE', 'UPDATE_ROLLBACK_COMPLETE'):
        raise ValueError('Stack must have completed deployment before publishing')
    values = {entry['OutputKey']: entry['OutputValue'] for entry in record['Outputs']}
    for key, pattern in {
        'HostInstanceId': r'i-[a-f0-9]+',
        'ArtifactBucket': r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',
        'DatabaseEndpoint': r'[a-z0-9.-]+\.rds\.amazonaws\.com',
        'AdminSecretArn': r'arn:aws:secretsmanager:[a-z0-9-]+:\d{12}:secret:[a-zA-Z0-9!/_+=.@-]+',
        'ApplicationSecretArn': r'arn:aws:secretsmanager:[a-z0-9-]+:\d{12}:secret:[a-zA-Z0-9!/_+=.@-]+',
    }.items():
        if not re.fullmatch(pattern, values.get(key, '')):
            raise ValueError(f'Missing or unexpected stack output: {key}')
    return values


def remote_commands(bucket, digest, config):
    encoded = base64.b64encode(json.dumps(config).encode()).decode()
    directory = '/var/tmp/apogee-release-' + digest
    source = f's3://{bucket}/releases/{digest}.tar.gz'
    # The SSM document executes these lines in one shell; quote all data as shell arguments.
    return [
        'set -eu', 'umask 077',
        f'mkdir -p {shlex.quote(directory)}', f'cd {shlex.quote(directory)}',
        f'aws --region {shlex.quote(config["region"])} s3 cp {shlex.quote(source)} release.tar.gz --only-show-errors',
        f'echo {shlex.quote(digest + "  release.tar.gz")} | sha256sum --check --status',
        'tar --extract --gzip --file release.tar.gz --no-same-owner',
        f'printf %s {shlex.quote(encoded)} | base64 --decode > deployment.json',
        'bash install.sh',
    ]


def publish(args):
    values = stack_outputs(args.region, args.stack)
    config = {'region': args.region, 'databaseEndpoint': values['DatabaseEndpoint'],
              'adminSecretArn': values['AdminSecretArn'],
              'applicationSecretArn': values['ApplicationSecretArn']}
    with tempfile.TemporaryDirectory(prefix='apogee-release-') as temporary:
        archive = Path(temporary) / 'release.tar.gz'
        digest = package(args.jar, archive)
        subprocess.run(['aws', '--region', args.region, 's3', 'cp', str(archive),
                        f's3://{values["ArtifactBucket"]}/releases/{digest}.tar.gz',
                        '--only-show-errors'], check=True)
    response = aws(args.region, 'ssm', 'send-command', '--instance-ids', values['HostInstanceId'],
                   '--document-name', 'AWS-RunShellScript', '--comment', 'Install Apogee release',
                   '--parameters', json.dumps({'commands': remote_commands(values['ArtifactBucket'], digest, config),
                                               'executionTimeout': ['900']}))
    command_id = response['Command']['CommandId']
    print(f'SSM deployment command: {command_id}', flush=True)
    # Poll only this explicit deployment, bounded to 15 minutes.
    for _ in range(180):
        time.sleep(5)
        try:
            result = aws(args.region, 'ssm', 'get-command-invocation', '--command-id', command_id,
                         '--instance-id', values['HostInstanceId'])
        except subprocess.CalledProcessError:
            continue  # SSM invocation visibility is eventually consistent.
        if result['Status'] == 'Success':
            print('Deployment passed the live telemetry readiness check. Use the tunnel command to open it.')
            return
        if result['Status'] in ('Failed', 'Cancelled', 'TimedOut', 'Undeliverable', 'Terminated'):
            raise RuntimeError(f'Deployment {result["Status"]}; inspect SSM command {command_id}')
    raise RuntimeError(f'Timed out waiting for deployment; inspect SSM command {command_id} before retrying')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action')
    local = sub.add_parser('package', help='Create a local release archive; makes no AWS calls')
    local.add_argument('--jar', type=Path, default=ROOT / 'target/apogee-0.1.0.jar')
    local.add_argument('--output', type=Path, default=ROOT / 'target/apogee-aws.tar.gz')
    remote = sub.add_parser('publish', help='Upload and install on an EXISTING stack; changes the remote app')
    remote.add_argument('--jar', type=Path, default=ROOT / 'target/apogee-0.1.0.jar')
    tunnel = sub.add_parser('tunnel', help='Open an authenticated SSM tunnel; requires session-manager-plugin')
    tunnel.add_argument('--port', type=int, default=8082, choices=range(1024, 65536), metavar='PORT')
    for command in (remote, tunnel):
        command.add_argument('--stack', required=True)
        command.add_argument('--region', required=True)
    args = parser.parse_args()
    if not args.action:
        parser.print_help()
        return
    if args.action == 'package':
        digest = package(args.jar, args.output)
        print(f'Created {args.output}\nSHA-256: {digest}\nNo AWS calls were made.')
    elif args.action == 'publish':
        publish(args)
    else:
        values = stack_outputs(args.region, args.stack)
        print(f'Open http://127.0.0.1:{args.port} once the tunnel connects.', flush=True)
        subprocess.run(['aws', '--region', args.region, 'ssm', 'start-session',
                        '--target', values['HostInstanceId'], '--document-name', 'AWS-StartPortForwardingSession',
                        '--parameters', json.dumps({'portNumber': ['8081'], 'localPortNumber': [str(args.port)]})],
                       check=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        # Do not echo captured AWS responses or credentials on failure.
        raise SystemExit(str(error))
