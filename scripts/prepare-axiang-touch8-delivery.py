#!/usr/bin/env python3
"""Build and verify frozen touch.8 with fresh app tests and small next-word checks.

Assets and original 28 native libraries remain byte-identical to touch.7.1.
Only libaxiangpredict.so is added. Old Rime tests are explicitly inherited.
No publication or signing-key access; prebuilt baseline stays read-only.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,os,re,shutil,struct,subprocess,sys,tempfile,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
BASE="a4dfe903826916c5388d08e3e8e7a73bb00775a2"
BASE_APK_HASH="4aff6ece07e080e90a3d3376171b69f7d315d93a17ad0fd9484c91f1a4d2ddf1"
BASE_APK_BYTES=268353151
BASE_INHERITED_HASH="3a97fafe165bb7800b4a634ea4985d1d7261edfdc22e1fd76b094a5169c8112a"
VERSION,CODE="1.2-touch.8",9
APK="AXiang-1.2-touch.8-arm64.apk"
BRIDGE="lib/arm64-v8a/libaxiangpredict.so"
FILES={APK,"GUIDE.zh-CN.md","SHA256SUMS.txt","build-verification.json","full-junit-reports.zip",
       "prebuilt-manifest.json","inherited-rime-evidence.json","packaged-rime-version.json",
       "source-patch-manifest.json","source-snapshot.json","typing-test-source.patch","unit-test-summary.json",
       "native-prediction-provenance.json","native-prediction-test-summary.json"}


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
    require(git(root,"rev-parse","HEAD^").decode().strip()==BASE,"touch.8 must have public touch.7.1 as one parent")


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
                            "scripts/prepare-axiang-touch8-delivery.py","docs/axiang/touch8.md"})
        require(permitted and not Path(name).is_absolute() and ".." not in Path(name).parts and
                Path(name).suffix in {".kt",".kts",".xml",".cpp",".h",".py",".md",".txt"},"Unreviewed public text path: "+name)
        raw=git(root,"show",source+":"+name);raw.decode("utf-8")
        require(len(raw)<1000000 and b"\0" not in raw and not re.search(
            rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|/tmp/codex-" rb"remote-attachments/",raw),
            "Private/binary source payload: "+name)
        records.append({"path":name,"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest()})
    raw=git(root,"diff","--full-index",BASE,source,"--",*paths)
    require(not re.search(rb"(?m)^GIT binary patch$",raw),"Public patch contains binary data")
    return raw,{"base_commit":BASE,"source_commit":source,"files":paths,"source_files":records,
                "patch":"typing-test-source.patch","bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),
                "private_logs_prebuilts_and_generated_binaries_included":False}


def archive_records(path,native_count):
    with zipfile.ZipFile(path) as archive:
        all_names=[i.filename for i in archive.infolist() if not i.is_dir()]
        require(len(all_names)==len(set(all_names)),"Duplicate APK ZIP entries")
        names=sorted(n for n in all_names if n.startswith(("assets/","lib/")))
        counts={"assets":sum(n.startswith("assets/") for n in names),"lib":sum(n.startswith("lib/") for n in names)}
        require(counts=={"assets":350,"lib":native_count},"Actual packaged input counts differ")
        return [{"path":n,"bytes":archive.getinfo(n).file_size,"sha256":hashlib.sha256(archive.read(n)).hexdigest()}
                for n in names],counts


def run(cmd,root,log,env=None):
    with log.open("w") as stream:subprocess.run([str(x) for x in cmd],cwd=root,env=env,check=True,
                                               stdout=stream,stderr=subprocess.STDOUT)


def native_proof(root,work,source):
    provenance=json.loads((work/"provenance.json").read_text());summary=json.loads((work/"test-summary.json").read_text())
    require(provenance.get("schema")==1 and summary.get("schema")==1 and
            digest(work/"libaxiangpredict.so")==provenance.get("native_sha256")==summary.get("native_sha256"),"Actual prediction bridge differs")
    require(summary.get("source_commit")==source and summary.get("source_checkout_dirty") is False and
            summary.get("host",{}).get("passed") is True and summary.get("host",{}).get("checks")==18 and
            summary.get("arm",{}).get("passed") is True and summary.get("arm",{}).get("checks")==230 and
            summary.get("arm",{}).get("nonempty_queries")==12 and
            summary.get("actual_android_shared_libime_execution") is True and
            summary.get("all_dependency_and_model_hashes_unchanged") is True and
            summary.get("host_language_model_execution") is False and
            summary.get("phone_latency_verified") is False,"Fresh prediction host/ARM checks did not pass")
    require(provenance.get("base_apk_sha256")==BASE_APK_HASH and provenance.get("abi")=="arm64-v8a" and
            min(provenance.get("elf_load_alignment",[0]))>=16384 and
            provenance.get("user_dictionary_learning") is False and provenance.get("writes_model_or_user_files") is False and
            provenance.get("rime_initialized_or_modified") is False and provenance.get("neural_model_added") is False,
            "Prediction ABI/isolation proof differs")
    for mapping in (provenance["source_files_sha256"],summary["harness_sources_sha256"]):
        for name,checksum in mapping.items():require(digest(root/name)==checksum,"Native proof source differs: "+name)
    require(summary["source_files_sha256"]==provenance["source_files_sha256"],"Native source proof maps differ")
    return provenance,summary


def execute(a):
    root=a.root.resolve();work=a.work.resolve();a.native_work=a.native_work.resolve();a.output=a.output.resolve()
    protected=[p.resolve() for p in (root,a.prebuilt,a.baseline,a.sdk,a.ndk,a.boost_headers,a.android_runtime)]
    generated=[work,a.native_work,a.output]
    def overlaps(left,right):
        return left==right or left in right.parents or right in left.parents
    require(all(not overlaps(g,p) for g in generated for p in protected) and
            all(not overlaps(left,right) for i,left in enumerate(generated) for right in generated[i+1:]),
            "Generated roots must be separate from each other and all source/read-only inputs")
    work.mkdir(parents=True,exist_ok=True)
    frozen(root,a.source_commit)
    patch_bytes,patch_manifest=source_patch(root,a.source_commit,a.source_allowlist)
    m=load_common(root);common=m.common
    baseline=a.baseline/"AXiang-1.2-touch.7.1-arm64.apk"
    require(baseline.is_file() and not baseline.is_symlink() and baseline.stat().st_size==BASE_APK_BYTES and digest(baseline)==BASE_APK_HASH,
            "Actual public touch.7.1 APK identity differs")
    before,counts=archive_records(baseline,28)
    for record in before:
        name=record["path"];path=a.prebuilt/("jniLibs/"+name.removeprefix("lib/") if name.startswith("lib/") else name)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size==record["bytes"] and digest(path)==record["sha256"],
                "Read-only prebuilt byte differs from baseline: "+name)
    with zipfile.ZipFile(baseline) as archive:speech=json.loads(archive.read("assets/asr/SOURCE.json"))
    require(digest(root/"app/libs/sherpa-onnx-1.13.8.jar")==speech["compile_jar"]["sha256"],"Unchanged ASR compile JAR differs")
    if a.prepare_only:
        print(json.dumps({"prepared_source_review":True,"source_commit":a.source_commit,"verified_original_inputs":378,
                          "build_started":False,"native_checks_started":False}));return
    # Actual fresh small checks, after freeze; do not reuse or re-label prototype evidence.
    run([sys.executable,"-B",root/"scripts/build-axiang-nextword.py","--root",root,"--work",a.native_work,
         "--apk",baseline,"--prebuilt",a.prebuilt,"--ndk",a.ndk,"--boost-headers",a.boost_headers],root,work/"native-build.log")
    run([sys.executable,"-B",root/"scripts/test-axiang-nextword.py","--root",root,"--work",a.native_work,
         "--prebuilt",a.prebuilt,"--ndk",a.ndk,"--android-runtime",a.android_runtime,"--qemu",a.qemu,
         "--source-commit",a.source_commit],root,work/"native-checks.log")
    frozen(root,a.source_commit)
    provenance,native_summary=native_proof(root,a.native_work,a.source_commit)
    jni=work/"jniLibs/arm64-v8a"
    require(not (work/"jniLibs").is_symlink() and not jni.is_symlink(),"Staged JNI directories must not be symlinks")
    jni.mkdir(parents=True,exist_ok=True)
    for record in before:
        if not record["path"].startswith("lib/"):continue
        original=a.prebuilt/("jniLibs/"+record["path"].removeprefix("lib/"))
        staged=jni/Path(record["path"]).name
        require(not staged.is_symlink(),"Staged original JNI must not be a symlink")
        if not staged.exists():os.link(original,staged)
        require(staged.is_file() and digest(staged)==record["sha256"],"Staged original JNI differs")
    staged=jni/"libaxiangpredict.so"
    require(not staged.is_symlink(),"Staged prediction bridge must not be a symlink")
    if staged.exists():staged.unlink()
    shutil.copyfile(a.native_work/"libaxiangpredict.so",staged)
    require({p.name for p in jni.iterdir()}=={Path(r["path"]).name for r in before if r["path"].startswith("lib/")}|{staged.name},
            "Extra staged native file")
    bridge_record={"path":BRIDGE,"bytes":staged.stat().st_size,"sha256":digest(staged)}
    files=sorted(before+[bridge_record],key=lambda r:r["path"])
    manifest={"schema":"touch8-v1","base_commit":BASE,"source_commit":a.source_commit,"source_checkout_dirty":False,
              "source_apk_sha256":BASE_APK_HASH,"source_apk_bytes":BASE_APK_BYTES,"counts":{"assets":350,"lib":29},
              "files":files,"base_files":before,"added_files":[BRIDGE],"changed_assets":[],"changed_native":[],
              "added_native":[BRIDGE]}
    write(work/"prebuilt-manifest.json",manifest)
    # The two verified roots remain separate; never mutate touch.6 pinned inputs.
    asset_root=json.dumps(str(a.prebuilt.resolve()),ensure_ascii=False).replace("$","\\$")
    native_root=json.dumps(str((work/"jniLibs").resolve()),ensure_ascii=False).replace("$","\\$")
    init="""// Exact original assets/native inputs plus independently compiled next-word bridge.
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
    require(tests["tests"]>=707 and tests["classes"]>=91,"Complete app regression suite required")
    class_names=sorted(ET.parse(report).getroot().get("name") for report in reports)
    require(len(class_names)==len(set(class_names))==len(reports),"JUnit classes must be distinct")
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
    after,after_counts=archive_records(apk,29)
    require(after==files,"Actual APK altered assets/original natives or changed the new bridge")
    for record in before:
        name=record["path"];path=a.prebuilt/("jniLibs/"+name.removeprefix("lib/") if name.startswith("lib/") else name)
        require(path.is_file() and not path.is_symlink() and path.stat().st_size==record["bytes"] and digest(path)==record["sha256"],
                "Build changed a read-only baseline input: "+name)
    marker=common.compiled_source(apk);require(marker["value"]==a.source_commit,"Actual DEX marker differs")
    version_raw=subprocess.check_output([sys.executable,"-B",root/"scripts/verify-packaged-rime-version.py",apk,"--expected-library-sha256",m.RIME_SHA256])
    packaged=m.public_json(version_raw,"Packaged engine verification")
    previous_path=a.baseline/"inherited-native-evidence.json"
    require(previous_path.is_file() and not previous_path.is_symlink() and digest(previous_path)==BASE_INHERITED_HASH,
            "Exact public touch.7.1 inherited Rime evidence differs")
    previous=json.loads(previous_path.read_text())
    require(previous.get("schema")=="touch7.1-inherited-native-v1" and previous.get("source_commit")==BASE and
            previous.get("inherited_from_source_commit")=="e5af7f1ead74c6891fa2035239e4d7ed523b0ed5" and
            previous.get("all_350_assets_and_28_native_libraries_byte_identical") is True and
            previous.get("native_host_tests_reexecuted") is False and previous.get("android_bionic_smoke_reexecuted") is False,
            "Baseline Rime evidence identity differs")
    inherited={"schema":"touch8-inherited-rime-v1","source_commit":a.source_commit,"baseline_source_commit":BASE,
               "baseline_apk_sha256":BASE_APK_HASH,"original_28_native_libraries_byte_identical":True,
               "all_350_assets_byte_identical":True,"old_rime_host_tests_reexecuted":False,"old_rime_arm_smoke_reexecuted":False,
               "proofs":previous["proofs"],"original_proof_source_commit":previous["inherited_from_source_commit"]}
    defaults=m.defaults(root)
    prefs=(root/"app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt").read_text()
    require(re.findall(r'"local_next_word_prediction"\s*,\s*(true|false)',prefs)==["true"],"Next-word default must be enabled")
    defaults["local_next_word_prediction"]=True
    verification={"schema":"touch8-v1","base_commit":BASE,"package":m.PACKAGE,"label":m.LABEL,"version_name":VERSION,"version_code":CODE,
                  "abi":"arm64-v8a","compiled_source_commit":a.source_commit,"compiled_source_marker":marker,
                  "source_checkout_head":a.source_commit,"source_checkout_dirty":False,"source_apk_sha256":BASE_APK_HASH,
                  "apk_sha256":digest(apk),"apk_bytes":apk.stat().st_size,"asset_count":350,"native_count":29,
                  "changed_assets":[],"changed_native":[],"added_native":[BRIDGE],"original_28_native_libraries_byte_identical":True,
                  "all_350_assets_byte_identical":True,"new_prediction_library_sha256":digest(staged),
                  "signature_verified":True,"signature_certificate_sha256":m.SIGNER,"zip_alignment_verified":True,
                  "network_permission":False,"new_neural_model_included":False,"new_model_asset_included":False,
                  "packaged_rime_version":m.RIME_VERSION,"packaged_librime_sha256":m.RIME_SHA256,
                  "unit_tests":tests["tests"],"unit_test_classes":tests["classes"],"unit_test_failures":0,"unit_test_errors":0,"unit_test_skipped":0,
                  "unit_test_scope":"full_application_suite","fresh_full_app_suite":True,
                  "native_prediction_host_checks":18,"native_prediction_arm_checks":230,"native_prediction_nonempty_contexts":12,
                  "native_prediction_scope":"host_utf8_and_actual_android_bionic_libime_FakeJNI_not_ART_or_phone",
                  "old_rime_host_tests_reexecuted":False,"old_rime_arm_smoke_reexecuted":False,
                  "next_word_prediction_backend":"existing_libime_statistical_language_model",
                  "independent_next_word_worker_added":True,"independent_rime_worker_thread_added":False,
                  "default_experimental_switches":defaults,"device_installation_or_launch_verified":False,
                  "phone_accuracy_or_latency_verified":False,"release_kind":"local_next_word_prediction_prerelease","publication_performed":False}
    snapshot={"branch":git(root,"branch","--show-current").decode().strip(),"commit":a.source_commit,"tree":git(root,"rev-parse","HEAD^{tree}").decode().strip(),
              "compiled_marker":marker,"compiled_source_is_fixed_commit":True,"private_logs_included":False,
              "url":"https://github.com/xisungod/fcitx5-android/tree/"+a.source_commit}
    guide=root/"docs/axiang/touch8.md"
    require(guide.is_file() and guide.read_bytes()==git(root,"show",a.source_commit+":docs/axiang/touch8.md"),"Guide must be exact frozen documentation")
    require(not a.output.exists() or not any(a.output.iterdir()),"Use an empty output folder")
    a.output.parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="touch8-delivery-",dir=a.output.parent) as temporary:
        staging=Path(temporary);shutil.copyfile(apk,staging/APK);shutil.copyfile(guide,staging/"GUIDE.zh-CN.md")
        for name,value in (("build-verification.json",verification),("unit-test-summary.json",tests),("prebuilt-manifest.json",manifest),
                           ("inherited-rime-evidence.json",inherited),("packaged-rime-version.json",packaged),("source-snapshot.json",snapshot),
                           ("source-patch-manifest.json",patch_manifest),("native-prediction-provenance.json",provenance),
                           ("native-prediction-test-summary.json",native_summary)):write(staging/name,value)
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
    p.add_argument("--native-work",type=Path,required=True);p.add_argument("--baseline",type=Path,required=True)
    p.add_argument("--prebuilt",type=Path,required=True);p.add_argument("--sdk",type=Path,required=True)
    p.add_argument("--ndk",type=Path,required=True);p.add_argument("--boost-headers",type=Path,required=True)
    p.add_argument("--android-runtime",type=Path,required=True);p.add_argument("--qemu",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True);p.add_argument("--expected-tests",type=int)
    p.add_argument("--prepare-only",action="store_true",help="Review frozen source/input identity only; no build or execution")
    execute(p.parse_args())


if __name__=="__main__":main()
