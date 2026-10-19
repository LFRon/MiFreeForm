package com.buildsession.betterYAMF.common.logging

import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.xposed.utils.log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Run against both variants. Throwing inside the message observes evaluation before Log.d,
 * without relying on an Android mock or executing the platform log writer in a JVM test.
 */
class LoggingEvaluationTest {
    private class MessageEvaluated : RuntimeException()

    private fun assertMessageEvaluation(write: (() -> String) -> Unit) {
        var evaluations = 0
        val message = { evaluations++; throw MessageEvaluated() }
        if (BuildConfig.DEBUG) {
            assertThrows(MessageEvaluated::class.java) { write(message) }
        } else {
            write(message)
        }
        assertEquals(if (BuildConfig.DEBUG) 1 else 0, evaluations)
    }

    @Test fun appDebugMessagesAreNotEvaluatedInRelease() = assertMessageEvaluation {
        AppLog.debug("MiWindowLogPolicy", it)
    }

    @Test fun hookDebugMessagesAreNotEvaluatedInRelease() = assertMessageEvaluation {
        log("MiWindowLogPolicy", it)
    }
}
