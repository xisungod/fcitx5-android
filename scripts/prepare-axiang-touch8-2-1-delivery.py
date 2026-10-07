#!/usr/bin/env python3
"""Verify frozen touch.8.2.1 SMS repair with fresh complete application tests.

All 380 baseline assets/libraries remain byte-identical. Existing completion,
continuation, native and Rime evidence retains its original bytes and source
identity; those suites are not re-run. No publication, real SMS, or key access.
"""
from __future__ import annotations
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,tempfile,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
sys.dont_write_bytecode=True
BASE="aa9232725bccd2072340068f7cfd2df1b41dc3ac"
BASE_APK_HASH="4690edd288ce06e8bcf9f829dced1cb1b5e449c92afcb2c86fe74c2e26705cbe"
BASE_APK_BYTES=272198575
BASE_TEST_HASH="13081f08ba19f74846daf69c1fc79c1197d53ea6bde88fb3edb0636e7043561c"
VERSION,CODE="1.2-touch.8.2.1",12
APK="AXiang-1.2-touch.8.2.1-arm64.apk"
BRIDGE="lib/arm64-v8a/libaxiangpredict.so"
COMPLETION="assets/typing/next_word_completions.tsv"
COMPLETION_SOURCE="app/src/main/assets/typing/next_word_completions.tsv"
PROOFS={
 "completion-resource-test-summary.json":"324724d8269a378fd50b7356555f14df1441801bdeb7672f4acc08d254454208",
 "inherited-native-prediction-evidence.json":"d6d6b333eaccb3722830eff319d8a273a192f36f77ddd3a70e537cf794a1f78a",
 "inherited-rime-evidence.json":"3e2d32a3e9c2db38347ec10b74c4d20874d4b51b383f38c650c1ce06c40266fe",
 "native-prediction-provenance.json":"e5c209ae1bac8f8a3f110e86631050ee6f38a60058a756a2776e620db99a2437",
 "native-prediction-test-summary.json":"cc9c900f1507971019e5348da058dd192755729fba64003c08230c7bf86d1bf6",
 "packaged-rime-version.json":"3b34810d8ac14b9b9a9af7f682d99bb5155805b1b3224dce5842fe4451f66753",
 "prediction-benchmark-baseline-candidates.json":"1d7c50581014784126b8d4cc07a026b536f0a2f0b5611eb1ce01530378e64b7e",
 "prediction-benchmark-current-candidates.json":"d23a00391a040ebfbfa97e514fc93febc0b42bd633b72eac328532c3d2ad8592",
 "prediction-benchmark-evaluation.json":"eb426a68a5f5e6266298d8e1fa9627b9414982c7c6a9b3310507217176430099",
 "prediction-benchmark-summary.json":"716d8a3d33486f4539de7e412ef7676f2332c9dbfcf926263094c86dc6959d2b"}
FILES={APK,"GUIDE.zh-CN.md","SHA256SUMS.txt","build-verification.json","full-junit-reports.zip",
       "prebuilt-manifest.json","source-patch-manifest.json","source-snapshot.json","typing-test-source.patch",
       "unit-test-summary.json","inherited-feature-evidence.json",*PROOFS}
PKG="app/src/main/java/org/fcitx/fcitx5/android/"
MAIN_PATHS={"app/src/main/AndroidManifest.xml",PKG+"FcitxApplication.kt",PKG+"data/otp/SmsCodeReceiver.kt",
 PKG+"data/otp/SmsCodeAccess.kt",PKG+"data/otp/SmsCodePermissionActivity.kt",PKG+"data/prefs/AppPrefs.kt",PKG+"input/FcitxInputMethodService.kt",PKG+"input/InputView.kt",
 PKG+"input/bar/KawaiiBarComponent.kt",PKG+"ui/main/MainActivity.kt",
 "app/src/main/res/values/strings.xml","app/src/main/res/values-zh-rCN/strings.xml",
 "app/src/main/res/values-zh-rTW/strings.xml","docs/axiang/touch8.2.1.md",
 "scripts/prepare-axiang-touch8-2-1-delivery.py"}


def require(condition,message):
    if not condition:raise ValueError(message)

def digest(path):
    with path.open("rb") as stream:return hashlib.file_digest(stream,"sha256").hexdigest()

def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+"\n")

def git(root,*args):return subprocess.check_output(["git","-C",str(root),*args])

def frozen(root,source):
    require(re.fullmatch(r"[0-9a-f]{40}",source) and git(root,"rev-parse","HEAD").decode().strip()==source and
            not git(root,"status","--porcelain"),"Require exact clean frozen source")
    require(git(root,"rev-parse","HEAD^").decode().strip()==BASE,"SMS repair must have public touch.8.2 as one parent")

def load_previous(root):
    spec=importlib.util.spec_from_file_location("touch82_delivery",root/"scripts/prepare-axiang-touch8-2-delivery.py")
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def source_patch(root,source,allowlist):
    paths=[p.decode() for p in git(root,"diff","--name-only","-z",BASE,source).split(b"\0") if p]
    approved=json.loads(allowlist.read_text())
    require(isinstance(approved,list) and len(approved)==len(set(approved)) and paths and
            set(paths)==set(approved),"Source differs from exact reviewed SMS path allowlist")
    records=[]
    for name in paths:
        require((name in MAIN_PATHS or name.startswith("app/src/test/java/")) and
                Path(name).suffix in {".kt",".xml",".py",".md"} and not Path(name).is_absolute() and
                ".." not in Path(name).parts,"Non-SMS/unreviewed public source path: "+name)
        raw=git(root,"show",source+":"+name);raw.decode("utf-8")
        require(len(raw)<1000000 and b"\0" not in raw and not re.search(
            rb"github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|/tmp/codex-" rb"remote-attachments/",raw),
            "Private/binary source payload: "+name)
        records.append(dict(path=name,bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest()))
    raw=git(root,"diff","--full-index",BASE,source,"--",*paths)
    require(not re.search(rb"(?m)^GIT binary patch$",raw),"Public patch contains binary data")
    return raw,dict(base_commit=BASE,source_commit=source,files=paths,source_files=records,
                    patch="typing-test-source.patch",bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),
                    private_logs_prebuilts_and_generated_binaries_included=False)


def inherited_proof(root,baseline,inputs,source,patch_manifest):
    records={};values={}
    for name,checksum in PROOFS.items():
        path=baseline/name
        require(path.is_file() and not path.is_symlink() and digest(path)==checksum,"Exact inherited proof differs: "+name)
        values[name]=json.loads(path.read_text())
        records[name]=dict(name=name,bytes=path.stat().st_size,sha256=checksum,
                          url="https://github.com/xisungod/fcitx5-android/releases/download/axiang-touch-1.2-touch.8.2/"+name)
    by_path={r["path"]:r for r in inputs};native=values["native-prediction-test-summary.json"]
    provenance=values["native-prediction-provenance.json"];completion=values["completion-resource-test-summary.json"]
    benchmark=values["prediction-benchmark-summary.json"]
    require(native.get("source_commit")=="d12bd16ece54b02c78aa28a92c2b161c51cc766b" and
            native.get("source_checkout_dirty") is False and native["host"]["checks"]==18 and native["arm"]["checks"]==359 and
            native["native_sha256"]==provenance["native_sha256"]==by_path[BRIDGE]["sha256"] and
            native["source_files_sha256"]==provenance["source_files_sha256"],"Original prediction proof differs")
    require(completion.get("source_commit")==benchmark.get("source_commit")==BASE and completion.get("tests")==22 and
            completion.get("resource_sha256")==benchmark.get("completion_asset_sha256")==by_path[COMPLETION]["sha256"] and
            completion.get("resource_bytes")==by_path[COMPLETION]["bytes"] and benchmark.get("cases")==200 and
            benchmark.get("passed") is True,"Original completion/continuation evidence differs")
    require(values["inherited-native-prediction-evidence.json"].get("source_commit")==BASE and
            values["inherited-rime-evidence.json"].get("source_commit")==BASE,"Inherited proof wrapper origin differs")
    mappings=[provenance["source_files_sha256"],native["harness_sources_sha256"],benchmark["source_files_sha256"],
              values["prediction-benchmark-current-candidates.json"]["provenance"]["production_kotlin_sources_sha256"],
              {"scripts/build-axiang-nextword-completions.py":completion["producer_sha256"],
               "scripts/check-axiang-nextword-completions.py":completion["test_driver_sha256"],
               COMPLETION_SOURCE:by_path[COMPLETION]["sha256"]}]
    unchanged={};changed=set(patch_manifest["files"])
    for mapping in mappings:
        for name,checksum in mapping.items():
            require(name not in changed and digest(root/name)==checksum and
                    hashlib.sha256(git(root,"show",BASE+":"+name)).hexdigest()==checksum,
                    "Feature source changed; inherited-only SMS build forbidden: "+name)
            require(name not in unchanged or unchanged[name]["sha256"]==checksum,"Conflicting inherited source hashes")
            unchanged[name]=dict(path=name,bytes=(root/name).stat().st_size,sha256=checksum)
    require(digest(root/COMPLETION_SOURCE)==by_path[COMPLETION]["sha256"] and
            (root/COMPLETION_SOURCE).read_bytes()==git(root,"show",BASE+":"+COMPLETION_SOURCE),"Unchanged corpus source differs")
    for field,prefix in (("model_files","assets/usr/share/libime/"),("dependency_files","lib/arm64-v8a/")):
        require(native.get(field)==provenance.get(field) and all(by_path[prefix+n]==dict(path=prefix+n,**r)
                for n,r in provenance[field].items()),"Inherited models/dependencies differ from actual APK")
    require(len(unchanged)==24,"Complete inherited-feature source map required")
    patch_manifest["unchanged_feature_source_files"]=sorted(unchanged.values(),key=lambda r:r["path"])
    for name,record in records.items():
        record["evidence_source_commit"]=(native["source_commit"] if name.startswith("native-prediction-") else
            values[name].get("source_commit") or values[name].get("provenance",{}).get("source_commit") or BASE)
    return values,dict(schema="touch8.2.1-inherited-features-v1",source_commit=source,baseline_source_commit=BASE,
        baseline_apk_sha256=BASE_APK_HASH,all_351_assets_byte_identical=True,all_29_native_libraries_byte_identical=True,
        all_380_packaged_inputs_byte_identical=True,prediction_native_proof_source_commit=native["source_commit"],
        rime_original_proof_source_commit=values["inherited-rime-evidence.json"]["original_proof_source_commit"],
        completion_proof_source_commit=BASE,continuation_benchmark_source_commit=BASE,
        native_rebuilt=False,native_tests_reexecuted=False,completion_tests_reexecuted=False,
        continuation_benchmark_reexecuted=False,rime_tests_reexecuted=False,
        inherited_native_host_checks=18,inherited_native_arm_checks=359,inherited_completion_checks=22,
        inherited_continuation_cases=200,inherited_native_nonempty_contexts=native["arm"]["nonempty_queries"],
        proofs=sorted(records.values(),key=lambda r:r["name"]))


def run(cmd,root,log,env=None):
    with log.open("w") as stream:subprocess.run([str(x) for x in cmd],cwd=root,env=env,check=True,stdout=stream,stderr=subprocess.STDOUT)


def sms_package_contract(aapt,apk):
    permissions=subprocess.check_output([aapt,"dump","permissions",apk],text=True)
    declared=set(re.findall(r"uses-permission(?:-sdk-[0-9]+)?: name='([^']+)'",permissions))
    require("android.permission.RECEIVE_SMS" in declared and "android.permission.READ_SMS" not in declared and
            "android.permission.INTERNET" not in declared,"Actual APK SMS/no-history/no-network permissions differ")
    tree=subprocess.check_output([aapt,"dump","xmltree",apk,"AndroidManifest.xml"],text=True)
    lines=tree.splitlines();blocks=[]
    for index,line in enumerate(lines):
        match=re.match(r"^(\s*)E: receiver(?:\s|\()",line)
        if not match:continue
        depth=len(match.group(1));end=index+1
        while end<len(lines):
            element=re.match(r"^(\s*)E:",lines[end])
            if element and len(element.group(1))<=depth:break
            end+=1
        block="\n".join(lines[index:end])
        if re.search(r'android:name\([^)]*\)="(?:\.data\.otp\.SmsCodeReceiver|org\.fcitx\.fcitx5\.android\.data\.otp\.SmsCodeReceiver)"',block):blocks.append(block)
    require(len(blocks)==1,"Actual APK must contain one SMS receiver")
    block=blocks[0]
    def boolean(name):
        match=re.search(r"android:"+name+r"\([^)]*\)=\(type 0x12\)0x([0-9a-fA-F]+)",block)
        require(match is not None,"Actual SMS receiver boolean missing: "+name)
        return int(match.group(1),16)!=0
    require(boolean("enabled") is False and boolean("exported") is True and
            re.search(r'android:permission\([^)]*\)="android\.permission\.BROADCAST_SMS"',block) and
            re.search(r'E: action.*\n\s*A: android:name\([^)]*\)="android\.provider\.Telephony\.SMS_RECEIVED"',block),
            "Actual receiver disabled/exported/protected-sender/action contract differs")
    return dict(receive_sms_declared=True,read_sms_history_declared=False,network_permission=False,
                receiver_default_disabled=True,receiver_exported=True,receiver_sender_permission="android.permission.BROADCAST_SMS",
                receiver_action="android.provider.Telephony.SMS_RECEIVED")


def execute(a):
    root=a.root.resolve();work=a.work.resolve();a.output=a.output.resolve()
    protected=[p.resolve() for p in (root,a.prebuilt,a.baseline,a.sdk)]
    require(all(work!=p and p not in work.parents and work not in p.parents and
                a.output!=p and p not in a.output.parents and a.output not in p.parents for p in protected) and
            work!=a.output and work not in a.output.parents and a.output not in work.parents,"Generated paths overlap read-only/source inputs")
    work.mkdir(parents=True,exist_ok=True);frozen(root,a.source_commit)
    patch,patch_manifest=source_patch(root,a.source_commit,a.source_allowlist)
    previous=load_previous(root);m=previous.load_common(root);common=m.common
    apk_base=a.baseline/"AXiang-1.2-touch.8.2-arm64.apk"
    require(apk_base.is_file() and not apk_base.is_symlink() and apk_base.stat().st_size==BASE_APK_BYTES and
            digest(apk_base)==BASE_APK_HASH,"Actual published touch.8.2 APK differs")
    files,counts=previous.archive_records(apk_base,29,351)
    originals=[r for r in files if r["path"] not in {BRIDGE,COMPLETION}]
    def original_path(record):
        n=record["path"];return a.prebuilt/("jniLibs/"+n.removeprefix("lib/") if n.startswith("lib/") else n)
    def verify_originals():
        for r in originals:
            p=original_path(r)
            require(p.is_file() and not p.is_symlink() and p.stat().st_size==r["bytes"] and digest(p)==r["sha256"],
                    "Read-only original input differs: "+r["path"])
    verify_originals()
    inherited,inherited_wrapper=inherited_proof(root,a.baseline,files,a.source_commit,patch_manifest)
    test_path=a.baseline/"unit-test-summary.json"
    require(digest(test_path)==BASE_TEST_HASH,"Exact prior application test manifest differs")
    baseline_tests=json.loads(test_path.read_text())
    require(baseline_tests.get("source_commit")==BASE and baseline_tests.get("tests")==829 and
            len(set(baseline_tests.get("class_names",[])))==baseline_tests.get("classes")==99,"Prior full-suite identity differs")
    with zipfile.ZipFile(apk_base) as archive:speech=json.loads(archive.read("assets/asr/SOURCE.json"))
    require(digest(root/"app/libs/sherpa-onnx-1.13.8.jar")==speech["compile_jar"]["sha256"],"Unchanged ASR compile JAR differs")
    if a.prepare_only:
        print(json.dumps(dict(prepared_source_review=True,source_commit=a.source_commit,verified_packaged_inputs=380,
                              inherited_raw_proofs_verified=len(PROOFS),build_started=False,feature_suites_started=False)));return
    jni=work/"jniLibs/arm64-v8a";assets=work/"asset-stage"
    require(not jni.is_symlink() and not jni.parent.is_symlink() and not assets.is_symlink(),"Staging roots must not be symlinks")
    jni.mkdir(parents=True,exist_ok=True)
    for r in files:
        n=r["path"];dest=jni/Path(n).name if n.startswith("lib/") else assets/n
        dest.parent.mkdir(parents=True,exist_ok=True)
        require(dest.parent.resolve()==dest.parent and not dest.is_symlink(),"Staged parent/file must not contain symlinks")
        if n==BRIDGE:
            if dest.exists():dest.unlink()
            with zipfile.ZipFile(apk_base) as archive:dest.write_bytes(archive.read(n))
        elif n==COMPLETION:
            if dest.exists():dest.unlink()
            shutil.copyfile(root/COMPLETION_SOURCE,dest)
        elif not dest.exists():os.link(original_path(r),dest)
        require(dest.is_file() and dest.stat().st_size==r["bytes"] and digest(dest)==r["sha256"],"Staged original input differs: "+n)
    staged_names={"lib/arm64-v8a/"+p.name for p in jni.iterdir()}|{str(p.relative_to(assets)) for p in assets.rglob("*") if p.is_file()}
    require(staged_names=={r["path"] for r in files},"Staged input membership differs")
    manifest=dict(schema="touch8.2.1-v1",base_commit=BASE,source_commit=a.source_commit,source_checkout_dirty=False,
                  source_apk_sha256=BASE_APK_HASH,source_apk_bytes=BASE_APK_BYTES,counts=counts,files=files,base_files=files,
                  changed_assets=[],changed_native=[],added_files=[],added_native=[],all_380_inputs_byte_identical=True)
    write(work/"prebuilt-manifest.json",manifest)
    asset_root=json.dumps(str(assets),ensure_ascii=False).replace("$","\\$")
    native_root=json.dumps(str(jni.parent),ensure_ascii=False).replace("$","\\$")
    init="""// Exact byte-identical baseline assets and native libraries.
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
    frozen(root,a.source_commit);verify_originals()
    tests,reports=common.unit_test_summary(root,a.expected_tests)
    class_names=sorted(ET.parse(report).getroot().get("name") for report in reports)
    require(tests["tests"]>=829 and tests["classes"]>=99 and len(class_names)==len(set(class_names))==len(reports) and
            set(baseline_tests["class_names"])<=set(class_names),"Whole suite/prior classes must pass")
    tests.update(source_commit=a.source_commit,scope="full_application_suite",fresh_execution=True,class_names=class_names,
                 baseline_class_names=baseline_tests["class_names"],baseline_unit_test_summary_sha256=BASE_TEST_HASH,
                 result_files=[dict(path=p.name,sha256=digest(p)) for p in reports])
    candidates=[p for p in (root/"app/build/outputs/apk/debug").glob("*.apk") if VERSION in p.name]
    require(len(candidates)==1,"Expected one newly versioned signed APK");apk=candidates[0];tools=a.sdk/"build-tools/36.1.0"
    run([sys.executable,"-B",root/"scripts/verify-xuancai-rime-package.py",apk,"--bundled-rime","--offline-dictation",
         "--aapt",tools/"aapt","--apksigner",tools/"apksigner","--expected-main-package",m.PACKAGE,
         "--expected-app-label",m.LABEL,"--expected-version-name",VERSION,"--expected-version-code",str(CODE)],root,work/"package-check.log")
    signature=subprocess.check_output([tools/"apksigner","verify","--print-certs",apk],text=True)
    require(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$",signature,re.M)==[m.SIGNER],"Durable signer differs")
    run([tools/"zipalign","-c","-P","16","4",apk],root,work/"zipalign.log")
    sms_contract=sms_package_contract(tools/"aapt",apk)
    after,after_counts=previous.archive_records(apk,29,351);require(after==files,"Actual APK changed an inherited asset/library")
    marker=common.compiled_source(apk);require(marker["value"]==a.source_commit,"Actual DEX marker differs")
    actual_rime=json.loads(subprocess.check_output([sys.executable,"-B",root/"scripts/verify-packaged-rime-version.py",apk,
                                                  "--expected-library-sha256",m.RIME_SHA256]))
    historical_rime=inherited["packaged-rime-version.json"]
    require(historical_rime.get("apk_sha256")==BASE_APK_HASH and historical_rime.get("apk_size_bytes")==BASE_APK_BYTES and
            actual_rime.get("apk_sha256")==digest(apk) and actual_rime.get("apk_size_bytes")==apk.stat().st_size and
            {k:v for k,v in actual_rime.items() if k not in {"apk_sha256","apk_size_bytes"}}==
            {k:v for k,v in historical_rime.items() if k not in {"apk_sha256","apk_size_bytes"}},
            "Actual packaged engine differs from inherited proof")
    inherited_proof(root,a.baseline,after,a.source_commit,patch_manifest)
    defaults=m.defaults(root)
    prefs=(root/"app/src/main/java/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt").read_text()
    require(re.findall(r'"local_next_word_prediction"\s*,\s*(true|false)',prefs)==["true"],"Next-word default differs")
    defaults["local_next_word_prediction"]=True
    require(re.findall(r'"verification_code_sms"\s*,\s*(true|false)',prefs)==["true"],"Incoming SMS reading must default on")
    defaults["verification_code_sms"]=True
    verification=dict(schema="touch8.2.1-v1",base_commit=BASE,package=m.PACKAGE,label=m.LABEL,version_name=VERSION,version_code=CODE,
        abi="arm64-v8a",compiled_source_commit=a.source_commit,compiled_source_marker=marker,source_checkout_head=a.source_commit,
        source_checkout_dirty=False,source_apk_sha256=BASE_APK_HASH,apk_sha256=digest(apk),apk_bytes=apk.stat().st_size,
        asset_count=351,native_count=29,changed_assets=[],changed_native=[],added_assets=[],added_native=[],
        all_351_assets_byte_identical=True,all_29_native_libraries_byte_identical=True,all_380_packaged_inputs_byte_identical=True,
        signature_verified=True,signature_certificate_sha256=m.SIGNER,zip_alignment_verified=True,network_permission=False,
        packaged_rime_version=m.RIME_VERSION,packaged_librime_sha256=m.RIME_SHA256,actual_packaged_rime_check=actual_rime,unit_tests=tests["tests"],unit_test_classes=tests["classes"],
        unit_test_failures=0,unit_test_errors=0,unit_test_skipped=0,unit_test_scope="full_application_suite",fresh_full_app_suite=True,
        feature_proofs_inherited=True,native_prediction_tests_reexecuted=False,native_prediction_rebuilt=False,
        completion_resource_tests_reexecuted=False,prediction_benchmark_reexecuted=False,old_rime_tests_reexecuted=False,
        inherited_native_prediction_host_checks=18,inherited_native_prediction_arm_checks=359,inherited_completion_resource_tests=22,
        inherited_prediction_benchmark_cases=200,inherited_native_prediction_proof_source_commit="d12bd16ece54b02c78aa28a92c2b161c51cc766b",
        inherited_completion_and_benchmark_source_commit=BASE,real_sms_receive_verified=False,sms_device_permission_flow_verified=False,
        sms_package_contract=sms_contract,sms_history_reading_added=False,sms_system_permission_override=False,
        sms_reading_default_on=True,sms_authorization_prompt_scope="user_entry_only",
        default_experimental_switches=defaults,new_neural_model_included=False,new_model_asset_included=False,
        device_installation_or_launch_verified=False,phone_accuracy_or_latency_verified=False,
        release_kind="sms_permission_and_suggestion_fix_prerelease",publication_performed=False)
    snapshot=dict(branch=git(root,"branch","--show-current").decode().strip(),commit=a.source_commit,tree=git(root,"rev-parse","HEAD^{tree}").decode().strip(),
                  compiled_marker=marker,compiled_source_is_fixed_commit=True,private_logs_included=False,
                  url="https://github.com/xisungod/fcitx5-android/tree/"+a.source_commit)
    guide=root/"docs/axiang/touch8.2.1.md"
    require(guide.is_file() and guide.read_bytes()==git(root,"show",a.source_commit+":docs/axiang/touch8.2.1.md"),"Guide must be exact frozen source")
    require(not a.output.exists() or not any(a.output.iterdir()),"Use an empty output folder");a.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="touch8-2-1-delivery-",dir=a.output.parent) as temporary:
        staging=Path(temporary);shutil.copyfile(apk,staging/APK);shutil.copyfile(guide,staging/"GUIDE.zh-CN.md")
        for name,value in (("build-verification.json",verification),("unit-test-summary.json",tests),("prebuilt-manifest.json",manifest),
                           ("source-patch-manifest.json",patch_manifest),("source-snapshot.json",snapshot),("inherited-feature-evidence.json",inherited_wrapper)):write(staging/name,value)
        for name in PROOFS:shutil.copyfile(a.baseline/name,staging/name)
        (staging/"typing-test-source.patch").write_bytes(patch)
        with zipfile.ZipFile(staging/"full-junit-reports.zip","w",zipfile.ZIP_DEFLATED) as archive:
            for report in reports:archive.write(report,report.name)
        (staging/"SHA256SUMS.txt").write_text("".join(digest(p)+"  "+p.name+"\n" for p in sorted(staging.iterdir())))
        require({p.name for p in staging.iterdir()}==FILES,"Public 21-file whitelist differs");frozen(root,a.source_commit)
        if a.output.exists():a.output.rmdir()
        staging.rename(a.output)
    print(json.dumps(verification,ensure_ascii=False))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root",type=Path,default=Path.cwd());p.add_argument("--source-commit",required=True)
    p.add_argument("--source-allowlist",type=Path,required=True);p.add_argument("--work",type=Path,required=True)
    p.add_argument("--baseline",type=Path,required=True);p.add_argument("--prebuilt",type=Path,required=True)
    p.add_argument("--sdk",type=Path,required=True);p.add_argument("--output",type=Path,required=True)
    p.add_argument("--expected-tests",type=int);p.add_argument("--prepare-only",action="store_true")
    execute(p.parse_args())


if __name__=="__main__":main()
