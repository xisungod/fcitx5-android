/* SPDX-License-Identifier: LGPL-2.1-or-later */
#define _POSIX_C_SOURCE 200809L
#include <rime_api.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Public host benchmark. Targets never enter Rime and no candidate is selected. */
static double now_ms(void) {
    struct timespec stamp;
    clock_gettime(CLOCK_MONOTONIC, &stamp);
    return stamp.tv_sec * 1000.0 + stamp.tv_nsec / 1e6;
}
static void json_string(const char* text) {
    putchar('"');
    for (; text && *text; ++text) {
        const unsigned char c = *text;
        if (c == '"' || c == '\\') { putchar('\\'); putchar(c); }
        else if (c < 32) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}
int main(int argc, char** argv) {
    if (argc < 7) return 2;
    RimeApi* api = rime_get_api();
    if (strcmp(api->get_version(), argv[4])) return 3;
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_public_latency_test";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    if (!api->find_module("lua") || !api->find_module("octagram")) return 4;
    const RimeSessionId session = api->create_session();
    if (!session || !api->select_schema(session, "rime_ice")) return 5;
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);
    for (int repeat = 0; repeat < atoi(argv[5]); ++repeat) {
        for (int sample = 6; sample < argc; ++sample) {
            const char* input = argv[sample];
            const size_t length = strlen(input);
            if (length > 255) return 6;
            double process[256], visible[256];
            char prefix[256] = {0};
            api->clear_composition(session);
            for (size_t index = 0; index < length; ++index) {
                const double started = now_ms();
                if (!api->process_key(session, input[index], 0)) return 7;
                process[index] = now_ms() - started;
                RIME_STRUCT(RimeContext, context);
                if (!api->get_context(session, &context)) return 8;
                api->free_context(&context);
                visible[index] = now_ms() - started;
                prefix[index] = input[index];
                if (!api->get_input(session) || strcmp(api->get_input(session), prefix)) return 9;
                RIME_STRUCT(RimeCommit, commit);
                if (api->get_commit(session, &commit)) {
                    api->free_commit(&commit);
                    return 10;
                }
            }
            printf("{\"input\":"); json_string(input);
            printf(",\"repeat\":%d,\"process_ms\":[", repeat);
            for (size_t index = 0; index < length; ++index)
                printf("%s%.6f", index ? "," : "", process[index]);
            printf("],\"visible_ms\":[");
            for (size_t index = 0; index < length; ++index)
                printf("%s%.6f", index ? "," : "", visible[index]);
            printf("],\"candidates\":[");
            RimeCandidateListIterator iterator = {0};
            if (api->candidate_list_begin(session, &iterator)) {
                for (int index = 0; index < 32 && api->candidate_list_next(&iterator); ++index) {
                    if (index) putchar(',');
                    json_string(iterator.candidate.text);
                }
                api->candidate_list_end(&iterator);
            }
            puts("]}"); fflush(stdout);
        }
    }
    api->destroy_session(session);
    api->finalize();
    return 0;
}
