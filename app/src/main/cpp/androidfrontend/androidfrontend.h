/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

#ifndef FCITX5_ANDROID_ANDROIDFRONTEND_H
#define FCITX5_ANDROID_ANDROIDFRONTEND_H

#include <fcitx/instance.h>
#include <fcitx/addoninstance.h>
#include <fcitx-utils/i18n.h>

#include "androidfrontend_public.h"
#include "inputcontextcache.h"

#include "../candidateattribution.h"

namespace fcitx {

class AndroidInputContext;

class AndroidFrontend : public AddonInstance {
public:
    explicit AndroidFrontend(Instance *instance);
#ifdef FCITX_CANDIDATE_ATTRIBUTION
    void attributionConfigure(int mode) { ime_attribution::configure(mode); }
    std::vector<int64_t> attributionDrain() { return ime_attribution::drain(); }
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, attributionConfigure);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, attributionDrain);
#endif

    Instance *instance() { return instance_; }

    void updateCandidateList(const std::vector<CandidateEntity> &candidates, int total, const CandidateScanState &state);
    void commitString(const std::string &str, int cursor, int64_t target);
    void updateClientPreedit(const Text &clientPreedit, int64_t target);
    void updateInputPanel(const Text &preedit, const Text &auxUp, const Text &auxDown, const std::vector<CandidateActionEntity> &tabs);
    void releaseInputContext(int uid);
    void updatePagedCandidate(const PagedCandidateEntity &paged);

    void keyEvent(const Key &key, bool isRelease, int timestamp);
    void forwardKey(const Key &key, bool isRelease, int64_t target);
    bool selectCandidate(int idx);
    bool isInputPanelEmpty();
    bool hasInputPreedit();
    void setInputTarget(int64_t target);
    bool candidateMatches(int idx, const std::string &text, int64_t revision);
    void resetInputContext();
    void repositionCursor(int idx);
    void focusInputContext(bool focus);
    void activateInputContext(int uid, const std::string &pkgName);
    void deactivateInputContext(int uid);
    [[nodiscard]] InputContext *activeInputContext() const;
    void setCapabilityFlags(uint64_t flag);
    std::vector<CandidateEntity> getCandidates(int offset, int limit);
    CandidateBatchEntity getCandidateBatch(int offset, int limit, int64_t revision, int64_t ruleRevision);
    CandidateScanState getCandidateScanState();
    std::vector<CandidateActionEntity> getCandidateActions(int idx);
    void triggerCandidateAction(int idx, int actionIdx);
    void triggerTabAction(int idx);
    void deleteSurrounding(int before, int after, int64_t target);
    void showToast(const std::string &s);
    void setCandidatePagingMode(int mode);
    void refreshCandidatePreferences();
    void offsetCandidatePage(int delta);
    void triggerCandidateListTabAction(int id);
    void setCandidateListCallback(const CandidateListCallback &callback);
    void setCommitStringCallback(const CommitStringCallback &callback);
    void setPreeditCallback(const ClientPreeditCallback &callback);
    void setInputPanelCallback(const InputPanelCallback &callback);
    void setKeyEventCallback(const KeyEventCallback &callback);
    void setInputMethodChangeCallback(const InputMethodChangeCallback &callback);
    void setStatusAreaUpdateCallback(const StatusAreaUpdateCallback &callback);
    void setDeleteSurroundingCallback(const DeleteSurroundingCallback &callback);
    void setToastCallback(const ToastCallback &callback);
    void setPagedCandidateCallback(const PagedCandidateCallback &callback);
    void setSwitchInputMethodCallback(const SwitchInputMethodCallback &callback);

private:
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, keyEvent);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, selectCandidate);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, isInputPanelEmpty);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, hasInputPreedit);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setInputTarget);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, candidateMatches);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, resetInputContext);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, repositionCursor);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, focusInputContext);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, activateInputContext);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, activeInputContext);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, deactivateInputContext);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setCapabilityFlags);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, getCandidates);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, getCandidateBatch);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, getCandidateScanState);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, getCandidateActions);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, triggerCandidateAction);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, triggerTabAction);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, showToast);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setCandidatePagingMode);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, refreshCandidatePreferences);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, offsetCandidatePage);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, triggerCandidateListTabAction);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setCandidateListCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setCommitStringCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setPreeditCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setInputPanelCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setKeyEventCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setInputMethodChangeCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setStatusAreaUpdateCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setDeleteSurroundingCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setToastCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setPagedCandidateCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidFrontend, setSwitchInputMethodCallback);

    Instance *instance_;
    FocusGroup focusGroup_;
    AndroidInputContext *activeIC_;
    InputContextCache icCache_;
    std::vector<std::unique_ptr<HandlerTableEntry<EventHandler>>> eventHandlers_;
    int pagingMode_;

    CandidateListCallback candidateListCallback = [](const std::vector<CandidateEntity> &, const int, const CandidateScanState &) {};
    CommitStringCallback commitStringCallback = [](const std::string &, const int, int64_t) {};
    ClientPreeditCallback preeditCallback = [](const Text &, int64_t) {};
    InputPanelCallback inputPanelCallback = [](const fcitx::Text &, const fcitx::Text &, const Text &, const std::vector<CandidateActionEntity> &) {};
    KeyEventCallback keyEventCallback = [](const int, const uint32_t, const uint32_t, const bool, const int, int64_t) {};
    InputMethodChangeCallback imChangeCallback = [](const InputMethodStatus &) {};
    StatusAreaUpdateCallback statusAreaUpdateCallback = [](const std::vector<ActionEntity> &, const InputMethodStatus &) {};
    DeleteSurroundingCallback deleteSurroundingCallback = [](const int, const int, int64_t) {};
    ToastCallback toastCallback = [](const std::string &) {};
    PagedCandidateCallback pagedCandidateCallback = [](const PagedCandidateEntity &) {};
    SwitchInputMethodCallback switchInputMethodCallback = [](const int, const std::string &) {};

    InputMethodStatus makeInputMethodStatus(InputContext* ic);
    std::vector<ActionEntity> makeStatusAreaActions(InputContext* ic);
};
} // namespace fcitx

#endif //FCITX5_ANDROID_ANDROIDFRONTEND_H
