/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "rime-touch-probe.h"

#include <algorithm>
#include <sstream>
#include <stdexcept>
#include <rime_api.h>
#include <rime/component.h>
namespace rime { class Config; class Translator; struct Ticket; }
// Import the packaged runtime's registry-component RTTI instead of emitting
// another weak copy in this RTLD_LOCAL JNI DSO. Android's libc++abi requires
// one identity for dynamic_cast even when the type names match. Declare these
// before config.h's derived components instantiate their base.
extern template class rime::Class<rime::Config, const std::string&>::Component;
extern template class rime::Class<rime::Translator, const rime::Ticket&>::Component;
#include <rime/config.h>
#include <rime/context.h>
#include <rime/dict/dictionary.h>
#include <rime/engine.h>
#include <rime/ticket.h>
#include <rime/gear/memory.h>
#include <rime/schema.h>
#include <rime/segmentation.h>
#include <rime/translation.h>
#include <rime/translator.h>

namespace axiang::typing {
#ifdef __ANDROID__
// Independently measured from constructors in the pinned shipped ARM64 ELF.
// Catch missing Engine patch, old Context headers or incompatible Boost layout.
static_assert(sizeof(rime::Engine) == 96, "Packaged Rime Engine ABI differs");
static_assert(sizeof(rime::Context) == 352, "Packaged Rime Context ABI differs");
#endif
namespace {
constexpr size_t kMaxInputBytes = 32;
constexpr size_t kMaxContextBytes = 192;
constexpr size_t kMaxCandidates = 3;
constexpr size_t kMaxInspectedCandidates = 24;

// A bare Engine has no ConcreteEngine processors, Switcher, notification sink
// or option-save callbacks. In particular, never call ApplySchema: that saves
// the user's globally selected schema even for a separate C API session.
class ReadOnlyEngine final : public rime::Engine {
public:
    explicit ReadOnlyEngine(const std::string& schema_id) {
        rime::Schema source(schema_id);
        if (!source.config()) throw ProbeUnavailable("SchemaUnavailable");
        std::string loaded_schema;
        if (!source.config()->GetString("schema/schema_id", &loaded_schema) ||
            loaded_schema != schema_id) throw ProbeUnavailable("SchemaUnavailable");
        std::ostringstream yaml;
        if (!source.config()->SaveToStream(yaml))
            throw ProbeUnavailable("SchemaCloneFailed");
        auto config = std::make_unique<rime::Config>();
        std::istringstream cloned(yaml.str());
        if (!config->LoadFromStream(cloned))
            throw ProbeUnavailable("SchemaCloneFailed");
        // Config created without a component and loaded only from a stream
        // has no file path; none of these changes can be automatically saved.
        config->SetBool("translator/enable_user_dict", false);
        config->SetBool("translator/enable_correction", false);
        schema_ = std::make_unique<rime::Schema>(schema_id, config.release());
    }
};

bool ChineseText(const std::string& text) {
    if (text.empty()) return false;
    for (size_t i = 0; i < text.size();) {
        auto first = static_cast<unsigned char>(text[i++]);
        uint32_t point = 0;
        size_t more = 0;
        if ((first & 0xe0) == 0xc0) { point = first & 0x1f; more = 1; }
        else if ((first & 0xf0) == 0xe0) { point = first & 0x0f; more = 2; }
        else if ((first & 0xf8) == 0xf0) { point = first & 0x07; more = 3; }
        else return false;
        if (i + more > text.size()) return false;
        for (size_t j = 0; j < more; ++j) {
            const auto next = static_cast<unsigned char>(text[i++]);
            if ((next & 0xc0) != 0x80) return false;
            point = (point << 6) | (next & 0x3f);
        }
        if (!((point >= 0x3400 && point <= 0x4dbf) ||
              (point >= 0x4e00 && point <= 0x9fff) ||
              (point >= 0xf900 && point <= 0xfaff) ||
              (point >= 0x20000 && point <= 0x323af))) return false;
    }
    return true;
}
} // namespace

class RimeTouchProbe::Impl {
public:
    explicit Impl(const std::string& schema_id) : engine(schema_id) {
        auto* component = rime::Translator::Require("script_translator");
        if (!component) throw ProbeUnavailable("TranslatorUnavailable");
        translator.reset(component->Create(rime::Ticket(&engine, "translator")));
        if (!translator) throw ProbeUnavailable("TranslatorUnavailable");
        // Verify the hard privacy setting at the actual instantiated object.
        auto* memory = dynamic_cast<rime::Memory*>(translator.get());
        if (!memory || memory->user_dict())
            throw ProbeUnavailable("PrivacyGuardFailed");
        if (!memory->dict() || !memory->dict()->loaded())
            throw ProbeUnavailable("DictionaryUnavailable");
    }

    ReadOnlyEngine engine;
    std::unique_ptr<rime::Translator> translator;
};

bool RimeTouchProbe::RuntimeReady() {
    return RuntimeStatus() == "Ready";
}

std::string RimeTouchProbe::RuntimeStatus() {
    const auto* api = rime_get_api();
    if (!api || !RIME_API_AVAILABLE(api, get_version) || !api->get_version ||
        !api->get_version()) return "RuntimeUnavailable";
    // This internal C++ bridge is compiled against the exact Android 1.16.1
    // revision, including prebuilder's Engine layout patch and Boost headers.
    // The build script also locks the unchanged packaged library's SHA256.
    // Other versions require a separately rebuilt and validated bridge.
    const std::string version(api->get_version());
    if (version != "1.16.1") {
        const bool printable = version.size() <= 24 &&
            std::all_of(version.begin(), version.end(), [](unsigned char c) {
                return (c >= '0' && c <= '9') || c == '.' || c == '-' ||
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            });
        return "VersionMismatch:" + (printable ? version : "unknown");
    }
    if (!RIME_API_AVAILABLE(api, is_maintenance_mode) || !api->is_maintenance_mode)
        return "RuntimeUnavailable";
    if (api->is_maintenance_mode()) return "MaintenanceMode";
    if (!rime::Config::Require("config")) return "ConfigUnavailable";
    if (!rime::Config::Require("schema")) return "SchemaUnavailable";
    if (!rime::Translator::Require("script_translator")) return "TranslatorUnavailable";
    return "Ready";
}

bool RimeTouchProbe::ValidInput(const std::string& input) {
    return !input.empty() && input.size() <= kMaxInputBytes &&
        std::all_of(input.begin(), input.end(), [](unsigned char c) {
            return (c >= 'a' && c <= 'z') || c == '\'';
        });
}

RimeTouchProbe::RimeTouchProbe(const std::string& schema_id) {
    if (schema_id != "rime_ice") throw ProbeUnavailable("UnsupportedSchema");
    const auto status = RuntimeStatus();
    if (status != "Ready") throw ProbeUnavailable(status.c_str());
    impl_ = std::make_unique<Impl>(schema_id);
}

RimeTouchProbe::~RimeTouchProbe() = default;

std::vector<ProbeCandidate> RimeTouchProbe::Query(
    const std::string& input, const std::string& preceding_text,
    std::chrono::nanoseconds budget) {
    std::vector<ProbeCandidate> output;
    if (!RuntimeReady() || !ValidInput(input) ||
        preceding_text.size() > kMaxContextBytes || budget.count() <= 0) return output;
    const auto started = std::chrono::steady_clock::now();
    auto* context = impl_->engine.context();
    context->Clear();
    context->commit_history().clear();
    if (!preceding_text.empty()) {
        context->commit_history().Push(rime::CommitRecord("touch_probe", preceding_text));
    }
    context->set_input(input);
    struct ClearAfterQuery {
        rime::Context* context;
        ~ClearAfterQuery() { context->Clear(); context->commit_history().clear(); }
    } cleanup{context};
    rime::Segment segment(0, static_cast<int>(input.size()));
    segment.tags.insert("abc");
    // Query/Evaluate is synchronous and cannot be forcibly interrupted. Check
    // elapsed time as soon as it returns and between bounded candidate steps.
    auto translation = impl_->translator->Query(input, segment);
    for (size_t rank = 1; translation && !translation->exhausted() &&
         rank <= kMaxInspectedCandidates && output.size() < kMaxCandidates; ++rank) {
        if (std::chrono::steady_clock::now() - started > budget) return {};
        const auto candidate = translation->Peek();
        if (candidate && candidate->start() == 0 && candidate->end() == input.size() &&
            ChineseText(candidate->text()) &&
            std::none_of(output.begin(), output.end(), [&](const ProbeCandidate& found) {
                return found.text == candidate->text();
            })) {
            // Rank is the engine's list position, never a probability/quality.
            output.push_back({candidate->text(), candidate->comment(),
                static_cast<int>(candidate->start()), static_cast<int>(candidate->end()),
                static_cast<int>(rank)});
        }
        if (output.size() < kMaxCandidates) translation->Next();
    }
    if (std::chrono::steady_clock::now() - started > budget) return {};
    return output;
}
} // namespace axiang::typing
