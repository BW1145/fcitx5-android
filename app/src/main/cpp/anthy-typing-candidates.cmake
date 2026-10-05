# Build the pinned Anthy state with dictionary previews for a fresh user.
set(ANTHY_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../plugin/anthy/src/main/cpp/fcitx5-anthy/src")
file(READ "${ANTHY_SOURCE_DIR}/state.cpp" anthy_state)
set(original_prediction [=[
        preedit_.predict();
        ic_->inputPanel().setCandidateList(preedit_.candidates());
]=])
set(typing_prediction [=[
        preedit_.predict();
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
]=])
string(FIND "${anthy_state}" "${original_prediction}" prediction_position)
if(prediction_position EQUAL -1)
    message(FATAL_ERROR "The pinned Anthy prediction block has changed")
endif()
string(REPLACE "${original_prediction}" "${typing_prediction}" anthy_state "${anthy_state}")
string(REPLACE "#include \"state.h\"" "#include \"state.h\"\n#include \"anthy-typing-candidate.h\"" anthy_state "${anthy_state}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/anthy-typing-state.cpp" "${anthy_state}")
set_source_files_properties("${ANTHY_SOURCE_DIR}/state.cpp" TARGET_DIRECTORY anthy PROPERTIES HEADER_FILE_ONLY TRUE)
target_sources(anthy PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/anthy-typing-state.cpp")
target_include_directories(anthy PRIVATE "${ANTHY_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
