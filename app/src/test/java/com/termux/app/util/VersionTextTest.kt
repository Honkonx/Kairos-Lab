package com.termux.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTextTest {
    @Test
    fun keepsOnlyTheNumbersOfWhatLlamaServerPrints() {
        assertEquals("0.6.0-dev", VersionText.clean("version: 0.6.0-dev (build 1, commit 6184e92)"))
    }

    @Test
    fun giteaLongLineBecomesJustDigits() {
        assertEquals("1.26.1", VersionText.clean("Gitea version 1.26.1 built with GNU Make 4.4.1, go1.25 : bindata, sqlite"))
    }

    @Test
    fun stripsLeadingVAndLabels() {
        assertEquals("3.14.6", VersionText.clean("v3.14.6"))
        assertEquals("3.14.6", VersionText.clean("V3.14.6"))
        assertEquals("1.2.3", VersionText.clean("Version 1.2.3"))
        assertEquals("1.2.3", VersionText.clean("versión = v1.2.3"))
        assertEquals("10.6", VersionText.clean("OpenSSH_10.6p1"))
    }

    @Test
    fun keepsNonNumericAndTruncatesLongText() {
        assertEquals("latest", VersionText.clean("latest"))
        assertEquals("vim-9", VersionText.clean("vim-9"))
        assertEquals("", VersionText.clean("   "))
        assertEquals("", VersionText.clean(""))
        assertEquals("una descripcion muy…", VersionText.clean("una descripcion muy larga sin ningun numero con puntos"))
    }
}
