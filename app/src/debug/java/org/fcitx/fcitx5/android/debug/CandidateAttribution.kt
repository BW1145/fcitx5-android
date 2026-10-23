/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.debug

/** Synthetic-input experiment only. Requires -PcandidateAttribution=true, Debug build.
 * Call only on the Fcitx engine dispatcher, e.g. enqueueInputBarrier.
 * Mode 0 disables collection, 1 measures normal behavior, 2 bypasses custom preferences.
 * configure clears counters; drain returns schema 1 followed by ns/count metric pairs.
 */
object CandidateAttribution {
    @JvmStatic external fun configure(mode: Int)
    @JvmStatic external fun drain(): LongArray
}
