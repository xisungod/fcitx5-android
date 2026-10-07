/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "axiangpredict.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <libime/core/languagemodel.h>
#include <libime/core/prediction.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unordered_set>

namespace axiang::predict {
namespace {
bool RegularReadable(const std::string& path, size_t maximum) {
    struct stat info{};
    if (path.empty() || path.size() > 4096 || path.find('\0') != std::string::npos ||
        stat(path.c_str(), &info) || !S_ISREG(info.st_mode) || info.st_size < 16 ||
        static_cast<uint64_t>(info.st_size) > maximum) return false;
    return std::ifstream(path, std::ios::binary).good();
}
std::vector<size_t> Boundaries(const std::string& han) {
    std::vector<size_t> result{0};
    for (size_t i = 0; i < han.size(); ++i)
        if ((static_cast<unsigned char>(han[i]) & 0xc0) != 0x80 && i > 0) result.push_back(i);
    result.push_back(han.size());
    return result;
}
}

class Predictor::Impl {
public:
    static constexpr size_t CandidatePool = 32;
    static constexpr size_t TokenizationPlans = 16;
    explicit Impl(const std::string& path) : model(path.c_str()) {
        // Force the read-only prediction sidecar into the measured cold phase.
        // A missing/corrupt sidecar must not become a silently ready predictor.
        if (model.languageModelFile()->predictionTrie().empty())
            throw std::runtime_error("PredictionDataUnavailable");
        prediction.setLanguageModel(&model);
        // Never attach HistoryBigram/UserLanguageModel or write user dictionaries.
    }
    bool Known(const std::string& word) const {
        return !model.isUnknown(model.index(word), word);
    }
    std::vector<std::string> Tokens(const std::string& text) const {
        if (text.empty()) return {};
        const auto offsets = Boundaries(text);
        size_t end = offsets.size() - 1;
        std::vector<std::string> reversed;
        // HanSuffix already bounds the entire context to 64 code points. Keep
        // every original token; suffix refinement must not discard earlier text.
        while (end) {
            size_t chosen = end - 1;
            for (size_t length = std::min<size_t>(8, end); length > 0; --length) {
                const size_t start = end - length;
                const auto word = text.substr(offsets[start], offsets[end] - offsets[start]);
                if (Known(word)) { chosen = start; break; }
            }
            reversed.push_back(text.substr(offsets[chosen], offsets[end] - offsets[chosen]));
            end = chosen;
        }
        std::reverse(reversed.begin(), reversed.end());
        return reversed;
    }
    std::vector<std::string> Refine(const std::vector<std::string>& original) const {
        if (original.empty()) return original;
        // Only the last three greedy tokens can change their boundaries. Each
        // proposal splits a known token into two known words, retaining all
        // preceding tokens and exactly the same Han text. Breadth first keeps
        // single boundary changes ahead of combinations within the fixed cap.
        const size_t fixed = original.size() - std::min<size_t>(3, original.size());
        auto key = [](const std::vector<std::string>& words) {
            std::string result;
            for (const auto& word : words) { result += word; result += '|'; }
            return result;
        };
        std::vector<std::vector<std::string>> plans{original};
        std::unordered_set<std::string> seen{key(original)};
        for (size_t plan = 0; plan < plans.size() && plans.size() < TokenizationPlans; ++plan) {
            const auto words = plans[plan];
            for (size_t index = words.size(); index > fixed && plans.size() < TokenizationPlans;) {
                --index;
                const auto offsets = Boundaries(words[index]);
                for (size_t cut = 1; cut + 1 < offsets.size() && plans.size() < TokenizationPlans; ++cut) {
                    const auto left = words[index].substr(0, offsets[cut]);
                    const auto right = words[index].substr(offsets[cut]);
                    if (!Known(left) || !Known(right)) continue;
                    auto next = words;
                    next[index] = left;
                    next.insert(next.begin() + index + 1, right);
                    if (seen.emplace(key(next)).second) plans.push_back(std::move(next));
                }
            }
        }
        auto score = [this](const std::vector<std::string>& words) {
            std::vector<std::string_view> views;
            views.reserve(words.size());
            for (const auto& word : words) views.emplace_back(word);
            return model.wordsScore(model.nullState(), views);
        };
        size_t best = 0;
        auto best_score = score(original);
        for (size_t plan = 1; plan < plans.size(); ++plan) {
            const auto value = score(plans[plan]);
            // Exact ties retain the original path rather than introducing a
            // segmentation preference unrelated to the actual language model.
            if (std::isfinite(value) && (!std::isfinite(best_score) || value > best_score)) {
                best = plan;
                best_score = value;
            }
        }
        return plans[best];
    }
    std::vector<std::string> Predict(const std::vector<std::string>& tokens, int limit) {
        if (tokens.empty() || !Known(tokens.back())) return {};
        // Model search and Han filtering use a stable bounded internal pool;
        // the requested display size no longer truncates raw words prematurely.
        auto raw = prediction.predict(tokens, CandidatePool);
        std::vector<std::string> result;
        std::unordered_set<std::string> seen;
        for (auto& word : raw) {
            if (HanCandidate(word) && seen.emplace(word).second) result.push_back(std::move(word));
            if (result.size() == static_cast<size_t>(limit)) break;
        }
        return result;
    }
    libime::LanguageModel model;
    libime::Prediction prediction;
};

Predictor::Predictor(const std::string& model_file) {
    if (!RegularReadable(model_file, 64 * 1024 * 1024) ||
        !RegularReadable(model_file + ".predict", 8 * 1024 * 1024))
        throw std::runtime_error("ModelFilesUnavailable");
    impl_ = std::make_unique<Impl>(model_file);
}
Predictor::~Predictor() = default;
std::vector<std::string> Predictor::Query(const std::string& context, int limit) {
    if (limit <= 0 || limit > static_cast<int>(Impl::CandidatePool)) return {};
    const auto han = HanSuffix(context);
    if (han.empty()) return {};
    const auto tokens = impl_->Refine(impl_->Tokens(han));
    auto result = impl_->Predict(tokens, limit);
    if (!result.empty()) return result;
    // Whole committed sentences are not vocabulary tokens. Try at most three
    // other known trailing words, preserving preceding token context for scoring.
    const auto offsets = Boundaries(han);
    const size_t length = offsets.size() - 1;
    int calls = 1;
    for (size_t size = std::min<size_t>(8, length); size > 0 && calls < 4; --size) {
        const size_t start = offsets[length - size];
        const auto suffix = han.substr(start);
        if ((!tokens.empty() && suffix == tokens.back()) || !impl_->Known(suffix)) continue;
        // The one full-context refinement above owns the 16-plan budget.
        // Fallback only changes the queried trailing word, preserving preceding
        // text without starting another tokenization search.
        auto alternate = impl_->Tokens(han.substr(0, start));
        alternate.push_back(suffix);
        ++calls;
        result = impl_->Predict(alternate, limit);
        if (!result.empty()) return result;
    }
    return {};
}
} // namespace axiang::predict
