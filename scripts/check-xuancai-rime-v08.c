/* Verify real librime 1.12 candidates, merged modules and composition-preserving mode changes. */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static RimeApi *api;
static void whole_phrase(RimeSessionId session, const char *keys, const char *expected) {
    api->clear_composition(session);
    char prefix[128] = {0};
    assert(strlen(keys) < sizeof(prefix));
    for (size_t i = 0; keys[i]; ++i) {
        assert(api->process_key(session, keys[i], 0));
        prefix[i] = keys[i];
        assert(strcmp(api->get_input(session), prefix) == 0);
    }
    RimeCandidateListIterator it = {0};
    assert(api->candidate_list_begin(session, &it));
    assert(api->candidate_list_next(&it));
    printf("Whole phrase: %s => %s\n", keys, it.candidate.text);
    assert(strcmp(it.candidate.text, expected) == 0);
    api->candidate_list_end(&it);
    assert(api->select_candidate(session, 0));
    RIME_STRUCT(RimeCommit, commit);
    assert(api->get_commit(session, &commit));
    assert(strcmp(commit.text, expected) == 0);
    api->free_commit(&commit);
    assert(!api->get_input(session) || !*api->get_input(session));
}
static void candidates(RimeSessionId session, const char *keys, const char *expected) {
    api->clear_composition(session);
    assert(api->simulate_key_sequence(session, keys));
    RimeCandidateListIterator it = {0};
    assert(api->candidate_list_begin(session, &it));
    int found = expected == NULL, count = 0;
    printf("%s =>", keys);
    while (count < 60 && api->candidate_list_next(&it)) {
        if (count < 6) printf(" %s", it.candidate.text);
        if (count == 0 && expected && strcmp(keys, "nihso") != 0 && strcmp(keys, "rq") != 0)
            assert(strcmp(expected, it.candidate.text) == 0);
        if (expected && strcmp(expected, it.candidate.text) == 0) found = 1;
        ++count;
    }
    api->candidate_list_end(&it);
    printf("\n");
    if (!found) { fprintf(stderr, "Missing candidate %s for %s\n", expected, keys); exit(1); }
    assert(count > 0);
}
int main(int argc, char **argv) {
    assert(argc == 4);
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1]; traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3]; traits.app_name = "rime.xuancai_v08_verify";
    traits.min_log_level = 2;
    api->setup(&traits); api->initialize(&traits);
    assert(strcmp(api->get_version(), "1.12.0") == 0);
    assert(api->find_module("lua")); assert(api->find_module("octagram"));
    RimeSessionId session = api->create_session(); assert(session);
    assert(api->select_schema(session, "rime_ice"));
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);
    candidates(session, "nihao", "你好");
    candidates(session, "zhongguo", "中国");
    candidates(session, "jisuanji", "计算机");
    candidates(session, "jintianwomentaolunyixiagongzuoanpai", "今天我们讨论一下工作安排");
    candidates(session, "mingtianxiawusandianwomenkaihui", "明天下午三点我们开会");
    candidates(session, "nihso", "你好");
    candidates(session, "shuangkashuangdai", "双卡双待");
    candidates(session, "shuagkashuangdai", "双卡双待");
    whole_phrase(session, "lianggehuangkimigcuiliao", "两个黄鹂鸣翠柳");
    whole_phrase(session, "lianggehuanglimingcuiliao", "两个黄鹂鸣翠柳");
    whole_phrase(session, "lianggehuangkimingcuiliu", "两个黄鹂鸣翠柳");
    whole_phrase(session, "lianggehuanglimigcuiliu", "两个黄鹂鸣翠柳");
    whole_phrase(session, "lianggehuanglimingcuiliu", "两个黄鹂鸣翠柳");
    whole_phrase(session, "yihangbailushangqingtiam", "一行白鹭上青天");
    // An intentionally appended word must not be swallowed by a phrase repair.
    whole_phrase(session, "lianggehuanglimingcuiliule", "两个黄鹂鸣翠柳了");
    whole_phrase(session, "lianggehuanglimingcuiliudezuozhe", "两个黄鹂鸣翠柳的作者");
    time_t now = time(NULL); char today[32];
    strftime(today, sizeof(today), "%Y-%m-%d", localtime(&now));
    candidates(session, "rq", today);
    candidates(session, "cC1+2", "3");
    api->clear_composition(session);
    api->simulate_key_sequence(session, "nihao");
    const char *input = api->get_input(session); assert(input && strcmp(input, "nihao") == 0);
    api->set_option(session, "ascii_mode", True);
    assert(api->get_option(session, "ascii_mode"));
    assert(strcmp(api->get_input(session), "nihao") == 0);
    RIME_STRUCT(RimeCommit, commit);
    assert(!api->get_commit(session, &commit));
    api->set_option(session, "ascii_mode", False);
    assert(strcmp(api->get_input(session), "nihao") == 0);
    // English characters must commit individually with no composing/underlined string.
    api->clear_composition(session);
    api->set_option(session, "ascii_mode", True);
    const char *english = "hello World!";
    for (const char *p = english; *p; ++p) {
        assert(api->process_key(session, *p, 0));
        RIME_STRUCT(RimeCommit, direct);
        assert(api->get_commit(session, &direct));
        assert(direct.text[0] == *p && direct.text[1] == '\0');
        api->free_commit(&direct);
        assert(!api->get_input(session) || !*api->get_input(session));
    }
    api->set_option(session, "ascii_mode", False);
    api->simulate_key_sequence(session, "nihao");
    api->set_option(session, "ascii_mode", True);
    assert(api->process_key(session, 'x', 0));
    RIME_STRUCT(RimeCommit, mixed);
    assert(api->get_commit(session, &mixed));
    assert(strcmp(mixed.text, "你好x") == 0);
    api->free_commit(&mixed);
    assert(!api->get_input(session) || !*api->get_input(session));
    puts("English direct commit: hello World!; pending Chinese + English: 你好x; no preedit.");
    FILE *maps = fopen("/proc/self/maps", "r"); assert(maps);
    char line[4096]; int model_loaded = 0, prebuilt_loaded = 0;
    while (fgets(line, sizeof(line), maps)) {
        if (strstr(line, "zh-hans-t-essay-bgw-compact.gram")) model_loaded = 1;
        if (strstr(line, "rime_ice.table.bin")) prebuilt_loaded = 1;
    }
    fclose(maps); assert(model_loaded); assert(prebuilt_loaded);
    api->destroy_session(session); api->finalize();
    puts("Full Lua, grammar configuration, correction and mode-switch checks passed.");
    return 0;
}
