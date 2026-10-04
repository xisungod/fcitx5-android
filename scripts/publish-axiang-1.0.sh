#!/usr/bin/env bash
set -euo pipefail
tag=axiang-v1.0
assets=/tmp/axiang-1.0
if gh release view "$tag" >/dev/null 2>&1; then
  published_commit=$(gh api "repos/$GH_REPO/git/ref/tags/$tag" --jq '.object.sha')
  if [[ "$published_commit" != "$GITHUB_SHA" ]]; then
    echo 'This version already points to a different source commit. Use a new version instead of replacing installed APKs.' >&2
    exit 1
  fi
  if [[ "$XUANCAI_SIGNING_MODE" == isolated ]]; then
    previous=$(mktemp -d)
    gh release download "$tag" --dir "$previous"
    if [[ -f "$previous/axiang-input-1.0-arm64.apk" && -f "$previous/axiang-rime-1.0-arm64.apk" && -f "$previous/SHA256SUMS.txt" ]]; then
      (cd "$previous" && sha256sum --check SHA256SUMS.txt)
      gh release edit "$tag" --notes-file /tmp/AXIANG-1.0.md
      echo 'The same commit was rebuilt successfully; preserved the published APK pair and its signing certificate.'
      exit 0
    fi
  fi
  gh release upload "$tag" "$assets"/* --clobber
  gh release edit "$tag" --notes-file /tmp/AXIANG-1.0.md
else
  gh release create "$tag" "$assets"/* --target "$GITHUB_SHA" \
    --title '阿翔输入法 1.0（含 Rime 中文引擎，arm64）' --notes-file /tmp/AXIANG-1.0.md --prerelease --latest=false
fi
