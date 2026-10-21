// SPDX-License-Identifier: LGPL-2.1-or-later
#pragma once
#include <fcitx/addoninstance.h>
#include <fcitx/inputcontext.h>
#include <string>
FCITX_ADDON_DECLARE_FUNCTION(AnthyEngine, personalIsConverting,
    bool(fcitx::InputContext *))
FCITX_ADDON_DECLARE_FUNCTION(AnthyEngine, personalSelectPreview,
    bool(fcitx::InputContext *, const std::string &))
FCITX_ADDON_DECLARE_FUNCTION(AnthyEngine, personalReading,
    std::string(fcitx::InputContext *))
