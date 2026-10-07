#!/usr/bin/env python3
"""Run real librime 1.16.1 read-only probe isolation with public APK fixtures.

The fixture preparation process alone owns Rime setup/deployment in a disposable
user directory. The production probe and isolation test never deploy or commit.
Linux timing is recorded separately and is not Android handset performance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import yaml


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def files(directory):
    return {str(p.relative_to(directory)): sha(p) for p in sorted(directory.rglob('*')) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    for name in ('rime-source', 'rime-build', 'dependencies', 'rime-data', 'component-headers', 'probe-provenance', 'work', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    source, build, deps = args.rime_source.resolve(), args.rime_build.resolve(), args.dependencies.resolve()
    shared, work = args.rime_data.resolve(), args.work.resolve()
    if work.exists():
        raise SystemExit('Use a new disposable work directory')
    work.mkdir(parents=True)
    provenance = json.loads(args.probe_provenance.read_text())
    assert provenance['runtime_version'] == '1.16.1'
    assert provenance['component_rtti_import']['all_required_imported_from_runtime']
    assert sha(args.component_headers / 'rime/component.h') == provenance['component_rtti_import']['bridge_header_sha256']
    assert sha(args.component_headers / 'rime/translator.h') == provenance['component_rtti_import']['translator_bridge_header_sha256']
    for name, expected in provenance['abi_header_sha256'].items():
        assert sha(source / 'src' / name) == expected, name
    original = files(shared)
    library = (build / 'lib/librime.so.1.16.1').resolve()
    assert library.is_file()
    env = dict(os.environ)
    env['LD_LIBRARY_PATH'] = ':'.join([str(build / 'lib'), str(deps / 'usr/lib/x86_64-linux-gnu')])
    flags = ['g++', '-std=c++17', '-O2', '-isystem', str(args.component_headers.resolve()),
             '-I', str(root / 'app/src/main/cpp/typing'), '-I', str(source / 'src'),
             '-I', str(build / 'src'), '-I', str(source / 'include'),
             '-I', str(deps / 'usr/include'), '-I', str(deps / 'usr/include/x86_64-linux-gnu')]
    units = {'prepare': ['scripts/prepare-rime-touch-fixture.cpp'],
             'check': ['scripts/check-rime-touch-probe.cpp', 'app/src/main/cpp/typing/rime-touch-probe.cpp'],
             'commit': ['scripts/check-rime-touch-commit.cpp', 'app/src/main/cpp/typing/rime-touch-probe.cpp'],
             'benchmark': ['scripts/benchmark-rime-touch-probe.cpp', 'app/src/main/cpp/typing/rime-touch-probe.cpp']}
    for name, sources in units.items():
        with (work / (name + '-build.log')).open('w') as log:
            subprocess.run(flags + [str(root / p) for p in sources] +
                           ['-L', str(build / 'lib'), '-lrime', '-o', str(work / name)],
                           check=True, stdout=log, stderr=log)
    user_setup = work / 'fixture-setup-user'
    user_setup.mkdir()
    with (work / 'fixture-setup.log').open('w') as log:
        subprocess.run([str(work / 'prepare'), str(shared), str(user_setup)], env=env,
                       check=True, stdout=log, stderr=log, timeout=180)
    assert files(shared) == original, 'Fixture setup changed the source APK assets'
    fixture = work / 'shared-fixture'
    shutil.copytree(shared, fixture)
    shutil.copyfile(root / 'scripts/rime/xuancai_correction.lua', fixture / 'lua/xuancai_correction.lua')
    upgraded = {}
    for name in ('rime_ice', 'melt_eng', 'xuancai_user', 'radical_pinyin'):
        generated = user_setup / 'build' / (name + '.prism.bin')
        assert generated.read_bytes().startswith(b'Rime::Prism/4.0'), name
        compiled_schema = yaml.safe_load((user_setup / 'build' / (name + '.schema.yaml')).read_text())
        packaged_schema = yaml.safe_load((shared / 'build' / (name + '.schema.yaml')).read_text())
        compiled_schema.pop('__build_info', None)
        packaged_schema.pop('__build_info', None)
        assert compiled_schema == packaged_schema, 'Fixture changed schema semantics: ' + name
        shutil.copyfile(generated, fixture / 'build' / generated.name)
        upgraded[generated.name] = {'source_packaged_sha256': original['build/' + generated.name],
                                   'generated_sha256': sha(generated)}
    evidence = {}
    for name in ('check', 'commit', 'benchmark'):
        user = work / (name + '-user')
        user.mkdir()
        with (work / (name + '.stderr')).open('w') as errors:
            result = subprocess.run([str(work / name), str(fixture), str(user), str(fixture / 'build')],
                                    env=env, check=True, capture_output=False, stdout=subprocess.PIPE,
                                    stderr=errors, text=True, timeout=180)
        evidence[name] = json.loads(result.stdout)
        (work / (name + '.json')).write_text(json.dumps(evidence[name], indent=2) + '\n')
        assert evidence[name]['engine'] == '1.16.1'
    check = evidence['check']
    checks = [k for k, value in check.items() if isinstance(value, bool)]
    assert all(check[k] for k in checks) and check['queries'] == 5 and check['returned_candidates'] > 0
    assert evidence['benchmark']['first_query_candidates'] > 0
    commit = evidence['commit']
    assert commit['partial_select_commits'] == 0 and commit['reset_replay_commits'] == 0
    assert commit['explicit_full_select_commits'] == 1 and commit['commit_notifications'] == 1
    assert commit['partial_restored_raw'] and commit['mode_options_properties_schema_unchanged']
    tracked = sorted(set(sum(units.values(), [])) | {
        'scripts/test-rime-touch-probe.py', 'app/src/main/cpp/typing/rime-touch-probe.h',
        'app/src/main/cpp/typing/rime-touch-jni.cpp', 'scripts/build-rime-touch-probe.py'})
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    clean = not subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip()
    summary = {'schema': 2, 'source_commit': head, 'source_working_tree_clean': clean,
               'tests': {'cases': 2, 'failures': 0, 'errors': 0, 'skipped': 0,
                         'suites': {'readonly_probe_isolation': {'cases': 1, 'failures': 0, 'errors': 0, 'skipped': 0},
                                    'explicit_probe_candidate_commit': {'cases': 1, 'failures': 0, 'errors': 0, 'skipped': 0}}},
               'host_rime': {'engine_version': '1.16.1', 'library_sha256': sha(library)},
               'test_sources_sha256': {p: sha(root / p) for p in tracked},
               'schemas_sha256': {p: sha(shared / 'build' / p) for p in ('rime_ice.schema.yaml', 'xuancai_user.schema.yaml')},
               'lua_source_sha256': sha(root / 'scripts/rime/xuancai_correction.lua'),
               'native_probe': {'library_sha256': provenance['native_sha256'],
                                'provenance_sha256': sha(args.probe_provenance), 'runtime_version': '1.16.1'},
               'fixture': {'source_assets_changed_by_setup': False, 'private_user_data_used': False,
                           'setup_deploy_only_in_disposable_user_directory': True,
                           'schema_semantics_unchanged': True, 'compiled_assets_only_prisms_overlaid': True,
                           'release_lua_source_overlaid': True,
                           'prism_upgrade': upgraded},
               'isolation': check, 'explicit_commit': commit, 'host_latency': evidence['benchmark'],
               'device_verification': False, 'phone_latency_verified': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'cases': 2, 'returned_candidates': check['returned_candidates'],
                      'engine': '1.16.1', 'source_clean': clean}))


if __name__ == '__main__':
    main()
