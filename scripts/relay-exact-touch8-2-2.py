#!/usr/bin/env python3
"""Relay the exact signed touch.8.2.2 SMS status repair build. Local prepare; explicit upload only."""


from __future__ import annotations

import argparse,base64,hashlib,json,os,re,shutil,stat,time,urllib.error,urllib.request,zipfile
from pathlib import Path


REPO = "xisungod/fcitx5-android"
SOURCE = APK_BYTES = APK_HASH = RELEASE = TAG = TESTS = IDENTITY_HASH = IDENTITY = None
APK = "AXiang-1.2-touch.8.2.2-arm64.apk"
SIGNER = "ffede124b18d54af5d4cfa9f3a32504fa2ccc898563bc4816bacbe3e46ec0494"
BASE_COMMIT = 'c9dbcc50e327f62a98ab0934d4ac4fa9bf20daa5'
FEATURE_SOURCE = 'aa9232725bccd2072340068f7cfd2df1b41dc3ac'
RIME_PROOF_APK_HASH = '4690edd288ce06e8bcf9f829dced1cb1b5e449c92afcb2c86fe74c2e26705cbe'
RIME_PROOF_APK_BYTES = 272198575
BASE_APK_HASH = 'a96209595d8def563cf0bea218d43c853ba233c8d1cfb7e597d2d5b342b76b17'
BASE_APK_BYTES = 273096708
BASE_TEST_HASH = 'd1d5d8d8dd12ab31d2f6d70848de9cb0f60d23096aa082702f939d4f00bbe94a'
RIME_VERSION = "1.16.1"
RIME_HASH = "e81472fd974a557e7233b0a0ca5659fa7da6c4a2eccb5a4a08c9d807d14c7159"
BRIDGE = 'lib/arm64-v8a/libaxiangpredict.so'
COMPLETION = 'assets/typing/next_word_completions.tsv'
COMPLETION_HASH = "e763c788f480dbc6934453c9c255173e2173ed9ca69d1cbeea405ddc3f7c4e88"
COMPLETION_BYTES = 7702187
PROOFS = {'completion-resource-test-summary.json': '324724d8269a378fd50b7356555f14df1441801bdeb7672f4acc08d254454208', 'inherited-native-prediction-evidence.json': 'd6d6b333eaccb3722830eff319d8a273a192f36f77ddd3a70e537cf794a1f78a', 'inherited-rime-evidence.json': '3e2d32a3e9c2db38347ec10b74c4d20874d4b51b383f38c650c1ce06c40266fe', 'native-prediction-provenance.json': 'e5c209ae1bac8f8a3f110e86631050ee6f38a60058a756a2776e620db99a2437', 'native-prediction-test-summary.json': 'cc9c900f1507971019e5348da058dd192755729fba64003c08230c7bf86d1bf6', 'packaged-rime-version.json': '3b34810d8ac14b9b9a9af7f682d99bb5155805b1b3224dce5842fe4451f66753', 'prediction-benchmark-baseline-candidates.json': '1d7c50581014784126b8d4cc07a026b536f0a2f0b5611eb1ce01530378e64b7e', 'prediction-benchmark-current-candidates.json': 'd23a00391a040ebfbfa97e514fc93febc0b42bd633b72eac328532c3d2ad8592', 'prediction-benchmark-evaluation.json': 'eb426a68a5f5e6266298d8e1fa9627b9414982c7c6a9b3310507217176430099', 'prediction-benchmark-summary.json': '716d8a3d33486f4539de7e412ef7676f2332c9dbfcf926263094c86dc6959d2b'}
FILES = {'prediction-benchmark-evaluation.json', 'AXiang-1.2-touch.8.2.2-arm64.apk', 'inherited-rime-evidence.json', 'prediction-benchmark-baseline-candidates.json', 'prediction-benchmark-summary.json', 'inherited-feature-evidence.json', 'full-junit-reports.zip', 'prebuilt-manifest.json', 'inherited-native-prediction-evidence.json', 'SHA256SUMS.txt', 'packaged-rime-version.json', 'source-patch-manifest.json', 'typing-test-source.patch', 'native-prediction-test-summary.json', 'native-prediction-provenance.json', 'source-snapshot.json', 'completion-resource-test-summary.json', 'build-verification.json', 'unit-test-summary.json', 'GUIDE.zh-CN.md', 'prediction-benchmark-current-candidates.json'}



def set_identity(path, expected_hash):
    global IDENTITY,SOURCE,APK_BYTES,APK_HASH,RELEASE,TAG,TESTS,IDENTITY_HASH,COMPLETION_HASH,COMPLETION_BYTES
    require(re.fullmatch(r"[0-9a-f]{64}",expected_hash) and digest(path)==expected_hash,"Pinned identity differs")
    d=public_json(path)
    require(d.get("format")=="axiang-touch8-2-2-exact-delivery-identity-v1" and d.get("repository")==REPO and
            d.get("apk_name")==APK and d.get("signer_sha256")==SIGNER and
            d.get("version_name")=="1.2-touch.8.2.2" and d.get("version_code")==13 and
            d.get("package")=="org.fcitx.fcitx5.android.axiang.touch2" and
            d.get("baseline_apk_sha256")==BASE_APK_HASH and
            re.fullmatch(r"[0-9a-f]{64}",d.get("completion_resource_sha256","")) and
            d.get("completion_resource_sha256")==COMPLETION_HASH and
            d.get("completion_resource_bytes")==COMPLETION_BYTES and
            re.fullmatch(r"[0-9a-f]{40}",d.get("source_commit","")) and
            re.fullmatch(r"[0-9a-f]{64}",d.get("apk_sha256","")) and
            isinstance(d.get("apk_bytes"),int) and 260000000 <= d["apk_bytes"] <= 300000000 and
            isinstance(d.get("release_id"),int) and d["release_id"]>0 and
            d.get("release_tag")=="axiang-touch-1.2-touch.8.2.2" and
            isinstance(d.get("unit_tests"),int) and d["unit_tests"]>=867 and
            d.get("unit_test_scope")=="full_application_suite" and
            d.get("contains_signing_key_or_user_logs") is False and set(d.get("public_files",{}))==FILES,
            "Invalid reviewed SMS status repair build identity")
    IDENTITY,IDENTITY_HASH=d,expected_hash
    SOURCE,APK_BYTES,APK_HASH=d["source_commit"],d["apk_bytes"],d["apk_sha256"]
    RELEASE,TAG,TESTS=d["release_id"],d["release_tag"],d["unit_tests"]
    COMPLETION_HASH,COMPLETION_BYTES=d["completion_resource_sha256"],d["completion_resource_bytes"]


def validate(folder):
    require(IDENTITY is not None,"Load reviewed identity first")
    require({p.name for p in folder.iterdir()}==FILES,"Public 21-file whitelist differs")
    for name in FILES:
        p=folder/name;r=IDENTITY["public_files"][name]
        require(p.is_file() and not p.is_symlink() and p.stat().st_size==r["bytes"] and digest(p)==r["sha256"],"Reviewed bytes differ: "+name)
    listed={}
    for line in (folder/"SHA256SUMS.txt").read_text().splitlines():
        checksum,name=line.split("  ",1)
        require(name in FILES-{"SHA256SUMS.txt"} and name not in listed and re.fullmatch(r"[0-9a-f]{64}",checksum) and digest(folder/name)==checksum,"Checksum differs")
        listed[name]=checksum
    require(set(listed)==FILES-{"SHA256SUMS.txt"},"Checksum membership differs")
    records={n:public_json(folder/n) for n in FILES if n.endswith(".json")};v=records["build-verification.json"]
    expected=dict(schema="touch8.2.2-v1",base_commit=BASE_COMMIT,compiled_source_commit=SOURCE,source_checkout_head=SOURCE,
        source_checkout_dirty=False,apk_sha256=APK_HASH,apk_bytes=APK_BYTES,package="org.fcitx.fcitx5.android.axiang.touch2",
        version_name="1.2-touch.8.2.2",version_code=13,signature_verified=True,signature_certificate_sha256=SIGNER,
        zip_alignment_verified=True,source_apk_sha256=BASE_APK_HASH,asset_count=351,native_count=29,
        changed_assets=[],changed_native=[],added_native=[],added_assets=[],all_351_assets_byte_identical=True,
        all_29_native_libraries_byte_identical=True,all_380_packaged_inputs_byte_identical=True,network_permission=False,
        new_neural_model_included=False,new_model_asset_included=False,packaged_rime_version=RIME_VERSION,packaged_librime_sha256=RIME_HASH,
        unit_test_scope="full_application_suite",unit_tests=TESTS,unit_test_failures=0,unit_test_errors=0,unit_test_skipped=0,fresh_full_app_suite=True,
        feature_proofs_inherited=True,native_prediction_tests_reexecuted=False,native_prediction_rebuilt=False,
        completion_resource_tests_reexecuted=False,prediction_benchmark_reexecuted=False,old_rime_tests_reexecuted=False,
        inherited_native_prediction_host_checks=18,inherited_native_prediction_arm_checks=359,inherited_completion_resource_tests=22,
        inherited_prediction_benchmark_cases=200,inherited_native_prediction_proof_source_commit="d12bd16ece54b02c78aa28a92c2b161c51cc766b",
        inherited_completion_and_benchmark_source_commit=FEATURE_SOURCE,real_sms_receive_verified=False,sms_device_permission_flow_verified=False,
        device_installation_or_launch_verified=False,phone_accuracy_or_latency_verified=False,
        sms_history_reading_added=False,sms_system_permission_override=False,
        sms_reading_default_on=True,sms_authorization_prompt_scope="user_entry_only",
        release_kind="sms_permission_and_suggestion_fix_prerelease",publication_performed=False)
    require(all(v.get(k)==value for k,value in expected.items()),"SMS verification identity/scope differs")
    require(v.get("default_experimental_switches",{}).get("local_next_word_prediction") is True and
            v.get("default_experimental_switches",{}).get("verification_code_sms") is True,"Existing defaults/SMS reading-on differ")
    require(v.get("sms_package_contract")==dict(receive_sms_declared=True,read_sms_history_declared=False,network_permission=False,
            receiver_default_disabled=True,receiver_exported=True,receiver_sender_permission="android.permission.BROADCAST_SMS",
            receiver_action="android.provider.Telephony.SMS_RECEIVED"),"Actual APK SMS permission contract differs")
    snap=records["source-snapshot.json"]
    require(v.get("compiled_source_marker",{}).get("value")==SOURCE and snap.get("commit")==SOURCE and
            snap.get("compiled_marker",{}).get("value")==SOURCE and snap.get("compiled_source_is_fixed_commit") is True and
            snap.get("private_logs_included") is False,"Source marker differs")
    pm=records["source-patch-manifest.json"]
    require(pm.get("base_commit")==BASE_COMMIT and pm.get("source_commit")==SOURCE and
            pm.get("private_logs_prebuilts_and_generated_binaries_included") is False and pm.get("patch")=="typing-test-source.patch" and
            pm.get("bytes")==(folder/pm["patch"]).stat().st_size and pm.get("sha256")==digest(folder/pm["patch"]),"Source patch differs")
    source_records={r["path"]:r for r in pm.get("source_files",[])}
    require(set(source_records)==set(pm.get("files",[])) and len(source_records)==len(pm.get("source_files",[])) and
            all(Path(p).suffix in {".kt",".xml",".py",".md"} and not Path(p).is_absolute() and ".." not in Path(p).parts and
                0<r.get("bytes",0)<1000000 and re.fullmatch(r"[0-9a-f]{64}",r.get("sha256","")) for p,r in source_records.items()),
            "SMS-only text source scope differs")
    tests=records["unit-test-summary.json"]
    require(tests.get("source_commit")==SOURCE and tests.get("fresh_execution") is True and tests.get("scope")=="full_application_suite" and
            tests.get("tests")==TESTS and tests.get("classes",0)>=103 and all(tests.get(k)==0 for k in ("failures","errors","skipped")),"Fresh whole application tests differ")
    import xml.etree.ElementTree as ET
    with zipfile.ZipFile(folder/"full-junit-reports.zip") as archive:
        names=archive.namelist();results={r["path"]:r["sha256"] for r in tests.get("result_files",[])}
        require(len(names)==len(set(names))==tests.get("classes")==v.get("unit_test_classes") and set(names)==set(results) and
                all(re.fullmatch(r"TEST-[^/]+\.xml",n) for n in names),"Whole report members differ")
        totals=dict(tests=0,failures=0,errors=0,skipped=0);classes=set()
        for name in names:
            raw=archive.read(name);require(hashlib.sha256(raw).hexdigest()==results[name],"JUnit raw digest differs")
            suite=ET.fromstring(raw);require(suite.tag=="testsuite","JUnit root differs");classes.add(suite.get("name"))
            for key in totals:totals[key]+=int(suite.get(key,"0"))
        require(all(totals[k]==tests[k] for k in totals) and classes==set(tests["class_names"]) and
                len(set(tests.get("baseline_class_names",[])))==103 and set(tests["baseline_class_names"])<=classes and
                tests.get("baseline_unit_test_summary_sha256")==BASE_TEST_HASH,"Whole suite totals/prior classes differ")
    manifest=records["prebuilt-manifest.json"]
    require(manifest.get("schema")=="touch8.2.2-v1" and manifest.get("source_commit")==SOURCE and manifest.get("base_commit")==BASE_COMMIT and
            manifest.get("source_checkout_dirty") is False and manifest.get("source_apk_sha256")==BASE_APK_HASH and
            manifest.get("source_apk_bytes")==BASE_APK_BYTES and manifest.get("counts")=={"assets":351,"lib":29} and
            manifest.get("added_files")==manifest.get("changed_assets")==manifest.get("changed_native")==manifest.get("added_native")==[] and
            manifest.get("all_380_inputs_byte_identical") is True and manifest.get("files")==manifest.get("base_files"),"All-original input manifest differs")
    inputs={r["path"]:r for r in manifest.get("files",[])}
    require(len(inputs)==len(manifest["files"])==380 and inputs.get(COMPLETION)==dict(path=COMPLETION,bytes=COMPLETION_BYTES,sha256=COMPLETION_HASH),
            "Unchanged corpus/input membership differs")
    for name,checksum in PROOFS.items():require(digest(folder/name)==checksum,"Historical proof bytes changed: "+name)
    wrapper=records["inherited-feature-evidence.json"]
    inherited_expected=dict(schema="touch8.2.2-inherited-features-v1",source_commit=SOURCE,baseline_source_commit=BASE_COMMIT,
        baseline_apk_sha256=BASE_APK_HASH,all_351_assets_byte_identical=True,all_29_native_libraries_byte_identical=True,
        all_380_packaged_inputs_byte_identical=True,prediction_native_proof_source_commit="d12bd16ece54b02c78aa28a92c2b161c51cc766b",
        rime_original_proof_source_commit="e5af7f1ead74c6891fa2035239e4d7ed523b0ed5",completion_proof_source_commit=FEATURE_SOURCE,
        continuation_benchmark_source_commit=FEATURE_SOURCE,native_rebuilt=False,native_tests_reexecuted=False,completion_tests_reexecuted=False,
        continuation_benchmark_reexecuted=False,rime_tests_reexecuted=False,inherited_native_host_checks=18,inherited_native_arm_checks=359,
        inherited_completion_checks=22,inherited_continuation_cases=200,inherited_native_nonempty_contexts=17)
    require(all(wrapper.get(k)==value for k,value in inherited_expected.items()),"Historical feature scope/source identities differ")
    refs={r["name"]:r for r in wrapper.get("proofs",[])}
    require(len(refs)==len(wrapper.get("proofs",[]))==10 and set(refs)==set(PROOFS) and all(
        r["sha256"]==PROOFS[n] and r["bytes"]==(folder/n).stat().st_size and r["url"]==
        "https://github.com/xisungod/fcitx5-android/releases/download/axiang-touch-1.2-touch.8.2/"+n and
        r["evidence_source_commit"]==("d12bd16ece54b02c78aa28a92c2b161c51cc766b" if n.startswith("native-prediction-") or n=="prediction-benchmark-baseline-candidates.json" else FEATURE_SOURCE)
        for n,r in refs.items()),"Raw inherited feature references differ")
    native=records["native-prediction-test-summary.json"];provenance=records["native-prediction-provenance.json"]
    completion=records["completion-resource-test-summary.json"];benchmark=records["prediction-benchmark-summary.json"]
    unchanged={r["path"]:r for r in pm.get("unchanged_feature_source_files",[])}
    require(len(unchanged)==len(pm.get("unchanged_feature_source_files",[]))==24 and not set(unchanged)&set(source_records),"Unchanged feature source registry differs")
    maps=[provenance["source_files_sha256"],native["harness_sources_sha256"],benchmark["source_files_sha256"],
          records["prediction-benchmark-current-candidates.json"]["provenance"]["production_kotlin_sources_sha256"],
          {"scripts/build-axiang-nextword-completions.py":completion["producer_sha256"],
           "scripts/check-axiang-nextword-completions.py":completion["test_driver_sha256"],
           "app/src/main/assets/typing/next_word_completions.tsv":COMPLETION_HASH}]
    require(all(unchanged.get(p,{}).get("sha256")==sha for mapping in maps for p,sha in mapping.items()),"Historical source byte invariants differ")
    for field,prefix in (("model_files","assets/usr/share/libime/"),("dependency_files","lib/arm64-v8a/")):
        require(native[field]==provenance[field] and all(inputs.get(prefix+n)==dict(path=prefix+n,**r) for n,r in provenance[field].items()),
                "Original native model/dependencies differ from actual APK")
    with zipfile.ZipFile(folder/APK) as archive:
        names=archive.namelist();selected=[n for n in names if n.startswith(("assets/","lib/")) and not n.endswith("/")]
        require(len(selected)==len(set(selected))==380 and set(selected)==set(inputs),"Actual APK input membership differs")
        for n in selected:
            raw=archive.read(n);r=inputs[n];require(len(raw)==r["bytes"] and hashlib.sha256(raw).hexdigest()==r["sha256"],"Actual original input differs: "+n)
        require(hashlib.sha256(archive.read("lib/arm64-v8a/librime.so")).hexdigest()==RIME_HASH,"Actual Rime library differs")
    actual_rime=v.get("actual_packaged_rime_check",{});historical=records["packaged-rime-version.json"]
    require(actual_rime.get("apk_sha256")==APK_HASH and actual_rime.get("apk_size_bytes")==APK_BYTES and
            historical.get("apk_sha256")==RIME_PROOF_APK_HASH and historical.get("apk_size_bytes")==RIME_PROOF_APK_BYTES and
            {k:v for k,v in actual_rime.items() if k not in {"apk_sha256","apk_size_bytes"}}==
            {k:v for k,v in historical.items() if k not in {"apk_sha256","apk_size_bytes"}},"Fresh APK binary Rime audit differs from historical engine")
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
