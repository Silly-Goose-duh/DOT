package com.dot.agent.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PolicyEngineTest {

    private val engine = PolicyEngine()

    @Test
    fun `local reads need no confirmation`() {
        val d = engine.evaluate(ToolCategory.LOCAL_READ, hasPermission = true, isAuthenticated = true)
        assertThat(d.requiresConfirmation).isFalse()
    }

    @Test
    fun `local writes need no confirmation`() {
        val d = engine.evaluate(ToolCategory.LOCAL_WRITE, hasPermission = true, isAuthenticated = true)
        assertThat(d.requiresConfirmation).isFalse()
    }

    @Test
    fun `external writes require confirmation`() {
        val d = engine.evaluate(ToolCategory.EXTERNAL_WRITE, hasPermission = true, isAuthenticated = true)
        assertThat(d.requiresConfirmation).isTrue()
        assertThat(d.reason).isEqualTo("external_state_change")
    }

    @Test
    fun `external reads require auth`() {
        val d = engine.evaluate(ToolCategory.EXTERNAL_READ, hasPermission = true, isAuthenticated = false)
        assertThat(d.requiresConfirmation).isTrue()
        assertThat(d.reason).isEqualTo("auth_required")
    }

    @Test
    fun `missing permission is reported not silently ignored`() {
        val d = engine.evaluate(ToolCategory.EXTERNAL_READ, hasPermission = false, isAuthenticated = true)
        assertThat(d.reason).isEqualTo("permission_required")
    }

    @Test
    fun `consequential actions are out of scope for v01`() {
        assertThat(engine.isOutOfScope("send_email")).isTrue()
        assertThat(engine.isOutOfScope("make_purchase")).isTrue()
        assertThat(engine.isOutOfScope("change_security_settings")).isTrue()
        assertThat(engine.isOutOfScope("create_task")).isFalse()
    }
}
