// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <fcitx/addoninstance.h>
#include <fcitx/inputcontext.h>
#include <string>
#include <vector>
FCITX_ADDON_DECLARE_FUNCTION(RimeEngine, personalContext,
    std::vector<std::string>(fcitx::InputContext *))
FCITX_ADDON_DECLARE_FUNCTION(RimeEngine, personalSchemas,
    std::vector<std::string>(const std::string &))

#ifdef FCITX_CANDIDATE_ATTRIBUTION
FCITX_ADDON_DECLARE_FUNCTION(RimeEngine, attributionConfigure, void(int))
FCITX_ADDON_DECLARE_FUNCTION(RimeEngine, attributionDrain, std::vector<int64_t>())
#endif
