/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef AXIANG_RIME_TOUCH_PROBE_H_
#define AXIANG_RIME_TOUCH_PROBE_H_

#include <chrono>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace axiang::typing {

struct ProbeCandidate {
    std::string text;
    std::string comment;
    int start;
    int end;
    int rank;
};

// Stable, non-text failure codes cross JNI; never export exception messages,
// schema paths, user input or dictionary contents into diagnostics.
class ProbeUnavailable final : public std::runtime_error {
public:
    explicit ProbeUnavailable(const char* status) : std::runtime_error(status) {}
};

// This object owns a bare Engine, Config and Context, never a Rime service
// session. Its implementation must run only on the Fcitx native queue.
class RimeTouchProbe {
public:
    explicit RimeTouchProbe(const std::string& schema_id);
    ~RimeTouchProbe();
    RimeTouchProbe(const RimeTouchProbe&) = delete;
    RimeTouchProbe& operator=(const RimeTouchProbe&) = delete;

    std::vector<ProbeCandidate> Query(const std::string& input,
                                     const std::string& preceding_text,
                                     std::chrono::nanoseconds budget);
    static bool RuntimeReady();
    static std::string RuntimeStatus();
    static bool ValidInput(const std::string& input);

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace axiang::typing
#endif
