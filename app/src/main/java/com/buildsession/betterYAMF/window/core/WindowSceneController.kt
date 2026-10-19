package com.buildsession.betterYAMF.window.core

import kotlin.math.roundToInt

/**
 * Pure scene event loop. This is the only owner of desired scene geometry, motion and position memory.
 * The shell decides intent; this controller turns it into immutable frames. The executor supplies
 * monotonic frame times and reports presentation results, including superseded frames.
 */
class WindowSceneController(
    private val windowId: Int,
    val style: SceneStyle,
    initialMetrics: SceneMetrics,
    initialContent: SceneRect,
    private val shell: WindowShellController = WindowShellController(WindowRegistration(windowId, style.density))
) {
    private var metrics = initialMetrics
    private var normalContent = initialContent
    private var preferredNormalWidth = initialContent.width
    private var preferredNormalHeight = initialContent.height
    private var pose = Pose(initialContent, 0f, 0f)
    private var ready = false
    private var taskReady = false
    private var destroyed = false
    private var visible = true
    private var prepareId = 0L
    private var nextId = 1L
    private var generation = 0L
    private var motion: Motion? = null
    private var velocitySeed: Map<Property, Float>? = null
    private var pendingCompletion: Pair<Long, Completion>? = null
    private var lastFrameId = 0L
    private var dragBase = pose
    private var resizeBase = initialContent
    private var previewBase: Pose? = null
    private var fullscreenHint = false
    private var fullscreenStage: FullscreenStage? = null
    private var fullscreenCommandId = 0L
    private var controllerColor = 0xFFFFFFFF.toInt()
    private var openAsMini = false
    private var awaitingSourceRelease = false
    private var sourceFrameAcknowledged = false
    private enum class EntryVisual { SOURCE, REVEALING, LIVE }
    private var entryVisual = EntryVisual.LIVE
    private var entryRevealFrameId: Long? = null
    private var entryLayoutChosen = false
    private var entrySource: SceneRect? = null
    private var entryCornerRadius = 0f
    private var entryVelocityX = 0f
    private var entryVelocityY = 0f
    private var dropX = -1
    private var dropY = -1
    private var lastCorner = DropKind.CORNER_TR
    private var effects = mutableListOf<WindowSceneEffect>()

    private data class Pose(
        val content: SceneRect, val mini: Float, val alpha: Float,
        val normalChrome: Float = 1f - mini, val entryRadius: Float = 0f,
        val cover: Float = 0f, val outline: Float = 0f
    )
    private enum class Property { X, Y, W, H, MINI, NORMAL_CHROME, ALPHA, ENTRY_RADIUS, COVER, OUTLINE }
    private enum class FullscreenStep { EXPAND, RELEASE, FINISH, RESTORE }
    private data class Completion(
        val miniCommandId: Long,
        val close: Boolean,
        val fullscreenStep: FullscreenStep? = null
    )
    private data class Motion(
        val generation: Long,
        val tracks: Map<Property, SpringTrack>,
        var target: Pose,
        val completion: Completion,
        val keepInside: Boolean,
        val fullscreenKind: FullscreenMotionKind? = null,
        var previousTimeMs: Long = -1L,
        var elapsedMs: Long = 0L
    )

    init {
        shell.onScreenMetricsChanged(metrics.width, metrics.height)
    }

    fun snapshot(): WindowSceneFrame = frame()
    fun shellSnapshot(): ShellSnapshot = shell.snapshot()
    fun normalViewport(): SceneRect = normalContent
    fun isAnimating(): Boolean = !destroyed && (motion != null || pendingCompletion != null ||
        entryVisual == EntryVisual.REVEALING || fullscreenStage?.let { it != FullscreenStage.DRAGGING } == true)
    fun prefersHighFrameRate(): Boolean {
        if (destroyed) return false
        val input = shell.snapshot()
        return isAnimating() || input.dragging || input.resizing || previewBase != null
    }

    fun onEvent(event: WindowSceneEvent): List<WindowSceneEffect> {
        effects = mutableListOf()
        if (destroyed) return emptyList()
        when (event) {
            is WindowSceneEvent.Input -> if (ready && taskReady && !awaitingSourceRelease &&
                entryVisual == EntryVisual.LIVE && permitsInput(event.event)) input(event.event)
            is WindowSceneEvent.OpenAsMini -> {
                openAsMini = true
                entrySource = event.source?.takeIf { it.width > 0f && it.height > 0f }
                entryCornerRadius = event.sourceCornerRadius.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
                entryVelocityX = event.velocityX.takeIf { it.isFinite() } ?: 0f
                entryVelocityY = event.velocityY.takeIf { it.isFinite() } ?: 0f
                dropX = event.dropX
                dropY = event.dropY
                awaitingSourceRelease = event.awaitSourceRelease
                sourceFrameAcknowledged = false
            }
            WindowSceneEvent.SourceReleased -> {
                awaitingSourceRelease = false
                val active = motion
                if (active != null) effects += WindowSceneEffect.RequestFrame(active.generation)
                else if (ready) republish()
            }
            is WindowSceneEvent.EntryLayout -> if (awaitingSourceRelease && !entryLayoutChosen && !taskReady && prepareId == 0L) {
                entryLayoutChosen = true
                metrics = event.metrics
                shell.onScreenMetricsChanged(metrics.width, metrics.height)
                normalContent = initialContent(metrics, style, event.width, event.height, event.preferredTop)
                preferredNormalWidth = normalContent.width
                preferredNormalHeight = normalContent.height
                // Retarget the frozen source bridge from its exact current pose. Do this before
                // Prepare: the live application must receive this final aspect exactly once.
                if (ready) enterMini(lastCorner, 0L)
                effects += WindowSceneEffect.ActivateEntry
            }
            WindowSceneEvent.PresentationInvalidated -> if (ready) republish()
            is WindowSceneEvent.TaskColor -> {
                val color = if (style.coloredController && event.color != 0) event.color else 0xFFFFFFFF.toInt()
                if (color != controllerColor) {
                    controllerColor = color
                    if (ready) republish()
                }
            }
            WindowSceneEvent.RestorePreview -> returnFromPull()
            WindowSceneEvent.VisualAvailable -> if (!ready && openAsMini && entrySource != null) {
                entryVisual = EntryVisual.SOURCE
                ready = true
                shell.onInput(WindowInputEvent.RequestMini(windowId, 0L)).forEach(::command)
            }
            WindowSceneEvent.TaskAvailable -> if (!taskReady && prepareId == 0L) {
                prepareId = nextId++
                effects += WindowSceneEffect.Prepare(prepareId, normalContent)
            }
            is WindowSceneEvent.Prepared -> if (event.commandId == prepareId && prepareId != 0L) {
                prepareId = 0L
                if (event.status == CommandStatus.DONE) {
                    taskReady = true
                    if (ready) {
                        republish()
                    } else if (openAsMini) {
                        ready = true
                        shell.onInput(WindowInputEvent.RequestMini(windowId, 0L)).forEach(::command)
                    } else {
                        ready = true
                        startMotion(Pose(normalContent, 0f, 1f), MiniAnimKind.OPEN)
                    }
                } else {
                    endSourceHandoff(event.status)
                    effects += WindowSceneEffect.Failed("prepare viewport")
                }
            }
            is WindowSceneEvent.Tick -> tick(event)
            is WindowSceneEvent.FrameCommitted -> frameCommitted(event)
            is WindowSceneEvent.FullscreenHandoff -> fullscreenHandoff(event)
            is WindowSceneEvent.Metrics -> {
                // A refresh-rate vote also produces onDisplayChanged. It is not a geometry event.
                if (metrics != event.metrics) {
                    metrics = event.metrics
                    shell.onScreenMetricsChanged(metrics.width, metrics.height)
                    normalContent = clamp(fitNormalContent(normalContent.copy(
                        width = preferredNormalWidth, height = preferredNormalHeight)), 0f)
                    if (ready && fullscreenStage != null) {
                        fullscreenMetricsChanged()
                    } else if (ready) {
                        if (shell.snapshot().mode == ShellMode.MINI) enterMini(lastCorner, 0L)
                        else startMotion(Pose(normalContent, 0f, pose.alpha), MiniAnimKind.OPEN)
                    }
                }
            }
            is WindowSceneEvent.Visibility -> {
                visible = event.visible
                if (ready) {
                    // A hidden window may not draw. Transfer a terminal waiter to the new frame;
                    // an old callback must never finish a newer animation after unlock.
                    republish()
                }
            }
            WindowSceneEvent.ResetPosition -> if (ready && fullscreenStage == null) {
                val c = pose.content
                val centered = c.copy(left = (metrics.width - c.width) / 2f, top = (metrics.height - c.height) / 2f)
                startMotion(Pose(clamp(centered, pose.mini), pose.mini, 1f), MiniAnimKind.OPEN)
            }
            WindowSceneEvent.Destroy -> {
                endSourceHandoff(CommandStatus.SUPERSEDED)
                interrupt()
                destroyed = true
                ready = false
                taskReady = false
                visible = false
                entryRevealFrameId = null
                fullscreenStage = null
                previewBase = null
                fullscreenHint = false
                pose = pose.copy(alpha = 0f, cover = 0f, outline = 0f)
            }
        }
        return effects.toList()
    }

    private fun permitsInput(event: WindowInputEvent): Boolean = when (fullscreenStage) {
        null -> true
        FullscreenStage.DRAGGING -> event !is WindowInputEvent.Press ||
            event.region == RegionId.BAR_BOTTOM || event.region == RegionId.BAR_SIDE
        FullscreenStage.RETURNING -> event is WindowInputEvent.Press &&
            (event.region == RegionId.BAR_BOTTOM || event.region == RegionId.BAR_SIDE)
        else -> false
    }

    private fun input(event: WindowInputEvent) {
        if (event.windowId != windowId) return
        if (event is WindowInputEvent.Press) {
            interrupt()
            if (fullscreenStage == FullscreenStage.RETURNING) fullscreenStage = FullscreenStage.DRAGGING
            dragBase = pose
            resizeBase = pose.content
        }
        shell.onInput(event).forEach(::command)
    }

    private fun command(command: WindowCommand) {
        when (command) {
            is WindowCommand.EnterMini -> enterMini(command.corner, command.cmdId)
            is WindowCommand.ExitMini -> {
                previewBase = null
                fullscreenStage = null
                startMotion(Pose(clamp(normalContent, 0f), 0f, 1f), MiniAnimKind.OPEN, command.cmdId)
            }
            is WindowCommand.MoveBy -> {
                velocitySeed = null
                pose = dragBase.copy(content = dragBase.content.offset(command.dx, command.dy))
                rememberNormal()
                present()
            }
            is WindowCommand.ClampToScreen -> {
                if (motion == null) {
                    when {
                        shell.snapshot().mode == ShellMode.NORMAL &&
                            (pose.mini > 0f || pose.normalChrome < 0.999f || pose.alpha < 0.999f) ->
                            startMotion(Pose(clamp(normalContent, 0f), 0f, 1f), MiniAnimKind.OPEN)
                        shell.snapshot().mode == ShellMode.MINI && pose.mini < 1f -> enterMini(lastCorner, 0L)
                        else -> startMotion(Pose(clamp(pose.content, pose.mini), pose.mini, 1f), MiniAnimKind.OPEN)
                    }
                }
            }
            is WindowCommand.FlingBy -> {
                // AndroidX FlingAnimation's default DragForce friction is -4.2 / second.
                val destination = clamp(pose.content.offset(command.velocityX / 4.2f, command.velocityY / 4.2f), pose.mini)
                velocitySeed = mapOf(Property.X to command.velocityX, Property.Y to command.velocityY)
                startMotion(Pose(destination, pose.mini, 1f), MiniAnimKind.OPEN)
            }
            is WindowCommand.ResizePreviewStart -> resizeBase = pose.content
            is WindowCommand.ResizePreviewUpdate -> {
                val factor = ResizePolicy.dragScaleFactor(resizeBase.width.roundToInt(), resizeBase.height.roundToInt(), command.dx, command.dy)
                val maxWidth = metrics.width - style.margin * 2f
                val maxHeight = metrics.height - metrics.topInset - metrics.bottomInset - style.topBar - style.bottomBar - style.margin * 2f
                val maxFactor = minOf(maxWidth / resizeBase.width, maxHeight / resizeBase.height).coerceAtLeast(ResizePolicy.MIN_FACTOR)
                val f = factor.coerceAtMost(maxFactor)
                pose = pose.copy(content = resizeBase.copy(width = resizeBase.width * f, height = resizeBase.height * f))
                rememberNormal(resized = true)
                present()
            }
            is WindowCommand.ResizePreviewCommit ->
                startMotion(Pose(clamp(pose.content, 0f), 0f, 1f), MiniAnimKind.OPEN)
            is WindowCommand.VerticalDragPreview -> {
                val base = previewBase ?: pose.also { previewBase = it }
                fullscreenStage = FullscreenStage.DRAGGING
                val c = dragBase.content
                val scale = 1f - command.progress.coerceIn(0f, 1f) * 0.3f
                val content = if (command.downward)
                    FullscreenAnimPolicy.previewContent(c, command.progress, metrics, style.margin,
                        if (metrics.height >= metrics.width) 0f else style.sideBar * dragBase.normalChrome,
                        if (metrics.height >= metrics.width) style.bottomBar * dragBase.normalChrome else 0f)
                else SceneRect(c.left + c.width * (1f - scale) / 2f,
                    c.top + c.height * (1f - scale) / 2f, c.width * scale, c.height * scale)
                val armed = command.downward && command.showFullscreenHint
                followPull(dragBase.copy(
                    content = content,
                    alpha = if (command.downward) base.alpha else base.alpha * (1f - command.progress),
                    cover = if (armed) 1f else 0f,
                    outline = if (armed) 1f else 0f
                ))
                fullscreenHint = armed
            }
            is WindowCommand.ClearVerticalDragPreview -> returnFromPull()
            is WindowCommand.RequestClose -> {
                fullscreenStage = null
                previewBase = null
                pose = pose.copy(cover = 0f, outline = 0f)
                fullscreenHint = false
                val c = pose.content
                startMotion(Pose(SceneRect(c.left + c.width / 2f, c.top + c.height / 2f, 1f, 1f), pose.mini, 0f), MiniAnimKind.MINIMIZE, close = true)
            }
            is WindowCommand.RequestFullscreen -> {
                previewBase = previewBase ?: pose
                fullscreenCommandId = nextId++
                fullscreenHint = false
                fullscreenStage = FullscreenStage.COVERING
                startMotion(pose.copy(alpha = 1f, cover = 1f, outline = 1f), MiniAnimKind.OPEN,
                    fullscreenKind = FullscreenMotionKind.COVER, fullscreenStep = FullscreenStep.EXPAND)
            }
            else -> effects += WindowSceneEffect.SystemCommand(command)
        }
    }

    private fun followPull(target: Pose) {
        val active = motion
        if (active?.fullscreenKind == FullscreenMotionKind.FOLLOW && active.completion.fullscreenStep == null) {
            active.target = target
            active.elapsedMs = 0L
            values(target).forEach { (property, value) -> active.tracks.getValue(property).retarget(value) }
            effects += WindowSceneEffect.RequestFrame(active.generation)
        } else {
            startMotion(target, MiniAnimKind.OPEN, fullscreenKind = FullscreenMotionKind.FOLLOW)
        }
    }

    private fun returnFromPull() {
        val base = previewBase ?: return
        fullscreenHint = false
        fullscreenStage = FullscreenStage.RETURNING
        startMotion(base.copy(cover = 0f, outline = 0f), MiniAnimKind.OPEN,
            fullscreenKind = FullscreenMotionKind.RETURN, fullscreenStep = FullscreenStep.RESTORE)
    }

    private fun expandFullscreen() {
        val card = frame().outer
        val radius = fullscreenRadius()
        fullscreenStage = FullscreenStage.EXPANDING
        // The opaque frame has committed with taskAlpha=0. Only the independent bridge moves
        // from now on; a new app layout never gets cropped into the old window's aspect.
        pose = pose.copy(content = card, mini = 0f, normalChrome = 0f, entryRadius = radius,
            alpha = 1f, cover = 1f, outline = 1f)
        startMotion(pose.copy(content = displayRect(), entryRadius = 0f), MiniAnimKind.OPEN,
            fullscreenKind = FullscreenMotionKind.EXPAND, fullscreenStep = FullscreenStep.RELEASE)
    }

    private fun fullscreenHandoff(event: WindowSceneEvent.FullscreenHandoff) {
        if (fullscreenStage != FullscreenStage.WAITING || event.commandId != fullscreenCommandId) return
        if (!event.result.ownershipKnown) {
            fullscreenStage = FullscreenStage.FINISHED
            effects += WindowSceneEffect.Failed("fullscreen ownership unavailable")
        } else if (event.result.status == CommandStatus.DONE && event.result.taskReleased) {
            fullscreenStage = FullscreenStage.REVEALING
            startMotion(pose.copy(cover = 0f, outline = 0f), MiniAnimKind.OPEN,
                fullscreenKind = FullscreenMotionKind.REVEAL, fullscreenStep = FullscreenStep.FINISH)
        } else if (event.result.taskReleased) {
            // Ownership cannot be rolled back by writing the obsolete task pose.
            fullscreenStage = FullscreenStage.FINISHED
            effects += WindowSceneEffect.FinishFullscreen
        } else {
            // Restore the still-owned task under an opaque card, then reveal its original pose.
            pose = (previewBase ?: pose).copy(cover = 1f, outline = 1f)
            returnFromPull()
        }
    }

    private fun fullscreenMetricsChanged() {
        when (fullscreenStage) {
            FullscreenStage.EXPANDING -> startMotion(pose.copy(content = displayRect(), entryRadius = 0f),
                MiniAnimKind.OPEN, fullscreenKind = FullscreenMotionKind.EXPAND, fullscreenStep = FullscreenStep.RELEASE)
            FullscreenStage.WAITING -> {
                pose = pose.copy(content = displayRect(), entryRadius = 0f)
                republish()
            }
            FullscreenStage.REVEALING -> {
                pose = pose.copy(content = displayRect(), entryRadius = 0f)
                startMotion(pose.copy(cover = 0f, outline = 0f), MiniAnimKind.OPEN,
                    fullscreenKind = FullscreenMotionKind.REVEAL, fullscreenStep = FullscreenStep.FINISH)
            }
            FullscreenStage.FINISHED, null -> Unit
            else -> {
                previewBase = previewBase?.copy(content = normalContent)
                returnFromPull()
            }
        }
    }

    private fun displayRect() = SceneRect(0f, 0f, metrics.width.toFloat(), metrics.height.toFloat())

    private fun fullscreenRadius(): Float = when (fullscreenStage) {
        FullscreenStage.EXPANDING, FullscreenStage.WAITING, FullscreenStage.REVEALING,
        FullscreenStage.FINISHED -> pose.entryRadius
        else -> maxOf(style.barCorner, style.miniCorner)
    }

    private fun enterMini(corner: DropKind, commandId: Long) {
        previewBase = null
        fullscreenStage = null
        fullscreenHint = false
        lastCorner = corner
        val miniInsets = MiniPolicy.ShellInsets(style.bezel.roundToInt(), style.bezel.roundToInt(), style.bezel.roundToInt(), style.bezel.roundToInt())
        val targetCard = MiniPolicy.enterTarget(
            corner, metrics.width, metrics.height, style.margin.roundToInt(), metrics.topInset,
            metrics.bottomInset, pose.content.top.roundToInt(), normalContent.width.roundToInt(),
            normalContent.height.roundToInt(), miniInsets
        )
        val target = SceneRect(
            (targetCard.left + miniInsets.left).toFloat(), (targetCard.top + miniInsets.top).toFloat(),
            (targetCard.width - miniInsets.width).toFloat(), (targetCard.height - miniInsets.height).toFloat()
        )
        if (openAsMini) {
            val source = entrySource ?: DropEntryPolicy.startRect(
                targetCard, dropX, dropY, metrics.width, metrics.height, style.margin.roundToInt(),
                metrics.topInset, metrics.bottomInset
            ).let { SceneRect(it.left.toFloat(), it.top.toFloat(), it.width.toFloat(), it.height.toFloat()) }
            // A real source rectangle continues the released recents card; never reveal a normal
            // intermediate window. Missing source geometry uses the documented seed-card policy.
            pose = Pose(source, 0f, if (entrySource == null) 0f else 1f, normalChrome = 0f,
                entryRadius = if (entrySource != null) entryCornerRadius else 0f)
            if (entrySource != null) velocitySeed = mapOf(Property.X to entryVelocityX, Property.Y to entryVelocityY)
            openAsMini = false
            entrySource = null
        }
        startMotion(Pose(target, 1f, 1f), MiniAnimKind.MINIMIZE, commandId)
    }

    private fun startMotion(
        target: Pose, kind: MiniAnimKind, commandId: Long = 0L, close: Boolean = false,
        fullscreenKind: FullscreenMotionKind? = null, fullscreenStep: FullscreenStep? = null
    ) {
        val seed = motion?.tracks?.mapValues { it.value.velocity } ?: velocitySeed
        interrupt()
        velocitySeed = null
        val completion = Completion(commandId, close, fullscreenStep)
        if (style.timeScale <= 0f || pose == target) {
            pose = target
            if (fullscreenStep == FullscreenStep.EXPAND) velocitySeed = seed
            presentCompletion(completion)
            return
        }
        val fromValues = values(pose)
        val targetValues = values(target)
        val tracks = Property.entries.associateWith { property ->
            val prop = when (property) {
                Property.X -> MiniAnimProp.TX
                Property.Y -> MiniAnimProp.TY
                Property.W, Property.MINI, Property.NORMAL_CHROME, Property.ENTRY_RADIUS -> MiniAnimProp.W
                Property.H -> MiniAnimProp.H
                Property.ALPHA, Property.COVER, Property.OUTLINE -> MiniAnimProp.ALPHA
            }
            val unitless = property == Property.MINI || property == Property.NORMAL_CHROME ||
                property == Property.ALPHA || property == Property.COVER || property == Property.OUTLINE
            SpringTrack(
                fromValues.getValue(property), targetValues.getValue(property),
                if (fullscreenKind != null) FullscreenAnimPolicy.spec(fullscreenKind, unitless, style.timeScale)
                else MiniAnimPolicy.spec(kind, prop, style.timeScale),
                if (unitless) 0.001f else 0.25f, if (unitless) 0.01f else 15f
            ).also { track -> seed?.get(property)?.let { track.seed(track.value, it) } }
        }
        val id = ++generation
        val keepInside = clamp(pose.content, pose.mini) == pose.content && clamp(target.content, target.mini) == target.content
        motion = Motion(id, tracks, target, completion, keepInside, fullscreenKind)
        present()
        if (!awaitingSourceRelease) effects += WindowSceneEffect.RequestFrame(id)
    }

    private fun tick(event: WindowSceneEvent.Tick) {
        if (awaitingSourceRelease) return
        val active = motion?.takeIf { it.generation == event.generation } ?: return
        val dt = if (active.previousTimeMs < 0L) 0L else (event.timeMs - active.previousTimeMs).coerceAtLeast(0L)
        active.previousTimeMs = event.timeMs
        active.elapsedMs += dt
        active.tracks.values.forEach { it.step(dt.toFloat()) }
        fun value(p: Property) = active.tracks.getValue(p).value
        pose = Pose(
            SceneRect(value(Property.X), value(Property.Y), value(Property.W).coerceAtLeast(1f), value(Property.H).coerceAtLeast(1f)),
            value(Property.MINI).coerceIn(0f, 1f), value(Property.ALPHA).coerceIn(0f, 1f),
            value(Property.NORMAL_CHROME).coerceIn(0f, 1f), value(Property.ENTRY_RADIUS).coerceAtLeast(0f),
            value(Property.COVER).coerceIn(0f, 1f), value(Property.OUTLINE).coerceIn(0f, 1f)
        )
        if (active.keepInside) {
            val bounded = clamp(pose.content, pose.mini)
            if (bounded.left != pose.content.left) active.tracks.getValue(Property.X).seed(bounded.left, 0f)
            if (bounded.top != pose.content.top) active.tracks.getValue(Property.Y).seed(bounded.top, 0f)
            pose = pose.copy(content = bounded)
        }
        val settled = active.tracks.values.all { it.atRest() } ||
            active.elapsedMs >= (MiniAnimPolicy.SETTLE_TIMEOUT_MS * style.timeScale).toLong()
        if (fullscreenStage == FullscreenStage.COVERING &&
            (active.tracks.getValue(Property.COVER).atRest() || settled)) {
            // Capture the geometry velocity at the cover fence instead of braking it to zero.
            velocitySeed = active.tracks.mapValues { it.value.velocity }
            pose = pose.copy(alpha = 1f, cover = 1f, outline = 1f)
            motion = null
            presentCompletion(active.completion)
        } else if (settled) {
            pose = active.target
            motion = null
            presentCompletion(active.completion)
        } else {
            present()
            effects += WindowSceneEffect.RequestFrame(active.generation)
        }
    }

    private fun presentCompletion(completion: Completion) {
        if (completion.fullscreenStep == FullscreenStep.EXPAND) fullscreenStage = FullscreenStage.HIDING
        present(completion)
    }

    private fun interrupt() {
        generation++
        motion?.let {
            velocitySeed = it.tracks.mapValues { track -> track.value.velocity }
            report(it.completion, CommandStatus.SUPERSEDED)
        }
        motion = null
        pendingCompletion?.let { report(it.second, CommandStatus.SUPERSEDED) }
        pendingCompletion = null
    }

    private fun report(completion: Completion, status: CommandStatus) {
        if (completion.miniCommandId > 0L) {
            shell.onInput(WindowInputEvent.CommandResult(windowId, 0L, completion.miniCommandId, status))
        }
        if (status == CommandStatus.DONE) {
            rememberNormal()
            if (completion.close) effects += WindowSceneEffect.FinishClose
            when (completion.fullscreenStep) {
                FullscreenStep.EXPAND -> expandFullscreen()
                FullscreenStep.RELEASE -> {
                    fullscreenStage = FullscreenStage.WAITING
                    effects += WindowSceneEffect.HandoffFullscreen(fullscreenCommandId)
                }
                FullscreenStep.FINISH -> {
                    fullscreenStage = FullscreenStage.FINISHED
                    effects += WindowSceneEffect.FinishFullscreen
                }
                FullscreenStep.RESTORE -> {
                    previewBase = null
                    fullscreenStage = null
                    rememberNormal()
                    present()
                }
                null -> Unit
            }
        }
    }

    private fun present(completion: Completion? = null) {
        // Source pixels have the old app's aspect. Switch at the settled destination pose,
        // after both owners are ready, and retain them until this exact replacement commits.
        if (entryVisual == EntryVisual.SOURCE && taskReady && !awaitingSourceRelease &&
            motion == null && pendingCompletion == null && completion == null && visible && pose.alpha > 0f) {
            entryVisual = EntryVisual.REVEALING
        }
        val id = nextId++
        lastFrameId = id
        if (entryVisual == EntryVisual.REVEALING) {
            entryRevealFrameId = id.takeIf { visible && pose.alpha > 0f }
        }
        if (completion != null) pendingCompletion = id to completion
        effects += WindowSceneEffect.Present(id, frame())
    }

    private fun republish() {
        val completion = pendingCompletion?.second
        pendingCompletion = null
        present(completion)
    }

    private fun frameCommitted(event: WindowSceneEvent.FrameCommitted) {
        if (entryVisual == EntryVisual.REVEALING && event.frameId == entryRevealFrameId) {
            entryRevealFrameId = null
            when (event.status) {
                CommandStatus.DONE -> {
                    entryVisual = EntryVisual.LIVE
                    effects += WindowSceneEffect.ReleaseEntryImage
                    present()
                }
                CommandStatus.SUPERSEDED -> present() // Coalescing cannot bypass the release fence.
                CommandStatus.FAILED -> Unit // The common failure path below tears down the host.
            }
        }
        // Readiness is a transaction fence, not an opacity threshold. Without source geometry
        // the seed is transparent: waiting for a visible frame here would also prevent its clock
        // from starting, leaving both sides waiting until the platform watchdog fires.
        if (awaitingSourceRelease && !sourceFrameAcknowledged &&
            event.status == CommandStatus.DONE && event.frameId == lastFrameId) {
            sourceFrameAcknowledged = true
            effects += WindowSceneEffect.SourceHandoffResult(CommandStatus.DONE)
        }
        val waiting = pendingCompletion
        if (waiting?.first == event.frameId) {
            if (!visible && event.status == CommandStatus.DONE &&
                (waiting.second.fullscreenStep == FullscreenStep.EXPAND || waiting.second.fullscreenStep == FullscreenStep.RELEASE)) {
                // A hidden shell has not covered the display. Visibility(true) republishes this
                // fence; only its subsequent visible image may advance the task handoff.
                return
            }
            pendingCompletion = null
            if (event.status == CommandStatus.SUPERSEDED && waiting.second.fullscreenStep != null) {
                // A bridge fence is not optional when its queued image was coalesced.
                present(waiting.second)
            } else report(waiting.second, event.status)
            if (event.status == CommandStatus.DONE && !waiting.second.close && waiting.second.fullscreenStep == null) {
                // Enable application input only after the settled frame has committed.
                present()
            }
        }
        if (event.status == CommandStatus.FAILED && event.frameId == lastFrameId) {
            endSourceHandoff(CommandStatus.FAILED)
            motion?.let { report(it.completion, CommandStatus.FAILED) }
            motion = null
            effects += WindowSceneEffect.Failed("present frame")
        }
    }

    private fun endSourceHandoff(status: CommandStatus) {
        if (awaitingSourceRelease) {
            awaitingSourceRelease = false
            effects += if (sourceFrameAcknowledged) WindowSceneEffect.CancelSourceHandoff(status)
                else WindowSceneEffect.SourceHandoffResult(status)
        }
    }

    private fun rememberNormal(resized: Boolean = false) {
        if (fullscreenStage == null && previewBase == null && pose.mini <= 0.001f &&
            shell.snapshot().mode == ShellMode.NORMAL && pose.alpha >= 0.999f) {
            normalContent = pose.content
            // A temporary display constraint must not become the user's remembered size.
            // Only an explicit resize (or the initial destination layout) changes that intent.
            if (resized) {
                preferredNormalWidth = pose.content.width
                preferredNormalHeight = pose.content.height
            }
        }
    }

    private fun values(p: Pose) = mapOf(
        Property.X to p.content.left, Property.Y to p.content.top, Property.W to p.content.width,
        Property.H to p.content.height, Property.MINI to p.mini, Property.NORMAL_CHROME to p.normalChrome,
        Property.ALPHA to p.alpha, Property.ENTRY_RADIUS to p.entryRadius,
        Property.COVER to p.cover, Property.OUTLINE to p.outline
    )

    private fun frame(): WindowSceneFrame {
        val m = pose.mini.coerceIn(0f, 1f)
        val normal = pose.normalChrome.coerceIn(0f, 1f)
        val portrait = metrics.height >= metrics.width
        val left = style.bezel * m
        val top = style.topBar * normal + style.bezel * m
        val right = (if (portrait) 0f else style.sideBar) * normal + style.bezel * m
        val bottom = (if (portrait) style.bottomBar else 0f) * normal + style.bezel * m
        val live = fullscreenStage != FullscreenStage.EXPANDING && fullscreenStage != FullscreenStage.WAITING &&
            fullscreenStage != FullscreenStage.REVEALING && fullscreenStage != FullscreenStage.FINISHED
        // Live content and Canvas share one raster rectangle. The independent exit bridge has
        // no task transform to round against and preserves its captured outer's subpixel pose.
        val raster = pose.content.rounded()
        val c = if (!live) pose.content else
            SceneRect(raster.left.toFloat(), raster.top.toFloat(), raster.width.toFloat(), raster.height.toFloat())
        val outer = SceneRect(c.left - left, c.top - top, c.width + left + right, c.height + top + bottom)
        val bridge = fullscreenStage?.let { stage ->
            val end = displayRect()
            val p = pose.outline.coerceIn(0f, 1f)
            fun mix(a: Float, b: Float) = a + (b - a) * p
            FullscreenVisual(stage, pose.cover, fullscreenRadius(),
                SceneRect(mix(outer.left, end.left), mix(outer.top, end.top),
                    mix(outer.width, end.width), mix(outer.height, end.height)),
                style.miniCorner, p * 0.14f,
                stage == FullscreenStage.DRAGGING || stage == FullscreenStage.RETURNING,
                if (stage == FullscreenStage.DRAGGING || stage == FullscreenStage.RETURNING) outer else end)
        }
        val baseRadius = pose.entryRadius + style.barCorner * normal + style.miniCorner * m
        val baseBottomRadius = pose.entryRadius + (if (portrait) style.barCorner * normal else 0f) + style.miniCorner * m
        val baseContentRadius = pose.entryRadius + (style.miniCorner - style.bezel).coerceAtLeast(0f) * m
        val contentRadius = if (bridge != null && live)
            baseContentRadius + (maxOf(baseContentRadius, bridge.cornerRadius) - baseContentRadius) * bridge.maskAlpha
            else baseContentRadius
        fun bridgeRadius(radius: Float) = if (bridge == null) radius
            else radius + (bridge.cornerRadius - radius) * bridge.maskAlpha
        return WindowSceneFrame(
            c, outer,
            m, if (visible) pose.alpha else 0f,
            bridgeRadius(baseRadius),
            contentRadius,
            normal * pose.alpha * (1f - pose.cover),
            ready && taskReady && visible && shell.snapshot().mode == ShellMode.NORMAL && motion == null &&
                pendingCompletion == null && entryVisual == EntryVisual.LIVE &&
                fullscreenStage == null && m <= 0.001f && normal >= 0.999f,
            portrait, fullscreenHint,
            outerBottomRadius = bridgeRadius(baseBottomRadius),
            controllerColor = controllerColor,
            controlsColor = if (controllerBrightness() < 128) 0xFFFFFFFF.toInt() else 0xFF777777.toInt(),
            fullscreen = bridge,
            taskAlpha = if (!visible || entryVisual == EntryVisual.SOURCE ||
                fullscreenStage == FullscreenStage.HIDING || !live) 0f else pose.alpha,
            presentsLiveContent = live,
            showEntryImage = entryVisual == EntryVisual.SOURCE
        )
    }

    private fun controllerBrightness(): Int =
        (((controllerColor ushr 16) and 255) * 299 + ((controllerColor ushr 8) and 255) * 587 +
            (controllerColor and 255) * 114) / 1000

    private fun clamp(content: SceneRect, mini: Float): SceneRect {
        val top = style.topBar * (1f - mini) + style.bezel * mini
        val bottom = (if (metrics.height >= metrics.width) style.bottomBar else 0f) * (1f - mini) + style.bezel * mini
        val side = style.bezel * mini
        val right = (if (metrics.height >= metrics.width) 0f else style.sideBar) * (1f - mini) + side
        val minX = style.margin + side
        val minY = metrics.topInset + style.margin + top
        val maxX = (metrics.width - style.margin - right - content.width).coerceAtLeast(minX)
        val maxY = (metrics.height - metrics.bottomInset - style.margin - bottom - content.height).coerceAtLeast(minY)
        return content.copy(left = content.left.coerceIn(minX, maxX), top = content.top.coerceIn(minY, maxY))
    }

    private fun fitNormalContent(content: SceneRect): SceneRect {
        val portrait = metrics.height >= metrics.width
        val availableWidth = (metrics.width - style.margin * 2f - if (portrait) 0f else style.sideBar).coerceAtLeast(1f)
        val availableHeight = (metrics.height - metrics.topInset - metrics.bottomInset - style.margin * 2f -
            style.topBar - if (portrait) style.bottomBar else 0f).coerceAtLeast(1f)
        val factor = minOf(1f, availableWidth / content.width, availableHeight / content.height)
        return content.copy(width = content.width * factor, height = content.height * factor)
    }

    companion object {
        fun initialContent(metrics: SceneMetrics, style: SceneStyle, width: Int, height: Int, preferredTop: Int): SceneRect {
            val portrait = metrics.height >= metrics.width
            val barHeight = style.topBar + if (portrait) style.bottomBar else 0f
            val rightBar = if (portrait) 0f else style.sideBar
            val availableWidth = (metrics.width - style.margin * 2f - rightBar).coerceAtLeast(1f)
            val availableHeight = (metrics.height - metrics.topInset - metrics.bottomInset -
                style.margin * 2f - barHeight).coerceAtLeast(1f)
            val safeWidth = width.coerceAtLeast(1).toFloat()
            val safeHeight = height.coerceAtLeast(1).toFloat()
            val scale = minOf(1f, availableWidth / safeWidth, availableHeight / safeHeight)
            val w = safeWidth * scale
            val h = safeHeight * scale
            val left = (metrics.width - w) / 2f
            // Keep the existing portrait placement (80dp above center), within system safe bands.
            val requestedTop = if (preferredTop != 0) preferredTop.toFloat() + style.topBar else
                (metrics.height - h - barHeight) / 2f + style.topBar - if (portrait) 80f * style.density else 0f
            val minTop = metrics.topInset + style.margin + style.topBar
            val maxTop = (metrics.height - metrics.bottomInset - style.margin - h -
                if (portrait) style.bottomBar else 0f).coerceAtLeast(minTop)
            val maxLeft = (metrics.width - style.margin - rightBar - w).coerceAtLeast(style.margin)
            return SceneRect(left.coerceIn(style.margin, maxLeft), requestedTop.coerceIn(minTop, maxTop), w, h)
        }
    }
}
