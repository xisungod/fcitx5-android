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


def inspect_apk(aapt, path, expected_label):
 badging=subprocess.check_output([aapt,'dump','badging',str(path)],text=True)
 package=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging,re.MULTILINE)
 require(package is not None,f'{path}: aapt did not report package/version fields')
 require(package[2]=='822',f'{path}: expected arm64 versionCode 822, got {package[2]}')
 require(package[3]=='1.0',f'{path}: expected versionName 1.0, got {package[3]}')
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
 return package[1],manifests[0]


def signer_certificates(apksigner, apk):
 output=subprocess.check_output([apksigner,'verify','--print-certs',str(apk)],text=True)
 digests=set(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',output,re.MULTILINE))
 require(digests,f'{apk}: apksigner did not report a verified signing certificate')
 return {digest.lower() for digest in digests}


def verify_host(main_apk, rime_apk, aapt_override, apksigner_override=None):
 sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
 sdk_aapt=Path(sdk)/'build-tools/36.1.0/aapt' if sdk else None
 aapt=aapt_override or (str(sdk_aapt) if sdk_aapt and sdk_aapt.is_file() else shutil.which('aapt'))
 require(aapt is not None,'Host verification requires --aapt or Android SDK build-tools 36.1.0')
 main_package,main_manifest=inspect_apk(aapt,main_apk,'阿翔输入法')
 plugin_package,manifest=inspect_apk(aapt,rime_apk,'阿翔输入法 Rime')
 base='org.fcitx.fcitx5.android'
 require(main_package in [base+'.axiang.v1',base+'.axiang'],f'Unexpected 阿翔输入法 1.0 host package {main_package}')
 expected_plugin=base+'.plugin.rime'+main_package[len(base):]
 require(plugin_package==expected_plugin,f'Rime package {plugin_package} does not match host {main_package}; expected {expected_plugin}')
 queries=[node for node in manifest['children'] if node['name']=='queries']
 host_queries={node['attrs'].get('android:name','') for query in queries for node in descendants(query,'package') if node['attrs'].get('android:name','').startswith(base)}
 require(host_queries=={main_package},f'Rime host package queries {sorted(host_queries)} do not match {main_package}')
 metadata={node['attrs'].get('android:name','') for node in descendants(manifest,'meta-data') if node['attrs'].get('android:name','').endswith('.plugin.METADATA')}
 actions={node['attrs'].get('android:name','') for node in descendants(manifest,'action') if node['attrs'].get('android:name','').endswith('.plugin.MANIFEST')}
 require(metadata=={main_package+'.plugin.METADATA'},f'Rime metadata host mismatch: {sorted(metadata)}')
 require(actions=={main_package+'.plugin.MANIFEST'},f'Rime manifest intent host mismatch: {sorted(actions)}')
 permissions={node['attrs'].get('android:name','') for node in main_manifest['children'] if node['name'].startswith('uses-permission')}
 require('android.permission.RECORD_AUDIO' in permissions,'Main APK must declare explicit microphone permission')
 applications=[node for node in main_manifest['children'] if node['name']=='application']
 require(len(applications)==1,'Main APK must have one application element')
 require(applications[0]['attrs'].get('android:allowBackup') is False,'Main APK must explicitly disable cloud backup')
 main_queries=[node for node in main_manifest['children'] if node['name']=='queries']
 query_actions={node['attrs'].get('android:name','') for query in main_queries for node in descendants(query,'action')}
 require('android.speech.RecognitionService' in query_actions,'Main APK is missing the system speech service query')
 permission_activities=[node for node in descendants(applications[0],'activity') if node['attrs'].get('android:name')==base+'.ui.main.OfflineVoicePermissionActivity']
 require(len(permission_activities)==1,'Main APK must declare one offline microphone permission Activity')
 require(permission_activities[0]['attrs'].get('android:exported') is False,'Microphone permission Activity must not be exported')
 aapt_path=Path(shutil.which(aapt) or aapt).resolve()
 nearby_signer=aapt_path.with_name('apksigner')
 apksigner=apksigner_override or (str(nearby_signer) if nearby_signer.is_file() else shutil.which('apksigner'))
 require(apksigner is not None,'Signature verification requires --apksigner or apksigner beside aapt')
 main_signers=signer_certificates(apksigner,main_apk)
 plugin_signers=signer_certificates(apksigner,rime_apk)
 require(main_signers==plugin_signers,'Host and Rime APK signing certificates differ')
 print(f'Actual APK host verified: {main_package} / {plugin_package}; labels, offline permissions, backup policy and plugin pairing match; version 1.0, versionCode 822, arm64-v8a.')
 print('Paired APK signing certificate SHA-256: '+', '.join(sorted(main_signers)))


parser=argparse.ArgumentParser(description='Verify packaged Rime data and optionally its actual APK host manifest.')
parser.add_argument('rime_apk',type=Path)
parser.add_argument('main_apk',type=Path,nargs='?',help='Main APK; when supplied, verify actual host package/manifest, ABI and version')
parser.add_argument('--aapt',help='Android SDK aapt executable for binary manifest inspection')
parser.add_argument('--apksigner',help='Android SDK apksigner executable; defaults to the binary beside aapt')
args=parser.parse_args()
if args.main_apk:
 verify_host(args.main_apk,args.rime_apk,args.aapt,args.apksigner)

with zipfile.ZipFile(args.rime_apk) as apk:
 assert apk.testzip() is None
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
 personal=apk.read('assets/usr/share/rime-data/xuancai_user.dict.yaml').decode()
 require('name: xuancai_user' in personal and '- cn_dicts/xuancai_mobile' in personal,'Empty personal dictionary is not deployable')
 for name in ['cn_dicts/Unicode-LICENSE.txt','cn_dicts/PinyinData-LICENSE.txt','xuancai_user.schema.yaml','xuancai_user.dict.yaml']:
  require(name in manifest['files_sha256'],'New dictionary provenance file missing: '+name)
 require(manifest['raw_dictionary_rows']['cn_dicts/41448']>=40000,'Extended character data is incomplete')
 require(manifest['raw_dictionary_rows']['cn_dicts/xuancai_mobile']==21,'Mobile dictionary source count is unexpected')
 print('Packaged full Lua, grammar, all dictionary sources, rare-character/mobile extensions, personal dictionary and precompiled tables verified.')
