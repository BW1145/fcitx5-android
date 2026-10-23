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
#include <tuple>

#include "androidfrontend.h"
#include "candidatepreferences.h"
#include "preferredcandidate.h"
#include "../personal-anthy-public.h"
#include "../personal-rime-public.h"

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
    void setInputTarget(int64_t target) { inputTarget_ = target; }
    int64_t inputTarget() const { return inputTarget_; }

    void commitStringImpl(const std::string &text) override {
        lastCommitted_ = text;
        frontend_->commitString(text, -1, inputTarget_);
    }

    void commitStringWithCursorImpl(const std::string &text, size_t cursor) override {
        lastCommitted_ = text;
        frontend_->commitString(text, static_cast<int>(cursor), inputTarget_);
    }

    void forwardKeyImpl(const ForwardKeyEvent &key) override {
        frontend_->forwardKey(key.rawKey(), key.isRelease(), inputTarget_);
    }

    void deleteSurroundingTextImpl(int offset, unsigned int size) override {
        const int before = -offset;
        const int after = offset + static_cast<int>(size);
        if (before < 0 || after < 0) {
            FCITX_WARN() << "Invalid deleteSurrounding request: offset=" << offset << ", size="
                         << size;
            return;
        }
        frontend_->deleteSurrounding(before, after, inputTarget_);
    }

    void updatePreeditImpl() override {
        frontend_->updateClientPreedit(filterText(inputPanel().clientPreedit()), inputTarget_);
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
            auto cursor = std::find(order_.begin(), order_.end(), cursorIndex);
            int displayCursor = cursor == order_.end() ? -1 : static_cast<int>(cursor - order_.begin());
            frontend_->updatePagedCandidate(PagedCandidateEntity(candidates,displayCursor,
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
        if (ensureCandidateRevision()) beginPreferences(paged_);
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

    bool candidateMatches(int idx, const std::string &text, int64_t revision) {
        if (ensureCandidateRevision()) { beginPreferences(paged_);return false; }
        if (revision != candidateRevision_) return false;
        if (!inputPanel().candidateList()) return false;
        idx = originalIndex(idx);
        if (idx < 0) return false;
        try { return filterString(rawCandidate(idx).text()) == text; }
        catch (const std::invalid_argument &) { return false; }
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
            ensurePreferenceScope();
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
        if (ensureCandidateRevision()) beginPreferences(paged_);
        if (prefer_ && isAnthy() && pageable->currentPage() >= 0) {
            const int page = pageable->currentPage() + (delta > 0 ? 1 : -1);
            if (page < 0 || page >= pageable->totalPages()) return;
            // setPage changes the lookup page without previewing Anthy's raw
            // cursor first. Select only after applying the page's preferences.
            pageable->setPage(page);
            beginPreferences(paged_);
            previewPreferredAnthy(false, true, delta, false);
            updateUserInterface(UserInterfaceComponent::InputPanel);
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
        if(key.states().testAny(KeyState::SimpleMask)) return false;
        if(key.sym()!=FcitxKey_space && key.sym()!=FcitxKey_0 &&
            !(key.sym()>=FcitxKey_1 && key.sym()<=FcitxKey_9)) return false;
        if (ensureCandidateRevision()) beginPreferences(paged_);
        if(!prefer_ || !inputPanel().candidateList()) return false;
        if(release) return true;
        const auto *entry=frontend_->instance()->inputMethodEntry(this);
        if(key.sym()==FcitxKey_space && entry && entry->uniqueName()=="anthy") {
            const bool converting=frontend_->instance()->addonManager().addon("anthy")
                ->call<IAnthyEngine::personalIsConverting>(this);
            return previewPreferredAnthy(converting);
        }
        const int index=key.sym()==FcitxKey_0 ? 9 :
            key.sym()>=FcitxKey_1 && key.sym()<=FcitxKey_9 ? key.sym()-FcitxKey_1 : 0;
        if(paged_) selectCandidatePaged(index); else selectCandidateBulk(index);
        return true;
    }

private:
    int64_t inputTarget_ = 0;
    int64_t candidateRevision_ = 0;
    std::weak_ptr<CandidateList> candidateListSnapshot_;
    std::tuple<int64_t, std::string, std::string, std::string> candidateContext_;

    bool ensureCandidateRevision(bool force = false) {
        static int64_t nextRevision = 0;
        auto entry = frontend_->instance()->inputMethodEntry(this);
        auto context = std::make_tuple(inputTarget_, entry ? entry->uniqueName() : "",
            inputPanel().preedit().toString(), inputPanel().clientPreedit().toString());
        const auto &list = inputPanel().candidateList();
        if (force || candidateListSnapshot_.lock() != list || candidateContext_ != context) {
            candidateRevision_ = ++nextRevision;
            candidateListSnapshot_ = list;
            candidateContext_ = std::move(context);
            return true;
        }
        return false;
    }
    bool isForgetAction(const CandidateAction &action) {
        auto entry=frontend_->instance()->inputMethodEntry(this);
        return (entry && entry->uniqueName()=="rime" && action.id()==0) ||
            action.text()=="Forget word" || action.text()=="忘记词汇";
    }
    CandidatePreferences preferences_{StandardPaths::global().userDirectory(StandardPathsType::PkgData)/"candidate-preferences.tsv"};
    std::string scope_;
    std::string legacyScope_;
    std::string lastCommitted_;
    bool prefer_=false, paged_=false, ended_=false, scopeReady_=false;
    int scanned_=0;
    std::vector<int> order_, lowered_;

    bool isAnthy() {
        const auto *entry=frontend_->instance()->inputMethodEntry(this);
        return entry && entry->uniqueName()=="anthy";
    }
    bool previewPreferredAnthy(bool advance, bool keepCurrent=false,
                               int direction=1, bool wrap=true) {
        auto list=inputPanel().candidateList();
        if (!list) return false;
        const auto *bulk=list->toBulk();
        const int total=paged_ || !bulk ? list->size() : bulk->totalSize();
        if (total<0) return false; // Anthy's candidate lists have a finite size.
        fillOrder(total);
        int current=-1;
        if (advance || keepCurrent) {
            const auto *cursor=list->toBulkCursor();
            current=paged_ || !cursor ? list->cursorIndex() : cursor->globalCursorIndex();
        }
        const auto *pageable=list->toPageable();
        const int startPage=pageable ? pageable->currentPage() : -1;
        auto movePage=[&]() {
            auto currentList=inputPanel().candidateList();
            auto *pages=currentList ? currentList->toPageable() : nullptr;
            if (!paged_ || !pages || startPage<0 || pages->totalPages()<=0) return false;
            int page=pages->currentPage()+(direction>0 ? 1 : -1);
            if (page<0 || page>=pages->totalPages()) {
                if (!wrap) return false;
                page=direction>0 ? 0 : pages->totalPages()-1;
            }
            pages->setPage(page);
            beginPreferences(true);
            fillOrder(currentList->size());
            return page!=startPage;
        };
        const int index=preferredCandidateIndex(order_,current,advance,movePage);
        if (index<0) return true; // No visible choice: do not preview a hidden word.
        return frontend_->instance()->addonManager().addon("anthy")
            ->call<IAnthyEngine::personalSelectPreview>(this,rawCandidate(index).text().toString());
    }

    void beginPreferences(bool paged) {
        ensureCandidateRevision(true);
        preferences_.load();paged_=paged;ended_=false;scanned_=0;
        order_.clear();lowered_.clear();
        scopeReady_=false;
        prefer_=false;
        // Without custom rules, do not query the engine's context
        // and encode a scope on every key just to discover there is no rule.
        // A first long-press action resolves its scope immediately before save.
        if (preferences_.empty()) return;
        ensurePreferenceScope();
        prefer_=preferences_.hasScope(scope_) || (!legacyScope_.empty() && preferences_.hasScope(legacyScope_));
    }
    void ensurePreferenceScope() {
        if (scopeReady_) return;
        auto *entry=frontend_->instance()->inputMethodEntry(this);
        auto preedit=inputPanel().preedit().toString();
        if(preedit.empty()) preedit=inputPanel().clientPreedit().toString();
        std::string engine=entry ? entry->uniqueName() : "";
        legacyScope_.clear();
        if (engine=="rime") {
            auto context=frontend_->instance()->addonManager().addon("rime")->call<IRimeEngine::personalContext>(this);
            if (context.size()==2) { engine += ":"+context[0];preedit=context[1]; }
        } else if (engine=="anthy") {
            preedit=frontend_->instance()->addonManager().addon("anthy")->call<IAnthyEngine::personalReading>(this);
            legacyScope_=preferenceHex("anthy")+"\t"+preferenceHex("japanese");
        }
        scope_=preferenceHex(engine)+"\t"+
            preferenceHex(preedit.empty() ? "predict:"+lastCommitted_ : preferenceContext(preedit));
        if (engine.rfind("rime:",0)==0) preferences_.migrateScope(
            preferenceHex("rime")+"\t"+preferenceHex(preedit.empty() ? "predict:"+lastCommitted_ : preferenceContext(preedit)), scope_);
        scopeReady_=true;
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
        std::vector<std::string> legacyWords;
        while(!ended_ && static_cast<int>(order_.size())<count) {
            if(total>=0 && scanned_>=total) ended_=true;
            else {
                try {
                    int index=scanned_++;
                    const auto &word=rawCandidate(index);
                    const auto text=filterString(word.text());
                    // Old Anthy rows did not save readings. Claim a legacy choice
                    // only when that word actually appears for this reading.
                    int mode=preferences_.mode(scope_,text);
                    if(!legacyScope_.empty()) {
                        const int legacyMode=preferences_.mode(legacyScope_,text);
                        if (legacyMode || preferences_.hasWord(legacyScope_,text)) {
                            legacyWords.push_back(text);
                            // A same-scope migration historically removes the
                            // row; otherwise an existing scoped choice wins.
                            if (legacyScope_==scope_) mode=0;
                            else if (!mode && !preferences_.hasWord(scope_,text)) mode=legacyMode;
                        }
                    }
                    if(mode==2) lowered_.push_back(index);
                    else if(mode==0) order_.push_back(index);
                } catch(const std::invalid_argument &) { ended_=true; }
            }
            // Keep demoted entries available after one expanded block of normal choices.
            if(order_.size()>=32 || ended_) {
                order_.insert(order_.end(),lowered_.begin(),lowered_.end());lowered_.clear();
            }
        }
        // One atomic save per expanded block, not one full-file write per
        // matching legacy word. Candidate classification above is identical
        // to claiming each word immediately, including duplicate candidates.
        preferences_.migrateWords(legacyScope_,scope_,legacyWords);
    }
    int originalIndex(int index) {
        if (ensureCandidateRevision()) beginPreferences(paged_);
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
        auto result = CandidateEntity("", filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
        result.revision = candidateRevision_;
        return result;
    }

    CandidateEntity candidateEntityWithLabel(const Text &label, const CandidateWord &candidate) {
        auto result = CandidateEntity(filterString(label), filterString(candidate.text()),
                               filterString(candidate.comment()), candidate.spaceBetweenComment());
        result.revision = candidateRevision_;
        return result;
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
    const auto target = activeIC_->inputTarget();
    if(activeIC_->preferredKey(key,isRelease)) return;
    KeyEvent keyEvent(activeIC_, key, isRelease);
    activeIC_->keyEvent(keyEvent);
    if (!keyEvent.accepted()) {
        auto sym = key.sym();
        keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, timestamp, target);
    }
}

void AndroidFrontend::forwardKey(const Key &key, bool isRelease, int64_t target) {
    auto sym = key.sym();
    keyEventCallback(sym, key.states(), Key::keySymToUnicode(sym), isRelease, -1, target);
}

void AndroidFrontend::commitString(const std::string &str, const int cursor, int64_t target) {
    commitStringCallback(str, cursor, target);
}

void AndroidFrontend::updateCandidateList(const std::vector<CandidateEntity> &candidates, const int size) {
    candidateListCallback(candidates, size);
}

void AndroidFrontend::updateClientPreedit(const Text &clientPreedit, int64_t target) {
    preeditCallback(clientPreedit, target);
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

bool AndroidFrontend::hasInputPreedit() {
    if (!activeIC_) return false;
    const auto &panel = activeIC_->inputPanel();
    return !panel.clientPreedit().empty() || !panel.preedit().empty();
}

void AndroidFrontend::setInputTarget(int64_t target) {
    if (activeIC_ && activeIC_->inputTarget() != target) {
        activeIC_->reset();
        activeIC_->setInputTarget(target);
    }
}

bool AndroidFrontend::candidateMatches(int idx, const std::string &text, int64_t revision) {
    return activeIC_ && activeIC_->candidateMatches(idx, text, revision);
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

void AndroidFrontend::deleteSurrounding(const int before, const int after, int64_t target) {
    deleteSurroundingCallback(before, after, target);
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
