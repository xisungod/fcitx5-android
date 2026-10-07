/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Disposable synthetic-data harness, ARM64 Android bionic + actual shared JNI
// library. FakeJNI is an adapter, not ART or Android UI. All engine / probe
// code is real.
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <rime_api.h>

#include <cstdarg>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
namespace {
struct Object : _jobject {
  virtual ~Object() = default;
};
struct Str : Object {
  std::string s;
  explicit Str(std::string x) : s(std::move(x)) {}
};
struct Bytes : Object {
  std::vector<jbyte> b;
  explicit Bytes(size_t n) : b(n) {}
};
struct Arr : Object {
  std::vector<jobject> a;
  explicit Arr(size_t n) : a(n) {}
};
struct Candidate : Object {
  std::string text, comment;
  int start, end, rank;
};
struct Class : Object {
  std::string name;
  explicit Class(std::string n) : name(std::move(n)) {}
};
std::string thread_name = "fcitx-main";
bool exception = false;
Object thread_obj;
enum Method { Current = 1, Name, GetBytes, StringCtor, CandidateCtor };
std::string S(jobject o) { return static_cast<Str *>(o)->s; }
jstring JS(std::string s) {
  return reinterpret_cast<jstring>(new Str(std::move(s)));
}
constexpr const char *kCandidateClass =
    "org/fcitx/fcitx5/android/core/RimeTouchProbe$Candidate";
jclass FindClass(JNIEnv *, const char *name) {
  const std::string n = name ? name : "";
  if (n != "java/lang/Thread" && n != "java/lang/String" &&
      n != kCandidateClass) {
    exception = true;
    return nullptr;
  }
  return reinterpret_cast<jclass>(new Class(n));
}
jmethodID MethodId(JNIEnv *, jclass cls, const char *name, const char *sig) {
  if (!cls || !name || !sig) {
    exception = true;
    return nullptr;
  }
  auto *c = static_cast<Class *>(reinterpret_cast<jobject>(cls));
  int m = 0;
  if (c->name == "java/lang/Thread" && std::string(name) == "getName" &&
      std::string(sig) == "()Ljava/lang/String;")
    m = Name;
  if (c->name == "java/lang/String" && std::string(name) == "getBytes" &&
      std::string(sig) == "(Ljava/lang/String;)[B")
    m = GetBytes;
  if (c->name == "java/lang/String" && std::string(name) == "<init>" &&
      std::string(sig) == "([BLjava/lang/String;)V")
    m = StringCtor;
  if (c->name == kCandidateClass && std::string(name) == "<init>" &&
      std::string(sig) == "(Ljava/lang/String;Ljava/lang/String;III)V")
    m = CandidateCtor;
  if (!m)
    exception = true;
  return reinterpret_cast<jmethodID>(static_cast<intptr_t>(m));
}
jmethodID StaticMethodId(JNIEnv *, jclass cls, const char *name,
                         const char *sig) {
  if (cls && name && sig &&
      static_cast<Class *>(reinterpret_cast<jobject>(cls))->name ==
          "java/lang/Thread" &&
      std::string(name) == "currentThread" &&
      std::string(sig) == "()Ljava/lang/Thread;")
    return reinterpret_cast<jmethodID>(Current);
  exception = true;
  return nullptr;
}
jobject StaticObjectV(JNIEnv *, jclass, jmethodID method, va_list) {
  return reinterpret_cast<intptr_t>(method) == Current ? &thread_obj : nullptr;
}
jobject ObjectV(JNIEnv *, jobject obj, jmethodID method, va_list args) {
  switch (reinterpret_cast<intptr_t>(method)) {
  case Name:
    return reinterpret_cast<jobject>(JS(thread_name));
  case GetBytes: {
    if (S(va_arg(args, jobject)) != "UTF-8") {
      exception = true;
      return nullptr;
    }
    auto s = S(obj);
    auto *b = new Bytes(s.size());
    std::memcpy(b->b.data(), s.data(), s.size());
    return b;
  }
  default:
    exception = true;
    return nullptr;
  }
}
jobject NewObjectV(JNIEnv *, jclass, jmethodID method, va_list args) {
  switch (reinterpret_cast<intptr_t>(method)) {
  case StringCtor: {
    auto *b = static_cast<Bytes *>(va_arg(args, jobject));
    if (S(va_arg(args, jobject)) != "UTF-8") {
      exception = true;
      return nullptr;
    }
    return new Str(
        std::string(reinterpret_cast<char *>(b->b.data()), b->b.size()));
  }
  case CandidateCtor: {
    auto *c = new Candidate;
    c->text = S(va_arg(args, jobject));
    c->comment = S(va_arg(args, jobject));
    c->start = va_arg(args, int);
    c->end = va_arg(args, int);
    c->rank = va_arg(args, int);
    return c;
  }
  default:
    exception = true;
    return nullptr;
  }
}
jstring NewStringUTF(JNIEnv *, const char *text) { return JS(text); }
const char *StringChars(JNIEnv *, jstring str, jboolean *) {
  return static_cast<Str *>(reinterpret_cast<jobject>(str))->s.c_str();
}
void ReleaseString(JNIEnv *, jstring, const char *) {}
jboolean ExceptionCheck(JNIEnv *) { return exception; }
jbyteArray NewBytes(JNIEnv *, jsize n) {
  return reinterpret_cast<jbyteArray>(new Bytes(n));
}
jsize ArrayLength(JNIEnv *, jarray array) {
  auto *o = reinterpret_cast<jobject>(array);
  if (auto *b = dynamic_cast<Bytes *>(static_cast<Object *>(o)))
    return b->b.size();
  return static_cast<Arr *>(o)->a.size();
}
void GetBytesRegion(JNIEnv *, jbyteArray array, jsize start, jsize n,
                    jbyte *out) {
  auto *b = static_cast<Bytes *>(reinterpret_cast<jobject>(array));
  std::memcpy(out, b->b.data() + start, n);
}
void SetBytesRegion(JNIEnv *, jbyteArray array, jsize start, jsize n,
                    const jbyte *in) {
  auto *b = static_cast<Bytes *>(reinterpret_cast<jobject>(array));
  std::memcpy(b->b.data() + start, in, n);
}
jobjectArray NewArray(JNIEnv *, jsize n, jclass, jobject fill) {
  auto *a = new Arr(n);
  for (auto &o : a->a)
    o = fill;
  return reinterpret_cast<jobjectArray>(a);
}
void SetObjectElement(JNIEnv *, jobjectArray array, jsize i, jobject o) {
  static_cast<Arr *>(reinterpret_cast<jobject>(array))->a.at(i) = o;
}
void DeleteLocalRef(JNIEnv *, jobject) {
} // Adapter retains small test allocations until process exit.
JNIEnv Env() {
  static JNINativeInterface t{};
  t.FindClass = FindClass;
  t.GetMethodID = MethodId;
  t.GetStaticMethodID = StaticMethodId;
  t.CallStaticObjectMethodV = StaticObjectV;
  t.CallObjectMethodV = ObjectV;
  t.NewObjectV = NewObjectV;
  t.NewStringUTF = NewStringUTF;
  t.GetStringUTFChars = StringChars;
  t.ReleaseStringUTFChars = ReleaseString;
  t.ExceptionCheck = ExceptionCheck;
  t.NewByteArray = NewBytes;
  t.GetArrayLength = ArrayLength;
  t.GetByteArrayRegion = GetBytesRegion;
  t.SetByteArrayRegion = SetBytesRegion;
  t.NewObjectArray = NewArray;
  t.SetObjectArrayElement = SetObjectElement;
  t.DeleteLocalRef = DeleteLocalRef;
  JNIEnv e{};
  e.functions = &t;
  return e;
}
void Require(bool ok, const char *m) {
  if (!ok)
    throw std::runtime_error(m);
}
using Snapshot = std::map<std::string, std::string>;
Snapshot Files(const std::filesystem::path &p) {
  Snapshot r;
  for (auto &e : std::filesystem::recursive_directory_iterator(p))
    if (e.is_regular_file()) {
      std::ifstream f(e.path(), std::ios::binary);
      r[e.path().lexically_relative(p).string()] =
          std::string(std::istreambuf_iterator<char>(f), {});
    }
  return r;
}
std::vector<std::string> Menu(RimeApi *api, RimeSessionId s) {
  RIME_STRUCT(RimeContext, c);
  Require(api->get_context(s, &c), "context");
  std::vector<std::string> r;
  for (int i = 0; i < c.menu.num_candidates; ++i)
    r.emplace_back(c.menu.candidates[i].text);
  api->free_context(&c);
  return r;
}
} // namespace
int main(int argc, char **argv) {
  if (argc != 4 &&
      !(argc == 5 && std::string(argv[4]) == "--prepare-fixture")) {
    std::cerr << "Usage: check-packaged-rime-touch-jni SHARED_DATA "
                 "DISPOSABLE_USER_DATA PROBE_SO [--prepare-fixture]\n";
    return 2;
  }
  try {
    void *h = dlopen(argv[3], RTLD_NOW | RTLD_LOCAL);
    const char *load_error = h ? nullptr : dlerror();
    Require(h, load_error ? load_error : "dlopen failed");
    const std::string prefix =
        "Java_org_fcitx_fcitx5_android_core_RimeTouchProbe_";
    auto create = reinterpret_cast<jlong (*)(JNIEnv *, jobject, jstring)>(
        dlsym(h, (prefix + "nativeCreate").c_str()));
    auto status = reinterpret_cast<jstring (*)(JNIEnv *, jobject)>(
        dlsym(h, (prefix + "nativeRuntimeStatus").c_str()));
    auto query = reinterpret_cast<jobjectArray (*)(JNIEnv *, jobject, jlong,
                                                   jstring, jstring, jlong)>(
        dlsym(h, (prefix + "nativeQuery").c_str()));
    auto destroy = reinterpret_cast<void (*)(JNIEnv *, jobject, jlong)>(
        dlsym(h, (prefix + "nativeDestroy").c_str()));
    Require(create && status && query && destroy, "JNI exports unavailable");
    auto env = Env();
    auto *api = rime_get_api();
    RIME_STRUCT(RimeTraits, t);
    t.shared_data_dir = argv[1];
    t.user_data_dir = argv[2];
    std::string pre = std::string(argv[1]) + "/build";
    t.prebuilt_data_dir = pre.c_str();
    t.app_name = "rime.axiang_arm64_shared_jni_public_test";
    t.min_log_level = 2;
    const char *modules[] = {"default", "deployer", "lua", "grammar", nullptr};
    if (argc == 5)
      t.modules = modules;
    api->setup(&t);
    api->initialize(&t);
    if (argc == 5) {
      Require(api->deploy_schema(
                  (std::string(argv[1]) + "/rime_ice.schema.yaml").c_str()),
              "fixture schema deploy");
      std::cout << "{\"fixture_only\":true,\"deployment\":true,\"runtime\":\""
                << api->get_version() << "\"}" << std::endl;
      api->finalize();
      dlclose(h);
      return 0;
    }
    auto live = api->create_session();
    Require(live && api->select_schema(live, "rime_ice"), "live session");
    api->set_option(live, "ascii_mode", False);
    for (char c : std::string("gaileme"))
      Require(api->process_key(live, c, 0), "gaileme typing");
    auto literal = Menu(api, live);
    Require(!literal.empty() && literal.front() == "改了么",
            "gaileme literal first");
    std::cout << "literal gaileme: " << literal.front() << std::endl;
    api->clear_composition(live);
    const std::string original = "wojintianxiangqubeijing";
    for (char c : original)
      Require(api->process_key(live, c, 0), "typing");
    auto menu = Menu(api, live);
    auto caret = api->get_caret_pos(live);
    auto files = Files(argv[2]);
    dl_iterate_phdr(
        [](dl_phdr_info *info, size_t, void *) {
          if (info->dlpi_name && *info->dlpi_name)
            std::cout << "loaded " << info->dlpi_name << std::endl;
          return 0;
        },
        nullptr);
    std::cout << "initial status " << S(status(&env, nullptr)) << std::endl;
    Require(S(status(&env, nullptr)) == "Ready:1.16.1", "runtime status");
    auto handle = create(&env, nullptr, JS("rime_ice"));
    std::cout << "create handle " << handle << " status "
              << S(status(&env, nullptr)) << std::endl;
    Require(handle > 0, "create failed");
    Require(S(status(&env, nullptr)) == "Ready:1.16.1", "create status");
    int count = 0, targets = 0;
    for (auto pair : std::vector<std::pair<std::string, std::string>>{
             {"nihao", "你好"},
             {"wojintian", "我今天"},
             {"beijing", "北京"},
             {"wojintianxiangqubeijing", "我今天想去北京"},
             {"qingbangwokanyixia", "请帮我看一下"},
             {"zhegewentizenmejiejue", "这个问题怎么解决"}}) {
      auto arr =
          query(&env, nullptr, handle, JS(pair.first), JS(""), 5000000000LL);
      Require(arr && !exception, "query JNI error");
      auto *a = static_cast<Arr *>(reinterpret_cast<jobject>(arr));
      Require(!a->a.empty() && a->a.size() <= 3, "candidates bounds");
      bool found = false;
      std::cout << "query " << pair.first << ": ";
      for (auto &o : a->a) {
        auto *c = static_cast<Candidate *>(o);
        Require(c->start == 0 && c->end == pair.first.size() && c->rank > 0,
                "full span/rank");
        count++;
        found = found || c->text == pair.second;
        std::cout << c->text << " | ";
      }
      std::cout << std::endl;
      Require(found, "expected target missing");
      targets++;
    }
    Require(create(&env, nullptr, JS("rime_ice")) == 0,
            "duplicate probe allowed");
    Require(S(status(&env, nullptr)) == "AlreadyCreated", "duplicate status");
    auto contextual =
        query(&env, nullptr, handle, JS("nihao"), JS("北京𠀀"), 5000000000LL);
    Require(contextual && !exception &&
                !static_cast<Arr *>(reinterpret_cast<jobject>(contextual))
                     ->a.empty(),
            "UTF8 context query");
    bool wrong_owner = false;
    std::thread other([&] {
      auto otherenv = Env();
      auto r =
          query(&otherenv, nullptr, handle, JS("nihao"), JS(""), 5000000000LL);
      wrong_owner =
          static_cast<Arr *>(reinterpret_cast<jobject>(r))->a.empty() &&
          S(status(&otherenv, nullptr)) == "HandleUnavailable";
    });
    other.join();
    Require(wrong_owner, "owner pthread not enforced");
    for (auto input : {std::string("nihao{space}"), std::string(33, 'a')}) {
      auto r = query(&env, nullptr, handle, JS(input), JS(""), 5000000000LL);
      Require(static_cast<Arr *>(reinterpret_cast<jobject>(r))->a.empty(),
              "invalid input accepted");
    }
    auto longcontext = query(&env, nullptr, handle, JS("nihao"),
                             JS(std::string(193, 'x')), 5000000000LL);
    Require(
        static_cast<Arr *>(reinterpret_cast<jobject>(longcontext))->a.empty(),
        "oversize context accepted");
    thread_name = "main";
    Require(S(status(&env, nullptr)) == "WrongThread", "wrong-thread status");
    auto wrong =
        query(&env, nullptr, handle, JS("nihao"), JS(""), 5000000000LL);
    Require(static_cast<Arr *>(reinterpret_cast<jobject>(wrong))->a.empty(),
            "wrong thread queried");
    thread_name = "fcitx-main";
    auto tiny = query(&env, nullptr, handle, JS("nihao"), JS(""), 1);
    Require(static_cast<Arr *>(reinterpret_cast<jobject>(tiny))->a.empty(),
            "budget");
    Require(S(status(&env, nullptr)) == "Ready:1.16.1",
            "budget not runtime error");
    destroy(&env, nullptr, handle);
    Require(create(&env, nullptr, JS("unsupported_schema")) == 0,
            "schema whitelist bypassed");
    Require(S(status(&env, nullptr)) == "UnsupportedSchema",
            "unsupported schema status");
    auto recreated = create(&env, nullptr, JS("rime_ice"));
    Require(recreated > 0, "recreate failed");
    destroy(&env, nullptr, recreated);
    Require(original == api->get_input(live), "raw changed");
    Require(caret == api->get_caret_pos(live), "caret changed");
    Require(menu == Menu(api, live), "menu changed");
    Require(files == Files(argv[2]), "user files changed");
    RIME_STRUCT(RimeCommit, commit);
    Require(!api->get_commit(live, &commit), "unexpected commit");
    std::cout
        << "{\"engine\":\"" << api->get_version()
        << "\",\"jni_adapter\":\"FakeJNI, no "
           "ART\",\"actual_shared_library\":true,\"queries\":6,\"returned_"
           "candidates\":"
        << count << ",\"targets_found\":" << targets
        << ",\"create_succeeded\":true,\"gaileme_literal_first\":true,\"wrong_"
           "thread_rejected\":true,\"owner_pthread_enforced\":true,\"duplicate_"
           "create_rejected\":true,\"unsupported_schema_rejected\":true,"
           "\"invalid_input_rejected\":true,\"oversize_context_rejected\":true,"
           "\"utf8_context_supported\":true,\"recreate_succeeded\":true,"
           "\"budget_empty_is_ready\":true,\"raw_input_unchanged\":true,"
           "\"caret_unchanged\":true,\"live_menu_unchanged\":true,\"user_files_"
           "unchanged\":true,\"no_commit\":true}"
        << std::endl;
    api->destroy_session(live);
    api->finalize();
    dlclose(h);
    return 0;
  } catch (const std::exception &e) {
    std::cerr << "FAIL " << e.what() << std::endl;
    return 5;
  }
}
