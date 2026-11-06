package io.github.nikkittap.timelineexporter.parser

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString

/** [this] as UTF-8 bytes behind a [Source], the common stand-in for `byteInputStream()`. */
internal fun String.byteSource(): Source = Buffer().apply { writeString(this@byteSource) }
