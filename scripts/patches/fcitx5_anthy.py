from .common import replace

def apply(source):
    replace(source / "engine.h", '#include "action.h"',
            '#include "action.h"\n#include "personal-anthy-public.h"')
    replace(source / "engine.h", '    auto state(fcitx::InputContext *ic)',
            '''    void personalSelectPreview(fcitx::InputContext *ic, const std::string &text);
    FCITX_ADDON_EXPORT_FUNCTION(AnthyEngine, personalSelectPreview);
    auto state(fcitx::InputContext *ic)''')
    replace(source / "state.h", '    void selectCandidate(unsigned int item);',
            '''    void selectCandidate(unsigned int item);
    void selectPreferredPreview(const std::string &text) {
        if (!preedit_.isConverting()) action_convert();
        auto candidates = preedit_.candidates();
        for (int i = 0; candidates && i < candidates->totalSize(); ++i) {
            if (candidates->candidateFromAll(i).text().toString() == text) {
                selectCandidateNoDirect(i);
                updateUI();
                return;
            }
        }
    }''')
    with (source / "engine.cpp").open("a", encoding="utf-8", newline="\n") as output:
        output.write('''
void AnthyEngine::personalSelectPreview(fcitx::InputContext *ic, const std::string &text) {
    state(ic)->selectPreferredPreview(text);
}
''')

    state=source / "state.cpp"
    replace(state, '''        preedit_.predict();
        ic_->inputPanel().setCandidateList(preedit_.candidates());
''', '''        preedit_.predict();
        auto candidates = preedit_.candidates();
        // Anthy's predictor only contains learned history. Offer dictionary
        // conversions when that history has no match, keeping the reading editable.
        if (candidates->totalSize() == 0) {
            candidates = std::make_unique<fcitx::CommonCandidateList>();
            candidates->setPageSize(*config().general->pageSize);
            candidates->setLayoutHint(*config().general->candidateLayout);
            preedit_.convert(FCITX_ANTHY_CANDIDATE_DEFAULT, isSingleSegment());
            auto conversions = preedit_.candidates();
            if (conversions) {
                for (int i = 0; i < conversions->totalSize(); ++i) {
                    candidates->append<AnthyTypingCandidate>(
                        this, conversions->candidateFromAll(i).text(), i);
                }
            }
            preedit_.revert();
        }
        ic_->inputPanel().setCandidateList(std::move(candidates));
''')
    replace(state, '#include "state.h"', '#include "state.h"\n#include "anthy-typing-candidate.h"')
    learn="!ic_->capabilityFlags().testAny(fcitx::CapabilityFlag::PasswordOrSensitive)"
    for argument in ("", "0", "preedit_.selectedSegment()"):
        selected=argument or "-1"
        replace(state, f"preedit_.commit({argument});", f"preedit_.commit({selected}, {learn});")
