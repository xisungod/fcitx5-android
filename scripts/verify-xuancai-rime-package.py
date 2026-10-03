#!/usr/bin/env python3
import hashlib,json,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
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
