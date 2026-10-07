/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "axiangpredict.h"
#include <cstdint>

namespace axiang::predict {
namespace {
bool Han(uint32_t cp) {
    return (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) ||
           (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x20000 && cp <= 0x2fa1f) ||
           (cp >= 0x30000 && cp <= 0x323af);
}
}
std::string HanSuffix(const std::string& context) {
    if (context.empty() || context.size() > 256) return {};
    size_t start = 0, count = 0;
    for (size_t pos = 0; pos < context.size();) {
        const auto first = static_cast<unsigned char>(context[pos]);
        size_t length = 0;
        uint32_t cp = 0, minimum = 0;
        if (first < 0x80) { length = 1; cp = first; }
        else if (first >= 0xc2 && first <= 0xdf) { length = 2; cp = first & 0x1f; minimum = 0x80; }
        else if (first >= 0xe0 && first <= 0xef) { length = 3; cp = first & 0x0f; minimum = 0x800; }
        else if (first >= 0xf0 && first <= 0xf4) { length = 4; cp = first & 0x07; minimum = 0x10000; }
        else return {};
        if (pos + length > context.size() || ++count > 64) return {};
        for (size_t i = 1; i < length; ++i) {
            const auto continuation = static_cast<unsigned char>(context[pos + i]);
            if ((continuation & 0xc0) != 0x80) return {};
            cp = (cp << 6) | (continuation & 0x3f);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff) || cp == 0) return {};
        pos += length;
        if (!Han(cp)) start = pos;
    }
    return context.substr(start);
}
bool HanCandidate(const std::string& value) {
    return !value.empty() && value.size() <= 64 && HanSuffix(value) == value;
}
} // namespace axiang::predict
