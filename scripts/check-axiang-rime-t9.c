/* Exercise the actual nine-key Rime prism, candidates, selection and mode boundaries. */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

static RimeApi *api;

static void type_code(RimeSessionId session, const char *code) {
    api->clear_composition(session);
    char prefix[128] = {0};
    assert(strlen(code) < sizeof(prefix));
    for (size_t i = 0; code[i]; ++i) {
        assert(api->process_key(session, code[i], 0));
        prefix[i] = code[i];
        assert(strcmp(api->get_input(session), prefix) == 0);
        RIME_STRUCT(RimeCommit, commit);
        assert(!api->get_commit(session, &commit));
    }
}

static void expect_word(RimeSessionId session, const char *code,
                        const char *word, const char *preedit, int first) {
    type_code(session, code);
    RimeCandidateListIterator iterator = {0};
    assert(api->candidate_list_begin(session, &iterator));
    int found = -1;
    for (int i = 0; i < 64 && api->candidate_list_next(&iterator); ++i) {
        if (strcmp(iterator.candidate.text, word) == 0) { found = i; break; }
    }
    api->candidate_list_end(&iterator);
    assert(found >= 0);
    if (first) assert(found == 0);
    if (preedit) {
        RIME_STRUCT(RimeContext, context);
        assert(api->get_context(session, &context));
        assert(strcmp(context.composition.preedit, preedit) == 0);
        api->free_context(&context);
    }
    assert(api->select_candidate(session, found));
    RIME_STRUCT(RimeCommit, commit);
    assert(api->get_commit(session, &commit));
    assert(strcmp(commit.text, word) == 0);
    api->free_commit(&commit);
    assert(!api->get_input(session) || !*api->get_input(session));
    printf("Nine-key %s => %s (candidate %d); no digit commits.\n", code, word, found);
}

int main(int argc, char **argv) {
    assert(argc == 4 || (argc == 5 &&
        (strcmp(argv[4], "personal") == 0 || strcmp(argv[4], "personal-update") == 0)));
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_t9_verify";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    assert(strcmp(api->get_version(), "1.12.0") == 0);
    RimeSchemaList schemas = {0};
    assert(api->get_schema_list(&schemas));
    assert(schemas.size >= 2);
    assert(strcmp(schemas.list[0].schema_id, "rime_ice") == 0);
    assert(strcmp(schemas.list[1].schema_id, "rime_ice_t9") == 0);
    api->free_schema_list(&schemas);
    RimeSessionId session = api->create_session();
    assert(session);
    assert(api->select_schema(session, "rime_ice_t9"));
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);

    if (argc == 5) {
        if (strcmp(argv[4], "personal-update") == 0) {
            expect_word(session, "385432678248426", "独立导入词条", NULL, 1);
        } else {
            expect_word(session, "98262244373624842692694364", "炫彩个人词条验证", NULL, 1);
            expect_word(session, "26948654942628748243426", "安卓离线部署词典", NULL, 1);
        }
    } else {
        expect_word(session, "64426", "你好", "ni hao", 1);
        expect_word(session, "94664486", "中国", "zhong guo", 1);
        expect_word(session, "54782654", "计算机", "ji suan ji", 1);
        expect_word(session, "74826452748264324", "双卡双待", "shuang ka shuang dai", 1);
        expect_word(session, "3298926669464", "大语言模型", "da yu yan mo xing", 1);
        expect_word(session, "9426", "小", "xiao", 1);
        expect_word(session, "94'26", "西安", NULL, 0);
        type_code(session, "64426");
        assert(api->process_key(session, 0xff08, 0)); /* BackSpace */
        assert(strcmp(api->get_input(session), "6442") == 0);
        assert(api->process_key(session, '6', 0));
        assert(strcmp(api->get_input(session), "64426") == 0);
        assert(api->process_key(session, ' ', 0));
        RIME_STRUCT(RimeCommit, spaced);
        assert(api->get_commit(session, &spaced));
        assert(strcmp(spaced.text, "你好") == 0);
        api->free_commit(&spaced);
        type_code(session, "9466");
        assert(api->process_key(session, 0xff1b, 0)); /* Escape */
        assert(!api->get_input(session) || !*api->get_input(session));
        RIME_STRUCT(RimeCommit, cancelled);
        assert(!api->get_commit(session, &cancelled));

        /* The alphabetic schema and English direct commit remain independent. */
        assert(api->select_schema(session, "rime_ice"));
        api->set_option(session, "ascii_mode", False);
        expect_word(session, "nihao", "你好", NULL, 1);
        api->set_option(session, "ascii_mode", True);
        assert(api->process_key(session, '2', 0));
        RIME_STRUCT(RimeCommit, digit);
        assert(api->get_commit(session, &digit));
        assert(strcmp(digit.text, "2") == 0);
        api->free_commit(&digit);
        assert(api->select_schema(session, "rime_ice_t9"));
        api->set_option(session, "ascii_mode", False);
        expect_word(session, "64426", "你好", "ni hao", 1);
        puts("T9 backspace, space selection, cancel, alphabetic switch and Latin digit boundaries passed.");
    }
    api->destroy_session(session);
    api->finalize();
    puts("Nine-key Rime native candidate checks passed.");
    return 0;
}
