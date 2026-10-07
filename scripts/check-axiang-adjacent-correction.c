/* Native acceptance checks for complete-word adjacent-key correction.
 * Use an empty user directory and the same schema/dictionary assets as the APK.
 * No word is committed until all ranking/control checks have completed, so a
 * learned selection cannot make a later regression falsely pass.
 */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static RimeApi *api;
static int test_cases;

static void type_input(RimeSessionId session, const char *input) {
    api->clear_composition(session);
    char prefix[128] = {0};
    assert(strlen(input) < sizeof(prefix));
    for (size_t i = 0; input[i]; ++i) {
        assert(api->process_key(session, input[i], 0));
        prefix[i] = input[i];
        assert(api->get_input(session));
        assert(strcmp(api->get_input(session), prefix) == 0);
        RIME_STRUCT(RimeCommit, commit);
        assert(!api->get_commit(session, &commit));
    }
}

static int rank_of(RimeSessionId session, const char *expected, int max_rank) {
    RimeCandidateListIterator iterator = {0};
    assert(api->candidate_list_begin(session, &iterator));
    int rank = 0, found = 0;
    while (rank < max_rank && api->candidate_list_next(&iterator)) {
        ++rank;
        if (strcmp(iterator.candidate.text, expected) == 0) {
            found = rank;
            break;
        }
    }
    api->candidate_list_end(&iterator);
    return found;
}

static void expect(RimeSessionId session, const char *input,
                   const char *expected, int max_rank) {
    type_input(session, input);
    int rank = rank_of(session, expected, max_rank);
    printf("Adjacent-key acceptance: %s => %s, rank %d (maximum %d)\n",
           input, expected, rank, max_rank);
    if (!rank) {
        fprintf(stderr, "Missing %s within first %d candidates for %s\n",
                expected, max_rank, input);
        exit(1);
    }
    assert(strcmp(api->get_input(session), input) == 0);
    ++test_cases;
}

static void expect_command_without_correction(RimeSessionId session, const char *input) {
    type_input(session, input);
    RimeCandidateListIterator iterator = {0};
    assert(api->candidate_list_begin(session, &iterator));
    int count = 0;
    while (count < 200 && api->candidate_list_next(&iterator)) {
        ++count;
        const char *comment = iterator.candidate.comment;
        assert(!comment || !strstr(comment, "纠错"));
        if (count == 1 && strcmp(input, "uuid") == 0) {
            const char *uuid = iterator.candidate.text;
            assert(strlen(uuid) == 36);
            assert(uuid[8] == '-' && uuid[13] == '-' && uuid[18] == '-' && uuid[23] == '-');
        }
    }
    api->candidate_list_end(&iterator);
    assert(count > 0);
    assert(strcmp(api->get_input(session), input) == 0);
    printf("Command %s keeps its candidates without typo suggestions.\n", input);
    ++test_cases;
}

int main(int argc, char **argv) {
    assert(argc == 4);
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    traits.prebuilt_data_dir = argv[3];
    traits.app_name = "rime.axiang_adjacent_acceptance";
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    assert(strcmp(api->get_version(), "1.16.1") == 0);
    assert(api->find_module("lua"));
    assert(api->find_module("octagram"));
    RimeSessionId session = api->create_session();
    assert(session);
    assert(api->select_schema(session, "rime_ice"));
    api->set_option(session, "ascii_mode", False);
    api->set_option(session, "traditionalization", False);

    /* Reported mistakes include cross-row and two simultaneous substitutions. */
    /* Full adjacency includes the J/B corner: buhaoa needs one replacement,
     * while nihaoa needs two (J/N and U/I). Both are plausible intentions;
     * require the target near the top without hardcoding it above another word. */
    expect(session, "juhaoa", "你好啊", 3);
    expect(session, "xiaoguniabg", "小姑娘", 1);
    /* xiao gu jiang is also exact full spelling. Keep its literal sentence
     * first; its possible neighboring-key intention remains a suggestion. */
    expect(session, "xiaogujiang", "小姑将", 1);
    expect(session, "xiaogujiang", "小姑娘", 5);
    expect(session, "xiaogunuabg", "小姑娘", 1);

    /* Independent words and key pairs: Y/H, G/T, F/G and Q/W. */
    expect(session, "pinhin", "拼音", 1);
    /* These also have exact full syllables. Protect their literal sentence,
     * while keeping the originally intended adjacent correction accessible. */
    expect(session, "xiaotuniang", "小图娘", 1);
    expect(session, "xiaotuniang", "小姑娘", 5);
    expect(session, "shuruga", "输入嘎", 1);
    expect(session, "shuruga", "输入法", 5);
    expect(session, "wingchu", "清楚", 1);

    /* Touching corners of staggered neighboring rows count as adjacent too:
     * N/K and F/V. These are dictionary-backed words, not production exceptions. */
    expect(session, "xiaogukiang", "小姑娘", 5);
    expect(session, "shuruva", "输入法", 5);

    /* Exact legal alternatives must keep their normal first candidate. */
    expect(session, "nihaoa", "你好啊", 1);
    expect(session, "xiaoguniang", "小姑娘", 1);
    expect(session, "juhao", "句号", 1);
    expect(session, "jiang", "将", 1);
    expect(session, "niang", "娘", 1);
    expect(session, "pinyin", "拼音", 1);
    expect(session, "shurufa", "输入法", 1);
    expect(session, "qingchu", "清楚", 1);
    expect(session, "nihaoma", "你好吗", 1);

    /* Legal but uncommon words stay first; intended adjacent alternatives stay accessible.
     * Homophones of a corrected spelling must not be discarded after its first word. */
    expect(session, "wentu", "吻突", 1);
    expect(session, "wentu", "问题", 5);
    expect(session, "wenti", "问题", 1);
    expect(session, "jihao", "记号", 1);
    expect(session, "jihao", "你好", 10);
    expect(session, "fuichu", "退出", 5);

    /* Initial abbreviations are not full spellings to be auto-repaired. */
    expect(session, "nh", "女孩", 1);
    expect(session, "xgn", "新概念", 1);
    expect(session, "zg", "这个", 1);

    time_t now = time(NULL);
    char today[32];
    strftime(today, sizeof(today), "%Y-%m-%d", localtime(&now));
    expect(session, "rq", today, 1);
    expect(session, "cC1+2", "3", 1);
    expect_command_without_correction(session, "rqen");
    expect_command_without_correction(session, "rqzh");
    expect_command_without_correction(session, "uuid");
    expect_command_without_correction(session, "nl");

    /* Corrections must still support ordinary editing and explicit selection. */
    type_input(session, "xiaogunuabg");
    assert(api->process_key(session, 0xff08, 0)); /* BackSpace */
    assert(strcmp(api->get_input(session), "xiaogunuab") == 0);
    assert(api->process_key(session, 'g', 0));
    assert(strcmp(api->get_input(session), "xiaogunuabg") == 0);
    assert(rank_of(session, "小姑娘", 1) == 1);
    assert(api->select_candidate(session, 0));
    RIME_STRUCT(RimeCommit, commit);
    assert(api->get_commit(session, &commit));
    assert(strcmp(commit.text, "小姑娘") == 0);
    api->free_commit(&commit);
    assert(!api->get_input(session) || !*api->get_input(session));
    ++test_cases;

    api->destroy_session(session);
    api->finalize();
    puts("Adjacent-key candidate, exact-spelling, abbreviation and command acceptance checks passed.");
    printf("{\"suite\":\"adjacent_correction\",\"cases\":%d,\"failures\":0}\n", test_cases);
    return 0;
}
