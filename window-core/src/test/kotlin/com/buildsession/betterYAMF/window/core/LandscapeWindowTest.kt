package com.buildsession.betterYAMF.window.core

import com.buildsession.betterYAMF.window.api.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LandscapeWindowTest {
    private val landscape = SceneMetrics(2400, 1080, 36, 24, rotation = 1, leftInset = 60, rightInset = 20)
    private val portrait = SceneMetrics(1080, 2400, 36, 24)
    private val style = SceneStyle(2f, 20f, 12f)

    @Test fun notificationClickPolicyPreservesEveryNativeOnlyInteraction() {
        val ordinary = NotificationEntryFacts(true, true, true, false, false, false, false, true, false)
        assertTrue(NotificationEntryPolicy.accepts(ordinary))
        for (native in listOf(ordinary.copy(landscape = false), ordinary.copy(activityIntent = false),
            ordinary.copy(clearable = false), ordinary.copy(ongoing = true), ordinary.copy(bubble = true),
            ordinary.copy(fullScreenIntent = true), ordinary.copy(groupSummary = true),
            ordinary.copy(profileUnlocked = false), ordinary.copy(systemInteraction = true)))
            assertFalse(native.toString(), NotificationEntryPolicy.accepts(native))
    }

    @Test fun defaultLandscapeLayoutIsCenteredVerticalAndKeepsFullscreenTextDensity() {
        val options = WindowOptions(style)
        val (w, h) = WindowLayoutPolicy.size(options, landscape)
        assertEquals(766, w); assertEquals(984, h)
        val c = WindowSceneController.initialContent(landscape, style, w, h, 0)
        val layout = WindowLayoutPolicy.layout(c, landscape)
        assertTrue(c.width < c.height)
        assertTrue(c.top - style.topBar >= landscape.topInset + style.margin)
        assertTrue(c.bottom <= landscape.height - landscape.bottomInset - style.margin)
        assertTrue(c.left >= landscape.leftInset + style.margin)
        assertTrue(c.right + style.sideBar <= landscape.width - landscape.rightInset - style.margin)
        val usableCenter = (landscape.leftInset + landscape.width - landscape.rightInset) / 2f
        assertEquals(usableCenter, c.left + (c.width + style.sideBar) / 2f, 0.1f)
        assertEquals(1080, layout.logicalViewport.width)
        assertTrue(layout.logicalViewport.height > layout.logicalViewport.width)
        assertEquals(ContentOrientation.PORTRAIT, layout.contentOrientation)
        assertEquals(layout.logicalViewport, WindowLayoutPolicy.layout(c.copy(width = c.width * 0.8f,
            height = c.height * 0.8f), landscape, layout.logicalViewport).logicalViewport)
    }

    @Test fun customWideDimensionsAreTurnedVerticalAndUniformlyFitWithoutDensityChanges() {
        val (w, h) = WindowLayoutPolicy.size(WindowOptions(style, 900, 300), landscape)
        assertEquals(600, w); assertEquals(1800, h)
        val c = WindowSceneController.initialContent(landscape, style, w, h, 0)
        assertEquals(1f / 3f, c.width / c.height, 0.0001f)
        assertTrue(c.height <= landscape.height - landscape.topInset - landscape.bottomInset)
        val staleLandscapeViewport = WindowRect(0, 0, 2400, 800)
        assertTrue(WindowLayoutPolicy.layout(c, landscape, staleLandscapeViewport).logicalViewport.height > 1080)
    }

    @Test fun landscapeSideAdmissionIncludesCornersButExcludesTopBottomAndUnsafeBands() {
        for (x in listOf(61f, 2379f)) for (y in listOf(37f, 1055f)) {
            val hit = DropTargetCalculator.compute(x, y, landscape.width, landscape.height, true,
                landscape.topInset, landscape.bottomInset, landscape.leftInset, landscape.rightInset)
            assertEquals(if (x < 100) DropKind.SIDE_LEFT else DropKind.SIDE_RIGHT, hit.hover)
            assertTrue(hit.active)
        }
        for (y in listOf(10f, 1079f)) assertFalse(DropTargetCalculator.compute(1000f, y,
            2400, 1080, true, 36, 24, 60, 20).active)
        assertFalse(DropTargetCalculator.compute(61f, 10f, 2400, 1080, true, 36, 24, 60, 20).active)
        val shell = WindowShellController(WindowRegistration(1, 2f))
        shell.onScreenMetricsChanged(landscape)
        assertTrue(shell.onInput(WindowInputEvent.RequestMini(1, 0L)).isEmpty())
        assertEquals(ShellMode.NORMAL, shell.snapshot().mode)
    }

    @Test fun simultaneousStashesHaveSeparatedSlotsAndCapacityDoesNotUseAnOverflowSidebar() {
        val slots = SideStashRegistry()
        val allocated = mutableListOf<Pair<Int, SideStashRegistry.Slot>>()
        for (id in 1..30) {
            val slot = slots.reserve(id, WindowSide.LEFT, 0.5f, landscape, style) ?: break
            allocated += id to slot
        }
        assertTrue(allocated.size > 2)
        val rects = allocated.map { SideStashPolicy.target(it.second.side, it.second.anchor, landscape, style) }
        for ((index, a) in rects.withIndex()) for (b in rects.drop(index + 1))
            assertTrue(abs(a.top - b.top) >= 52f * style.density + 8f * style.density - 0.1f)
        assertNull(slots.reserve(99, WindowSide.LEFT, 0.5f, landscape, style))
        assertNotNull(slots.reserve(100, WindowSide.RIGHT, 0.5f, landscape, style))
        val first = allocated.first()
        // Reserving a restore in progress must not free its icon; only its commit calls release.
        assertEquals(first.second, slots.reserve(first.first, WindowSide.LEFT, 0.1f, landscape, style))
        slots.release(first.first)
        assertNotNull(slots.reserve(101, WindowSide.LEFT, 0.5f, landscape, style))
    }

    @Test fun landscapeCutoutInsetsDoNotExposeTheWholeStashedIcon() {
        for (side in WindowSide.entries) {
            val target = SideStashPolicy.target(side, 0.5f, landscape, style)
            val visible = checkNotNull(SideStashPolicy.visiblePart(target, landscape))
            assertEquals(target.width * 0.45f, visible.width, 0.001f)
            assertTrue(target.left < 0f || target.right > landscape.width)
        }
    }

    @Test fun rotationReflowPrioritizesOlderStashesAndReportsOverflowForNormalRestoration() {
        val slots = SideStashRegistry()
        for (id in 1..12) assertNotNull(slots.reserve(id, WindowSide.LEFT, 0.5f, portrait, style))
        slots.configure(landscape, style)
        assertNotNull(slots.get(1))
        assertNull(slots.get(12))
        val retained = (1..12).mapNotNull(slots::get)
        assertTrue(retained.size < 12)
        assertEquals(retained.size, retained.map { it.anchor }.toSet().size)
    }

    @Test fun anExistingMiniRotatesToAnIconAndStaysAnIconWhenPortraitReturns() {
        val d = Driver(portrait)
        d.input(WindowInputEvent.RequestMini(1, 1)); d.settle()
        assertEquals(ShellMode.MINI, d.scene.shellSnapshot().mode)
        d.send(WindowSceneEvent.Metrics(landscape)); d.settle()
        assertEquals(ShellMode.STASHED, d.scene.shellSnapshot().mode)
        assertNotNull(d.frame.sideStash)
        assertFalse(d.frame.taskVisible)
        d.send(WindowSceneEvent.Metrics(portrait)); d.settle()
        assertEquals(ShellMode.STASHED, d.scene.shellSnapshot().mode)
        assertNotNull(d.frame.sideStash)
        assertFalse(d.frame.acceptsContent)
    }

    @Test fun rotationWithBothSidesFullRestoresMiniToOrdinaryInsteadOfOverlappingIcons() {
        val slots = SideStashRegistry()
        for (side in WindowSide.entries) for (id in 10..50)
            slots.reserve(id + if (side == WindowSide.LEFT) 0 else 100, side, 0.5f, landscape, style)
        // Configure the rotation geometry after constructing a portrait mini; existing slots keep
        // their older priority when the mini requests admission.
        val d = Driver(portrait, slots)
        d.input(WindowInputEvent.RequestMini(1, 1)); d.settle()
        d.send(WindowSceneEvent.Metrics(landscape)); d.settle()
        assertEquals(ShellMode.NORMAL, d.scene.shellSnapshot().mode)
        assertNull(d.frame.sideStash)
        assertTrue(d.frame.acceptsContent)
        assertEquals(0f, d.frame.miniFraction, 0f)
    }

    @Test fun aFullEdgeKeepsTheRejectedDropsModeAndVisibleGeometryConsistent() {
        for (mini in listOf(false, true)) {
            val slots = SideStashRegistry()
            for (id in 10..60) slots.reserve(id, WindowSide.LEFT, 0.5f, portrait, style)
            val d = Driver(portrait, slots)
            if (mini) { d.input(WindowInputEvent.RequestMini(1, d.time)); d.settle() }
            val region = if (mini) RegionId.BODY_MASK else RegionId.HANDLE
            d.input(WindowInputEvent.Press(1, d.time, region, d.frame.content.left, d.frame.content.top))
            d.input(WindowInputEvent.Move(1, d.time + 10, region, 1f, 1200f))
            d.input(WindowInputEvent.Up(1, d.time + 20, region, 1f, 1200f)); d.settle()
            assertNull(slots.get(1))
            assertNull(d.frame.sideStash)
            assertEquals(if (mini) ShellMode.MINI else ShellMode.NORMAL, d.scene.shellSnapshot().mode)
            assertEquals(if (mini) 1f else 0f, d.frame.miniFraction, 0f)
            assertEquals(!mini, d.frame.acceptsContent)
            if (mini) {
                d.input(WindowInputEvent.TapConfirmed(1, d.time, RegionId.BODY_MASK,
                    d.frame.content.left, d.frame.content.top)); d.settle()
                assertTrue(d.frame.acceptsContent)
            }
        }
    }

    @Test fun keyboardOcclusionNeverReallocatesStashedIcons() {
        val d = Driver(landscape)
        d.stash()
        val before = d.frame
        d.send(WindowSceneEvent.KeyboardOcclusion(600)); d.settle()
        assertEquals(before.sideStash, d.frame.sideStash)
        assertEquals(before.input, d.frame.input)
        d.send(WindowSceneEvent.KeyboardOcclusion(0)); d.settle()
        assertEquals(before.sideStash, d.frame.sideStash)
    }

    @Test fun viewportFactsMigrateOnlyIncompatibleLayoutsAndStayHiddenUntilPreparation() {
        for (legacy in listOf(false, true)) {
            val d = Driver(portrait)
            val old = if (legacy) WindowRect(0, 0, 2400, 800) else WindowRect(0, 0, 1080, 1800)
            d.send(WindowSceneEvent.Metrics(landscape, old))
            assertEquals(if (legacy) 2 else 1, d.preparations.size)
            if (legacy) {
                assertEquals(0f, d.frame.taskAlpha, 0f)
                assertFalse(d.frame.acceptsContent)
                assertFalse(d.frame.taskFocusable)
                assertTrue(d.scene.normalLayout(old).logicalViewport.height > 1080)
                d.send(WindowSceneEvent.Prepared(d.preparations.last().commandId, CommandStatus.DONE))
            }
            d.settle()
            assertTrue(d.frame.acceptsContent)
        }
    }

    private inner class Driver(metrics: SceneMetrics, slots: SideStashRegistry = SideStashRegistry()) {
        val scene = WindowSceneController(1, style, metrics,
            WindowSceneController.initialContent(metrics, style, 766, 984, 0), stashSlots = slots)
        var time = 1L
        var requested: Long? = null
        val preparations = mutableListOf<WindowSceneEffect.Prepare>()
        val frame get() = scene.snapshot()
        init {
            val prepare = send(WindowSceneEvent.TaskAvailable).filterIsInstance<WindowSceneEffect.Prepare>().single()
            send(WindowSceneEvent.Prepared(prepare.commandId, CommandStatus.DONE)); settle()
        }
        fun send(event: WindowSceneEvent): List<WindowSceneEffect> {
            val out = scene.onEvent(event)
            out.forEach { when (it) {
                is WindowSceneEffect.Prepare -> preparations += it
                is WindowSceneEffect.RequestFrame -> requested = it.generation
                is WindowSceneEffect.Present -> send(WindowSceneEvent.FrameCommitted(it.frameId, CommandStatus.DONE))
                else -> Unit
            } }
            return out
        }
        fun input(e: WindowInputEvent) = send(WindowSceneEvent.Input(e))
        fun settle() { repeat(300) { requested?.let { g -> requested = null; time += 16; send(WindowSceneEvent.Tick(g, time)) } }; assertNull(requested) }
        fun stash() {
            input(WindowInputEvent.Press(1, time, RegionId.HANDLE, frame.content.left, frame.content.top))
            input(WindowInputEvent.Move(1, time + 10, RegionId.HANDLE, 61f, 500f))
            input(WindowInputEvent.Up(1, time + 20, RegionId.HANDLE, 61f, 500f)); settle()
        }
    }
}
