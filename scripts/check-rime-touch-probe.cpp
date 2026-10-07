/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Host isolation test. Production bridge never owns Rime's lifecycle.
#include "rime-touch-probe.h"
#include <rime_api.h>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Snapshot = std::map<std::string, std::string>;
Snapshot Files(const std::filesystem::path& directory) {
    Snapshot result;
    for (const auto& entry : std::filesystem::recursive_directory_iterator(directory)) {
        if (!entry.is_regular_file()) continue;
        std::ifstream stream(entry.path(), std::ios::binary);
        result.emplace(entry.path().lexically_relative(directory).string(),
            std::string(std::istreambuf_iterator<char>(stream), {}));
    }
    return result;
}

std::vector<std::string> Menu(RimeApi* api, RimeSessionId session) {
    RIME_STRUCT(RimeContext, context);
    if (!api->get_context(session, &context)) throw std::runtime_error("live context unavailable");
    std::vector<std::string> candidates;
    for (int i = 0; i < context.menu.num_candidates; ++i) {
        candidates.emplace_back(context.menu.candidates[i].text);
    }
    api->free_context(&context);
    return candidates;
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 4) {
        std::cerr << "Usage: check-rime-touch-probe SHARED_DATA TEMP_USER_DATA PREBUILT_DATA\n";
        return 2;
    }
    auto* api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_touch2_isolation_test";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    if (std::string(api->get_version()) != "1.16.1" ||
        axiang::typing::RimeTouchProbe::RuntimeStatus() != "Ready") return 6;
    const auto live = api->create_session();
    if (!live || !api->select_schema(live, "rime_ice")) return 3;
    api->set_option(live, "ascii_mode", False);
    const std::string original = "wojintianxiangqubeijing";
    for (char key : original) if (!api->process_key(live, key, 0)) return 4;
    const auto original_menu = Menu(api, live);
    const auto original_caret = api->get_caret_pos(live);
    const auto original_ascii = api->get_option(live, "ascii_mode");
    RIME_STRUCT(RimeStatus, before_status);
    if (!api->get_status(live, &before_status)) return 7;
    const std::string original_schema = before_status.schema_id;
    api->free_status(&before_status);
    const auto files_before = Files(argv[2]);
    size_t queries = 0;
    size_t returned = 0;
    bool limit = true;
    bool all_queries_returned = true;
    bool expected_text_returned = true;
    {
        axiang::typing::RimeTouchProbe probe("rime_ice");
        const std::map<std::string, std::string> expected{{"nihao", "你好"},
            {"wojintian", "我今天"}, {"beijing", "北京"},
            {"wojintianxiangqubeijing", "我今天想去北京"}};
        for (const auto& input : {"nihao", "wojintian", "beijing", "wojintianxiangqubeijing"}) {
            const auto candidates = probe.Query(input, "", std::chrono::seconds(5));
            ++queries;
            returned += candidates.size();
            all_queries_returned = all_queries_returned && !candidates.empty();
            bool found_expected = false;
            limit = limit && candidates.size() <= 3;
            for (const auto& candidate : candidates) {
                found_expected = found_expected || candidate.text == expected.at(input);
                limit = limit && candidate.start == 0 &&
                    candidate.end == static_cast<int>(std::strlen(input)) && candidate.rank > 0;
            }
            expected_text_returned = expected_text_returned && found_expected;
        }
        if (!probe.Query("nihao", "北京", std::chrono::seconds(5)).empty()) ++queries;
        limit = limit && probe.Query("nihao{space}", "", std::chrono::seconds(5)).empty();
        limit = limit && probe.Query(std::string(33, 'a'), "", std::chrono::seconds(5)).empty();
        limit = limit && probe.Query("nihao", std::string(193, 'x'),
                                      std::chrono::seconds(5)).empty();
        limit = limit && probe.Query("nihao", "", std::chrono::nanoseconds(1)).empty();
    }
    const bool raw_unchanged = original == api->get_input(live);
    const bool caret_unchanged = original_caret == api->get_caret_pos(live);
    const bool menu_unchanged = original_menu == Menu(api, live);
    const bool files_unchanged = files_before == Files(argv[2]);
    const bool options_unchanged = original_ascii == api->get_option(live, "ascii_mode");
    RIME_STRUCT(RimeStatus, after_status);
    if (!api->get_status(live, &after_status)) return 8;
    const bool schema_unchanged = original_schema == after_status.schema_id;
    api->free_status(&after_status);
    RIME_STRUCT(RimeCommit, unexpected_commit);
    const bool no_commit = !api->get_commit(live, &unexpected_commit);
    if (!no_commit) api->free_commit(&unexpected_commit);
    std::cout << "{\"engine\":\"" << api->get_version() << "\",\"queries\":" << queries
              << ",\"returned_candidates\":" << returned
              << ",\"raw_input_unchanged\":" << (raw_unchanged ? "true" : "false")
              << ",\"caret_unchanged\":" << (caret_unchanged ? "true" : "false")
              << ",\"live_menu_unchanged\":" << (menu_unchanged ? "true" : "false")
              << ",\"user_files_unchanged\":" << (files_unchanged ? "true" : "false")
              << ",\"live_options_unchanged\":" << (options_unchanged ? "true" : "false")
              << ",\"live_schema_unchanged\":" << (schema_unchanged ? "true" : "false")
              << ",\"no_live_commit\":" << (no_commit ? "true" : "false")
              << ",\"all_queries_returned_candidates\":" << (all_queries_returned ? "true" : "false")
              << ",\"expected_fullspan_text_returned\":" << (expected_text_returned ? "true" : "false")
              << ",\"bounded_fullspan_candidates\":" << (limit ? "true" : "false") << "}\n";
    api->destroy_session(live);
    api->finalize();
    return raw_unchanged && caret_unchanged && menu_unchanged && files_unchanged &&
        options_unchanged && schema_unchanged && no_commit && limit &&
        all_queries_returned && expected_text_returned && returned > 0 ? 0 : 5;
}
