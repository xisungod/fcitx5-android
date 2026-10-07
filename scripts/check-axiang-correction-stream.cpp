/* SPDX-License-Identifier: LGPL-2.1-or-later */
// Inspect public synthetic prefixes separately from the latency measurement.
#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/service.h>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
void JsonString(const std::string& text) {
    std::cout << '"';
    for (const unsigned char c : text) {
        if (c == '"' || c == '\\') std::cout << '\\' << c;
        else if (c < 32) std::cout << "\\u" << std::hex << std::setw(4)
                                 << std::setfill('0') << static_cast<int>(c) << std::dec;
        else std::cout << c;
    }
    std::cout << '"';
}
void Require(bool value) {
    if (!value) throw std::runtime_error("Public prefix replay failed");
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) return 2;
    auto* api = rime_get_api();
    Require(std::string(api->get_version()) == argv[4]);
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_public_stream_replay";
    traits.min_log_level = 2;
    api->setup(&traits); api->initialize(&traits);
    const auto session = api->create_session();
    try {
        Require(session && api->select_schema(session, "rime_ice"));
        api->set_option(session, "ascii_mode", False);
        api->set_option(session, "traditionalization", False);
        auto* context = rime::Service::instance().GetSession(session)->context();
        const auto options = context->options();
        const auto properties = context->properties();
        for (int sample = 5; sample < argc; ++sample) {
            const std::string input(argv[sample]);
            api->clear_composition(session);
            for (size_t index = 0; index < input.size(); ++index) {
                Require(api->process_key(session, input[index], 0));
                Require(input.substr(0, index + 1) == api->get_input(session));
                RIME_STRUCT(RimeCommit, commit);
                Require(!api->get_commit(session, &commit));
                std::cout << "{\"input\":"; JsonString(input);
                std::cout << ",\"prefix\":"; JsonString(input.substr(0, index + 1));
                std::cout << ",\"candidates\":[";
                for (int rank = 0; rank < 32; ++rank) {
                    const auto candidate = context->composition().back().GetCandidateAt(rank);
                    if (!candidate) break;
                    if (rank) std::cout << ',';
                    std::cout << "{\"text\":"; JsonString(candidate->text());
                    std::cout << ",\"type\":"; JsonString(candidate->type());
                    std::cout << ",\"comment\":"; JsonString(candidate->comment());
                    std::cout << ",\"preedit\":"; JsonString(candidate->preedit());
                    std::cout << ",\"start\":" << candidate->start()
                              << ",\"end\":" << candidate->end() << '}';
                }
                std::cout << "]}\n";
                Require(context->options() == options && context->properties() == properties);
            }
        }
        api->destroy_session(session); api->finalize();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        api->destroy_session(session); api->finalize();
        return 1;
    }
}
