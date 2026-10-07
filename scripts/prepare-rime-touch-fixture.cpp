/* SPDX-License-Identifier: LGPL-2.1-or-later */
// TEST FIXTURE ONLY. Production probe never initializes or deploys Rime.
// Upgrade old APK prism caches with the same runtime in a disposable user dir.
#include <rime_api.h>
#include <iostream>
#include <string>

int main(int argc, char** argv) {
    if (argc != 3) {
        std::cerr << "Usage: prepare-rime-touch-fixture SHARED_DATA DISPOSABLE_USER_DATA\n";
        return 2;
    }
    auto* api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    const char* modules[] = {"default", "deployer", "lua", "grammar", nullptr};
    traits.shared_data_dir = argv[1];
    traits.user_data_dir = argv[2];
    const auto prebuilt = std::string(argv[1]) + "/build";
    traits.prebuilt_data_dir = prebuilt.c_str();
    traits.app_name = "rime.axiang_public_fixture_setup";
    traits.modules = modules;
    traits.min_log_level = 2;
    api->setup(&traits);
    api->initialize(&traits);
    if (std::string(api->get_version()) != "1.16.1") {
        api->finalize();
        return 3;
    }
    bool successful = true;
    for (const auto* schema : {"rime_ice", "melt_eng", "xuancai_user", "radical_pinyin"}) {
        const auto file = std::string(argv[1]) + "/" + schema + ".schema.yaml";
        const bool deployed = api->deploy_schema(file.c_str());
        std::cout << schema << ' ' << deployed << '\n';
        successful = successful && deployed;
    }
    api->finalize();
    return successful ? 0 : 4;
}
