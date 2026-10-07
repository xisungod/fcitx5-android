#!/usr/bin/env python3
"""Relay the exact signed touch.8.1 local-next-word build. Local prepare; explicit upload only."""


from __future__ import annotations

import argparse,base64,hashlib,json,os,re,shutil,stat,time,urllib.error,urllib.request,zipfile
from pathlib import Path


REPO = "xisungod/fcitx5-android"
SOURCE = APK_BYTES = APK_HASH = RELEASE = TAG = TESTS = IDENTITY_HASH = IDENTITY = None
APK = "AXiang-1.2-touch.8.1-arm64.apk"
SIGNER = "ffede124b18d54af5d4cfa9f3a32504fa2ccc898563bc4816bacbe3e46ec0494"
BASE_COMMIT = "5b27a0489eb21dc317957740b580ac89eba4531b"
BASE_APK_HASH = "5f04bd499df1552878cf353cad882bad709837d6f3ad257edcd34cb26e6eb441"
RIME_VERSION = "1.16.1"
RIME_HASH = "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
BRIDGE = "lib/arm64-v8a/libaxiangpredict.so"
COMPLETION = "assets/typing/next_word_completions.tsv"
COMPLETION_HASH = "d7e2c57d9c5f000c736767fe97929650df2ffaec1e39b883b89e8dfb99ced1a1"
COMPLETION_BYTES = 938370
BASE_NATIVE_PROOF_HASH = "8f9382464d89b044828642d74fda0b85191a5575f86cd94e58b814b32b6ab405"
FILES = {APK,"GUIDE.zh-CN.md","SHA256SUMS.txt","build-verification.json","full-junit-reports.zip",
         "prebuilt-manifest.json","inherited-rime-evidence.json","packaged-rime-version.json",
         "source-patch-manifest.json","source-snapshot.json","typing-test-source.patch","unit-test-summary.json",
         "native-prediction-provenance.json","native-prediction-test-summary.json","completion-resource-test-summary.json"}



def set_identity(path, expected_hash):
    global IDENTITY,SOURCE,APK_BYTES,APK_HASH,RELEASE,TAG,TESTS,IDENTITY_HASH
    require(re.fullmatch(r"[0-9a-f]{64}",expected_hash) and digest(path)==expected_hash,"Pinned identity differs")
    d=public_json(path)
    require(d.get("format")=="axiang-touch8-1-exact-delivery-identity-v1" and d.get("repository")==REPO and
            d.get("apk_name")==APK and d.get("signer_sha256")==SIGNER and
            d.get("version_name")=="1.2-touch.8.1" and d.get("version_code")==10 and
            d.get("package")=="org.fcitx.fcitx5.android.axiang.touch2" and
            d.get("baseline_apk_sha256")==BASE_APK_HASH and
            re.fullmatch(r"[0-9a-f]{40}",d.get("source_commit","")) and
            re.fullmatch(r"[0-9a-f]{64}",d.get("apk_sha256","")) and
            isinstance(d.get("apk_bytes"),int) and 260000000 <= d["apk_bytes"] <= 300000000 and
            isinstance(d.get("release_id"),int) and d["release_id"]>0 and
            d.get("release_tag")=="axiang-touch-1.2-touch.8.1" and
            isinstance(d.get("unit_tests"),int) and d["unit_tests"]>=707 and
            d.get("unit_test_scope")=="full_application_suite" and
            d.get("contains_signing_key_or_user_logs") is False and set(d.get("public_files",{}))==FILES,
            "Invalid reviewed local-next-word build identity")
    IDENTITY,IDENTITY_HASH=d,expected_hash
    SOURCE,APK_BYTES,APK_HASH=d["source_commit"],d["apk_bytes"],d["apk_sha256"]
    RELEASE,TAG,TESTS=d["release_id"],d["release_tag"],d["unit_tests"]


def validate(folder):
    require(IDENTITY is not None,"Load reviewed identity first")
    require({p.name for p in folder.iterdir()}==FILES,"Public file whitelist differs")
    for name in FILES:
        p=folder/name;r=IDENTITY["public_files"][name]
        require(p.is_file() and not p.is_symlink() and p.stat().st_size==r["bytes"] and digest(p)==r["sha256"],
                "Reviewed bytes differ: "+name)
    listed={}
    for line in (folder/"SHA256SUMS.txt").read_text().splitlines():
        checksum,name=line.split("  ",1)
        require(name in FILES-{"SHA256SUMS.txt"} and name not in listed and
                re.fullmatch(r"[0-9a-f]{64}",checksum) and digest(folder/name)==checksum,"Checksum differs")
        listed[name]=checksum
    require(set(listed)==FILES-{"SHA256SUMS.txt"},"Checksum membership differs")
    records={n:public_json(folder/n) for n in FILES if n.endswith(".json")}
    v=records["build-verification.json"]
    expected={"schema":"touch8.1-v1","base_commit":BASE_COMMIT,
              "compiled_source_commit":SOURCE,"source_checkout_head":SOURCE,"source_checkout_dirty":False,
              "apk_sha256":APK_HASH,"apk_bytes":APK_BYTES,"package":"org.fcitx.fcitx5.android.axiang.touch2",
              "version_name":"1.2-touch.8.1","version_code":10,"signature_verified":True,
              "signature_certificate_sha256":SIGNER,"zip_alignment_verified":True,
              "source_apk_sha256":BASE_APK_HASH,"asset_count":351,"native_count":29,
              "changed_assets":[],"changed_native":[BRIDGE],"added_native":[],"added_assets":[COMPLETION],
              "original_28_native_libraries_byte_identical":True,"all_350_assets_byte_identical":True,
              "network_permission":False,"new_neural_model_included":False,"new_model_asset_included":False,
              "packaged_rime_version":RIME_VERSION,"packaged_librime_sha256":RIME_HASH,
              "unit_test_scope":"full_application_suite","unit_tests":TESTS,
              "unit_test_failures":0,"unit_test_errors":0,"unit_test_skipped":0,
              "fresh_full_app_suite":True,
              "native_prediction_host_checks":records["native-prediction-test-summary.json"]["host"]["checks"],
              "native_prediction_arm_checks":records["native-prediction-test-summary.json"]["arm"]["checks"],
              "native_prediction_nonempty_contexts":records["native-prediction-test-summary.json"]["arm"]["nonempty_queries"],
              "completion_resource_tests":records["completion-resource-test-summary.json"]["tests"],
              "completion_resource_sha256":COMPLETION_HASH,"completion_resource_bytes":COMPLETION_BYTES,
              "new_completion_asset_included":True,
              "native_prediction_scope":"host_utf8_and_actual_android_bionic_libime_FakeJNI_not_ART_or_phone",
              "old_rime_host_tests_reexecuted":False,"old_rime_arm_smoke_reexecuted":False,
              "next_word_prediction_backend":"existing_libime_statistical_language_model",
              "independent_next_word_worker_added":True,"independent_rime_worker_thread_added":False,
              "device_installation_or_launch_verified":False,"phone_accuracy_or_latency_verified":False,
              "release_kind":"next_word_segmentation_and_completion_prerelease","publication_performed":False}
    for k,value in expected.items():require(v.get(k)==value,"Verification differs: "+k)
    require(v.get("default_experimental_switches",{}).get("local_next_word_prediction") is True,"Next-word default differs")
    snap=records["source-snapshot.json"]
    require(v.get("compiled_source_marker",{}).get("value")==SOURCE and snap.get("commit")==SOURCE and
            snap.get("compiled_marker",{}).get("value")==SOURCE and snap.get("compiled_source_is_fixed_commit") is True and
            snap.get("private_logs_included") is False,"Source marker differs")
    pm=records["source-patch-manifest.json"]
    require(pm.get("base_commit")==BASE_COMMIT and pm.get("source_commit")==SOURCE and
            pm.get("private_logs_prebuilts_and_generated_binaries_included") is False and
            pm.get("patch")=="typing-test-source.patch" and pm.get("bytes")== (folder/pm["patch"]).stat().st_size and
            pm.get("sha256")==digest(folder/pm["patch"]),"Source patch differs")
    sources={r["path"]:r for r in pm.get("source_files",[])}
    require(set(sources)==set(pm.get("files",[])) and len(sources)==len(pm.get("source_files",[])) and
            all(Path(p).suffix in {".kt",".kts",".xml",".cpp",".h",".py",".md",".txt",".tsv"} and
                not Path(p).is_absolute() and ".." not in Path(p).parts and
                re.fullmatch(r"[0-9a-f]{64}",r.get("sha256","")) for p,r in sources.items()),"Public text source scope differs")
    summary=records["unit-test-summary.json"]
    require(summary.get("source_commit")==SOURCE and summary.get("fresh_execution") is True and
            summary.get("scope")=="full_application_suite" and summary.get("tests")==TESTS and
            summary.get("classes",0)>=91 and
            all(summary.get(k)==0 for k in ("failures","errors","skipped")),"Full application tests differ")
    import xml.etree.ElementTree as ET
    with zipfile.ZipFile(folder/"full-junit-reports.zip") as archive:
        names=archive.namelist();results={r["path"]:r["sha256"] for r in summary.get("result_files",[])}
        require(len(names)==len(set(names))==summary.get("classes")==v.get("unit_test_classes") and
                set(names)==set(results) and all(re.fullmatch(r"TEST-[^/]+\.xml",n) for n in names),"Full report members differ")
        totals=dict(tests=0,failures=0,errors=0,skipped=0);classes=set()
        for name in names:
            raw=archive.read(name);require(hashlib.sha256(raw).hexdigest()==results[name],"Full report digest differs")
            suite=ET.fromstring(raw);require(suite.tag=="testsuite","Full report root differs")
            classes.add(suite.get("name"))
            for key in totals:totals[key]+=int(suite.get(key,"0"))
        require(all(totals[k]==summary[k] for k in totals) and classes==set(summary["class_names"]),"Full test totals differ")
    inherited=records["inherited-rime-evidence.json"]
    require(inherited.get("schema")=="touch8.1-inherited-rime-v1" and inherited.get("source_commit")==SOURCE and inherited.get("baseline_source_commit")==BASE_COMMIT and
            inherited.get("baseline_apk_sha256")==BASE_APK_HASH and
            inherited.get("original_28_native_libraries_byte_identical") is True and
            inherited.get("all_350_assets_byte_identical") is True and
            inherited.get("old_rime_host_tests_reexecuted") is False and
            inherited.get("old_rime_arm_smoke_reexecuted") is False and
            inherited.get("original_proof_source_commit")=="e5af7f1ead74c6891fa2035239e4d7ed523b0ed5","Rime proof inheritance differs")
    proof_names={"native-probe-provenance.json","native-regression-summary.json","runtime-smoke-summary.json"}
    proofs=inherited.get("proofs",[])
    require(len(proofs)==3 and {r.get("name") for r in proofs}==proof_names and
            all(re.fullmatch(r"[0-9a-f]{64}",r.get("sha256","")) and
                r.get("url")=="https://github.com/xisungod/fcitx5-android/releases/download/axiang-touch-1.2-touch.7/"+r["name"]
                for r in proofs),"Inherited Rime references differ")
    manifest=records["prebuilt-manifest.json"]
    require(manifest.get("schema")=="touch8.1-v1" and manifest.get("source_commit")==SOURCE and
            manifest.get("base_commit")==BASE_COMMIT and manifest.get("source_checkout_dirty") is False and
            manifest.get("source_apk_sha256")==BASE_APK_HASH and manifest.get("source_apk_bytes")==268488294 and
            manifest.get("counts")=={"assets":351,"lib":29} and manifest.get("added_files")==[COMPLETION] and
            manifest.get("changed_assets")==[] and manifest.get("changed_native")==[BRIDGE] and
            manifest.get("added_native")==[],"Input manifest differs")
    inputs={r["path"]:r for r in manifest.get("files",[])};base={r["path"]:r for r in manifest.get("base_files",[])}
    require(len(inputs)==len(manifest["files"])==380 and len(base)==len(manifest["base_files"])==379 and
            set(inputs)==set(base)|{COMPLETION} and all(inputs[n]==r for n,r in base.items() if n!=BRIDGE) and
            inputs[COMPLETION]==dict(path=COMPLETION,bytes=COMPLETION_BYTES,sha256=COMPLETION_HASH) and inputs[BRIDGE]!=base[BRIDGE],"Original input bytes/membership differ")
    provenance=records["native-prediction-provenance.json"];native=records["native-prediction-test-summary.json"]
    require(provenance.get("schema")==native.get("schema")==1 and provenance.get("base_apk_sha256")==BASE_APK_HASH and
            provenance.get("native_sha256")==native.get("native_sha256")==inputs[BRIDGE]["sha256"]==v.get("new_prediction_library_sha256") and
            provenance.get("native_bytes")==inputs[BRIDGE]["bytes"] and provenance.get("abi")=="arm64-v8a" and
            min(provenance.get("elf_load_alignment",[0]))>=16384 and
            provenance.get("user_dictionary_learning") is False and provenance.get("writes_model_or_user_files") is False and
            provenance.get("rime_initialized_or_modified") is False and provenance.get("neural_model_added") is False and
            native.get("source_commit")==SOURCE and native.get("source_checkout_dirty") is False and
            native.get("host",{}).get("passed") is True and native.get("host",{}).get("checks")==18 and
            native.get("arm",{}).get("passed") is True and native.get("arm",{}).get("checks",0)>230 and
            native.get("arm",{}).get("nonempty_queries")==len(native.get("arm",{}).get("queries",[]))>=17 and
            native.get("all_dependency_and_model_hashes_unchanged") is True and
            native.get("actual_android_shared_libime_execution") is True and native.get("host_language_model_execution") is False and
            native.get("art_verified") is False and native.get("phone_latency_verified") is False,"Fresh new prediction native proof differs")
    require(native.get("source_files_sha256")==provenance.get("source_files_sha256") and
            provenance.get("source_files_sha256") and provenance.get("actually_included_public_headers_sha256"),"Native source/header proof maps differ")
    unchanged={r["path"]:r for r in pm.get("unchanged_native_source_files",[])}
    require(len(unchanged)==len(pm.get("unchanged_native_source_files",[])) and not set(unchanged)&set(sources) and
            pm.get("baseline_native_provenance_sha256")==BASE_NATIVE_PROOF_HASH,"Inherited native source records differ")
    source_records={**sources,**unchanged}
    require(native["arm"].get("internal_candidate_pool_limit")==32 and native["arm"].get("tokenization_plan_limit")==16 and
            native["arm"].get("recent_greedy_tokens_refined")==3 and
            all(native["arm"].get("quality_regressions",{}).get(k) is True for k in
                ("you_are_doing_what_first","want_to_do_what_top3","today_word_preserved","thanks_hello_preserved","real_full_pool_retained")),"Segmentation and expanded pool regressions differ")
    completion=records["completion-resource-test-summary.json"]
    require(completion.get("schema")=="touch8.1-completion-v1" and completion.get("source_commit")==SOURCE and
            completion.get("source_checkout_dirty") is False and completion.get("fresh_execution") is True and
            completion.get("pinned_source_reproduction_verified") is True and completion.get("tests",0)>=11 and
            all(completion.get(k)==0 for k in ("failures","errors","skipped")) and
            completion.get("resource")==COMPLETION and completion.get("resource_sha256")==COMPLETION_HASH and
            completion.get("resource_bytes")==COMPLETION_BYTES and
            sources.get("scripts/check-axiang-nextword-completions.py",{}).get("sha256")==completion.get("test_driver_sha256") and
            sources.get("scripts/build-axiang-nextword-completions.py",{}).get("sha256")==completion.get("producer_sha256"),"Fresh corpus extraction/reproduction proof differs")
    for mapping in (provenance["source_files_sha256"],native["harness_sources_sha256"]):
        require(all(source_records.get(p,{}).get("sha256")==sha for p,sha in mapping.items()),"Fresh native proof differs from frozen public source")
    for field,prefix in (("model_files","assets/usr/share/libime/"),("dependency_files","lib/arm64-v8a/")):
        require(native.get(field)==provenance.get(field) and
                all(inputs.get(prefix+name)==dict(path=prefix+name,**r) for name,r in provenance.get(field,{}).items()),
                "Fresh native dependency/model proof differs from actual APK")
    with zipfile.ZipFile(folder/APK) as archive:
        names=archive.namelist();selected=[n for n in names if n.startswith(("assets/","lib/")) and not n.endswith("/")]
        require(len(selected)==len(set(selected))==380 and set(selected)==set(inputs),"Actual APK input membership differs")
        for name in selected:
            raw=archive.read(name);r=inputs[name]
            require(len(raw)==r["bytes"] and hashlib.sha256(raw).hexdigest()==r["sha256"],"Actual APK input differs: "+name)
        require(hashlib.sha256(archive.read("lib/arm64-v8a/librime.so")).hexdigest()==RIME_HASH,"Actual Rime differs")
    require(digest(folder/APK)==APK_HASH and (folder/APK).stat().st_size==APK_BYTES,"Actual APK identity differs")
    return records

def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o600)
    temporary.replace(path)


def api(method, route, payload=None, github_token=False):
    require(route.startswith("git/blobs"), "Only Git blob API operations are permitted")
    headers = {"Accept": "application/vnd.github+json", "Content-Type": "application/json",
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "AXiang-exact-public-APK-relay"}
    if github_token:
        token = os.environ.get("GH_TOKEN")
        require(bool(token), "Runner GH_TOKEN must be supplied")
        headers["Authorization"] = "Bearer " + token
    body = json.dumps(payload, separators=(",", ":")).encode() if payload is not None else None
    request = urllib.request.Request("https://api.github.com/repos/" + REPO + "/" + route,
                                     data=body, method=method, headers=headers)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise RuntimeError(f"Git blob API {method} returned HTTP {error.code}") from None
        except urllib.error.URLError:
            if attempt == 2:
                raise RuntimeError("Git blob API transport failed") from None
        time.sleep(5 * (attempt + 1))


def public_json(path):
    raw = path.read_bytes()
    require(len(raw) < 2 * 1024 * 1024 and not re.search(
        rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
        rb"/tmp/codex-remote-attachments/|/workspace/|/home/|/private/|AXiang-(?:typing-test-\d{10,}|touch-diagnostics)", raw),
        "Public evidence includes credentials, local paths or private user report identity: " + path.name)
    return json.loads(raw)


def prepare(args):
    validate(args.folder)
    require(1024 * 1024 <= args.chunk_bytes <= 8 * 1024 * 1024, "Chunk size must be 1–8 MiB")
    args.work.mkdir(parents=True, exist_ok=True)
    metadata = args.work / "metadata.zip"
    if args.metadata:
        with zipfile.ZipFile(args.metadata) as archive:
            require(set(archive.namelist()) == FILES - {APK}, "Existing metadata members differ")
            for name in FILES - {APK}:
                require(archive.read(name) == (args.folder / name).read_bytes(), "Existing metadata bytes differ")
        if args.metadata.resolve() != metadata.resolve():
            shutil.copyfile(args.metadata, metadata)
    else:
        with zipfile.ZipFile(metadata, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in sorted(FILES - {APK}):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, (args.folder / name).read_bytes())
    records = []
    for path, kind in ((args.folder / APK, "apk"), (metadata, "metadata")):
        chunks = []
        with path.open("rb") as source:
            offset = 0
            while data := source.read(args.chunk_bytes):
                chunks.append({"offset": offset, "bytes": len(data), "git_blob_sha": git_sha(data),
                               "sha256": hashlib.sha256(data).hexdigest()})
                offset += len(data)
        records.append({"kind": kind, "name": path.name, "bytes": path.stat().st_size,
                        "sha256": digest(path), "chunks": chunks})
    manifest = {"format": "axiang-exact-public-git-blob-relay-v1", "repository": REPO,
                "source_commit": SOURCE, "release_id": RELEASE, "release_tag": TAG,
                "identity_sha256": IDENTITY_HASH,
                "apk_sha256": APK_HASH, "signer_sha256": SIGNER, "files": records,
                "public_files": sorted(FILES), "binary_objects_referenced_by_commit": False,
                "contains_signing_key_or_user_logs": False}
    save(args.work / "relay-manifest.json", manifest)
    expected_blobs = {c["git_blob_sha"] for r in records for c in r["chunks"]}
    require(set(args.existing_blob) <= expected_blobs, "Existing blob is not one of the exact prepared chunks")
    save(args.work / "relay-upload-state.json", {"uploaded_blob_shas": sorted(set(args.existing_blob)),
         "manifest_sha256": digest(args.work / "relay-manifest.json")})
    print(json.dumps({"external_writes": False, "chunks": sum(len(r["chunks"]) for r in records),
                      "manifest": str(args.work / "relay-manifest.json"),
                      "manifest_sha256": digest(args.work / "relay-manifest.json")}, ensure_ascii=False))


def upload(args):
    validate(args.folder)
    manifest_path = args.work / "relay-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    state_path = args.work / "relay-upload-state.json"
    state = json.loads(state_path.read_text())
    require(state["manifest_sha256"] == digest(manifest_path), "Prepared relay manifest changed")
    uploaded = set(state["uploaded_blob_shas"])
    for record in manifest["files"]:
        path = args.folder / APK if record["kind"] == "apk" else args.work / "metadata.zip"
        require(path.stat().st_size == record["bytes"] and digest(path) == record["sha256"],
                "Prepared relay source differs")
        with path.open("rb") as source:
            for index, chunk in enumerate(record["chunks"]):
                source.seek(chunk["offset"])
                data = source.read(chunk["bytes"])
                require(git_sha(data) == chunk["git_blob_sha"] and
                        hashlib.sha256(data).hexdigest() == chunk["sha256"], "Prepared chunk differs")
                if chunk["git_blob_sha"] not in uploaded:
                    result = api("POST", "git/blobs", {"content": base64.b64encode(data).decode("ascii"),
                                                       "encoding": "base64"})
                    require(result["sha"] == chunk["git_blob_sha"], "Uploaded immutable blob differs")
                    uploaded.add(result["sha"])
                    state["uploaded_blob_shas"] = sorted(uploaded)
                    save(state_path, state)
                print(f"Ready {record['kind']} chunk {index + 1}/{len(record['chunks'])}", flush=True)
    print(json.dumps({"created_only_immutable_unreferenced_blobs": True,
                      "uploaded_blobs": len(uploaded), "manifest_sha256": digest(manifest_path)}))


def restore(args):
    require(digest(args.manifest) == args.manifest_sha256, "Pinned relay manifest differs")
    manifest = json.loads(args.manifest.read_text())
    require(manifest["format"] == "axiang-exact-public-git-blob-relay-v1" and
            manifest["repository"] == REPO and manifest["source_commit"] == SOURCE and
            manifest["identity_sha256"] == IDENTITY_HASH and
            manifest["release_id"] == RELEASE and manifest["release_tag"] == TAG and
            manifest["apk_sha256"] == APK_HASH and manifest["signer_sha256"] == SIGNER and
            set(manifest["public_files"]) == FILES and
            manifest["binary_objects_referenced_by_commit"] is False and
            manifest["contains_signing_key_or_user_logs"] is False,
            "Pinned relay identity differs")
    require(len(manifest["files"]) == 2 and {r["kind"] for r in manifest["files"]} == {"apk", "metadata"},
            "Only APK and metadata ZIP are valid relay payloads")
    require(not args.output.exists(), "Use a fresh restoration directory")
    args.output.mkdir(parents=True)
    metadata = args.output.parent / "relay-metadata.zip"
    for record in manifest["files"]:
        require(record["name"] == (APK if record["kind"] == "apk" else "metadata.zip"), "Invalid relay name")
        path = args.output / APK if record["kind"] == "apk" else metadata
        offset = 0
        with path.open("wb") as target:
            for chunk in record["chunks"]:
                require(chunk["offset"] == offset and 0 < chunk["bytes"] <= 8 * 1024 * 1024 and
                        bool(re.fullmatch(r"[0-9a-f]{40}", chunk["git_blob_sha"])), "Invalid relay chunk")
                blob = api("GET", "git/blobs/" + chunk["git_blob_sha"], github_token=args.github_token)
                require(blob["encoding"] == "base64" and blob["sha"] == chunk["git_blob_sha"] and
                        blob["size"] == chunk["bytes"], "Blob identity or size differs")
                data = base64.b64decode(blob["content"].replace("\n", ""), validate=True)
                require(len(data) == chunk["bytes"] and git_sha(data) == chunk["git_blob_sha"] and
                        hashlib.sha256(data).hexdigest() == chunk["sha256"], "Blob bytes differ")
                target.write(data)
                offset += len(data)
        require(offset == record["bytes"] and digest(path) == record["sha256"], "Reconstructed payload differs")
    with zipfile.ZipFile(metadata) as archive:
        require(set(archive.namelist()) == FILES - {APK} and len(archive.infolist()) == len(FILES) - 1,
                "Metadata ZIP members differ")
        for info in archive.infolist():
            require(not info.is_dir() and info.file_size < 32 * 1024 * 1024 and
                    not stat.S_ISLNK(info.external_attr >> 16), "Metadata ZIP member is unsafe")
            (args.output / info.filename).write_bytes(archive.read(info))
    metadata.unlink()
    validate(args.output)
    print(json.dumps({"restored_exact_previously_verified_apk": True, "apk_sha256": APK_HASH,
                      "apk_bytes": APK_BYTES, "compiled_source_commit": SOURCE,
                      "jvm_tests": TESTS,
                      "private_signing_material_transferred": False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--identity-sha256", required=True)
    sub = parser.add_subparsers(dest="mode", required=True)
    for command in ("prepare", "upload"):
        p = sub.add_parser(command)
        p.add_argument("--folder", type=Path, required=True)
        p.add_argument("--work", type=Path, required=True)
        if command == "prepare":
            p.add_argument("--chunk-bytes", type=int, default=8 * 1024 * 1024)
            p.add_argument("--metadata", type=Path)
            p.add_argument("--existing-blob", action="append", default=[])
    p = sub.add_parser("restore")
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--manifest-sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--github-token", action="store_true")
    args = parser.parse_args()
    set_identity(args.identity, args.identity_sha256)
    {"prepare": prepare, "upload": upload, "restore": restore}[args.mode](args)


if __name__ == '__main__':
    main()
