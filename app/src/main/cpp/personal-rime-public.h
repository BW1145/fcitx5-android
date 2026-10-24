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
FCITX_ADDON_DECLARE_FUNCTION(RimeEngine, personalMoveCursor,
    bool(fcitx::InputContext *, int))
