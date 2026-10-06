/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Public synthetic input only; run with the same baseline assets and disposable user dir.
#include "rime-touch-probe.h"
#include <rime_api.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
double Milliseconds(Clock::time_point started) {
    return std::chrono::duration<double, std::milli>(Clock::now() - started).count();
}
double Percentile(std::vector<double> values, double quantile) {
    std::sort(values.begin(), values.end());
    return values[static_cast<size_t>(std::ceil(quantile * values.size())) - 1];
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 4) return 2;
    auto* api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_touch2_public_synthetic_latency";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    const auto live = api->create_session();
    if (!live || !api->select_schema(live, "rime_ice")) return 3;
    api->set_option(live, "ascii_mode", False);
    for (char c : std::string("ni")) api->process_key(live, c, 0);
    const auto created_at = Clock::now();
    auto probe = std::make_unique<axiang::typing::RimeTouchProbe>("rime_ice");
    const auto cold_create_ms = Milliseconds(created_at);
    const auto first_started = Clock::now();
    const auto first = probe->Query("nihao", "", std::chrono::seconds(5));
    const auto first_query_ms = Milliseconds(first_started);
    constexpr int rounds = 100;
    const std::vector<std::string> inputs{
        "nihao", "jingchanghui", "xiaoguniang", "wojintianxiangqubeijingkanpengyo"};
    std::cout << std::fixed << std::setprecision(6)
        << "{\"schema\":1,\"engine\":\"" << api->get_version()
        << "\",\"environment\":\"Linux x86_64 host; not Android device latency\","
           "\"cold_definition\":\"first probe object while live Rime and its baseline dictionaries are already loaded; OS cache not flushed\","
           "\"jni_kotlin_overhead_included\":false,\"cold_create_ms\":" << cold_create_ms
        << ",\"first_query_ms\":" << first_query_ms
        << ",\"cold_create_plus_first_query_ms\":" << cold_create_ms + first_query_ms
        << ",\"first_query_candidates\":" << first.size()
        << ",\"production_result_budget_ms\":20,\"warm\":[";
    bool first_input = true;
    for (const auto& input : inputs) {
        std::vector<double> elapsed;
        size_t over_budget = 0;
        size_t total_candidates = 0;
        for (int round = 0; round < rounds; ++round) {
            const auto started = Clock::now();
            const auto candidates = probe->Query(input, "", std::chrono::milliseconds(20));
            const auto duration = Milliseconds(started);
            elapsed.push_back(duration);
            if (duration > 20) ++over_budget;
            total_candidates += candidates.size();
        }
        if (!first_input) std::cout << ',';
        first_input = false;
        std::cout << "{\"public_input\":\"" << input << "\",\"input_letters\":" << input.size()
            << ",\"rounds\":" << rounds << ",\"p50_ms\":" << Percentile(elapsed, 0.5)
            << ",\"p95_ms\":" << Percentile(elapsed, 0.95)
            << ",\"max_ms\":" << *std::max_element(elapsed.begin(), elapsed.end())
            << ",\"over_20ms_count\":" << over_budget
            << ",\"mean_returned_candidates\":" << double(total_candidates) / rounds << '}';
    }
    const auto raw = api->get_input(live);
    const bool unchanged = raw && std::string(raw) == "ni";
    std::cout << "],\"live_raw_unchanged\":" << (unchanged ? "true" : "false") << "}\n";
    probe.reset();
    api->destroy_session(live);
    api->finalize();
    return unchanged ? 0 : 4;
}
