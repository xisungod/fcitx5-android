#!/usr/bin/env python3
"""Build frozen touch.8.2 with fresh app/corpus/continuation checks.

The original 350 assets and all 29 libraries stay byte-identical to touch.8.1.
Only the existing completion TSV changes. Native and Rime proofs are inherited
in their original bytes/source identity; the original native regression suites are not
re-run. A fresh public continuation benchmark executes the unchanged library.
No publication or signing-key access; prebuilt baseline stays read-only.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,os,re,shutil,struct,subprocess,sys,tempfile,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
BASE="d12bd16ece54b02c78aa28a92c2b161c51cc766b"
BASE_APK_HASH="3f64045efa7b2e58d57b927ad22353ea07f4c80a20dec725cc807facd3c0e2b8"
BASE_APK_BYTES=268992145
BASE_INHERITED_HASH="63de2e8c06471dcda07199ffaf16de9bb534f4347ca50c71e2a9654121cb1696"
BASE_NATIVE_PROOF_HASH="e5c209ae1bac8f8a3f110e86631050ee6f38a60058a756a2776e620db99a2437"
BASE_NATIVE_SUMMARY_HASH="cc9c900f1507971019e5348da058dd192755729fba64003c08230c7bf86d1bf6"
BASE_NATIVE_ORIGIN_APK="5f04bd499df1552878cf353cad882bad709837d6f3ad257edcd34cb26e6eb441"
BASE_JUNIT_SUMMARY_HASH="5d0b8862c2e40b921c3a1a02d734a8ccd8e088541b77031e73cc3acd57abdb39"
BENCHMARK_SOURCE="docs/axiang/prediction-continuation-benchmark.tsv"
BENCHMARK_SHA256="ea0c77882305c9a024496b9a219f2c77e7ee17184b8e64714d91f871ac2083ad"
BENCHMARK_FILES={"summary.json":"prediction-benchmark-summary.json",
                 "current-candidates.json":"prediction-benchmark-current-candidates.json",
                 "baseline-candidates.json":"prediction-benchmark-baseline-candidates.json",
                 "evaluation.json":"prediction-benchmark-evaluation.json"}
VERSION,CODE="1.2-touch.8.2",11
APK="AXiang-1.2-touch.8.2-arm64.apk"
BRIDGE="lib/arm64-v8a/libaxiangpredict.so"
COMPLETION="assets/typing/next_word_completions.tsv"
COMPLETION_SOURCE="app/src/main/assets/typing/next_word_completions.tsv"
BASE_COMPLETION_HASH="d7e2c57d9c5f000c736767fe97929650df2ffaec1e39b883b89e8dfb99ced1a1"
BASE_COMPLETION_BYTES=938370
COMPLETION_MAX_BYTES=16*1024*1024
FILES={APK,"GUIDE.zh-CN.md","SHA256SUMS.txt","build-verification.json","full-junit-reports.zip",
       "prebuilt-manifest.json","inherited-rime-evidence.json","packaged-rime-version.json",
       "source-patch-manifest.json","source-snapshot.json","typing-test-source.patch","unit-test-summary.json",
       "native-prediction-provenance.json","native-prediction-test-summary.json","inherited-native-prediction-evidence.json",
       "completion-resource-test-summary.json",*BENCHMARK_FILES.values()}


def require(condition, message):
    if not condition:
        raise ValueError(message)

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")

def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])

def load_common(root):
    spec = importlib.util.spec_from_file_location("touch7_common", root / "scripts/prepare-axiang-touch7-delivery.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def frozen(root,source):
    require(re.fullmatch(r"[0-9a-f]{40}",source) and git(root,"rev-parse","HEAD").decode().strip()==source and
            not git(root,"status","--porcelain"),"Require the clean frozen full source")
    require(git(root,"rev-parse","HEAD^").decode().strip()==BASE,"touch.8.2 must have public touch.8.1 as one parent")


def source_patch(root,source,allowlist):
    paths=[p.decode() for p in git(root,"diff","--name-only","-z",BASE,source).split(b"\0") if p]
    approved=json.loads(allowlist.read_text())
    require(isinstance(approved,list) and len(approved)==len(set(approved)) and paths and
            set(paths)==set(approved),"Changed source differs from exact reviewed path allowlist")
    records=[]
    for name in paths:
        permitted=(name.startswith(("app/src/main/java/","app/src/main/res/","app/src/test/java/")) or
                   name in {"app/build.gradle.kts","app/src/main/cpp/CMakeLists.txt",
                            "app/src/main/cpp/axiangpredict/CMakeLists.txt","app/src/main/cpp/axiangpredict.h",
                            "app/src/main/cpp/axiangpredict.cpp","app/src/main/cpp/axiangpredict-text.cpp",
                            "app/src/main/cpp/axiangpredict-jni.cpp","scripts/build-axiang-nextword.py",
                            "scripts/test-axiang-nextword.py","scripts/check-axiang-nextword.cpp",
                            COMPLETION_SOURCE,"scripts/build-axiang-nextword-completions.py","scripts/check-axiang-nextword-completions.py",
                            "scripts/prepare-axiang-touch8-2-delivery.py","docs/axiang/touch8.2.md",
                            "scripts/check-axiang-prediction-benchmark.cpp","scripts/check-axiang-prediction-benchmark.kt",
                            "scripts/check-axiang-prediction-benchmark.py","scripts/evaluate-axiang-prediction-benchmark.py",
                            "scripts/test-axiang-prediction-benchmark.py",BENCHMARK_SOURCE})
        require(permitted and not Path(name).is_absolute() and ".." not in Path(name).parts and
                Path(name).suffix in {".kt",".kts",".xml",".cpp",".h",".py",".md",".txt",".tsv"} and
                (Path(name).suffix!=".tsv" or name in {COMPLETION_SOURCE,BENCHMARK_SOURCE}),"Unreviewed public text path: "+name)
        raw=git(root,"show",source+":"+name);raw.decode("utf-8")
        require(len(raw)<(COMPLETION_MAX_BYTES if name==COMPLETION_SOURCE else 1000000) and b"\0" not in raw and not re.search(
            rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|/tmp/codex-" rb"remote-attachments/",raw),
            "Private/binary source payload: "+name)
        records.append({"path":name,"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()})
    raw=git(root,"diff","--full-index",BASE,source,"--",*paths)
    require(not re.search(rb"(?m)^GIT binary patch$",raw),"Public patch contains binary data")
    return raw,{"base_commit":BASE,"source_commit":source,"files":paths,"source_files":records,
                "patch":"typing-test-source.patch","bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),
                "private_logs_prebuilts_and_generated_binaries_included":False}


def archive_records(path,native_count,asset_count=350):
    with zipfile.ZipFile(path) as archive:
        all_names=[i.filename for i in archive.infolist() if not i.is_dir()]
        require(len(all_names)==len(set(all_names)),"Duplicate APK ZIP entries")
        names=sorted(n for n in all_names if n.startswith(("assets/","lib/")))
        counts={"assets":sum(n.startswith("assets/") for n in names),"lib":sum(n.startswith("lib/") for n in names)}
        require(counts=={"assets":asset_count,"lib":native_count},"Actual packaged input counts differ")
        return [{"path":n,"bytes":archive.getinfo(n).file_size,"sha256":hashlib.sha256(archive.read(n)).hexdigest()}
                for n in names],counts


def run(cmd,root,log,env=None):
    with log.open("w") as stream:subprocess.run([str(x) for x in cmd],cwd=root,env=env,check=True,
                                               stdout=stream,stderr=subprocess.STDOUT)


def native_proof(root,baseline,records,patch_manifest):
    """Check pinned original proof bytes and unchanged sources; never re-label."""
    proof=baseline/"native-prediction-provenance.json"
    test=baseline/"native-prediction-test-summary.json"
    require(proof.is_file() and test.is_file() and not proof.is_symlink() and not test.is_symlink() and
            digest(proof)==BASE_NATIVE_PROOF_HASH and digest(test)==BASE_NATIVE_SUMMARY_HASH,
            "Exact public touch.8.1 native proof bytes differ")
    provenance=json.loads(proof.read_text());summary=json.loads(test.read_text())
    inputs={r["path"]:r for r in records}
    require(provenance.get("schema")==summary.get("schema")==1 and
            provenance.get("native_sha256")==summary.get("native_sha256")==inputs[BRIDGE]["sha256"] and
            provenance.get("native_bytes")==inputs[BRIDGE]["bytes"] and provenance.get("abi")=="arm64-v8a" and
            provenance.get("base_apk_sha256")==BASE_NATIVE_ORIGIN_APK and
            min(provenance.get("elf_load_alignment",[0]))>=16384 and
            summary.get("source_commit")==BASE and summary.get("source_checkout_dirty") is False and
            summary.get("host",{}).get("passed") is True and summary.get("host",{}).get("checks")==18 and
            summary.get("arm",{}).get("passed") is True and summary.get("arm",{}).get("checks")==359 and
            summary.get("arm",{}).get("nonempty_queries")==len(summary.get("arm",{}).get("queries",[]))==17 and
            summary.get("actual_android_shared_libime_execution") is True and
            summary.get("all_dependency_and_model_hashes_unchanged") is True and
            summary.get("host_language_model_execution") is False and summary.get("phone_latency_verified") is False and
            provenance.get("user_dictionary_learning") is False and provenance.get("writes_model_or_user_files") is False and
            provenance.get("rime_initialized_or_modified") is False and provenance.get("neural_model_added") is False,
            "Inherited native proof origin/ABI/isolation differs")
    require(summary["source_files_sha256"]==provenance["source_files_sha256"],"Native source maps differ")
    unchanged={};changed=set(patch_manifest["files"])
    for mapping in (provenance["source_files_sha256"],summary["harness_sources_sha256"]):
        for name,checksum in mapping.items():
            require(name not in changed and digest(root/name)==checksum and
                    hashlib.sha256(git(root,"show",BASE+":"+name)).hexdigest()==checksum,
                    "Native source changed; inherited-only build forbidden: "+name)
            unchanged[name]={"path":name,"bytes":(root/name).stat().st_size,"sha256":checksum}
    for field,prefix in (("model_files","assets/usr/share/libime/"),("dependency_files","lib/arm64-v8a/")):
        require(summary.get(field)==provenance.get(field) and all(
            inputs.get(prefix+name)==dict(path=prefix+name,**r) for name,r in provenance[field].items()),
            "Inherited model/dependency differs from actual baseline APK")
    patch_manifest["unchanged_native_source_files"]=sorted(unchanged.values(),key=lambda r:r["path"])
    patch_manifest["baseline_native_provenance_sha256"]=BASE_NATIVE_PROOF_HASH
    patch_manifest["baseline_native_test_summary_sha256"]=BASE_NATIVE_SUMMARY_HASH
    return provenance,summary


def benchmark_proof(root,work,source,completion_hash,bridge_hash,baseline_records,patch_manifest):
    summary=json.loads((work/"summary.json").read_text())
    require(summary.get("schema")==1 and summary.get("passed") is True and
            summary.get("source_commit")==source and summary.get("baseline_source_commit")==BASE and
            all(summary.get(k)==200 for k in ("cases","successful_native_queries","successful_current_kotlin_queries","successful_baseline_kotlin_queries")) and
            summary.get("benchmark_sha256")==BENCHMARK_SHA256 and summary.get("native_sha256")==bridge_hash and
            summary.get("completion_asset_sha256")==completion_hash and
            summary.get("baseline_completion_asset_sha256")==BASE_COMPLETION_HASH and
            summary.get("all_dependency_and_model_hashes_unchanged") is True and
            summary.get("actual_android_shared_libime_execution") is True and
            all(summary.get(k) is False for k in ("art_verified","phone_ui_or_installation_verified","phone_latency_verified","user_intent_accuracy_measured")),
            "Fresh known-continuation benchmark execution/source/scope differs")
    inputs={r["path"]:{k:r[k] for k in ("bytes","sha256")} for r in baseline_records}
    executed=summary.get("executed_files_match_actual_baseline_apk",{})
    expected_entries={BRIDGE,"lib/arm64-v8a/libIMECore.so","lib/arm64-v8a/libFcitx5Utils.so","lib/arm64-v8a/libc++_shared.so",
                      "assets/usr/share/libime/zh_CN.lm","assets/usr/share/libime/zh_CN.lm.predict"}
    require(set(executed)==expected_entries and all(executed[n]==inputs[n] for n in expected_entries),
            "Benchmark executed files differ from exact baseline APK")
    mappings=summary.get("source_files_sha256",{})
    require(mappings and all(digest(root/name)==checksum for name,checksum in mappings.items()),
            "Benchmark compiled source differs from exact frozen source")
    spec=importlib.util.spec_from_file_location("touch82_benchmark_scorer",root/"scripts/evaluate-axiang-prediction-benchmark.py")
    evaluator=importlib.util.module_from_spec(spec);spec.loader.exec_module(evaluator)
    gold,gold_hash=evaluator._benchmark.load_benchmark(root/BENCHMARK_SOURCE)
    require(gold_hash==BENCHMARK_SHA256,"Frozen gold identity changed")
    evaluation=json.loads((work/"evaluation.json").read_text())
    require(evaluation.get("schema")=="axiang-continuation-benchmark-result-v1" and
            evaluation.get("benchmark_sha256")==BENCHMARK_SHA256,"Benchmark scorer identity differs")
    scores={};changed=set(patch_manifest["files"]);native_sources={r["path"] for r in patch_manifest["unchanged_native_source_files"]}
    unchanged={};baseline_sources={}
    for name in ("current","baseline"):
        path=work/(name+"-candidates.json");raw=json.loads(path.read_text());provenance=raw.get("provenance",{})
        expected_source=source if name=="current" else BASE
        expected_asset=completion_hash if name=="current" else BASE_COMPLETION_HASH
        require(provenance.get("source_commit")==expected_source and provenance.get("baseline_apk_sha256")==BASE_APK_HASH and
                provenance.get("completion_asset_sha256")==expected_asset and provenance.get("native_sha256")==bridge_hash and
                provenance.get("benchmark_sha256")==BENCHMARK_SHA256 and
                provenance.get("executed_files_match_actual_baseline_apk")==executed and
                provenance.get("actual_android_shared_libime_execution") is True and
                all(provenance.get(k) is False for k in ("art_verified","phone_ui_or_installation_verified","phone_latency_verified","user_intent_accuracy_measured")),
                "Actual "+name+" candidate provenance differs")
        kotlin_sources=provenance.get("production_kotlin_sources_sha256",{})
        require(len(kotlin_sources)==5 and all(hashlib.sha256(git(root,"show",expected_source+":"+p)).hexdigest()==checksum
                    for p,checksum in kotlin_sources.items()),"Actual "+name+" Kotlin source differs")
        if name=="baseline":
            for p,checksum in kotlin_sources.items():
                baseline_sources[p]={"path":p,"bytes":len(git(root,"show",BASE+":"+p)),"sha256":checksum}
        values,proof=evaluator.load_candidates(path,gold);scores[name]=evaluator.evaluate(gold,values)
        require(evaluation.get(name)==scores[name] and summary.get(name)==scores[name]["all"] and
                summary.get(name+"_splits")==scores[name]["splits"] and
                evaluation.get(name+"_provenance")==proof,"Actual benchmark results differ from independently recomputed score")
    delta={k:scores["current"]["all"][k]-scores["baseline"]["all"][k] for k in
           ("covered_top5","covered_first","empty_offers","explicit_negative_violating_contexts")}
    require(summary.get("delta")==evaluation.get("delta")==delta,"Actual benchmark comparison differs")
    for name,checksum in mappings.items():
        if name in changed or name in native_sources:continue
        require(hashlib.sha256(git(root,"show",BASE+":"+name)).hexdigest()==checksum,"Unchanged benchmark source differs")
        unchanged[name]={"path":name,"bytes":(root/name).stat().st_size,"sha256":checksum}
    patch_manifest["unchanged_benchmark_source_files"]=sorted(unchanged.values(),key=lambda r:r["path"])
    patch_manifest["baseline_benchmark_source_files"]=sorted(baseline_sources.values(),key=lambda r:r["path"])
    return summary


def execute(a):
    root=a.root.resolve();work=a.work.resolve();a.output=a.output.resolve()
    protected=[p.resolve() for p in (root,a.prebuilt,a.baseline,a.sdk,a.completion_data_root,a.completion_source_manifest,
                                a.ndk,a.android_runtime,a.qemu,a.java,a.gradle_cache)]
    generated=[work,a.output]
    def overlaps(left,right):
        return left==right or left in right.parents or right in left.parents
    require(all(not overlaps(g,p) for g in generated for p in protected) and
            all(not overlaps(left,right) for i,left in enumerate(generated) for right in generated[i+1:]),
            "Generated roots must be separate from each other and all source/read-only inputs")
    work.mkdir(parents=True,exist_ok=True)
    frozen(root,a.source_commit)
    patch_bytes,patch_manifest=source_patch(root,a.source_commit,a.source_allowlist)
    m=load_common(root);common=m.common
    baseline=a.baseline/"AXiang-1.2-touch.8.1-arm64.apk"
    require(baseline.is_file() and not baseline.is_symlink() and baseline.stat().st_size==BASE_APK_BYTES and digest(baseline)==BASE_APK_HASH,
            "Actual public touch.8.1 APK identity differs")
    before,counts=archive_records(baseline,29,351)
    originals=[r for r in before if r["path"] not in {BRIDGE,COMPLETION}]
    base_completion=next(r for r in before if r["path"]==COMPLETION)
    require(base_completion==dict(path=COMPLETION,bytes=BASE_COMPLETION_BYTES,sha256=BASE_COMPLETION_HASH),"Baseline completion identity differs")
    for record in originals:
        name=record["path"];path=a.prebuilt/("jniLibs/"+name.removeprefix("lib/") if name.startswith("lib/") else name)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size==record["bytes"] and digest(path)==record["sha256"],
                "Read-only prebuilt byte differs from baseline: "+name)
    with zipfile.ZipFile(baseline) as archive:speech=json.loads(archive.read("assets/asr/SOURCE.json"))
    require(digest(root/"app/libs/sherpa-onnx-1.13.8.jar")==speech["compile_jar"]["sha256"],"Unchanged ASR compile JAR differs")
    completion=root/COMPLETION_SOURCE
    require(completion.is_file() and not completion.is_symlink() and 0<completion.stat().st_size<COMPLETION_MAX_BYTES and
            completion.read_bytes()==git(root,"show",a.source_commit+":"+COMPLETION_SOURCE),
            "Reviewed frozen completion asset differs")
    completion_bytes=completion.stat().st_size;completion_hash=digest(completion)
    require(completion_hash!=BASE_COMPLETION_HASH,"Expanded completion resource must differ from baseline")
    provenance,native_summary=native_proof(root,a.baseline,before,patch_manifest)
    baseline_test_path=a.baseline/"unit-test-summary.json"
    require(digest(baseline_test_path)==BASE_JUNIT_SUMMARY_HASH,"Exact prior full-suite class manifest differs")
    baseline_tests=json.loads(baseline_test_path.read_text())
    require(baseline_tests.get("source_commit")==BASE and baseline_tests.get("tests")==820 and
            len(set(baseline_tests.get("class_names",[])))==baseline_tests.get("classes")==99, "Prior full-suite identity differs")
    require(digest(root/BENCHMARK_SOURCE)==BENCHMARK_SHA256,"Frozen public gold200 differs")
    inherited_native={"schema":"touch8.2-inherited-native-v1","source_commit":a.source_commit,
                      "baseline_source_commit":BASE,"baseline_apk_sha256":BASE_APK_HASH,
                      "native_proof_source_commit":BASE,"native_provenance_sha256":BASE_NATIVE_PROOF_HASH,
                      "native_test_summary_sha256":BASE_NATIVE_SUMMARY_HASH,
                      "prediction_library_sha256":provenance["native_sha256"],
                      "all_29_native_libraries_byte_identical":True,"all_350_original_assets_byte_identical":True,
                      "native_rebuilt":False,"native_host_tests_reexecuted":False,"native_arm_tests_reexecuted":False,
                      "inherited_host_checks":native_summary["host"]["checks"],
                      "inherited_arm_checks":native_summary["arm"]["checks"],
                      "inherited_nonempty_contexts":native_summary["arm"]["nonempty_queries"],
                      "proofs":[{"name":n,"sha256":checksum,"url":"https://github.com/xisungod/fcitx5-android/releases/download/axiang-touch-1.2-touch.8.1/"+n}
                          for n,checksum in (("native-prediction-provenance.json",BASE_NATIVE_PROOF_HASH),
                                             ("native-prediction-test-summary.json",BASE_NATIVE_SUMMARY_HASH))]}
    if a.prepare_only:
        print(json.dumps({"prepared_source_review":True,"source_commit":a.source_commit,"verified_original_inputs":378,
                          "baseline_inputs":380,"changed_completion_sha256":completion_hash,"changed_completion_bytes":completion_bytes,
                          "inherited_native_proofs_verified":True,"build_started":False,"native_checks_started":False}));return
    run([sys.executable,"-B",root/"scripts/check-axiang-nextword-completions.py","--data-root",a.completion_data_root,
         "--source-manifest",a.completion_source_manifest,"-v"],root,work/"completion-checks.log")
    completion_log=(work/"completion-checks.log").read_text()
    completion_counts=re.findall(r"(?m)^Ran ([0-9]+) tests? in ",completion_log)
    require(len(completion_counts)==1 and int(completion_counts[0])>=11 and re.search(r"(?m)^OK$",completion_log) and
            not re.search(r"(?mi)skipped|failures=|errors=|FAILED",completion_log),"Complete completion extraction/reproduction checks required")
    completion_summary={"schema":"touch8.2-completion-v1","source_commit":a.source_commit,"source_checkout_dirty":False,
                        "tests":int(completion_counts[0]),"failures":0,"errors":0,"skipped":0,
                        "resource":COMPLETION,"resource_bytes":completion_bytes,"resource_sha256":completion_hash,
                        "fresh_execution":True,"pinned_source_reproduction_verified":True,
                        "test_driver_sha256":digest(root/"scripts/check-axiang-nextword-completions.py"),
                        "producer_sha256":digest(root/"scripts/build-axiang-nextword-completions.py")}
    jni=work/"jniLibs/arm64-v8a"
    require(not (work/"jniLibs").is_symlink() and not jni.is_symlink(),"Staged JNI directories must not be symlinks")
    jni.mkdir(parents=True,exist_ok=True)
    for record in originals:
        if not record["path"].startswith("lib/"):continue
        original=a.prebuilt/("jniLibs/"+record["path"].removeprefix("lib/"))
        staged=jni/Path(record["path"]).name
        require(not staged.is_symlink(),"Staged original JNI must not be a symlink")
        if not staged.exists():os.link(original,staged)
        require(staged.is_file() and digest(staged)==record["sha256"],"Staged original JNI differs")
    staged=jni/"libaxiangpredict.so"
    require(not staged.is_symlink(),"Staged prediction bridge must not be a symlink")
    if staged.exists():staged.unlink()
    with zipfile.ZipFile(baseline) as archive:staged.write_bytes(archive.read(BRIDGE))
    require({p.name for p in jni.iterdir()}=={Path(r["path"]).name for r in before if r["path"].startswith("lib/")}|{staged.name},
            "Extra staged native file")
    bridge_record={"path":BRIDGE,"bytes":staged.stat().st_size,"sha256":digest(staged)}
    asset_root_path=work/"asset-stage"
    require(not asset_root_path.is_symlink(),"Asset stage must not be a symlink")
    for record in originals:
        if not record["path"].startswith("assets/"):continue
        destination=asset_root_path/record["path"]
        destination.parent.mkdir(parents=True,exist_ok=True)
        require(destination.parent.resolve()==destination.parent,"Asset stage parents must not contain symlinks")
        require(not destination.is_symlink(),"Original asset stage must not be a symlink")
        if not destination.exists():os.link(a.prebuilt/record["path"],destination)
        require(digest(destination)==record["sha256"],"Staged original asset differs")
    asset_destination=asset_root_path/COMPLETION
    require(asset_destination.parent.resolve()==asset_destination.parent,"Completion stage parents must not contain symlinks")
    require(not asset_destination.is_symlink(),"Completion stage must not be a symlink")
    if asset_destination.exists():asset_destination.unlink()
    shutil.copyfile(completion,asset_destination)
    completion_record={"path":COMPLETION,"bytes":completion_bytes,"sha256":completion_hash}
    files=sorted(originals+[bridge_record,completion_record],key=lambda r:r["path"])
    manifest={"schema":"touch8.2-v1","base_commit":BASE,"source_commit":a.source_commit,"source_checkout_dirty":False,
              "source_apk_sha256":BASE_APK_HASH,"source_apk_bytes":BASE_APK_BYTES,"counts":{"assets":351,"lib":29},
              "files":files,"base_files":before,"added_files":[],"changed_assets":[COMPLETION],"changed_native":[],
              "added_native":[]}
    write(work/"prebuilt-manifest.json",manifest)
    benchmark_work=work/"prediction-benchmark"
    run([sys.executable,"-B",root/"scripts/test-axiang-prediction-benchmark.py","--root",root,"--work",benchmark_work,
         "--prebuilt",a.prebuilt,"--bridge",staged,"--baseline-apk",baseline,"--ndk",a.ndk,"--android-runtime",a.android_runtime,"--qemu",a.qemu,
         "--java",a.java,"--gradle-cache",a.gradle_cache,"--baseline-commit",BASE,"--source-commit",a.source_commit],
        root,work/"prediction-benchmark.log")
    benchmark_summary=benchmark_proof(root,benchmark_work,a.source_commit,completion_hash,provenance["native_sha256"],before,patch_manifest)
    frozen(root,a.source_commit)
    # The two verified roots remain separate; never mutate touch.6 pinned inputs.
    asset_root=json.dumps(str(asset_root_path.resolve()),ensure_ascii=False).replace("$","\\$")
    native_root=json.dumps(str((work/"jniLibs").resolve()),ensure_ascii=False).replace("$","\\$")
    init="""// Byte-identical original assets and all native libraries; only completion TSV changes.
def verifiedAssets = new File(@ASSETS@)
def verifiedJni = new File(@JNI@)
gradle.beforeProject { p ->
    p.tasks.configureEach { task ->
        def type = task.class
        def nativeInstaller = false
        while (type != null) {
            if (type.simpleName == 'CMakeBuildInstallTask') nativeInstaller = true
            type = type.superclass
        }
        if (nativeInstaller) task.enabled = false
    }
    ['com.android.application', 'com.android.library'].each { plugin ->
        p.plugins.withId(plugin) {
            p.extensions.getByName('androidComponents').finalizeDsl { dsl ->
                dsl.externalNativeBuild.cmake.path = null
                dsl.buildFeatures.prefab = false
                if (plugin == 'com.android.library') dsl.buildFeatures.prefabPublishing = false
                if (p.path == ':app') {
                    dsl.buildTypes.getByName('debug').resValue('string', 'app_name', 'AXiang 触点测试2')
                    dsl.sourceSets.getByName('main').assets.setSrcDirs([new File(verifiedAssets, 'assets')])
                    dsl.sourceSets.getByName('main').jniLibs.setSrcDirs([verifiedJni])
                    dsl.packaging.jniLibs.keepDebugSymbols.add('**/*.so')
                }
            }
        }
    }
}
""".replace("@ASSETS@",asset_root).replace("@JNI@",native_root)
    (work/"inputs.init.gradle").write_text(init)
    (work/"test.init.gradle").write_text("gradle.beforeProject { p -> p.tasks.withType(Test).configureEach { maxHeapSize = '2g'; outputs.upToDateWhen { false } } }\n")
    results=root/"app/build/test-results/testDebugUnitTest"
    if results.exists():shutil.rmtree(results)
    env=dict(os.environ,AXIANG_SOURCE_ROOT=str(root),XUANCAI_APP_SUFFIX=".axiang.touch2")
    run(["/workspace/toolchains/axiang/gradle","--no-daemon","--offline","-I",work/"inputs.init.gradle","-I",work/"test.init.gradle",
         "-PbuildABI=arm64-v8a","-PbuildVersionName="+VERSION,"-PbuildVersionCode="+str(CODE),"-PbuildCommitHash="+a.source_commit,
         ":app:assembleDebug",":app:testDebugUnitTest"],root,work/"full-build-tests.log",env)
    frozen(root,a.source_commit)
    tests,reports=common.unit_test_summary(root,a.expected_tests)
    require(tests["tests"]>=820 and tests["classes"]>=99,"Complete app regression suite required")
    class_names=sorted(ET.parse(report).getroot().get("name") for report in reports)
    require(len(class_names)==len(set(class_names))==len(reports),"JUnit classes must be distinct")
    require(set(baseline_tests["class_names"])<=set(class_names),"Full suite dropped prior test classes")
    tests["baseline_class_names"]=baseline_tests["class_names"]
    tests["baseline_unit_test_summary_sha256"]=BASE_JUNIT_SUMMARY_HASH
    tests["class_names"]=class_names
    tests["result_files"]=[{"path":report.name,"sha256":digest(report)} for report in reports]
    tests.update({"source_commit":a.source_commit,"scope":"full_application_suite","fresh_execution":True})
    candidates=[p for p in (root/"app/build/outputs/apk/debug").glob("*.apk") if VERSION in p.name]
    require(len(candidates)==1,"Expected one newly versioned signed APK")
    apk=candidates[0];tools=a.sdk/"build-tools/36.1.0"
    run([sys.executable,"-B",root/"scripts/verify-xuancai-rime-package.py",apk,"--bundled-rime","--offline-dictation",
         "--aapt",tools/"aapt","--apksigner",tools/"apksigner","--expected-main-package",m.PACKAGE,
         "--expected-app-label",m.LABEL,"--expected-version-name",VERSION,"--expected-version-code",str(CODE)],root,work/"package-check.log")
    signature=subprocess.check_output([tools/"apksigner","verify","--print-certs",apk],text=True)
    require(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",signature,re.M)==[m.SIGNER],"Durable signer differs")
    run([tools/"zipalign","-c","-P","16","4",apk],root,work/"zipalign.log")
    permissions=subprocess.check_output([tools/"aapt","dump","permissions",apk],text=True)
    require("android.permission.INTERNET" not in permissions,"Unexpected INTERNET permission")
    after,after_counts=archive_records(apk,29,351)
    require(after==files,"Actual APK altered assets/original native bytes or expanded completion resource")
    for record in originals:
        name=record["path"];path=a.prebuilt/("jniLibs/"+name.removeprefix("lib/") if name.startswith("lib/") else name)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size==record["bytes"] and digest(path)==record["sha256"],
                "Build changed a read-only baseline input: "+name)
    marker=common.compiled_source(apk);require(marker["value"]==a.source_commit,"Actual DEX marker differs")
    version_raw=subprocess.check_output([sys.executable,"-B",root/"scripts/verify-packaged-rime-version.py",apk,"--expected-library-sha256",m.RIME_SHA256])
    packaged=m.public_json(version_raw,"Packaged engine verification")
    previous_path=a.baseline/"inherited-rime-evidence.json"
    require(previous_path.is_file() and not previous_path.is_symlink() and digest(previous_path)==BASE_INHERITED_HASH,
            "Exact public touch.8.1 inherited Rime evidence differs")
    previous=json.loads(previous_path.read_text())
    require(previous.get("schema")=="touch8.1-inherited-rime-v1" and previous.get("source_commit")==BASE and
            previous.get("original_proof_source_commit")=="e5af7f1ead74c6891fa2035239e4d7ed523b0ed5" and
            previous.get("all_350_assets_byte_identical") is True and previous.get("original_28_native_libraries_byte_identical") is True and
            previous.get("old_rime_host_tests_reexecuted") is False and previous.get("old_rime_arm_smoke_reexecuted") is False,
            "Baseline Rime evidence identity differs")
    inherited={"schema":"touch8.2-inherited-rime-v1","source_commit":a.source_commit,"baseline_source_commit":BASE,
               "baseline_apk_sha256":BASE_APK_HASH,"original_28_native_libraries_byte_identical":True,
               "all_350_assets_byte_identical":True,"old_rime_host_tests_reexecuted":False,"old_rime_arm_smoke_reexecuted":False,
               "proofs":previous["proofs"],"original_proof_source_commit":previous["original_proof_source_commit"]}
    defaults=m.defaults(root)
    prefs=(root/"app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt").read_text()
    require(re.findall(r'"local_next_word_prediction"\s*,\s*(true|false)',prefs)==["true"],"Next-word default must be enabled")
    defaults["local_next_word_prediction"]=True
    verification={"schema":"touch8.2-v1","base_commit":BASE,"package":m.PACKAGE,"label":m.LABEL,"version_name":VERSION,"version_code":CODE,
                  "abi":"arm64-v8a","compiled_source_commit":a.source_commit,"compiled_source_marker":marker,
                  "source_checkout_head":a.source_commit,"source_checkout_dirty":False,"source_apk_sha256":BASE_APK_HASH,
                  "apk_sha256":digest(apk),"apk_bytes":apk.stat().st_size,"asset_count":351,"native_count":29,
                  "changed_assets":[COMPLETION],"changed_native":[],"added_native":[],"added_assets":[],"original_28_native_libraries_byte_identical":True,
                  "all_350_assets_byte_identical":True,"all_29_native_libraries_byte_identical":True,
                  "prediction_library_sha256":digest(staged),
                  "signature_verified":True,"signature_certificate_sha256":m.SIGNER,"zip_alignment_verified":True,
                  "network_permission":False,"new_neural_model_included":False,"new_model_asset_included":False,
                  "packaged_rime_version":m.RIME_VERSION,"packaged_librime_sha256":m.RIME_SHA256,
                  "unit_tests":tests["tests"],"unit_test_classes":tests["classes"],"unit_test_failures":0,"unit_test_errors":0,"unit_test_skipped":0,
                  "unit_test_scope":"full_application_suite","fresh_full_app_suite":True,
                  "inherited_native_prediction_host_checks":native_summary["host"]["checks"],"inherited_native_prediction_arm_checks":native_summary["arm"]["checks"],
                  "inherited_native_prediction_nonempty_contexts":native_summary["arm"]["nonempty_queries"],
                  "native_prediction_proof_source_commit":BASE,"native_prediction_tests_reexecuted":False,"native_prediction_rebuilt":False,
                  "completion_resource_tests":completion_summary["tests"],"completion_resource_sha256":completion_hash,
                  "completion_resource_bytes":completion_bytes,"new_completion_asset_included":False,"expanded_completion_asset_included":True,
                  "prediction_benchmark_cases":200,"fresh_prediction_benchmark_execution":True,
                  "prediction_benchmark_source_sha256":BENCHMARK_SHA256,
                  "prediction_benchmark_current":benchmark_summary["current"],
                  "prediction_benchmark_baseline":benchmark_summary["baseline"],
                  "prediction_benchmark_delta":benchmark_summary["delta"],
                  "native_prediction_scope":"host_utf8_and_actual_android_bionic_libime_FakeJNI_not_ART_or_phone",
                  "old_rime_host_tests_reexecuted":False,"old_rime_arm_smoke_reexecuted":False,
                  "next_word_prediction_backend":"existing_libime_statistical_language_model",
                  "independent_next_word_worker_added":False,"independent_rime_worker_thread_added":False,
                  "default_experimental_switches":defaults,"device_installation_or_launch_verified":False,
                  "phone_accuracy_or_latency_verified":False,"release_kind":"three_source_completion_expansion_prerelease","publication_performed":False}
    snapshot={"branch":git(root,"branch","--show-current").decode().strip(),"commit":a.source_commit,"tree":git(root,"rev-parse","HEAD^{tree}").decode().strip(),
              "compiled_marker":marker,"compiled_source_is_fixed_commit":True,"private_logs_included":False,
              "url":"https://github.com/xisungod/fcitx5-android/tree/"+a.source_commit}
    guide=root/"docs/axiang/touch8.2.md"
    require(guide.is_file() and guide.read_bytes()==git(root,"show",a.source_commit+":docs/axiang/touch8.2.md"),"Guide must be exact frozen documentation")
    require(not a.output.exists() or not any(a.output.iterdir()),"Use an empty output folder")
    a.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="touch8-2-delivery-",dir=a.output.parent) as temporary:
        staging=Path(temporary);shutil.copyfile(apk,staging/APK);shutil.copyfile(guide,staging/"GUIDE.zh-CN.md")
        for name,value in (("build-verification.json",verification),("unit-test-summary.json",tests),("prebuilt-manifest.json",manifest),
                           ("inherited-rime-evidence.json",inherited),("packaged-rime-version.json",packaged),("source-snapshot.json",snapshot),
                           ("source-patch-manifest.json",patch_manifest),("inherited-native-prediction-evidence.json",inherited_native),
                           ("completion-resource-test-summary.json",completion_summary)):write(staging/name,value)
        for name in ("native-prediction-provenance.json","native-prediction-test-summary.json"):
            shutil.copyfile(a.baseline/name,staging/name)
        for source_name,public_name in BENCHMARK_FILES.items():
            shutil.copyfile(benchmark_work/source_name,staging/public_name)
        (staging/"typing-test-source.patch").write_bytes(patch_bytes)
        with zipfile.ZipFile(staging/"full-junit-reports.zip","w",zipfile.ZIP_DEFLATED) as archive:
            for report in reports:archive.write(report,report.name)
        (staging/"SHA256SUMS.txt").write_text("".join(digest(p)+"  "+p.name+"\n" for p in sorted(staging.iterdir())))
        require({p.name for p in staging.iterdir()}==FILES,"Public artifact whitelist differs")
        frozen(root,a.source_commit)
        if a.output.exists():a.output.rmdir()
        staging.rename(a.output)
    print(json.dumps(verification,ensure_ascii=False))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,default=Path.cwd());p.add_argument("--source-commit",required=True)
    p.add_argument("--source-allowlist",type=Path,required=True);p.add_argument("--work",type=Path,required=True)
    p.add_argument("--baseline",type=Path,required=True)
    p.add_argument("--prebuilt",type=Path,required=True);p.add_argument("--sdk",type=Path,required=True)
    p.add_argument("--ndk",type=Path,required=True);p.add_argument("--android-runtime",type=Path,required=True)
    p.add_argument("--qemu",type=Path,required=True);p.add_argument("--java",type=Path,required=True)
    p.add_argument("--gradle-cache",type=Path,required=True)
    p.add_argument("--completion-data-root",type=Path,required=True);p.add_argument("--completion-source-manifest",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True);p.add_argument("--expected-tests",type=int)
    p.add_argument("--prepare-only",action="store_true",help="Review frozen source/input identity only; no build or execution")
    execute(p.parse_args())


if __name__=="__main__":main()
