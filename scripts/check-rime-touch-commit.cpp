/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Uses a disposable host user directory. Android production never initializes Rime.
#include "rime-touch-probe.h"
#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/service.h>
#include <rime/segmentation.h>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
void Require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

std::string Commit(RimeApi* api, RimeSessionId session) {
    RIME_STRUCT(RimeCommit, commit);
    if (!api->get_commit(session, &commit)) return {};
    std::string text = commit.text;
    api->free_commit(&commit);
    return text;
}

void Replay(RimeApi* api, RimeSessionId session, const std::string& keys) {
    api->clear_composition(session);
    Require(Commit(api, session).empty(), "reset committed text");
    for (size_t i = 0; i < keys.size(); ++i) {
        Require(api->process_key(session, keys[i], 0), "replay key unhandled");
        Require(Commit(api, session).empty(), "replay committed text");
        Require(keys.substr(0, i + 1) == api->get_input(session), "replay changed raw prefix");
    }
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 4) return 2;
    auto* api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_touch2_commit_test";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    Require(std::string(api->get_version()) == "1.16.1", "runtime version mismatch");
    const auto session = api->create_session();
    Require(session && api->select_schema(session, "rime_ice"), "missing schema");
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);
    auto* context = rime::Service::instance().GetSession(session)->context();
    const auto options = context->options();
    const auto properties = context->properties();
    Require(context->get_option("_auto_commit"), "pinned express_editor auto_commit disabled");
    size_t commit_notifications = 0;
    const auto notification = context->commit_notifier().connect(
        [&](rime::Context*) { ++commit_notifications; });

    const std::string original = "nihaoma";
    Replay(api, session, original);
    Require(commit_notifications == 0, "original replay learned");
    // Select an actual partial live candidate, then discard it and reconstruct
    // the original raw input. No direct commit/space/enter fallback is used.
    int partial_index = -1;
    for (int i = 0; i < 48; ++i) {
        auto candidate = context->composition().back().GetCandidateAt(i);
        if (!candidate) break;
        if (candidate->end() < original.size()) { partial_index = i; break; }
    }
    Require(partial_index >= 0, "no partial live candidate");
    Require(api->select_candidate(session, partial_index), "partial select failed");
    Require(Commit(api, session).empty(), "partial select committed");
    Require(commit_notifications == 0 && context->IsComposing(), "partial selection learned/cleared");
    Replay(api, session, original);
    Require(commit_notifications == 0 && original == api->get_input(session), "partial rollback failed");

    const std::string alternative = "nihao";
    {
    axiang::typing::RimeTouchProbe probe("rime_ice");
    const auto probed = probe.Query(alternative, "", std::chrono::seconds(5));
    Require(!probed.empty(), "no probe candidate");
    Replay(api, session, alternative);
    int full_index = -1;
    std::string selected;
    for (int i = 0; i < 48; ++i) {
        auto candidate = context->composition().back().GetCandidateAt(i);
        if (!candidate) break;
        if (candidate->text() == probed.front().text && candidate->end() == alternative.size()) {
            full_index = i;
            selected = candidate->text();
            break;
        }
    }
    Require(full_index >= 0, "exact probe text absent from full-span live list");
    Require(api->select_candidate(session, full_index), "full select failed");
    Require(Commit(api, session) == selected, "full selection committed a different value");
    Require(Commit(api, session).empty(), "full select double committed");
    Require(commit_notifications == 1 && !context->IsComposing(), "full select failed/double learned");
    } // Dispose the cached probe before the host's Rime finalization below.
    Require(options == context->options() && properties == context->properties(), "reset changed mode/options");
    char schema[64] = {};
    Require(api->get_current_schema(session, schema, sizeof(schema)) &&
            std::string(schema) == "rime_ice", "reset changed schema");
    std::cout << "{\"engine\":\"" << api->get_version() << "\",\"pinned_auto_commit\":true,"
                 "\"partial_select_commits\":0,\"reset_replay_commits\":0,"
                 "\"partial_restored_raw\":true,\"explicit_full_select_commits\":1,"
                 "\"commit_notifications\":1,\"mode_options_properties_schema_unchanged\":true}\n";
    notification.disconnect();
    api->destroy_session(session);
    api->finalize();
}
