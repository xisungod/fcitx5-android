/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef AXIANG_LIBIME_NEXT_WORD_H_
#define AXIANG_LIBIME_NEXT_WORD_H_
#include <memory>
#include <string>
#include <vector>

namespace axiang::predict {
// Standard UTF-8, at most 64 code points. Only the final contiguous Han run
// supplies language context; punctuation/emoji at the caret suppress prediction.
std::string HanSuffix(const std::string& context);
bool HanCandidate(const std::string& value);

class Predictor final {
public:
    explicit Predictor(const std::string& model_file);
    ~Predictor();
    Predictor(const Predictor&) = delete;
    Predictor& operator=(const Predictor&) = delete;
    std::vector<std::string> Query(const std::string& context, int limit);
private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace axiang::predict
#endif
