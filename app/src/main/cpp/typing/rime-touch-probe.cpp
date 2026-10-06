/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "rime-touch-probe.h"

#include <algorithm>
#include <sstream>
#include <stdexcept>
#include <rime_api.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/ticket.h>
#include <rime/gear/memory.h>
#include <rime/schema.h>
#include <rime/segmentation.h>
#include <rime/translation.h>
#include <rime/translator.h>

namespace axiang::typing {
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
        if (!source.config()) throw std::runtime_error("schema unavailable");
        std::ostringstream yaml;
        if (!source.config()->SaveToStream(yaml))
            throw std::runtime_error("schema clone failed");
        auto config = std::make_unique<rime::Config>();
        std::istringstream cloned(yaml.str());
        if (!config->LoadFromStream(cloned))
            throw std::runtime_error("schema clone failed");
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
        if (!component) throw std::runtime_error("translator unavailable");
        translator.reset(component->Create(rime::Ticket(&engine, "translator")));
        if (!translator) throw std::runtime_error("translator unavailable");
        // Verify the hard privacy setting at the actual instantiated object.
        auto* memory = dynamic_cast<rime::Memory*>(translator.get());
        if (!memory || memory->user_dict())
            throw std::runtime_error("probe user dictionary must be disabled");
    }

    ReadOnlyEngine engine;
    std::unique_ptr<rime::Translator> translator;
};

bool RimeTouchProbe::RuntimeReady() {
    const auto* api = rime_get_api();
    return api && RIME_API_AVAILABLE(api, get_version) &&
        std::string(api->get_version()) == "1.12.0" &&
        RIME_API_AVAILABLE(api, is_maintenance_mode) &&
        !api->is_maintenance_mode() &&
        rime::Config::Require("config") && rime::Config::Require("schema") &&
        rime::Translator::Require("script_translator");
}

bool RimeTouchProbe::ValidInput(const std::string& input) {
    return !input.empty() && input.size() <= kMaxInputBytes &&
        std::all_of(input.begin(), input.end(), [](unsigned char c) {
            return (c >= 'a' && c <= 'z') || c == '\'';
        });
}

RimeTouchProbe::RimeTouchProbe(const std::string& schema_id) {
    if (schema_id != "rime_ice" || !RuntimeReady())
        throw std::runtime_error("probe unavailable");
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
