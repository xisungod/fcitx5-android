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
 """Read element/attribute relationships from aapt's binary XML dump."""
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
   attribute=re.match(r'A: ([^=(]+)(?:\([^)]*\))?="([^"\n]*)"',stripped)
   if attribute:
    stack[-1][1]['attrs'][attribute[1]]=attribute[2]
 return roots


def descendants(node, name):
 for child in node['children']:
  if child['name']==name:
   yield child
  yield from descendants(child,name)


def inspect_apk(aapt, path):
 badging=subprocess.check_output([aapt,'dump','badging',str(path)],text=True)
 package=re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",badging,re.MULTILINE)
 require(package is not None,f'{path}: aapt did not report package/version fields')
 require(package[2]=='802',f'{path}: expected arm64 versionCode 802, got {package[2]}')
 require(package[3]=='xuancai-black-v14',f'{path}: wrong V14 versionName {package[3]}')
 native=re.search(r'^native-code:\s*(.*)$',badging,re.MULTILINE)
 abis=re.findall(r"'([^']+)'",native[1]) if native else []
 require(abis==['arm64-v8a'],f'{path}: expected only arm64-v8a, got {abis}')
 xml=subprocess.check_output([aapt,'dump','xmltree',str(path),'AndroidManifest.xml'],text=True)
 roots=manifest_tree(xml)
 manifests=[node for node in roots if node['name']=='manifest']
 require(len(manifests)==1,f'{path}: expected one AndroidManifest root')
 require(manifests[0]['attrs'].get('package')==package[1],f'{path}: binary manifest package disagrees with badging')
 return package[1],manifests[0]


def verify_host(main_apk, rime_apk, aapt_override):
 sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
 sdk_aapt=Path(sdk)/'build-tools/36.1.0/aapt' if sdk else None
 aapt=aapt_override or (str(sdk_aapt) if sdk_aapt and sdk_aapt.is_file() else shutil.which('aapt'))
 require(aapt is not None,'Host verification requires --aapt or Android SDK build-tools 36.1.0')
 main_package,_=inspect_apk(aapt,main_apk)
 plugin_package,manifest=inspect_apk(aapt,rime_apk)
 base='org.fcitx.fcitx5.android'
 require(main_package in [base+'.xuancai.black.v14',base+'.xuancai.black'],f'Unexpected V14 host package {main_package}')
 expected_plugin=base+'.plugin.rime'+main_package[len(base):]
 require(plugin_package==expected_plugin,f'Rime package {plugin_package} does not match host {main_package}; expected {expected_plugin}')
 queries=[node for node in manifest['children'] if node['name']=='queries']
 host_queries={node['attrs'].get('android:name','') for query in queries for node in descendants(query,'package') if node['attrs'].get('android:name','').startswith(base)}
 require(host_queries=={main_package},f'Rime host package queries {sorted(host_queries)} do not match {main_package}')
 metadata={node['attrs'].get('android:name','') for node in descendants(manifest,'meta-data') if node['attrs'].get('android:name','').endswith('.plugin.METADATA')}
 actions={node['attrs'].get('android:name','') for node in descendants(manifest,'action') if node['attrs'].get('android:name','').endswith('.plugin.MANIFEST')}
 require(metadata=={main_package+'.plugin.METADATA'},f'Rime metadata host mismatch: {sorted(metadata)}')
 require(actions=={main_package+'.plugin.MANIFEST'},f'Rime manifest intent host mismatch: {sorted(actions)}')
 print(f'Actual APK host verified: {main_package} / {plugin_package}; queries, plugin.METADATA and plugin.MANIFEST match; versionCode 802, arm64-v8a.')


parser=argparse.ArgumentParser(description='Verify packaged Rime data and optionally its actual APK host manifest.')
parser.add_argument('rime_apk',type=Path)
parser.add_argument('main_apk',type=Path,nargs='?',help='Main APK; when supplied, verify actual host package/manifest, ABI and version')
parser.add_argument('--aapt',help='Android SDK aapt executable for binary manifest inspection')
args=parser.parse_args()
if args.main_apk:
 verify_host(args.main_apk,args.rime_apk,args.aapt)

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
 assert 'derive/^([a-z]*[aeio])ng$/$1g/' in schema
 assert 'language: zh-hans-t-essay-bgw-compact' in schema
 print('Packaged full Lua, grammar, all dictionary sources and precompiled tables verified.')
