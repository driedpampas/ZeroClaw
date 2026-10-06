/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package org.eu.nl.syu.zeroclaw.ui.screen.settings.doctor

import org.eu.nl.syu.zeroclaw.model.DoctorSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DoctorSummary] computation.
 *
 * The [DoctorViewModel] itself requires an [android.app.Application]
 * context, so we test the summary logic independently.
 */
@DisplayName("DoctorSummary")
class DoctorViewModelTest {
    @Test
    @DisplayName("computes summary from empty list")
    fun `empty list gives zero counts`() {
        val summary = DoctorSummary.from(emptyList())
        assertEquals(0, summary.passCount)
        assertEquals(0, summary.warnCount)
        assertEquals(0, summary.failCount)
    }

    @Test
    @DisplayName("computes summary from mixed checks")
    fun `mixed checks counted correctly`() {
        val checks =
            listOf(
                testCheck("1", org.eu.nl.syu.zeroclaw.model.CheckStatus.PASS),
                testCheck("2", org.eu.nl.syu.zeroclaw.model.CheckStatus.PASS),
                testCheck("3", org.eu.nl.syu.zeroclaw.model.CheckStatus.WARN),
                testCheck("4", org.eu.nl.syu.zeroclaw.model.CheckStatus.FAIL),
                testCheck("5", org.eu.nl.syu.zeroclaw.model.CheckStatus.RUNNING),
            )
        val summary = DoctorSummary.from(checks)
        assertEquals(2, summary.passCount)
        assertEquals(1, summary.warnCount)
        assertEquals(1, summary.failCount)
    }

    private fun testCheck(
        id: String,
        status: org.eu.nl.syu.zeroclaw.model.CheckStatus,
    ): org.eu.nl.syu.zeroclaw.model.DiagnosticCheck =
        org.eu.nl.syu.zeroclaw.model.DiagnosticCheck(
            id = id,
            category = org.eu.nl.syu.zeroclaw.model.DiagnosticCategory.CONFIG,
            title = "Test check $id",
            status = status,
        )
}
