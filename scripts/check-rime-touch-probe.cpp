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
    const auto live = api->create_session();
    if (!live || !api->select_schema(live, "rime_ice")) return 3;
    api->set_option(live, "ascii_mode", False);
    const std::string original = "wojintianxiangqubeijing";
    for (char key : original) if (!api->process_key(live, key, 0)) return 4;
    const auto original_menu = Menu(api, live);
    const auto original_caret = api->get_caret_pos(live);
    const auto files_before = Files(argv[2]);
    size_t queries = 0;
    size_t returned = 0;
    bool limit = true;
    {
        axiang::typing::RimeTouchProbe probe("rime_ice");
        for (const auto& input : {"nihao", "wojintian", "beijing", "wojintianxiangqubeijing"}) {
            const auto candidates = probe.Query(input, "", std::chrono::seconds(5));
            ++queries;
            returned += candidates.size();
            limit = limit && candidates.size() <= 3;
            for (const auto& candidate : candidates) {
                limit = limit && candidate.start == 0 &&
                    candidate.end == static_cast<int>(std::strlen(input)) && candidate.rank > 0;
            }
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
    std::cout << "{\"engine\":\"" << api->get_version() << "\",\"queries\":" << queries
              << ",\"returned_candidates\":" << returned
              << ",\"raw_input_unchanged\":" << (raw_unchanged ? "true" : "false")
              << ",\"caret_unchanged\":" << (caret_unchanged ? "true" : "false")
              << ",\"live_menu_unchanged\":" << (menu_unchanged ? "true" : "false")
              << ",\"user_files_unchanged\":" << (files_unchanged ? "true" : "false")
              << ",\"bounded_fullspan_candidates\":" << (limit ? "true" : "false") << "}\n";
    api->destroy_session(live);
    api->finalize();
    return raw_unchanged && caret_unchanged && menu_unchanged && files_unchanged && limit && returned > 0 ? 0 : 5;
}
