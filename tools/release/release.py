"""Sign a Daybud release using an external private configuration; never overwrite.

The config holds old/new Keystore, Alias, StorePassword, KeyPassword fields,
lineage (path) and newCertSha256. See docs/release-signing.md. No key generation
or network publication occurs here. Android 26/27 intentionally retain old v2.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
OLD_CERT = '55384b8e26f776f9389df25721a3cd955752a03bc7cdf8e94c0f249829dfa16c'
RELEASE_CERT = '04d67f1f2d46129cd3ec33ce26eabf53b86e4149dc870dd13ab081a78a5cbb8a'


def require_external(path):
    path = Path(path).resolve()
    if path.is_relative_to(ROOT):
        raise ValueError('Private configuration and keys must be outside the repository')
    return path


def require_new_output(path):
    if Path(path).exists():
        raise ValueError('Output already exists; refuse to replace a release artifact')


def validate_badging(text, version, code):
    text = '\n'.join(text.splitlines())
    packages = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'(?: .*)?$", text, re.MULTILINE)
    if packages != [('com.example.lifemanager', str(code), version)]:
        raise ValueError('Unexpected package or version')
    if 'application-debuggable' in text:
        raise ValueError('Release APK must not be debuggable')
    if re.findall(r"^sdkVersion:'([^']+)'$", text, re.MULTILINE) != ['26'] or re.findall(r"^targetSdkVersion:'([^']+)'$", text, re.MULTILINE) != ['35']:
        raise ValueError('Unexpected SDK range')


def validate_new_certificate(value):
    if not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Expected a lowercase full SHA256 certificate fingerprint')
    if value == OLD_CERT:
        raise ValueError('Production certificate must be distinct from the preview certificate')
    if value != RELEASE_CERT:
        raise ValueError('Unexpected production identity; a future rotation needs a separate reviewed policy change')


def run(arguments, env=None):
    result = subprocess.run([str(a) for a in arguments], capture_output=True, env=env)
    if result.returncode:
        # Signing tools may echo passwords/arguments on errors. No raw diagnostics.
        raise RuntimeError(f'{Path(arguments[0]).name} failed (exit {result.returncode}); output suppressed')
    return result.stdout.decode('utf-8', errors='replace')


def sign(args):
    config_path = require_external(args.config)
    config = json.loads(config_path.read_text(encoding='utf-8'))
    new_cert = config['newCertSha256']
    validate_new_certificate(new_cert)
    for prefix in ('old', 'new'):
        key = require_external(config[prefix + 'Keystore'])
        if not key.is_file():
            raise ValueError('Missing external keystore')
    lineage = require_external(config['lineage'])
    if not lineage.is_file():
        raise ValueError('Missing rotation lineage')
    output = Path(args.output).resolve()
    receipt = output.with_suffix('.signing.json')
    require_new_output(output)
    require_new_output(receipt)
    if output in (config_path, lineage, Path(config['oldKeystore']).resolve(), Path(config['newKeystore']).resolve()):
        raise ValueError('Output must not point to signing material')
    source = Path(args.input).resolve()
    if not source.is_file():
        raise ValueError('Input APK missing')
    sdk = Path(args.sdk).resolve()
    java = Path(args.java_home).resolve() / 'bin'
    env = dict(os.environ, JAVA_HOME=str(Path(args.java_home).resolve()))
    for prefix in ('old', 'new'):
        env['DAYBUD_' + prefix.upper() + '_STORE'] = config[prefix + 'StorePassword']
        env['DAYBUD_' + prefix.upper() + '_KEY'] = config[prefix + 'KeyPassword']
    validate_badging(run([sdk / 'aapt.exe', 'dump', 'badging', source], env), args.version, args.code)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='daybud-sign-') as directory:
        temporary = Path(directory)
        classes = temporary / 'classes'
        classes.mkdir()
        apksig = sdk / 'lib' / 'apksigner.jar'
        run([java / 'javac.exe', '-cp', apksig, '-d', classes, Path(__file__).with_name('VerifySignature.java')], env)
        verifier = [java / 'java.exe', '-cp', str(classes) + os.pathsep + str(apksig), 'VerifySignature']
        lineage_evidence = json.loads(run(verifier + ['lineage', lineage, OLD_CERT, new_cert], env))
        aligned = temporary / 'aligned.apk'
        signed = temporary / 'signed.apk'
        run([sdk / 'zipalign.exe', '-P', '16', '4', source, aligned], env)
        command = [sdk / 'apksigner.bat', 'sign', '--out', signed, '--lineage', lineage,
                   '--rotation-min-sdk-version', '28', '--v1-signing-enabled', 'false',
                   '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
                   '--v4-signing-enabled', 'false', '--debuggable-apk-permitted', 'false']
        for index, prefix in enumerate(('old', 'new')):
            if index:
                command.append('--next-signer')
            command.extend(['--ks', config[prefix + 'Keystore'], '--ks-key-alias', config[prefix + 'Alias'],
                            '--ks-pass', 'env:DAYBUD_' + prefix.upper() + '_STORE',
                            '--key-pass', 'env:DAYBUD_' + prefix.upper() + '_KEY'])
        run(command + [aligned], env)
        evidence = json.loads(run(verifier + ['apk', signed, OLD_CERT, new_cert], env))
        run([sdk / 'zipalign.exe', '-c', '-P', '16', '4', signed], env)
        validate_badging(run([sdk / 'aapt.exe', 'dump', 'badging', signed], env), args.version, args.code)
        data = signed.read_bytes()
        # Exclusive create is the final no-overwrite guard, including concurrent runs.
        with output.open('xb') as stream:
            stream.write(data)
        metadata = dict(name=output.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                        versionName=args.version, versionCode=args.code, debuggable=False,
                        zipalign16KiB=True, legacyCertificateSha256=OLD_CERT,
                        certificateSha256=new_cert, rotationMinSdk=28,
                        lineage=lineage_evidence, verification=evidence)
        with receipt.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(metadata, indent=2) + '\n')
    print(json.dumps(metadata))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('config', 'sdk', 'java-home', 'input', 'output', 'version'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--code', required=True, type=int)
    args = parser.parse_args()
    try:
        sign(args)
    except (ValueError, RuntimeError, KeyError, OSError) as error:
        # JSON/parser errors may include private config values: do not echo them.
        parser.exit(1, f'Release signing stopped ({type(error).__name__}); check configuration and required inputs.\n')


if __name__ == '__main__':
    main()
