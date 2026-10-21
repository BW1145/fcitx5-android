/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.view.Choreographer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Called on Main; cancellation also removes the scheduled frame callback. */
internal suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val clock = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
    clock.postFrameCallback(callback)
    continuation.invokeOnCancellation { clock.removeFrameCallback(callback) }
}
