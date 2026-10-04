/* Verify real librime 1.12 candidates, merged modules and composition-preserving mode changes. */
#include <rime_api.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static RimeApi *api;
int main(int argc, char **argv) {
    assert(argc >= 5);
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
    char prefix[128] = {0};
    assert(strlen(argv[4]) < sizeof(prefix));
    for (size_t i=0; argv[4][i]; ++i) {
      prefix[i] = argv[4][i];
      assert(api->process_key(session, argv[4][i], 0));
      assert(strcmp(api->get_input(session), prefix) == 0);
      printf("%s", prefix);
      RimeCandidateListIterator it = {0};
      assert(api->candidate_list_begin(session, &it));
      for(int j=0;j<4 && api->candidate_list_next(&it);++j) printf("\t%s",it.candidate.text);
      api->candidate_list_end(&it);
      puts("");
    }
    api->destroy_session(session); api->finalize();

    return 0;
}
