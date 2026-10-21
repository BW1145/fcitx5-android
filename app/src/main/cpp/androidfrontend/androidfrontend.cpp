/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>
#include <fcitx/candidatelist.h>
#include <fcitx/focusgroup.h>
#include <fcitx/inputcontextmanager.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputmethodmanager.h>
#include <fcitx/inputpanel.h>
#include <fcitx/statusarea.h>
#include <fcitx-utils/event.h>
#include <fcitx-utils/standardpaths.h>

#include "androidfrontend.h"
#include "candidatepreferences.h"
#include "../personal-anthy-public.h"

namespace fcitx {

class AndroidInputContext : public InputContextV2 {
public:
    AndroidInputContext(AndroidFrontend *frontend,
                        InputContextManager &inputContextManager,
                        int uid,
                        const std::string &pkgName)
            : InputContextV2(inputContextManager, pkgName),
              frontend_(frontend),
              uid_(uid) {
        created();
    }

    ~AndroidInputContext() override {
        frontend_->releaseInputContext(uid_);
        destroy();
    }

    [[nodiscard]] const char *frontend() const override { return "androidfrontend"; }

    void commitStringImpl(const std::string &text) override {
        lastCommitted_ = text;
        frontend_->commitString(text, -1);
    }

    void commitStringWithCursorImpl(const std::string &text, size_t cursor) override {
        lastCommitted_ = text;
        frontend_->commitString(text, static_cast<int>(cursor));
    }

    void forwardKeyImpl(const ForwardKeyEvent &key) override {
        frontend_->forwardKey(key.rawKey(), key.isRelease());
    }

    void deleteSurroundingTextImpl(int offset, unsigned int size) override {
        const int before = -offset;
        const int after = offset + static_cast<int>(size);
        if (before < 0 || after < 0) {
            FCITX_WARN() << "Invalid deleteSurrounding request: offset=" << offset << ", size="
                         << size;
            return;
        }
        frontend_->deleteSurrounding(before, after);
    }

    void updatePreeditImpl() override {
        frontend_->updateClientPreedit(filterText(inputPanel().clientPreedit()));
    }

    void updateInputPanel() {
        const InputPanel &ip = inputPanel();
        if (ip.empty()) {
          frontend_->updateInputPanel(/*preedit=*/{},
                                      /*auxUp=*/ip.overlayMessage(),
                                      /*auxDown=*/{},
                                      /*tabs=*/{});
          return;
        }
        std::vector<CandidateActionEntity> tabs;
        auto list = ip.candidateList();
        if (list) {
            auto tabbed = ip.candidateList()->toTabbed();
            if (tabbed) {
                auto tabActions = tabbed->tabActions();
                for (const auto &a: tabActions) {
                    tabs.emplace_back(a);
                }
            }
        }
        frontend_->updateInputPanel(
                filterText(ip.preedit()),
                filterText(ip.auxUp()),
                filterText(ip.auxDown()),
                tabs
        );
    }

    void reloadPreferences() { preferences_.load(true); }

    void updateCandidatesBulk() {
        beginPreferences(false);
        if (prefer_) {
            auto candidates = preferenceCandidates(0,16);
            frontend_->updateCandidateList(candidates, ended_ ? static_cast<int>(order_.size()) : -1);
            return;
        }
        std::vector<CandidateEntity> candidates;
        int total = 0;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                total = bulk->totalSize();
                // limit candidate count to 16 (for paging)
                const int limit = total < 0 ? 16 : std::min(total, 16);
                for (int i = 0; i < limit; i++) {
                    try {
                        // maybe unnecessary; I don't see anywhere using `CandidateWord::setPlaceHolder`
                        // if (candidate.isPlaceHolder()) continue;
                        candidates.emplace_back(candidateEntity(bulk->candidateFromAll(i)));
                    } catch (const std::invalid_argument &e) {
                        FCITX_WARN() << "updateCandidatesBulk(): " << e.what();
                        total = static_cast<int>(candidates.size());
                        break;
                    }
                }
            } else {
                total = list->size();
                for (int i = 0; i < total; i++) {
                    candidates.emplace_back(candidateEntity(list->candidate(i)));
                }
            }
        }
        frontend_->updateCandidateList(candidates, total);
    }

    void updateCandidatesPaged() {
        beginPreferences(true);
        const auto &list = inputPanel().candidateList();
        if (!list) {
            frontend_->updatePagedCandidate(PagedCandidateEntity::Empty);
            return;
        }
        int cursorIndex = list->cursorIndex();
        CandidateLayoutHint layoutHint = list->layoutHint();
        bool hasPrev = false;
        bool hasNext = false;
        const auto &pageable = list->toPageable();
        if (pageable) {
            hasPrev = pageable->hasPrev();
            hasNext = pageable->hasNext();
        }
        if (prefer_) {
            auto candidates=preferenceCandidates(0,list->size());
            frontend_->updatePagedCandidate(PagedCandidateEntity(candidates,candidates.empty() ? -1 : 0,
                layoutHint,hasPrev,hasNext));
            return;
        }
        int size = list->size();
        std::vector<CandidateEntity> candidates;
        candidates.reserve(size);
        for (int i = 0; i < size; i++) {
            candidates.emplace_back(candidateEntityWithLabel(list->label(i), list->candidate(i)));
        }
        PagedCandidateEntity paged(candidates, cursorIndex, layoutHint, hasPrev, hasNext);
        frontend_->updatePagedCandidate(paged);
    }

    bool selectCandidateBulk(int idx) {
        idx = originalIndex(idx);
        if(idx<0) return false;
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        const auto &bulk = list->toBulk();
        try {
            if (bulk) {
                bulk->candidateFromAll(idx).select(this);
            } else {
                list->candidate(idx).select(this);
            }
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    bool selectCandidatePaged(int idx) {
        idx = originalIndex(idx);
        if(idx<0) return false;
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return false;
        }
        try {
            list->candidate(idx).select(this);
        } catch (const std::invalid_argument &e) {
            FCITX_WARN() << "selectCandidate index out of range";
            return false;
        }
        return true;
    }

    std::vector<CandidateEntity> getCandidates(const int offset, const int limit) {
        if(prefer_) return preferenceCandidates(offset,limit);
        std::vector<CandidateEntity> candidates;
        const auto &list = inputPanel().candidateList();
        if (list) {
            const int last = offset + limit;
            const auto &bulk = list->toBulk();
            if (bulk) {
                const int totalSize = bulk->totalSize();
                const int end = totalSize < 0 ? last : std::min(totalSize, last);
                for (int i = offset; i < end; i++) {
                    try {
                        candidates.emplace_back(candidateEntity(bulk->candidateFromAll(i)));
                    } catch (const std::invalid_argument &e) {
                        break;
                    }
                }
            } else {
                const int end = std::min(list->size(), last);
                for (int i = offset; i < end; i++) {
                    candidates.emplace_back(candidateEntityWithLabel(list->label(i), list->candidate(i)));
                }
            }
        }
        return candidates;
    }

    std::vector<CandidateActionEntity> getCandidateAction(int idx) {
        idx=originalIndex(idx);
        if(idx<0) return {};
        std::vector<CandidateActionEntity> actions;
        CandidateAction forget;forget.setId(-1001);forget.setText("忘记");
        CandidateAction lower;lower.setId(-1002);lower.setText("降低优先级");
        actions.emplace_back(forget);
        actions.emplace_back(lower);
        const auto &list = inputPanel().candidateList();
        if (list) {
            const auto &actionable = list->toActionable();
            if (actionable) {
                if (idx >= list->size()) {
                    const auto &bulk = list->toBulk();
                    if (bulk) {
                        try {
                            const auto &c = bulk->candidateFromAll(idx);
                            for (const auto &a: actionable->candidateActions(c)) {
                                if(!isForgetAction(a)) actions.emplace_back(a);
                            }
                        } catch (const std::exception &e) {
                            FCITX_WARN() << "getCandidateAction(" << idx << ") failed:" << e.what();
                        }
                    }
                } else {
                    const auto &c = list->candidate(idx);
                    for (const auto &a: actionable->candidateActions(c)) {
                        if(!isForgetAction(a)) actions.emplace_back(a);
                    }
                }
            }
        }
        return actions;
    }

    void triggerCandidateAction(int idx, const int actionIdx) {
        idx=originalIndex(idx);
        const auto &list = inputPanel().candidateList();
        if (!list || idx<0) return;
        if(actionIdx == -1001 || actionIdx == -1002) {
            const auto &word=rawCandidate(idx);
            auto text=filterString(word.text());
            preferences_.set(scope_,text,actionIdx == -1001 ? 1 : 2);
            if(actionIdx == -1001) {
                if(auto actionable=list->toActionable()) {
                    for(const auto &action:actionable->candidateActions(word)) {
                        if(isForgetAction(action)) {
                            actionable->triggerAction(word,action.id());break;
                        }
                    }
                }
            }
            updateUserInterface(UserInterfaceComponent::InputPanel);
            return;
        }
        const auto &actionable = list->toActionable();
        if (!actionable) return;
        if (idx >= list->size()) {
            const auto &bulk = list->toBulk();
            if (bulk) {
                try {
                    const auto &c = bulk->candidateFromAll(idx);
                    actionable->triggerAction(c, actionIdx);
                } catch (const std::exception &e) {
                    FCITX_WARN() << "triggerCandidateAction(" << idx << ") failed:" << e.what();
                }
            }
        } else {
            const auto &c = list->candidate(idx);
            actionable->triggerAction(c, actionIdx);
        }
    }

    void triggerTabAction(const int idx) {
        const auto &list = inputPanel().candidateList();
        if (!list) return;
        const auto &tabbed = list->toTabbed();
        if (!tabbed) return;
        try {
            tabbed->triggerTabAction(idx);
        } catch (const std::exception &e) {
            FCITX_WARN() << "triggerTabAction(" << idx << ") failed:" << e.what();
        }
    }

    void offsetCandidatePage(int delta) {
        if (delta == 0) {
            return;
        }
        const auto &list = inputPanel().candidateList();
        if (!list) {
            return;
        }
        const auto &pageable = list->toPageable();
        if (!pageable) {
            return;
        }
        if (delta > 0 && pageable->hasNext()) {
            pageable->next();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        } else if (delta < 0 && pageable->hasPrev()) {
            pageable->prev();
            updateUserInterface(UserInterfaceComponent::InputPanel);
        }
    }

    bool preferredKey(const Key &key,bool release) {
        if(!prefer_ || !inputPanel().candidateList() || key.states().testAny(KeyState::SimpleMask)) return false;
        if(key.sym()!=FcitxKey_space && key.sym()!=FcitxKey_0 &&
            !(key.sym()>=FcitxKey_1 && key.sym()<=FcitxKey_9)) return false;
        if(release) return true;
        const auto *entry=frontend_->instance()->inputMethodEntry(this);
        if(key.sym()==FcitxKey_space && entry && entry->uniqueName()=="anthy") {
            const auto index=originalIndex(0);
            if(index>=0) frontend_->instance()->addonManager().addon("anthy")->call<IAnthyEngine::personalSelectPreview>(
                this,rawCandidate(index).text().toString());
            return true;
        }
        const int index=key.sym()==FcitxKey_0 ? 9 :
            key.sym()>=FcitxKey_1 && key.sym()<=FcitxKey_9 ? key.sym()-FcitxKey_1 : 0;
        if(paged_) selectCandidatePaged(index); else selectCandidateBulk(index);
        return true;
    }

private:
    bool isForgetAction(const CandidateAction &action) {
        auto entry=frontend_->instance()->inputMethodEntry(this);
        return (entry && entry->uniqueName()=="rime" && action.id()==0) ||
            action.text()=="Forget word" || action.text()=="忘记词汇";
    }
    CandidatePreferences preferences_{StandardPaths::global().userDirectory(StandardPathsType::PkgData)/"candidate-preferences.tsv"};
    std::string scope_;
    std::string lastCommitted_;
    bool prefer_=false, paged_=false, ended_=false;
    int scanned_=0;
    std::vector<int> order_, lowered_;

    void beginPreferences(bool paged) {
        preferences_.load();paged_=paged;ended_=false;scanned_=0;
        order_.clear();lowered_.clear();
        auto *entry=frontend_->instance()->inputMethodEntry(this);
        auto preedit=inputPanel().preedit().toString();
        if(preedit.empty()) preedit=inputPanel().clientPreedit().toString();
        if(entry && entry->uniqueName()=="anthy") preedit="japanese";
        scope_=preferenceHex(entry ? entry->uniqueName() : "")+"\t"+
            preferenceHex(preedit.empty() ? "predict:"+lastCommitted_ : preferenceContext(preedit));
        prefer_=preferences_.hasScope(scope_);
    }
    const CandidateWord &rawCandidate(int index) {
        const auto &list=inputPanel().candidateList();
        if(!paged_ && list->toBulk()) return list->toBulk()->candidateFromAll(index);
        return list->candidate(index);
    }
    void fillOrder(int count) {
        const auto &list=inputPanel().candidateList();
        if(!list) { ended_=true;return; }
        auto bulk=paged_ ? nullptr : list->toBulk();
        int total=bulk ? bulk->totalSize() : list->size();
        while(!ended_ && static_cast<int>(order_.size())<count) {
            if(total>=0 && scanned_>=total) ended_=true;
            else {
                try {
                    int index=scanned_++;
                    const auto &word=rawCandidate(index);
                    int mode=preferences_.mode(scope_,filterString(word.text()));
                    if(mode==2) lowered_.push_back(index);
                    else if(mode==0) order_.push_back(index);
                } catch(const std::invalid_argument &) { ended_=true; }
            }
            // Keep demoted entries available after one expanded block of normal choices.
            if(order_.size()>=32 || ended_) {
                order_.insert(order_.end(),lowered_.begin(),lowered_.end());lowered_.clear();
            }
        }
    }
    int originalIndex(int index) {
        if(index<0) return -1;
        if(!prefer_) return index;
        fillOrder(index+1);
        return index<static_cast<int>(order_.size()) ? order_[index] : -1;
    }
    std::vector<CandidateEntity> preferenceCandidates(int offset,int limit) {
        fillOrder(offset+limit);
        std::vector<CandidateEntity> result;
        for(int i=offset;i<offset+limit && i<static_cast<int>(order_.size());++i)
            result.emplace_back(candidateEntity(rawCandidate(order_[i])));
        return result;
    }
    AndroidFrontend *frontend_;
    int uid_;

    inline Text filterText(const Text &orig) {
        return frontend_->instance()->outputFilter(this, orig);
    }

    inline std::string filterString(const Text &orig) {
        return filterText(orig).toString();
    }

    CandidateEntity candidateEntity(const CandidateWord &candidate) {
        return CandidateEntity("", filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
    }

    CandidateEntity candidateEntityWithLabel(const Text &label, const CandidateWord &candidate) {
        return CandidateEntity(filterString(label), filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
    }
};

AndroidFrontend::AndroidFrontend(Instance *instance)
        : instance_(instance),
          focusGroup_("android", instance->inputContextManager()),
          activeIC_(nullptr),
          icCache_(),
          eventHandlers_(),
          pagingMode_(0) {
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextInputMethodActivated,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputMethodActivatedEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                imChangeCallback(makeInputMethodStatus(activeIC_));
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextSwitchInputMethod,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextSwitchInputMethodEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                const auto &reason = static_cast<std::underlying_type<InputMethodSwitchedReason>::type>(e.reason());
                const std::string &oldIM = e.oldInputMethod();
                switchInputMethodCallback(reason, oldIM);
            }
    ));
    eventHandlers_.emplace_back(instance_->watchEvent(
            EventType::InputContextFlushUI,
            EventWatcherPhase::Default,
            [this](Event &event) {
                auto &e = static_cast<InputContextFlushUIEvent &>(event);
                if (e.inputContext() != activeIC_) return;
                switch (e.component()) {
                    case UserInterfaceComponent::InputPanel: {
                        activeIC_->updateInputPanel();
                        if (pagingMode_ == 0) {
                            activeIC_->updateCandidatesBulk();
                        } else {
                            activeIC_->updateCandidatesPaged();
                        }
                        break;
                    }
                    case UserInterfaceComponent::StatusArea: {
                        statusAreaUpdateCallback(makeStatusAreaActions(activeIC_), makeInputMethodStatus(activeIC_));
                        break;
                    }
                }
            }
    ));
}

void AndroidFrontend::keyEvent(const Key &key, bool isRelease, const int timestamp) {
    if (!activeIC_) return;
    if(activeIC_->preferredKey(key,isRelease)) return;
    KeyEvent keyEvent(activeIC_, key, isRelease);
    activeIC_->keyEvent(keyEvent);
    if (!keyEvent.accepted()) {
        auto sym = key.sym();
        keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, timestamp);
    }
}

void AndroidFrontend::forwardKey(const Key &key, bool isRelease) {
    auto sym = key.sym();
    keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, -1);
}

void AndroidFrontend::commitString(const std::string &str, const int cursor) {
    commitStringCallback(str, cursor);
}

void AndroidFrontend::updateCandidateList(const std::vector<CandidateEntity> &candidates, const int size) {
    candidateListCallback(candidates, size);
}

void AndroidFrontend::updateClientPreedit(const Text &clientPreedit) {
    preeditCallback(clientPreedit);
}

void AndroidFrontend::updateInputPanel(const Text &preedit, const Text &auxUp, const Text &auxDown, const std::vector<CandidateActionEntity> &tabs) {
    inputPanelCallback(preedit, auxUp, auxDown, tabs);
}

void AndroidFrontend::releaseInputContext(const int uid) {
    icCache_.release(uid);
}

bool AndroidFrontend::selectCandidate(int idx) {
    if (!activeIC_) return false;
    if (pagingMode_) {
        return activeIC_->selectCandidatePaged(idx);
    } else {
        return activeIC_->selectCandidateBulk(idx);
    }
}

std::vector<CandidateActionEntity> AndroidFrontend::getCandidateActions(const int idx) {
    if (!activeIC_) return {};
    return activeIC_->getCandidateAction(idx);
}

void AndroidFrontend::triggerCandidateAction(const int idx, const int actionIdx) {
    if (!activeIC_) return;
    activeIC_->triggerCandidateAction(idx, actionIdx);
}

void AndroidFrontend::triggerTabAction(const int idx) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(idx);
}

bool AndroidFrontend::isInputPanelEmpty() {
    if (!activeIC_) return true;
    return activeIC_->inputPanel().empty();
}

void AndroidFrontend::resetInputContext() {
    if (!activeIC_) return;
    activeIC_->reset();
}

void AndroidFrontend::repositionCursor(int position) {
    if (!activeIC_) return;
    InvokeActionEvent event(InvokeActionEvent::Action::LeftClick, position, activeIC_);
    activeIC_->invokeAction(event);
}

void AndroidFrontend::focusInputContext(bool focus) {
    if (!activeIC_) return;
    if (focus) {
        activeIC_->focusIn();
    } else {
        activeIC_->focusOut();
    }
}

void AndroidFrontend::activateInputContext(const int uid, const std::string &pkgName) {
    auto *ptr = icCache_.find(uid);
    if (ptr) {
        activeIC_ = dynamic_cast<AndroidInputContext *>(ptr->get());
    } else {
        auto *ic = new AndroidInputContext(this, instance_->inputContextManager(), uid, pkgName);
        activeIC_ = ic;
        icCache_.insert(uid, ic);
        ic->setFocusGroup(&focusGroup_);
    }
}

InputContext *AndroidFrontend::activeInputContext() const {
    return activeIC_;
}

void AndroidFrontend::deactivateInputContext(const int uid) {
    auto *ptr = icCache_.find(uid);
    if (!ptr) return;
    focusGroup_.setFocusedInputContext(nullptr);
    activeIC_ = nullptr;
}

void AndroidFrontend::setCapabilityFlags(uint64_t flag) {
    if (!activeIC_) return;
    activeIC_->setCapabilityFlags(CapabilityFlags(flag));
}

void AndroidFrontend::setCandidateListCallback(const CandidateListCallback &callback) {
    candidateListCallback = callback;
}

std::vector<CandidateEntity> AndroidFrontend::getCandidates(const int offset, const int limit) {
    if (!activeIC_) return {};
    return activeIC_->getCandidates(offset, limit);
}

void AndroidFrontend::deleteSurrounding(const int before, const int after) {
    deleteSurroundingCallback(before, after);
}

void AndroidFrontend::showToast(const std::string &s) {
    toastCallback(s);
}

void AndroidFrontend::setCandidatePagingMode(const int mode) {
    pagingMode_ = mode;
    if (mode == 0) {
        activeIC_->updateCandidatesBulk();
    } else {
        activeIC_->updateCandidatesPaged();
    }
}

void AndroidFrontend::refreshCandidatePreferences() {
    if(!activeIC_) return;
    activeIC_->reloadPreferences();
    if(pagingMode_==0) activeIC_->updateCandidatesBulk();
    else activeIC_->updateCandidatesPaged();
}

void AndroidFrontend::updatePagedCandidate(const PagedCandidateEntity &paged) {
    pagedCandidateCallback(paged);
}

void AndroidFrontend::offsetCandidatePage(int delta) {
    if (!activeIC_) return;
    activeIC_->offsetCandidatePage(delta);
}

void AndroidFrontend::triggerCandidateListTabAction(int id) {
    if (!activeIC_) return;
    activeIC_->triggerTabAction(id);
}

void AndroidFrontend::setCommitStringCallback(const CommitStringCallback &callback) {
    commitStringCallback = callback;
}

void AndroidFrontend::setPreeditCallback(const ClientPreeditCallback &callback) {
    preeditCallback = callback;
}

void AndroidFrontend::setInputPanelCallback(const InputPanelCallback &callback) {
    inputPanelCallback = callback;
}

void AndroidFrontend::setKeyEventCallback(const KeyEventCallback &callback) {
    keyEventCallback = callback;
}

void AndroidFrontend::setInputMethodChangeCallback(const InputMethodChangeCallback &callback) {
    imChangeCallback = callback;
}

void AndroidFrontend::setStatusAreaUpdateCallback(const StatusAreaUpdateCallback &callback) {
    statusAreaUpdateCallback = callback;
}

void AndroidFrontend::setDeleteSurroundingCallback(const DeleteSurroundingCallback &callback) {
    deleteSurroundingCallback = callback;
}

void AndroidFrontend::setToastCallback(const ToastCallback &callback) {
    toastCallback = callback;
}

void AndroidFrontend::setPagedCandidateCallback(const PagedCandidateCallback &callback) {
    pagedCandidateCallback = callback;
}

void AndroidFrontend::setSwitchInputMethodCallback(const SwitchInputMethodCallback &callback) {
    switchInputMethodCallback = callback;
}

InputMethodStatus AndroidFrontend::makeInputMethodStatus(InputContext *ic) {
    auto *entry = instance_->inputMethodEntry(ic);
    auto *engine = instance_->inputMethodEngine(ic);
    return {entry, engine, ic};
}

std::vector<ActionEntity> AndroidFrontend::makeStatusAreaActions(fcitx::InputContext *ic) {
    auto actions = std::vector<ActionEntity>();
    for (auto group: {fcitx::StatusGroup::BeforeInputMethod,
                      fcitx::StatusGroup::InputMethod,
                      fcitx::StatusGroup::AfterInputMethod}) {
        for (auto act: ic->statusArea().actions(group)) {
            actions.emplace_back(act, ic);
        }
    }
    return actions;
}

class AndroidFrontendFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidFrontend(manager->instance());
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidFrontendFactory)
