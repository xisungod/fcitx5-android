/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Public spelling regressions with a disposable host user directory.
#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/service.h>
#include <rime/gear/translator_commons.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void Require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
std::string Commit(RimeApi* api, RimeSessionId session) {
    RIME_STRUCT(RimeCommit, commit);
    if (!api->get_commit(session, &commit)) return {};
    const std::string text = commit.text;
    api->free_commit(&commit);
    return text;
}
void Replay(RimeApi* api, RimeSessionId session, const std::string& input) {
    api->clear_composition(session);
    Require(Commit(api, session).empty(), "reset unexpectedly committed");
    for (size_t index = 0; index < input.size(); ++index) {
        Require(api->process_key(session, input[index], 0), "letter unhandled");
        Require(Commit(api, session).empty(), "typing unexpectedly committed");
        Require(input.substr(0, index + 1) == api->get_input(session), "literal prefix changed");
    }
}
std::vector<rime::an<rime::Candidate>> Candidates(rime::Context* context) {
    std::vector<rime::an<rime::Candidate>> result;
    for (int index = 0; index < 32; ++index) {
        auto candidate = context->composition().back().GetCandidateAt(index);
        if (!candidate) break;
        result.push_back(candidate);
    }
    return result;
}
int Rank(const std::vector<rime::an<rime::Candidate>>& candidates, const std::string& text) {
    for (size_t index = 0; index < candidates.size(); ++index)
        if (candidates[index]->text() == text) return static_cast<int>(index);
    return -1;
}
void CheckPage(RimeApi* api, RimeSessionId session,
               const std::vector<rime::an<rime::Candidate>>& candidates, int expected_page) {
    RIME_STRUCT(RimeContext, context);
    Require(api->get_context(session, &context), "page context unavailable");
    const auto page = context.menu.page_no;
    const auto page_size = context.menu.page_size;
    Require(page == expected_page && page_size > 0, "page navigation changed");
    for (int index = 0; index < context.menu.num_candidates; ++index) {
        const auto absolute = static_cast<size_t>(page * page_size + index);
        Require(absolute < candidates.size() &&
                candidates[absolute]->text() == context.menu.candidates[index].text,
                "paged menu no longer matches absolute native candidate indices");
    }
    api->free_context(&context);
}
void CheckTraditional(RimeApi* api, RimeSessionId session, rime::Context* context) {
    // A native correction Phrase may subsequently be wrapped by script
    // conversion. Native learning must still obtain that genuine Phrase,
    // not an extra ranking wrapper. The synthetic short-repair candidates
    // do not supply this property and are not counted as native learning.
    api->set_option(session, "traditionalization", True);
    Replay(api, session, "gaileme");
    const auto traditional = Candidates(context);
    const int traditional_rank = Rank(traditional, "好了麼");
    if (traditional_rank < 0) {
        for (const auto& candidate : traditional)
        std::cerr << "traditional candidate: " << candidate->text() << " / "
              << candidate->type() << " / " << candidate->end() << '\n';
    }
    Require(traditional_rank > 0 && traditional[traditional_rank]->end() == 7,
        "traditional full-span correction missing");
    bool native_phrase = false;
    for (const auto& genuine : rime::Candidate::GetGenuineCandidates(traditional[traditional_rank])) {
        if (std::dynamic_pointer_cast<rime::Phrase>(genuine) && genuine->text() == "好了么" &&
        genuine->quality() < 0.0) native_phrase = true;
    }
    Require(native_phrase, "ranking or script conversion hid genuine native correction Phrase");
    size_t commits = 0;
    auto notification = context->commit_notifier().connect([&](rime::Context*) { ++commits; });
    const auto before_traditional = commits;
    Require(api->select_candidate(session, traditional_rank), "traditional correction select failed");
    Require(Commit(api, session) == "好了麼" && Commit(api, session).empty() &&
        commits == before_traditional + 1 && !context->IsComposing(),
        "traditional native correction selection changed text or double committed");
    api->set_option(session, "traditionalization", False);
    notification.disconnect();
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 4 && argc != 5) return 2;
    const bool conversion_fixture = argc == 5 && std::string(argv[4]) == "conversion";
    auto* api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_correction_ranking_public_test";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    const auto session = api->create_session();
    try {
        Require(std::string(api->get_version()) == "1.12.0", "unexpected Rime version");
        Require(session && api->select_schema(session, "rime_ice"), "missing pinned schema");
        api->set_option(session, "ascii_mode", False);
        api->set_option(session, "traditionalization", False);
        auto* context = rime::Service::instance().GetSession(session)->context();
        const auto options = context->options();
        const auto properties = context->properties();
        if (conversion_fixture) {
            CheckTraditional(api, session, context);
            Require(context->options() == options && context->properties() == properties,
                    "conversion selection changed mode options or properties");
            std::cout << "{\"schema\":1,\"engine_version\":\"1.12.0\","
                         "\"tests\":{\"cases\":1,\"failures\":0,\"errors\":0,\"skipped\":0},"
                         "\"traditional_native_phrase_preserved\":true,"
                         "\"full_selection_commits_once\":true}\n";
            api->destroy_session(session);
            api->finalize();
            return 0;
        }
        size_t cases = 0;
        const std::vector<std::pair<std::string, std::string>> exact_cases{
            {"gaileme", "改了么"}, {"gaoleme", "高了么"}, {"nihaoa", "你好啊"},
            {"xiaoguniang", "小姑娘"}, {"jingchanghui", "经常会"},
            {"qinggeiwofagexiaoxi", "请给我发个消息"},
            {"zhunimeitiandoukaixin", "祝你每天都开心"},
            {"baoleme", "饱了么"}, {"haoleme", "好了么"},
            {"jihaoa", "几号啊"}, {"nuhaoa", "怒号啊"},
            {"jch", "轿车"}, {"jib", "级别"}
        };
        for (const auto& [input, expected] : exact_cases) {
            Replay(api, session, input);
            const auto candidates = Candidates(context);
            Require(!candidates.empty() && candidates.front()->text() == expected,
                    "literal or abbreviation first candidate was displaced");
            if (input == "gaileme" || input == "gaoleme") {
                for (const auto& correction : {"饱了么", "好了么"}) {
                    const int rank = Rank(candidates, correction);
                    Require(rank > 0 && rank < 5, "correction recall lost from first five");
                    Require(candidates[rank]->quality() < candidates.front()->quality(),
                            "supplement outranks complete literal candidate");
                }
            }
            if (input == "jihaoa" || input == "nuhaoa") {
                const int rank = Rank(candidates, "你好啊");
                Require(rank >= 0 && rank < 5, "valid-spelling typo suggestion lost from first five");
            }
            ++cases;
        }
        Replay(api, session, "jibgchsnghui");
        Require(Candidates(context).front()->text() == "经常会",
                "invalid-spelling first correction recall changed");
        ++cases;

        Replay(api, session, "gaileme");
        const auto candidates = Candidates(context);
        const auto caret = api->get_caret_pos(session);
        CheckPage(api, session, candidates, 0);
        Require(api->change_page(session, False), "next page unavailable");
        CheckPage(api, session, candidates, 1);
        Require(std::string(api->get_input(session)) == "gaileme" &&
                caret == api->get_caret_pos(session) && Commit(api, session).empty(),
                "pagination edited or committed original spelling");
        Require(api->change_page(session, True), "previous page unavailable");
        CheckPage(api, session, candidates, 0);
        ++cases;

        // Selecting the exact literal, and then a retained correction, uses the
        // native candidate index and auto-commits exactly once. No forced space,
        // replacement spelling replay or directly manufactured commit is used.
        size_t commits = 0;
        auto notification = context->commit_notifier().connect([&](rime::Context*) { ++commits; });
        for (const auto& selected : {"改了么", "饱了么"}) {
            Replay(api, session, "gaileme");
            const auto menu = Candidates(context);
            const int rank = Rank(menu, selected);
            Require(rank >= 0 && menu[rank]->end() == 7, "full-span selection missing");
            const auto genuine = rime::Candidate::GetGenuineCandidate(menu[rank]);
            Require(genuine && genuine->text() == selected, "wrapper lost genuine selection text");
            const auto before = commits;
            Require(api->select_candidate(session, rank), "native selection failed");
            Require(Commit(api, session) == selected && Commit(api, session).empty() &&
                    commits == before + 1 && !context->IsComposing(),
                    "full native selection changed text or committed more than once");
            ++cases;
        }
        Replay(api, session, "nihaoma");
        int partial = -1;
        const auto partial_menu = Candidates(context);
        for (size_t index = 0; index < partial_menu.size(); ++index)
            if (partial_menu[index]->end() < 7) { partial = static_cast<int>(index); break; }
        const auto before = commits;
        Require(partial >= 0 && api->select_candidate(session, partial), "partial selection unavailable");
        Require(Commit(api, session).empty() && commits == before && context->IsComposing(),
                "partial native selection committed");
        Replay(api, session, "nihaoma");
        Require(commits == before, "partial reset/replay committed or learned");
        ++cases;

        notification.disconnect();
        Require(context->options() == options && context->properties() == properties,
                "ranking changed mode options or properties");
        std::cout << "{\"schema\":1,\"engine_version\":\"1.12.0\",\"tests\":{\"cases\":" << cases
                  << ",\"failures\":0,\"errors\":0,\"skipped\":0},"
                     "\"literal_prefix_unchanged\":true,\"correction_recall_preserved\":true,"
                     "\"native_pagination_indices_unchanged\":true,\"full_selection_commits_once\":true,"
                     "\"partial_selection_does_not_commit\":true,"
                     "\"mode_options_properties_unchanged\":true}\n";
        api->destroy_session(session);
        api->finalize();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "ranking regression: " << error.what() << '\n';
        if (session) api->destroy_session(session);
        api->finalize();
        return 1;
    }
}
