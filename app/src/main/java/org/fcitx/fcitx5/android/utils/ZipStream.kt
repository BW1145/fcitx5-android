/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    var entry = nextEntry
    val canonicalDest = destDir.canonicalFile
    val destinationPrefix = canonicalDest.path.trimEnd(File.separatorChar) + File.separator
    while (entry != null) {
        val file = File(canonicalDest, entry.name).canonicalFile
        val isRootDirectory = entry.isDirectory && file == canonicalDest
        if (File(entry.name).isAbsolute ||
            (!isRootDirectory && !file.path.startsWith(destinationPrefix))
        ) {
            throw SecurityException("ZIP entry escapes destination: ${entry.name}")
        }
        if (entry.isDirectory) {
            if (!file.isDirectory && !file.mkdirs()) {
                throw IOException("Cannot create ZIP directory: ${entry.name}")
            }
        } else {
            val parent = file.parentFile!!
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Cannot create ZIP parent directory: ${entry.name}")
            }
            file.outputStream().use { copyTo(it) }
        }
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}

