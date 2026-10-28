package com.buildsession.betterYAMF.xposed.adapters.baseline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe decides how every framework member is reached, so it is tested against real class
 * shapes instead of a Build string. */
class FrameworkShapeTest {
    private class TaskWithLeash { fun getLeash(): Any? = null }
    private class TaskWithoutLeash { fun getSurfaceControl(): Any? = null }
    private open class LeashBase { fun getLeash(): Any? = null }
    private class TaskInheritingLeash : LeashBase()
    private class InsetsThreeArgument {
        fun calculateInsets(frame: android.graphics.Rect, types: Int, ignoreVisibility: Boolean): Int = 0
    }
    private class InsetsFourArgument {
        fun calculateInsets(frame: android.graphics.Rect, hostBounds: android.graphics.Rect?,
            types: Int, ignoreVisibility: Boolean): Int = 0
    }
    private class InsetsThreeArgumentOfAnotherType {
        fun calculateInsets(frame: Any, hostBounds: Any?, types: Int): Int = 0
    }
    private class PolicyWithGetters { fun getStatusBar(): Any? = null }
    private class PolicyWithFields { @Suppress("unused") private val mStatusBar: Any? = null }
    private class ShadeKeepingCollapse { fun collapseOnMainThread() = Unit }
    private class ShadeWithoutCollapse { fun animateCollapseShadeForcedDelayed() = Unit }
    private class AnimatorWithController { @Suppress("unused") private val mSurfaceController: Any? = null }
    private class AnimatorWithoutController { @Suppress("unused") private val mSurfaceControl: Any? = null }

    private fun describe(task: Class<*>? = null, insets: Class<*>? = null, policy: Class<*>? = null,
        shade: Class<*>? = null, animator: Class<*>? = null) =
        FrameworkShape.describe(task, insets, policy, shade, animator)

    @Test fun aospServerShapeUsesOnlyTheSharedContainerSurface() {
        val shape = describe(task = TaskWithoutLeash::class.java,
            insets = InsetsFourArgument::class.java, animator = AnimatorWithoutController::class.java)
        assertEquals(FrameworkBaseline.AOSP, shape.baseline)
        assertFalse(shape.taskLeash)
        assertFalse(shape.insetsIgnoreVisibility)
        assertFalse(shape.windowSurfaceController)
    }

    @Test fun googleServerShapeExposesTheAnimationLeash() {
        val shape = describe(task = TaskWithLeash::class.java,
            insets = InsetsThreeArgument::class.java, animator = AnimatorWithController::class.java)
        assertEquals(FrameworkBaseline.GOOGLE, shape.baseline)
        assertTrue(shape.taskLeash)
        assertTrue(shape.insetsIgnoreVisibility)
        assertTrue(shape.windowSurfaceController)
    }

    @Test fun anInheritedMemberStillClassifiesTheBranch() {
        assertTrue(describe(task = TaskInheritingLeash::class.java).taskLeash)
    }

    @Test fun theSystemUiProbeAnswersIndependentlyOfTheServerProbe() {
        val aospUi = describe(shade = ShadeKeepingCollapse::class.java)
        assertEquals(FrameworkBaseline.AOSP, aospUi.baseline)
        assertTrue(aospUi.shadeCollapseOnMainThread)

        val googleUi = describe(shade = ShadeWithoutCollapse::class.java)
        assertEquals(FrameworkBaseline.GOOGLE, googleUi.baseline)
        assertFalse(googleUi.shadeCollapseOnMainThread)
    }

    @Test fun theSystemUiProbeOutranksAStaleServerFact() {
        // A build can keep ShadeController while replacing Task; SystemUI's own class decides there.
        val shape = describe(task = TaskWithLeash::class.java, shade = ShadeKeepingCollapse::class.java)
        assertEquals(FrameworkBaseline.AOSP, shape.baseline)
        assertTrue(shape.taskLeash)
    }

    @Test fun theLauncherProcessReportsNoBaselineInsteadOfGuessing() {
        val shape = describe()
        assertEquals(FrameworkBaseline.UNKNOWN, shape.baseline)
        assertFalse(shape.taskLeash)
        assertFalse(shape.insetsIgnoreVisibility)
        assertFalse(shape.displayPolicyBarGetters)
        assertFalse(shape.shadeCollapseOnMainThread)
        assertFalse(shape.windowSurfaceController)
    }

    @Test fun aThreeArgumentOverloadOfAnotherTypeIsNotTheReviewedOne() {
        // The AOSP branch has a three-argument calculateInsets, but not the ignoreVisibility one.
        assertFalse(describe(insets = InsetsThreeArgumentOfAnotherType::class.java).insetsIgnoreVisibility)
    }

    @Test fun displayPolicyMembersAreProbedPerAccessorNotAssumed() {
        assertTrue(describe(policy = PolicyWithGetters::class.java).displayPolicyBarGetters)
        assertFalse(describe(policy = PolicyWithFields::class.java).displayPolicyBarGetters)
    }

    @Test fun theSummaryNamesEveryFactForTheBootLog() {
        val summary = describe(task = TaskWithLeash::class.java).summary
        for (fact in listOf("baseline=google", "taskLeash=true", "insetsIgnoreVisibility=false",
            "barGetters=false", "shadeCollapse=false", "surfaceController=false"))
            assertTrue("missing $fact in $summary", summary.contains(fact))
    }
}
