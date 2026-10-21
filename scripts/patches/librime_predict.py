from .common import replace

def apply(source):
    predictor = source / "src/predictor.cc"
    # Suggestions follow committed text; an empty editor starts with its toolbar.
    replace(predictor, '    PredictAndUpdate(ctx, "$");\n', "")
    # Dismissing predictions also lets the same Backspace reach the editor.
    replace(predictor, "      ctx->Clear();\n      return kAccepted;",
            "      ctx->Clear();\n      return keycode == XK_BackSpace ? kNoop : kAccepted;")
    predict = source / "src/predict_engine.cc"
    replace(predict, '#include "predict_db.h"', '#include "predict_db.h"\n#include <opencc/Config.hpp>\n#include <opencc/Converter.hpp>')
    replace(predict, "  if (const auto* candidates = db_->Lookup(context_query)) {", """  const auto* candidates = db_->Lookup(context_query);
  if (!candidates) {
    static auto traditional = opencc::Config().NewFromFile(
        (Service::instance().deployer().shared_data_dir / "opencc" / "s2t.json").u8string());
    candidates = db_->Lookup(traditional->Convert(context_query));
  }
  if (candidates) {""")
