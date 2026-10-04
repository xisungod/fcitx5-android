#!/usr/bin/env python3
import base64, os
from pathlib import Path
names=['XUANCAI_SIGN_KEY_BASE64','XUANCAI_SIGN_KEY_PWD','XUANCAI_SIGN_KEY_ALIAS']
values=[os.environ.get(name,'') for name in names]
if any(values) and not all(values):raise SystemExit('Provide all three signing secrets; incomplete signing configuration cannot be used.')
with open(os.environ['GITHUB_ENV'],'a') as output:
 if all(values):
  path=Path(os.environ['RUNNER_TEMP'])/'xuancai-signing.ks'
  path.touch(mode=0o600,exist_ok=False)
  path.write_bytes(base64.b64decode(values[0],validate=True))
  output.write('SIGN_KEY_FILE='+str(path)+'\nXUANCAI_APP_SUFFIX=.xuancai.black\nXUANCAI_SIGNING_MODE=stable\n')
  print('Using the fixed application ID and repository signing key.')
 else:
  output.write('XUANCAI_APP_SUFFIX=.xuancai.black.v10\nXUANCAI_SIGNING_MODE=isolated\n')
  print('Signing secrets unavailable: building isolated V10. This is not an in-place update to V09.')
