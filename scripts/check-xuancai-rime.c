/* Smoke-test the assembled shared data with the public librime API. */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static RimeApi *api;

static void expect_candidate(RimeSessionId session, const char *keys, const char *expected) {
    api->clear_composition(session);
    assert(api->simulate_key_sequence(session, keys));
    RIME_STRUCT(RimeContext, ctx);
    assert(api->get_context(session, &ctx));
    int found = 0;
    printf("%s:", keys);
    for (int i = 0; i < ctx.menu.num_candidates; ++i) {
        printf(" %s", ctx.menu.candidates[i].text);
        if (strcmp(ctx.menu.candidates[i].text, expected) == 0) found = 1;
    }
    puts("");
    api->free_context(&ctx);
    assert(found);
}

int main(int argc, char **argv) {
    assert(argc == 4);
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.staging_dir = argv[3];
    traits.app_name = "rime.xuancai_verify";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    assert(strcmp(api->get_version(), "1.16.1") == 0);
    RimeSessionId session = api->create_session();
    assert(session);
    assert(api->select_schema(session, "rime_ice"));
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);
    expect_candidate(session, "nihao", "你好");
    expect_candidate(session, "zhongguo", "中国");
    expect_candidate(session, "jisuanji", "计算机");
    expect_candidate(session, "hello", "hello");
    api->destroy_session(session);
    api->finalize();
    puts("Rime Ice Chinese and English candidate checks passed.");
    puts("{\"suite\":\"rime_smoke\",\"cases\":4,\"failures\":0}");
    return 0;
}
