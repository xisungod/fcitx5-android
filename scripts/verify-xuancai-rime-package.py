#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def require(condition, message):
 if not condition:
  raise SystemExit(message)


def manifest_tree(dump):
 """Read aapt's XML tree, retaining typed booleans instead of guessing from text."""
 roots=[]
 stack=[]
 for line in dump.splitlines():
  stripped=line.lstrip()
  indent=len(line)-len(stripped)
  if stripped.startswith('E: '):
   while stack and stack[-1][0]>=indent:
    stack.pop()
   node={'name':stripped[3:].split()[0], 'attrs':{}, 'children':[]}
   (stack[-1][1]['children'] if stack else roots).append(node)
   stack.append((indent,node))
  elif stripped.startswith('A: ') and stack:
   attribute=re.match(r'A: ([^=(\s]+)(?:\([^)]*\))?=(.*)$',stripped)
   if not attribute:
    continue
   value=attribute[2]
   quoted=re.match(r'"([^"\n]*)"(?:\s|$)',value)
   typed=re.fullmatch(r'\(type 0x([0-9a-fA-F]+)\)0x([0-9a-fA-F]+)',value)
   if quoted:
    parsed=quoted[1]
   elif typed:
    kind=int(typed[1],16)
    number=int(typed[2],16)
    parsed=bool(number) if kind==0x12 else number
   else:
    # Resource references are useful evidence, but never accepted as boolean flags.
    parsed=value
   stack[-1][1]['attrs'][attribute[1]]=parsed
 return roots


def descendants(node, name):
 for child in node['children']:
  if child['name']==name:
   yield child
  yield from descendants(child,name)


def inspect_apk(aapt, path, expected_label, expected_version_name='1.0', offline_dictation=False):
 badging=subprocess.check_output([aapt,'dump','badging',str(path)],text=True)
 package=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging,re.MULTILINE)
 require(package is not None,f'{path}: aapt did not report package/version fields')
 require(package[2]=='822',f'{path}: expected arm64 versionCode 822, got {package[2]}')
 require(package[3]==expected_version_name,f'{path}: expected versionName {expected_version_name}, got {package[3]}')
 labels=re.findall(r"^application-label(?:-[^:]+)?:'([^']*)'$",badging,re.MULTILINE)
 labels+=re.findall(r"^application: label='([^']*)'",badging,re.MULTILINE)
 require(labels and set(labels)=={expected_label},f'{path}: application labels {sorted(set(labels))} do not match {expected_label}')
 native=re.search(r'^native-code:\s*(.*)$',badging,re.MULTILINE)
 abis=re.findall(r"'([^']+)'",native[1]) if native else []
 require(abis==['arm64-v8a'],f'{path}: expected only arm64-v8a, got {abis}')
 xml=subprocess.check_output([aapt,'dump','xmltree',str(path),'AndroidManifest.xml'],text=True)
 roots=manifest_tree(xml)
 manifests=[node for node in roots if node['name']=='manifest']
 require(len(manifests)==1,f'{path}: expected one AndroidManifest root')
 require(manifests[0]['attrs'].get('package')==package[1],f'{path}: binary manifest package disagrees with badging')
 permissions={node['attrs'].get('android:name','') for node in manifests[0]['children'] if node['name'].startswith('uses-permission')}
 require('android.permission.INTERNET' not in permissions,f'{path}: online permission is forbidden')
 if offline_dictation:
  require('android.permission.RECORD_AUDIO' in permissions,f'{path}: local dictation must declare microphone permission')
 else:
  require('android.permission.RECORD_AUDIO' not in permissions,f'{path}: microphone permission is forbidden without local dictation')
 named_elements={node['attrs'].get('android:name','') for name in ['action','service'] for node in descendants(manifests[0],name)}
 require('android.speech.RecognitionService' not in named_elements,f'{path}: speech recognition service declarations and queries are forbidden')
 voice_activities=[node for node in descendants(manifests[0],'activity') if node['attrs'].get('android:name','').endswith('.OfflineVoicePermissionActivity')]
 require(not voice_activities,f'{path}: the removed voice permission Activity is still declared')
 return package[1],manifests[0]


def signer_certificates(apksigner, apk):
 output=subprocess.check_output([apksigner,'verify','--print-certs',str(apk)],text=True)
 digests=set(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',output,re.MULTILINE))
 require(digests,f'{apk}: apksigner did not report a verified signing certificate')
 return {digest.lower() for digest in digests}


def resolve_android_tools(aapt_override, apksigner_override=None):
 sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
 sdk_aapt=Path(sdk)/'build-tools/36.1.0/aapt' if sdk else None
 aapt=aapt_override or (str(sdk_aapt) if sdk_aapt and sdk_aapt.is_file() else shutil.which('aapt'))
 require(aapt is not None,'Host verification requires --aapt or Android SDK build-tools 36.1.0')
 aapt_path=Path(shutil.which(aapt) or aapt).resolve()
 nearby_signer=aapt_path.with_name('apksigner')
 apksigner=apksigner_override or (str(nearby_signer) if nearby_signer.is_file() else shutil.which('apksigner'))
 require(apksigner is not None,'Signature verification requires --apksigner or apksigner beside aapt')
 return aapt,apksigner


def verify_main_identity(main_package, main_manifest, expected_main_package):
 base='org.fcitx.fcitx5.android'
 allowed_hosts=[expected_main_package] if expected_main_package else [base+'.axiang.v1',base+'.axiang']
 require(main_package in allowed_hosts,f'Unexpected 阿翔输入法 host package {main_package}; expected {allowed_hosts}')
 require(main_package==base+'.axiang' or main_package.startswith(base+'.axiang.'),f'Host package is outside the 阿翔输入法 namespace: {main_package}')
 applications=[node for node in main_manifest['children'] if node['name']=='application']
 require(len(applications)==1,'Main APK must have one application element')
 require(applications[0]['attrs'].get('android:allowBackup') is False,'Main APK must explicitly disable cloud backup')


def verify_bundled_host(main_apk, aapt_override, apksigner_override, expected_main_package, expected_version_name, offline_dictation=False):
 aapt,apksigner=resolve_android_tools(aapt_override,apksigner_override)
 main_package,main_manifest=inspect_apk(aapt,main_apk,'阿翔输入法',expected_version_name,offline_dictation)
 verify_main_identity(main_package,main_manifest,expected_main_package)
 signers=signer_certificates(apksigner,main_apk)
 microphone='explicit microphone permission for local dictation' if offline_dictation else 'no microphone permission'
 print(f'Single APK host verified: {main_package}; label, version {expected_version_name}, versionCode 822, arm64-v8a, no network permission or speech service, {microphone}, cloud backup disabled.')
 print('APK signing certificate SHA-256: '+', '.join(sorted(signers)))


def verify_offline_dictation(apk):
 provenance=json.loads(apk.read('assets/asr/SOURCE.json'))
 require(provenance.get('sherpa_onnx_version')=='1.13.8','Unexpected local speech runtime version')
 require(provenance.get('provider')=='cpu','This build must identify its actually bundled CPU provider')
 require(provenance.get('network_at_runtime') is False,'Local speech provenance must forbid runtime networking')
 require(provenance.get('abi')=='arm64-v8a','Local speech runtime ABI mismatch')
 required={
  'lib/arm64-v8a/libonnxruntime.so',
  'lib/arm64-v8a/libsherpa-onnx-jni.so',
  'assets/asr/zh-14m/encoder.int8.onnx',
  'assets/asr/zh-14m/decoder.onnx',
  'assets/asr/zh-14m/joiner.int8.onnx',
  'assets/asr/zh-14m/tokens.txt',
  'assets/asr/punctuation/model.int8.onnx',
  'assets/asr/licenses/NOTICE.txt',
  'assets/asr/licenses/sherpa-onnx-LICENSE.txt',
  'assets/asr/licenses/onnxruntime-LICENSE.txt',
  'assets/asr/licenses/onnxruntime-ThirdPartyNotices.txt',
  'assets/asr/licenses/espeak-ng-LICENSE.txt',
  'assets/asr/licenses/zh-14m-model-card.md',
  'assets/asr/licenses/punctuation-model-card.md',
 }
 hashes=provenance.get('apk_files_sha256',{})
 require(required <= hashes.keys(),'Speech model, runtime, or license provenance is incomplete')
 for path,digest in hashes.items():
  require(path in apk.namelist(),'Missing bundled speech asset: '+path)
  data=apk.read(path)
  require(hashlib.sha256(data).hexdigest()==digest,'Bundled speech file checksum mismatch: '+path)
  if path.endswith('.so'):
   require(data[:6]==b'\x7fELF\x02\x01' and int.from_bytes(data[18:20],'little')==183,'Speech runtime must be ELF64 AArch64: '+path)
 descriptor=json.loads(apk.read('assets/descriptor.json'))
 require(not any(path.startswith('asr/') and digest for path,digest in descriptor['files'].items()),
         'Speech model assets must not be duplicated into Fcitx data storage')
 print(f'Offline speech CPU runtime, Chinese streaming ASR, local punctuation and {len(hashes)} source/license checksums verified.')


def verify_host(main_apk, rime_apk, aapt_override, apksigner_override=None, expected_main_package=None, expected_version_name='1.0'):
 aapt,apksigner=resolve_android_tools(aapt_override,apksigner_override)
 main_package,main_manifest=inspect_apk(aapt,main_apk,'阿翔输入法',expected_version_name)
 plugin_package,manifest=inspect_apk(aapt,rime_apk,'阿翔输入法 Rime',expected_version_name)
 verify_main_identity(main_package,main_manifest,expected_main_package)
 base='org.fcitx.fcitx5.android'
 expected_plugin=base+'.plugin.rime'+main_package[len(base):]
 require(plugin_package==expected_plugin,f'Rime package {plugin_package} does not match host {main_package}; expected {expected_plugin}')
 queries=[node for node in manifest['children'] if node['name']=='queries']
 host_queries={node['attrs'].get('android:name','') for query in queries for node in descendants(query,'package') if node['attrs'].get('android:name','').startswith(base)}
 require(host_queries=={main_package},f'Rime host package queries {sorted(host_queries)} do not match {main_package}')
 metadata={node['attrs'].get('android:name','') for node in descendants(manifest,'meta-data') if node['attrs'].get('android:name','').endswith('.plugin.METADATA')}
 actions={node['attrs'].get('android:name','') for node in descendants(manifest,'action') if node['attrs'].get('android:name','').endswith('.plugin.MANIFEST')}
 require(metadata=={main_package+'.plugin.METADATA'},f'Rime metadata host mismatch: {sorted(metadata)}')
 require(actions=={main_package+'.plugin.MANIFEST'},f'Rime manifest intent host mismatch: {sorted(actions)}')
 main_signers=signer_certificates(apksigner,main_apk)
 plugin_signers=signer_certificates(apksigner,rime_apk)
 require(main_signers==plugin_signers,'Host and Rime APK signing certificates differ')
 print(f'Actual APK host verified: {main_package} / {plugin_package}; labels, no network/audio permissions or speech service, backup policy and plugin pairing match; version {expected_version_name}, versionCode 822, arm64-v8a.')
 print('Paired APK signing certificate SHA-256: '+', '.join(sorted(main_signers)))


def verify_bundled_assets(apk):
 names=set(apk.namelist())
 native_path='lib/arm64-v8a/librime.so'
 require(native_path in names,'Bundled APK is missing the native Rime engine')
 with apk.open(native_path) as native:
  elf=native.read(20)
 require(elf[:6]==b'\x7fELF\x02\x01' and int.from_bytes(elf[18:20],'little')==183,'Bundled Rime library is not a 64-bit ARM ELF')
 required=[
  'usr/share/fcitx5/addon/rime.conf',
  'usr/share/fcitx5/inputmethod/rime.conf',
  'usr/share/rime-data/build/rime_ice.table.bin',
  'usr/share/rime-data/build/rime_ice.prism.bin',
  'usr/share/rime-data/rime_ice_t9.schema.yaml',
  'usr/share/rime-data/xuancai_user_t9.schema.yaml',
  'usr/share/rime-data/build/rime_ice_t9.schema.yaml',
  'usr/share/rime-data/build/rime_ice_t9.prism.bin',
  'usr/share/rime-data/build/xuancai_user_t9.schema.yaml',
  'usr/share/rime-data/build/xuancai_user_t9.prism.bin',
  'usr/share/rime-data/build/xuancai_user.prism.bin',
  'usr/share/opencc/s2t.json',
  'usr/share/opencc/t2s.json',
  'usr/share/opencc/STCharacters.ocd2',
  'usr/share/opencc/TSCharacters.ocd2',
  'usr/share/licenses/rime-ice/LICENSE',
  'usr/share/licenses/rime-ice/README.md',
  'usr/share/licenses/rime-ice/SOURCE.json',
  'usr/share/licenses/rime-grammar/LICENSE',
  'usr/share/licenses/rime-grammar/SOURCE.json',
 ]
 required += ['usr/share/licenses/rime-builtin/'+name for name in [
  'SOURCE.json','librime-LICENSE.txt','librime-lua-LICENSE.txt',
  'librime-octagram-LICENSE.txt','darts-clone-LICENSE.txt','fcitx5-rime-LICENSE.txt',
  'rime-essay-LICENSE.txt','rime-prelude-LICENSE.txt','rime-luna-pinyin-LICENSE.txt','rime-stroke-LICENSE.txt',
 ]]
 for path in required:
  require('assets/'+path in names,'Bundled Rime asset missing: '+path)
 provenance=json.loads(apk.read('assets/usr/share/licenses/rime-builtin/SOURCE.json'))
 require(provenance.get('engine')=='1.12.0','Bundled Rime provenance engine version is incorrect')
 require({'librime','librime-lua','librime-octagram'} <= provenance.get('sources',{}).keys(),'Bundled Rime source provenance is incomplete')
 addon=apk.read('assets/usr/share/fcitx5/addon/rime.conf').decode()
 inputmethod=apk.read('assets/usr/share/fcitx5/inputmethod/rime.conf').decode()
 require(re.search(r'^Library=export:librime\s*$',addon,re.MULTILINE),'Rime addon does not load the bundled native library')
 require(re.search(r'^Addon=rime\s*$',inputmethod,re.MULTILINE),'Rime input method does not reference the bundled addon')
 descriptor=json.loads(apk.read('assets/descriptor.json'))
 files=descriptor['files']
 symlinks=descriptor.get('symlinks',{})
 require(symlinks.get('usr/share/rime-data/opencc')=='usr/share/opencc','Bundled Rime OpenCC descriptor symlink is missing or incorrect')
 require(not (files.keys() & symlinks.keys()),'Descriptor has conflicting file and symlink paths')
 combined=dict(files)
 combined.update(symlinks)
 digest=hashlib.sha256(', '.join(path+value for path,value in combined.items()).encode()).hexdigest()
 require(digest==descriptor['sha256'],'Asset descriptor aggregate hash is invalid')
 prefixes=('usr/share/rime-data/','usr/share/opencc/','usr/share/licenses/rime-ice/','usr/share/licenses/rime-grammar/','usr/share/licenses/rime-builtin/')
 covered={name.removeprefix('assets/') for name in names if name.startswith('assets/') and not name.endswith('/') and name.removeprefix('assets/').startswith(prefixes)} | set(required)
 for path in covered:
  require(path in files and files[path], 'Bundled Rime file is absent from the asset descriptor: '+path)
  require(hashlib.sha256(apk.read('assets/'+path)).hexdigest()==files[path],'Bundled Rime descriptor hash mismatch: '+path)
 for path,digest in files.items():
  if digest and path.startswith(prefixes):
   require('assets/'+path in names,'Descriptor references a missing bundled Rime file: '+path)
 print(f'Bundled native Rime, addon registration, OpenCC link, licenses and {len(covered)} descriptor file hashes verified.')


parser=argparse.ArgumentParser(description='Verify packaged Rime data and optionally its actual APK host manifest.')
parser.add_argument('rime_apk',type=Path,help='APK containing Rime data; with --bundled-rime this is the single main APK')
parser.add_argument('main_apk',type=Path,nargs='?',help='Main APK; when supplied, verify actual host package/manifest, ABI and version')
parser.add_argument('--aapt',help='Android SDK aapt executable for binary manifest inspection')
parser.add_argument('--apksigner',help='Android SDK apksigner executable; defaults to the binary beside aapt')
parser.add_argument('--expected-main-package',help='Exact expected host application ID for an isolated development build')
parser.add_argument('--expected-version-name',default='1.0',help='Exact expected Android versionName (default: 1.0)')
parser.add_argument('--bundled-rime',action='store_true',help='Verify native Rime and all data directly inside the single main APK')
parser.add_argument('--offline-dictation',action='store_true',help='Require the explicitly enabled local microphone feature; network/system recognition remains forbidden')
args=parser.parse_args()
require(not (args.bundled_rime and args.main_apk),'--bundled-rime accepts one main APK, not a separate Rime/main pair')
require(not args.offline_dictation or args.bundled_rime,'--offline-dictation requires the single bundled main APK')
if args.bundled_rime:
 verify_bundled_host(args.rime_apk,args.aapt,args.apksigner,args.expected_main_package,args.expected_version_name,args.offline_dictation)
elif args.main_apk:
 verify_host(args.main_apk,args.rime_apk,args.aapt,args.apksigner,args.expected_main_package,args.expected_version_name)

with zipfile.ZipFile(args.rime_apk) as apk:
 assert apk.testzip() is None
 if args.bundled_rime:
  verify_bundled_assets(apk)
 if args.offline_dictation:
  verify_offline_dictation(apk)
 manifest=json.loads(apk.read('assets/usr/share/licenses/rime-ice/SOURCE.json'))
 for name,digest in manifest['files_sha256'].items():
  if name in ['LICENSE','README.md']:path='licenses/rime-ice/'+name
  elif name.startswith('grammar-license/'):path='licenses/rime-grammar/'+name.removeprefix('grammar-license/')
  elif name.startswith('opencc/'):path=name
  else:path='rime-data/'+name
  assert hashlib.sha256(apk.read('assets/usr/share/'+path)).hexdigest()==digest,name
 base='assets/usr/share/rime-data/build/'
 prebuilt=json.loads(apk.read(base+'PREBUILT.json'))
 assert prebuilt['engine']=='1.12.0'
 for name,digest in prebuilt['files_sha256'].items():assert hashlib.sha256(apk.read(base+name)).hexdigest()==digest,name
 schema=apk.read('assets/usr/share/rime-data/rime_ice.schema.yaml').decode()
 assert 'lua_translator@*date_translator' in schema and 'lua_translator@*xuancai_correction' in schema
 assert 'lua_processor@*xuancai_ascii' in schema
 assert 'script_translator@xuancai_user' in schema
 assert '- xuancai_user' in schema
 assert 'derive/^([a-z]*[aeio])ng$/$1g/' in schema
 assert 'language: zh-hans-t-essay-bgw-compact' in schema
 dictionary=apk.read('assets/usr/share/rime-data/rime_ice.dict.yaml').decode()
 table_lines=[line.split('#',1)[0].strip() for line in dictionary.splitlines()]
 require('- cn_dicts/41448' in table_lines,'Extended character table was not enabled')
 require(table_lines.index('- cn_dicts/8105') < table_lines.index('- cn_dicts/41448'),'Rare characters precede the common-character table')
 require('- cn_dicts/xuancai_mobile' in table_lines,'Mobile vocabulary was not enabled')
 for name in ['xuancai_user.table.bin','xuancai_user.prism.bin','xuancai_user.schema.yaml']:
  require(name in prebuilt['files_sha256'],'Personal dictionary prebuilt file missing: '+name)
 for name in ['rime_ice_t9.prism.bin','rime_ice_t9.schema.yaml','xuancai_user_t9.prism.bin','xuancai_user_t9.schema.yaml']:
  require(name in prebuilt['files_sha256'],'Nine-key prebuilt file missing: '+name)
 t9=apk.read('assets/usr/share/rime-data/rime_ice_t9.schema.yaml').decode()
 require('xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/' in t9,'Nine-key Pinyin mapping missing')
 require('prism: rime_ice_t9' in t9 and 'user_dict: rime_ice' in t9,'Nine-key main dictionary configuration is incorrect')
 require('prism: xuancai_user_t9' in t9,'Nine-key personal dictionary configuration is incorrect')
 compiled_t9=apk.read(base+'rime_ice_t9.schema.yaml').decode()
 require(re.search(r'^  dictionary: rime_ice\s*$',compiled_t9,re.MULTILINE),'Nine-key schema must use the existing main Chinese table')
 require('rime_ice_t9.table.bin' not in prebuilt['files_sha256'],'Nine-key unnecessarily duplicates the main Chinese table')
 default=apk.read(base+'default.yaml').decode()
 require(default.index('schema: rime_ice\n') < default.index('schema: rime_ice_t9\n'),'Alphabetic Pinyin must remain the first/default schema')
 personal=apk.read('assets/usr/share/rime-data/xuancai_user.dict.yaml').decode()
 require('name: xuancai_user' in personal and '- cn_dicts/xuancai_mobile' in personal,'Empty personal dictionary is not deployable')
 for name in ['cn_dicts/Unicode-LICENSE.txt','cn_dicts/PinyinData-LICENSE.txt','xuancai_user.schema.yaml','xuancai_user.dict.yaml','rime_ice_t9.schema.yaml','xuancai_user_t9.schema.yaml']:
  require(name in manifest['files_sha256'],'New dictionary provenance file missing: '+name)
 require(manifest['raw_dictionary_rows']['cn_dicts/41448']>=40000,'Extended character data is incomplete')
 require(manifest['raw_dictionary_rows']['cn_dicts/xuancai_mobile']==21,'Mobile dictionary source count is unexpected')
 print('Packaged full Lua, grammar, all dictionary sources, rare-character/mobile extensions, personal dictionary, nine-key schemas and precompiled tables verified.')
