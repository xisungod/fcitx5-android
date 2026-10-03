/* Smoke-test the assembled shared data with the public librime API. */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static void expect_candidate(RimeSessionId session, const char *keys, const char *expected) {
    RimeClearComposition(session);
    assert(RimeSimulateKeySequence(session, keys));
    RIME_STRUCT(RimeContext, ctx);
    assert(RimeGetContext(session, &ctx));
    int found = 0;
    printf("%s:", keys);
    for (int i = 0; i < ctx.menu.num_candidates; ++i) {
        printf(" %s", ctx.menu.candidates[i].text);
        if (strcmp(ctx.menu.candidates[i].text, expected) == 0) found = 1;
    }
    puts("");
    RimeFreeContext(&ctx);
    assert(found);
}

int main(int argc, char **argv) {
    assert(argc == 4);
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.staging_dir = argv[3];
    traits.app_name = "rime.xuancai_verify";
    traits.min_log_level = 2;
    RimeSetup(&traits);
    RimeInitialize(&traits);
    RimeSessionId session = RimeCreateSession();
    assert(session);
    assert(RimeSelectSchema(session, "rime_ice"));
    RimeSetOption(session, "ascii_mode", False);
    RimeSetOption(session, "traditionalization", False);
    expect_candidate(session, "nihao", "你好");
    expect_candidate(session, "zhongguo", "中国");
    expect_candidate(session, "jisuanji", "计算机");
    expect_candidate(session, "hello", "hello");
    RimeDestroySession(session);
    RimeFinalize();
    puts("Rime Ice Chinese and English candidate checks passed.");
    return 0;
}
