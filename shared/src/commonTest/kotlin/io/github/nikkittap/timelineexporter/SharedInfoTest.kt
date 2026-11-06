package io.github.nikkittap.timelineexporter

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedInfoTest {
    @Test fun moduleName() = assertEquals("shared", SharedInfo.MODULE)
}
