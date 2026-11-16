/* SPDX-License-Identifier: LGPL-2.1-or-later */
/* Probe real cold Rime candidates without committing any test data to a user DB. */
#define _POSIX_C_SOURCE 200809L
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double ms(void) {
    struct timespec now;
    assert(clock_gettime(CLOCK_MONOTONIC, &now) == 0);
    return now.tv_sec * 1000.0 + now.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    if (argc != 5) { fprintf(stderr, "usage: %s shared user prebuilt schema\n", argv[0]); return 2; }
    RimeApi *api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1]; traits.user_data_dir = argv[2]; traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_grammar_ab"; traits.min_log_level = 2;
    double t = ms();
    api->setup(&traits); api->initialize(&traits);
    assert(strcmp(api->get_version(), "1.12.0") == 0);
    assert(api->find_module("lua") && api->find_module("octagram"));
    RimeSessionId session = api->create_session(); assert(session);
    assert(api->select_schema(session, argv[4]));
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);
    api->set_option(session, "emoji", False);
    fprintf(stderr, "initialize+select_schema_ms=%.2f\n", ms()-t);
    char *line = NULL;
    size_t cap = 0;
    ssize_t n = 0;
    while ((n = getline(&line, &cap, stdin)) > 0) {
        if (line[n-1] == '\n') line[--n] = '\0';
        if (!n || line[0] == '#') continue;
        api->clear_composition(session);
        t = ms();
        const Bool simulated = api->simulate_key_sequence(session, line);
        const char *composition = api->get_input(session);
        if (!simulated || !composition || strcmp(composition, line)) {
            fprintf(stderr, "incomplete_input=%s composition=%s\n", line, composition ? composition : "(none)");
            puts(line);
            continue;
        }
        printf("%s", line);
        RimeCandidateListIterator it = {0};
        if (api->candidate_list_begin(session, &it)) {
            for (int i = 0; i < 5 && api->candidate_list_next(&it); ++i)
                printf("\t%s", it.candidate.text);
            api->candidate_list_end(&it);
        }
        fprintf(stderr, "composition_ms=%s %.2f\n", line, ms()-t);
        puts("");
    }
    free(line);
    const char *expected_grammar = getenv("RIME_AB_EXPECT_GRAMMAR");
    if (expected_grammar) {
        FILE *maps = fopen("/proc/self/maps", "r");
        assert(maps);
        line = NULL; cap = 0;
        int found = 0;
        while (getline(&line, &cap, maps) > 0) {
            if (strstr(line, expected_grammar)) { found = 1; break; }
        }
        free(line); fclose(maps);
        fprintf(stderr, "grammar_mapped=%d %s\n", found, expected_grammar);
        assert(found);
    }
    api->destroy_session(session);
    api->finalize();
    return 0;
}
