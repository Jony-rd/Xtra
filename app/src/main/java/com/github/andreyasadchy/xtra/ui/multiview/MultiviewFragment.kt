package com.github.andreyasadchy.xtra.ui.multiview
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutPlan

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.ClipData
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMultiviewBinding
import com.github.andreyasadchy.xtra.databinding.PlayerVolumeBinding
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewPlaybackSnapshot
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.AddMultiviewStreamsSheet
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutManager
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewSlotView
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewStreamDeckDialog
import com.github.andreyasadchy.xtra.ui.player.SleepTimerDialog
import com.github.andreyasadchy.xtra.ui.player.PlayerSwipeBrightnessViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerSwipeGestureController
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MultiviewFragment : Fragment(R.layout.fragment_multiview) {
    private var _binding: FragmentMultiviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MultiviewViewModel by activityViewModels { MultiviewViewModel.MultiviewViewModelFactory }
    private val slotViews = linkedMapOf<String, MultiviewSlotView>()
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable {
        if (controlsLockCount > 0) return@Runnable
        setControlsOverlayVisible(false)
        slotViews.values.forEach { it.setControlsVisible(false) }
    }
    private var latestState = MultiviewSessionState()
    private var latestPlayback: Map<String, MultiviewPlaybackSnapshot> = emptyMap()
    private var renderedChatKey: String? = null
    private var renderedLayoutKey: String? = null
    private var previousNavBarVisibility = View.VISIBLE
    private var controlsLockCount = 0
    private var suppressBackgroundOnNextStop = false
    private var previousCutoutMode: Int? = null
    private var lastImeBottom = 0
    private var lastRootWidth = 0
    private var lastRootHeight = 0
    private var pipEnteredFromPortrait = false

    // Brightness/volume swipe, main stream only. Same shared settings and
    // brightness state as the single-stream player (activityViewModels).
    private val swipeBrightnessViewModel: PlayerSwipeBrightnessViewModel by activityViewModels()
    private var swipeGestureController: PlayerSwipeGestureController? = null
    private var swipeControllerIdentity: String? = null

    // Toolbar buttons meant to hold your own artwork: full button = full
    // image, no padding. Add a button's id here to make it swap-only too.
    private val CUSTOM_ICON_BUTTON_IDS = setOf(
        R.id.backButton,
        R.id.orientationButton,
        R.id.pipButton,
        R.id.chatButton,
        R.id.seekToLiveButton,
        R.id.streamDeckButton,
        R.id.fillButton,
        R.id.addStreamButton,
        R.id.layoutButton,
    )
    private var previousRequestedOrientation: Int? = null

    private val bindingOrNull: FragmentMultiviewBinding?
        get() = _binding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMultiviewBinding.bind(view)

        requireActivity().findViewById<View>(R.id.navBarContainer)?.let { navBar ->
            previousNavBarVisibility = navBar.visibility
            navBar.visibility = View.GONE
        }

                // Full-immersive multiview: never pad for system bars or the display
        // cutout — the streams own the whole screen. Only the IME may push
        // content up so the chat input stays visible while typing.
        ViewCompat.setOnApplyWindowInsetsListener(binding.multiviewRoot) { root, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.updatePadding(top = 0, bottom = ime.bottom, left = 0, right = 0)
            if (ime.bottom != lastImeBottom) {
                lastImeBottom = ime.bottom
                root.post { if (_binding != null) updateOrientationLayout() }
            }
            insets
        }

        // Portrait heights depend on the real screen width/height, so redo the
        // sizing whenever the root actually changes size (rotation, split screen).
        binding.multiviewRoot.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width != lastRootWidth || height != lastRootHeight) {
                lastRootWidth = width
                lastRootHeight = height
                v.post { if (_binding != null) updateOrientationLayout() }
            }
        }

                enterImmersiveMode()

        binding.backButton.setOnClickListener { findNavController().popBackStack() }
        binding.orientationButton.setOnClickListener { toggleMultiviewOrientation() }
        binding.addStreamButton.setOnClickListener { showAddStreamSheet() }
        binding.chatButton.setOnClickListener { toggleChat() }
        binding.layoutButton.setOnClickListener { toggleLayoutMode() }
        binding.fillButton.setOnClickListener { viewModel.setFillVideo(!latestState.fillVideo) }
        binding.pipButton.setOnClickListener { (activity as? MainActivity)?.launchFloatingWindowOrPip() }
        binding.seekToLiveButton.setOnClickListener { seekAllToLive() }
        binding.streamDeckButton.setOnClickListener { showStreamDeckPanel() }
        binding.chatHeader.apply {
            minimumHeight = dp(CHAT_HEADER_DP)
            updatePadding(top = 0, bottom = 0)
        }

        childFragmentManager.setFragmentResultListener(
            AddMultiviewStreamsSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            unlockControls()
            result.parcelableArrayList<Stream>(AddMultiviewStreamsSheet.RESULT_STREAMS)?.let(viewModel::addStreams)
        }
        childFragmentManager.setFragmentResultListener(
            AddMultiviewStreamsSheet.DISMISSED_KEY,
            viewLifecycleOwner,
        ) { _, _ -> unlockControls() }

        val initialStreams = arguments?.parcelableArrayList<Stream>(ARG_STREAMS)
        val initialStream = arguments?.parcelable<Stream>(ARG_STREAM)
        val streamsToInit = if (!initialStreams.isNullOrEmpty()) initialStreams else listOfNotNull(initialStream)
        if (streamsToInit.isNotEmpty()) {
            viewModel.initialize(streamsToInit.firstOrNull())
        }
        streamsToInit.forEach { stream ->
            val identity = MultiviewSessionReducer.stableIdentity(stream)
            if (identity != null && viewModel.state.value.streams.none {
                    MultiviewSessionReducer.stableIdentity(it).equals(identity, true)
                }
            ) {
                if (viewModel.state.value.streams.size >= 4) {
                    val targetIdentity = viewModel.state.value.identities.lastOrNull()
                    if (targetIdentity != null) {
                        viewModel.replaceStream(targetIdentity, stream)
                    }
                } else {
                    viewModel.addStreams(listOf(stream))
                }
            }
        }
        viewModel.startRaidMonitoring()
        updateOrientationLayout()
        binding.multiviewRoot.doOnLayout { updateOrientationLayout() }
        revealControls()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collectLatest { state ->
                        latestState = state
                        render()
                    }
                }
                launch {
                    viewModel.playback.collectLatest { playback ->
                        latestPlayback = playback
                        render()
                    }
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (latestState.focusedIdentity != null) {
                        viewModel.setFocus(null)
                        revealControls()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        if ((activity as? MainActivity)?.playerFragment != null) return
        enterImmersiveMode()
        (activity as? MainActivity)?.prepareMultiviewPictureInPicture()
        viewModel.onStart()
    }

    override fun onStop() {
        exitImmersiveMode()
        if ((activity as? MainActivity)?.playerFragment != null) {
            suppressBackgroundOnNextStop = false
            super.onStop()
            return
        }
        val inPictureInPicture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            requireActivity().isInPictureInPictureMode
        val allowBackground = !suppressBackgroundOnNextStop
        suppressBackgroundOnNextStop = false
        viewModel.onStop(allowBackground = allowBackground, inPictureInPicture = inPictureInPicture)
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_binding != null) {
            requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = View.GONE
            enterImmersiveMode()
            binding.root.post {
                if (_binding != null) {
                    requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = View.GONE
                    enterImmersiveMode()
                }
            }
            updateOrientationLayout()
            render()
        }
    }

    private fun enterImmersiveMode() {
        val mainActivity = activity as? MainActivity ?: return
        val window = mainActivity.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (previousCutoutMode == null) {
                previousCutoutMode = window.attributes.layoutInDisplayCutoutMode
            }
            // SHORT_EDGES lets content render into the camera cutout area,
            // which removes the black bar beside the camera in landscape.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        // Re-dispatch insets so the activity clears the navHostFragment
        // cutout margins while multiview is open.
        window.decorView.requestApplyInsets()
    }

    private fun exitImmersiveMode() {
        val mainActivity = activity ?: return
        val window = mainActivity.window
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        val cutoutMode = previousCutoutMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cutoutMode != null) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = cutoutMode
            }
            previousCutoutMode = null
        }
        window.decorView.requestApplyInsets()
    }

    override fun onDestroyView() {
        viewModel.stopRaidMonitoring()
        swipeGestureController?.release(restoreBrightness = true)
        swipeGestureController = null
        swipeControllerIdentity = null
        previousRequestedOrientation?.let { requireActivity().requestedOrientation = it }
        previousRequestedOrientation = null
        controlsHandler.removeCallbacks(hideControls)
        controlsLockCount = 0
        slotViews.forEach { (identity, slotView) ->
            viewModel.playbackCoordinator.detach(identity, slotView.playerView)
            (slotView.parent as? ViewGroup)?.removeView(slotView)
        }
        slotViews.clear()
        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .forEach(::releaseChatFragment)
        renderedChatKey = null
        renderedLayoutKey = null
        (activity as? MainActivity)?.clearMultiviewPictureInPicture()
        requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = previousNavBarVisibility
        exitImmersiveMode()
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val binding = _binding ?: return
        val state = latestState
        val desired = state.streams.mapNotNull { stream ->
            MultiviewSessionReducer.stableIdentity(stream)?.let { it to stream }
        }.toMap()

        slotViews.keys.toList().filterNot(desired::containsKey).forEach { identity ->
            slotViews.remove(identity)?.let { slotView ->
                viewModel.playbackCoordinator.detach(identity, slotView.playerView)
                (slotView.parent as? ViewGroup)?.removeView(slotView)
            }
        }
        desired.forEach { (identity, stream) ->
            if (identity !in slotViews) {
                slotViews[identity] = createSlotView(identity)
            }
            slotViews.getValue(identity).bind(
                identity = identity,
                stream = stream,
                snapshot = latestPlayback[identity],
                audioVolume = viewModel.audioVolume(identity),
                focused = identity.equals(state.focusedIdentity, true),
                fillVideo = state.fillVideo,
            )
        }
        updateMainStreamSwipeControls(state)

        viewModel.playbackCoordinator.sync(
            streams = state.streams,
            activeIdentity = state.activeIdentity,
            focusedIdentity = state.focusedIdentity,
            qualityMode = state.qualityMode,
            qualityOverrides = state.qualityOverrides,
            audioVolumes = state.audioVolumes,
        )
        applyLayout(state)
        updateOrientationLayout()
        updateToolbar(state)
        updateChat(state)
        binding.videoGrid.doOnLayout { renderTileBounds() }
    }

    /**
     * Attaches brightness/volume swipe to whichever stream currently counts as
     * "main" (focused, else audio-active, else the first stream) and detaches
     * it from anywhere else. Skips the work entirely when the main stream
     * hasn't changed, so this is safe to call on every render().
     */
    private fun updateMainStreamSwipeControls(state: MultiviewSessionState) {
        if (_binding == null) return
        if (swipeGestureController == null) {
            val controller = PlayerSwipeGestureController(
                context = requireContext(),
                window = requireActivity().window,
                brightness = swipeBrightnessViewModel,
                host = binding.multiviewContent,
                getSpeed = { null },
                setSpeed = { },
            )
            swipeGestureController = controller
        }
        slotViews.values.forEach { it.swipeController = swipeGestureController }
    }

    /**
     * Forces multiview into whichever orientation it isn't currently in, ignoring
     * the phone's own rotation-lock setting (same thing video apps do). Uses the
     * "sensor" variants so the phone can still flip between the two landscape
     * directions (or upright/upside-down portrait) once forced into that plane.
     * Whatever the orientation was before the first tap is restored when you leave
     * multiview, so the rest of the app goes back to normal.
     */
    private fun toggleMultiviewOrientation() {
        val activity = requireActivity()
        if (previousRequestedOrientation == null) {
            previousRequestedOrientation = activity.requestedOrientation
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        activity.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        binding.root.postDelayed({
            if (_binding != null) {
                requireActivity().findViewById<View>(R.id.navBarContainer)?.visibility = View.GONE
                enterImmersiveMode()
            }
        }, 150)
    }

    private fun createSlotView(identity: String): MultiviewSlotView {
        return MultiviewSlotView(requireContext()).apply {
            onTap = {
                viewModel.setActive(identity)
                if (!latestState.combinedChat && (latestState.chatVisible || isTwoStreamLandscapeChatOff())) {
                    // keep the toggle as it is (on or off), only switch whose chat is shown
                    viewModel.setChat(latestState.chatVisible, combined = false, identity = identity)
                }
                revealControls(this)
            }
            onDoubleTap = {
                val unfocus = latestState.focusedIdentity.equals(identity, true)
                viewModel.setFocus(if (unfocus) null else identity)
                if (!unfocus) followChatInTwoStreamLandscape(identity)
                revealControls(this)
            }
            onLongPress = {
                revealControls(this)
                showSlotMenu(this)
            }
            onOverflow = {
                revealControls(this)
                showSlotMenu(this)
            }
            onAudioClick = {
                viewModel.toggleAudio(identity)
                revealControls(this)
            }
            onRemove = {
                viewModel.remove(identity)
            }
            onRetry = { viewModel.playbackCoordinator.retry(identity) }
        }
    }

    private var latestLayoutPlan: MultiviewLayoutPlan = MultiviewLayoutPlan(
        placements = emptyList(),
        portraitHeightWidthRatio = 9f / 16f,
    )

    private fun applyLayout(state: MultiviewSessionState) {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (activity?.isInPictureInPictureMode == true)
        val landscape = if (inPip) !pipEnteredFromPortrait else resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val plan = MultiviewLayoutManager.plan(
            identities = state.identities,
            activeIdentity = state.activeIdentity,
            mode = state.layoutMode,
            focusedIdentity = state.focusedIdentity,
            landscape = landscape,
            chatVisible = state.chatVisible,
            fillVideo = state.fillVideo,
        )
        latestLayoutPlan = plan

        val layoutKey = plan.placements.joinToString("|") {
            "${it.identity}:${it.left},${it.top},${it.right},${it.bottom}"
        }
        if (layoutKey == renderedLayoutKey && binding.videoGrid.childCount == plan.placements.size) {
            renderTileBounds()
            return
        }

        renderedLayoutKey = layoutKey
        binding.videoGrid.removeAllViews()

        plan.placements.forEach { placement ->
            val slotView = slotViews[placement.identity] ?: return@forEach
            binding.videoGrid.addView(
                slotView,
                FrameLayout.LayoutParams(0, 0),
            )
            slotView.dragHandle.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val clipData = ClipData.newPlainText("slot_identity", placement.identity)
                    val shadow = View.DragShadowBuilder(slotView)
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        slotView.startDragAndDrop(clipData, shadow, placement.identity, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        slotView.startDrag(clipData, shadow, placement.identity, 0)
                    }
                    true
                } else false
            }
            slotView.setOnDragListener { _, dragEvent ->
                when (dragEvent.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        slotView.setDragHighlight(true)
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        slotView.setDragHighlight(false)
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        slotView.setDragHighlight(false)
                        val draggedIdentity = dragEvent.localState as? String
                            ?: dragEvent.clipData?.getItemAt(0)?.text?.toString()
                        if (draggedIdentity != null && draggedIdentity != placement.identity) {
                            val targetIndex = latestState.identities.indexOf(placement.identity)
                            if (targetIndex >= 0) {
                                viewModel.reorder(draggedIdentity, targetIndex)
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        slotView.setDragHighlight(false)
                        true
                    }
                    else -> true
                }
            }
            slotView.doOnLayout {
                viewModel.playbackCoordinator.attach(placement.identity, slotView.playerView)
                viewModel.playbackCoordinator.updateTileBounds(
                    placement.identity,
                    slotView.width,
                    slotView.height,
                )
            }
        }

        binding.videoGrid.doOnLayout { renderTileBounds() }
    }

    private fun renderTileBounds() {
        val parentWidth = binding.videoGrid.width
        val parentHeight = binding.videoGrid.height
        if (parentWidth <= 0 || parentHeight <= 0) return

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val specialTwoStream = isTwoStreamLandscapeChatOff()
        val placements = latestLayoutPlan.placements
        val firstIdentity = placements.firstOrNull()?.identity

        // Landscape: the main stream and the right-hand stack must never cross the
        // column split, whatever the plan says. Chat on keeps exactly 20% of the
        // screen for the stack (25% of the 80% video area); chat off keeps 20% of
        // the full screen. 2 streams with chat on and Fill split the video 50/50.
        val sideFraction = when {
            !landscape || specialTwoStream || placements.size < 2 -> null
            placements.size == 2 && latestState.chatVisible && latestState.fillVideo -> 0.5f
            latestState.chatVisible -> 0.25f
            else -> LANDSCAPE_SIDE_WEIGHT
        }
        val sideStart = sideFraction?.let { ((1f - it) * parentWidth).roundToInt() }

        // Portrait "line" layout: a straight stack of full-width rows.
        val portraitStack = !landscape && placements.size > 1 &&
            placements.all { it.left == 0f && it.right == 1f }

        placements.forEachIndexed { index, placement ->
            val slotView = slotViews[placement.identity] ?: return@forEachIndexed

            var left = (placement.left * parentWidth).roundToInt()
            var top = (placement.top * parentHeight).roundToInt()
            var right = (placement.right * parentWidth).roundToInt()
            var bottom = (placement.bottom * parentHeight).roundToInt()
            if (specialTwoStream && placement.identity != firstIdentity) {
                // 2 streams, landscape, chat off: the small stream is exactly as tall
                // as 16:9 needs, flush top-right. Chat takes everything under it.
                bottom = top + specialSecondTileHeight(right - left, parentHeight)
            }
            if (sideStart != null) {
                if (placement.identity == firstIdentity) {
                    left = 0
                    right = sideStart
                } else {
                    left = sideStart
                    right = parentWidth
                }
            }
            if (portraitStack) {
                // Every stream gets exactly the same row height, so Fill mode can
                // never leave one tile fat and another one thin.
                top = (index.toFloat() * parentHeight / placements.size).roundToInt()
                bottom = ((index + 1).toFloat() * parentHeight / placements.size).roundToInt()
            }

            val width = (right - left).coerceAtLeast(1)
            val height = (bottom - top).coerceAtLeast(1)

            val current = slotView.layoutParams as? FrameLayout.LayoutParams
            if (current == null ||
                current.width != width ||
                current.height != height ||
                current.leftMargin != left ||
                current.topMargin != top
            ) {
                slotView.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                    leftMargin = left
                    topMargin = top
                }
            }

            if (slotView.width > 0 && slotView.height > 0) {
                viewModel.playbackCoordinator.updateTileBounds(
                    placement.identity,
                    slotView.width,
                    slotView.height,
                )
            }
        }
    }

    private fun isTwoStreamLandscapeChatOff(state: MultiviewSessionState = latestState): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            !state.chatVisible &&
            state.streams.size == 2
    }

    /** 2 streams, landscape, chat toggle off: chat still shows, so keep it on the stream the user picked. */
    private fun followChatInTwoStreamLandscape(identity: String) {
        if (isTwoStreamLandscapeChatOff() && !latestState.combinedChat) {
            viewModel.setChat(false, combined = false, identity = identity)
        }
    }

    private fun seekAllToLive() {
        viewModel.playbackCoordinator.seekAllToLive()
        binding.seekToLiveButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        binding.seekToLiveButton.setColorFilter(android.graphics.Color.parseColor("#00F5D4"))
        binding.seekToLiveButton.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150L).withEndAction {
            binding.seekToLiveButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150L).withEndAction {
                binding.seekToLiveButton.clearColorFilter()
            }.start()
        }.start()
        revealControls()
    }

    private fun updateToolbar(state: MultiviewSessionState) {
        val active = state.streams.firstOrNull { stream ->
            MultiviewSessionReducer.stableIdentity(stream).equals(state.activeIdentity, true)
        }
        val specialTwoStreamChat = isTwoStreamLandscapeChatOff(state)

        binding.addStreamButton.isVisible = state.streams.size < MAX_STREAMS
        binding.pipButton.isVisible = (activity as? MainActivity)?.canMinimizeMultiview() == true &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && requireActivity().isInPictureInPictureMode)
        binding.chatButton.isVisible = !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && active != null
        binding.seekToLiveButton.isVisible = state.streams.isNotEmpty()
        binding.streamDeckButton.isVisible = state.streams.isNotEmpty()
        binding.fillButton.setImageResource(R.drawable.ic_hw_spiderweb_fill)
        binding.fillButton.contentDescription = getString(
            if (state.fillVideo) R.string.multiview_fit else R.string.multiview_fill
        )
        binding.chatContainer.isVisible = state.chatVisible || specialTwoStreamChat
        val identity = state.chatIdentity ?: state.activeIdentity
        binding.chatTitle.text =
            identity?.let { chatIdentity ->
                state.streams.firstOrNull {
                    MultiviewSessionReducer.stableIdentity(it).equals(chatIdentity, true)
                }
            }?.let(::displayName) ?: getString(R.string.multiview_chat)
        binding.chatButton.contentDescription = getString(
            if (state.chatVisible) R.string.multiview_hide_chat else R.string.multiview_chat,
        )
    }

    private fun updateChat(state: MultiviewSessionState) {
        val specialTwoStreamChat = isTwoStreamLandscapeChatOff(state)
        val showChat = state.chatVisible || specialTwoStreamChat

        if (!showChat) {
            if (renderedChatKey == null && childFragmentManager.findFragmentById(R.id.chatContent) == null) {
                return
            }
            renderedChatKey = null
            childFragmentManager.fragments
                .filter { it.id == R.id.chatContent }
                .forEach { fragment ->
                    releaseChatFragment(fragment)
                }
            childFragmentManager.findFragmentById(R.id.chatContent)?.let { current ->
                childFragmentManager.beginTransaction()
                    .remove(current)
                    .commitNowAllowingStateLoss()
            }
            return
        }

        val chatIdentity = state.chatIdentity ?: state.activeIdentity
        val singleStream = state.streams.firstOrNull {
            MultiviewSessionReducer.stableIdentity(it).equals(chatIdentity, true)
        } ?: run {
            renderedChatKey = null
            return
        }

        val key = "single:$chatIdentity"
        val existing = childFragmentManager.findFragmentById(R.id.chatContent)
        val targetTag = "$SINGLE_CHAT_TAG$chatIdentity"
        if (key == renderedChatKey && existing != null && existing.tag == targetTag) return
        renderedChatKey = key

        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .forEach { fragment ->
                releaseChatFragment(fragment)
            }

        val fragment = ChatFragment.newInstance(
            singleStream.channelId,
            singleStream.channelLogin,
            displayName(singleStream),
            singleStream.id,
        )
        childFragmentManager.beginTransaction()
            .replace(R.id.chatContent, fragment, targetTag)
            .commitNowAllowingStateLoss()
    }

    private fun releaseChatFragment(fragment: Fragment) {
        if (fragment is ChatFragment) {
            // Hiding a Fragment does not call onStop. Explicitly disconnect
            // before removing it so switching A -> B -> combined cannot leave
            // hidden IRC sessions running beside the combined sessions.
            fragment.disconnect()
        }
    }

    private fun toggleChat() {
        if (latestState.chatVisible) {
            viewModel.setChat(false)
        } else {
            val identity = latestState.activeIdentity ?: latestState.identities.firstOrNull() ?: return
            viewModel.setChat(true, combined = false, identity = identity)
        }
        revealControls()
    }

    private fun showAddStreamSheet() {
        val freeSlots = MAX_STREAMS - latestState.streams.size
        if (freeSlots <= 0) {
            Toast.makeText(requireContext(), R.string.multiview_max_streams, Toast.LENGTH_SHORT).show()
            return
        }
        revealControls()
        lockControls()
        AddMultiviewStreamsSheet.newInstance(latestState.identities, freeSlots)
            .show(childFragmentManager, AddMultiviewStreamsSheet.TAG)
    }

    private fun toggleLayoutMode() {
        val state = latestState
        val streamCount = state.streams.size
        val newMode = when {
            streamCount >= 4 -> {
                when (state.layoutMode) {
                    MultiviewLayoutMode.FOCUS -> MultiviewLayoutMode.TIERED
                    MultiviewLayoutMode.TIERED -> MultiviewLayoutMode.AUTO
                    else -> MultiviewLayoutMode.FOCUS
                }
            }
            else -> {
                if (state.layoutMode == MultiviewLayoutMode.FOCUS) MultiviewLayoutMode.AUTO else MultiviewLayoutMode.FOCUS
            }
        }
        if (newMode == MultiviewLayoutMode.FOCUS && state.focusedIdentity == null) {
            viewModel.setFocus(state.activeIdentity ?: state.identities.firstOrNull())
        }
        viewModel.setLayoutMode(newMode)
    }

    private fun showLayoutMenu() {
        revealControls()
        lockControls()
        val modes = MultiviewLayoutMode.entries
        val labels = modes.map { mode -> getString(mode.labelRes()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_layout)
            .setSingleChoiceItems(labels, modes.indexOf(latestState.layoutMode)) { dialog, which ->
                val selected = modes[which]
                if (selected == MultiviewLayoutMode.FOCUS && latestState.focusedIdentity == null) {
                    viewModel.setFocus(latestState.activeIdentity ?: latestState.identities.firstOrNull())
                }
                viewModel.setLayoutMode(selected)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun computeStreamDeckAnchorBottomPx(): Int? {
        val binding = _binding ?: return null
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return null
        val anchorView = when {
            binding.chatContainer.isVisible && binding.chatHeader.isShown -> binding.chatHeader
            !latestState.fillVideo && binding.videoGrid.isShown -> binding.videoGrid
            else -> return null
        }
        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val bottom = loc[1] + anchorView.height
        return bottom.takeIf { it > 0 }
    }

    private fun showStreamDeckPanel() {
        val context = context ?: return
        if (latestState.streams.isEmpty()) return
        lockControls()
        val isFloatingWindow = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInMultiWindowMode == true) ||
            resources.configuration.screenHeightDp < 520
        val anchorBottomPx = computeStreamDeckAnchorBottomPx()
        MultiviewStreamDeckDialog.show(
            context = context,
            isFloatingWindow = isFloatingWindow,
            anchorBottomPx = anchorBottomPx,
            stateProvider = { latestState },
            playbackProvider = { latestPlayback },
            viewModel = viewModel,
            onOpenSleepTimer = {
                SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0)
                    .show(childFragmentManager, null)
            },
            onOpenNormalPlayer = ::openNormalPlayer,
            onUnlockControls = { unlockControls() },
        )
    }

    private fun showSlotQualityMenu(identity: String, slot: MultiviewSlotView) {
        val stream = slot.stream ?: return
        val autoLabel = getString(R.string.multiview_quality_auto)
        val qualities = listOf(autoLabel) + latestPlayback[identity]
            ?.availableQualities.orEmpty()
        lockControls()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.multiview_quality_for, displayName(stream)))
            .setItems(qualities.toTypedArray()) { _, which ->
                viewModel.setQualityOverride(identity, qualities[which].takeUnless { it == autoLabel })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls(slot) }
            .show()
    }

    private fun showSlotMenu(slot: MultiviewSlotView) {
        val identity = slot.identity
        val stream = slot.stream ?: return
        lockControls()
        PopupMenu(requireContext(), slot.actionsAnchor, Gravity.END).apply {
            menu.add(R.string.multiview_quality).setOnMenuItemClickListener {
                showSlotQualityMenu(identity, slot)
                true
            }
            menu.add(R.string.multiview_volume).setOnMenuItemClickListener {
                showVolumeDialog(identity, stream)
                true
            }
            menu.add(
                if (viewModel.audioVolume(identity) > 0f) R.string.multiview_mute
                else R.string.multiview_unmute,
            ).setOnMenuItemClickListener {
                viewModel.toggleAudio(identity)
                true
            }
            menu.add(R.string.multiview_open_player).setOnMenuItemClickListener {
                openNormalPlayer(identity)
                true
            }
            menu.add(R.string.multiview_remove_stream).setOnMenuItemClickListener {
                viewModel.remove(identity)
                true
            }
            setOnDismissListener { unlockControls(slot) }
            show()
        }
    }

    private fun showVolumeDialog(identity: String, stream: Stream) {
        lockControls()
        val volumeBinding = PlayerVolumeBinding.inflate(layoutInflater)
        var currentVolume = viewModel.audioVolume(identity)
        fun renderVolume(volume: Float) {
            currentVolume = volume.coerceIn(0f, 1f)
            val percent = (currentVolume * 100f).toInt()
            volumeBinding.volumeText.text = percent.toString()
            volumeBinding.volumeMute.setImageResource(
                if (currentVolume == 0f) R.drawable.baseline_volume_off_black_24
                else R.drawable.baseline_volume_up_black_24,
            )
        }

        volumeBinding.volumeBar.value = currentVolume * 100f
        renderVolume(currentVolume)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.multiview_volume_for, displayName(stream)))
            .setView(volumeBinding.root)
            .setNegativeButton(R.string.multiview_close, null)
            .create()
        volumeBinding.volumeBar.addOnChangeListener { _, value, _ ->
            renderVolume(value / 100f)
            viewModel.setAudioVolume(identity, currentVolume, persist = false)
        }
        volumeBinding.volumeBar.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                viewModel.persistSession()
            }
        })
        volumeBinding.volumeMute.setOnClickListener {
            val next = if (currentVolume > 0f) {
                0f
            } else {
                viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
            }
            volumeBinding.volumeBar.value = next * 100f
            renderVolume(next)
            viewModel.setAudioVolume(identity, next)
        }
        dialog.setOnDismissListener {
            viewModel.persistSession()
            unlockControls()
        }
        dialog.show()
    }

    private fun openNormalPlayer(identity: String) {
        val stream = latestState.streams.firstOrNull {
            MultiviewSessionReducer.stableIdentity(it).equals(identity, true)
        } ?: return
        (activity as? MainActivity)?.let { mainActivity ->
            pauseForExternalPlayer()
            mainActivity.startStream(stream)
        }
    }

    private fun revealControls(slot: MultiviewSlotView? = null) {
        val binding = _binding ?: return
        setControlsOverlayVisible(true)
        slotViews.values.forEach { it.setControlsVisible(it === slot) }
        controlsHandler.removeCallbacks(hideControls)
        if (controlsLockCount == 0) {
            controlsHandler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS)
        }
    }

    private fun setControlsOverlayVisible(visible: Boolean) {
        val binding = _binding ?: return
        if (binding.controlsOverlay.parent === binding.chatHeader) {
            binding.controlsOverlay.isVisible = true
        } else {
            binding.controlsOverlay.isVisible = visible
        }

        // The toolbar is an overlay. Do not reserve vertical space for it:
        // the compact layouts are intentionally edge-to-edge.
        binding.videoGrid.updatePadding(0, 0, 0, 0)
        binding.multiviewContent.doOnLayout { renderTileBounds() }
    }

    private fun lockControls() {
        controlsLockCount++
        revealControls()
    }

    private fun unlockControls(slot: MultiviewSlotView? = null) {
        controlsLockCount = (controlsLockCount - 1).coerceAtLeast(0)
        if (controlsLockCount == 0) revealControls(slot)
    }

    private fun moveControlsToChatHeader() {
        if (binding.controlsOverlay.parent !== binding.chatHeader) {
            (binding.controlsOverlay.parent as? ViewGroup)?.removeView(binding.controlsOverlay)
            binding.chatHeader.addView(
                binding.controlsOverlay,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(CHAT_HEADER_DP),
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }

        binding.controlsOverlay.background = null
        binding.controlsOverlay.minimumHeight = dp(CHAT_HEADER_DP)
        setToolbarButtonSize(CHAT_HEADER_DP)
        binding.controlsOverlay.isVisible = true
    }

    private fun moveControlsToVideo() {
        if (binding.controlsOverlay.parent !== binding.multiviewContent) {
            (binding.controlsOverlay.parent as? ViewGroup)?.removeView(binding.controlsOverlay)
            binding.multiviewContent.addView(
                binding.controlsOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(56),
                    Gravity.BOTTOM or Gravity.START,
                ),
            )
        }

        binding.controlsOverlay.background =
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_multiview_toolbar)
        binding.controlsOverlay.minimumHeight = dp(56)
        setToolbarButtonSize(56)
        positionVideoToolbar()
    }

    private fun positionVideoToolbar() {
        binding.controlsOverlay.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(56),
            Gravity.BOTTOM or Gravity.START,
        ).apply {
            setMargins(dp(8), 0, 0, dp(8))
        }
    }

    private fun setToolbarButtonSize(sizeDp: Int) {
        val p = dp(if (sizeDp >= 56) 3 else if (sizeDp >= 40) 2 else 2)
        for (index in 0 until binding.controlsOverlay.childCount) {
            val child = binding.controlsOverlay.getChildAt(index)
            child.layoutParams = child.layoutParams.apply {
                width = dp(sizeDp)
                height = dp(sizeDp)
            }
            child.setPadding(p, p, p, p)
        }
    }

    private fun updateOrientationLayout() {
        val binding = _binding ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && requireActivity().isInPictureInPictureMode) {
            binding.chatContainer.isVisible = false
            binding.specialRightColumn.isVisible = false
            binding.controlsOverlay.visibility = View.GONE
            binding.multiviewRoot.orientation = if (pipEnteredFromPortrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 1f
            }
            binding.videoGrid.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            renderTileBounds()
            return
        }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val specialTwoStreamChat = isTwoStreamLandscapeChatOff(latestState)

        fun moveChatToRoot() {
            if (binding.chatContainer.parent === binding.multiviewRoot) return
            (binding.chatContainer.parent as? ViewGroup)?.removeView(binding.chatContainer)
            binding.multiviewRoot.addView(
                binding.chatContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ),
            )
        }

        fun moveChatToSpecialColumn() {
            if (binding.chatContainer.parent === binding.specialRightColumn) return
            (binding.chatContainer.parent as? ViewGroup)?.removeView(binding.chatContainer)
            binding.specialRightColumn.addView(
                binding.chatContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    Gravity.BOTTOM,
                ),
            )
        }

        if (landscape) {
            binding.multiviewRoot.orientation = LinearLayout.HORIZONTAL

            if (specialTwoStreamChat) {
                moveChatToSpecialColumn()

                (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 0f
                }?.also { binding.multiviewContent.layoutParams = it }

                binding.chatContainer.isVisible = true
                binding.specialRightColumn.isVisible = true
                moveControlsToVideo()

                binding.multiviewContent.doOnLayout {
                    val totalWidth = binding.multiviewContent.width
                    val totalHeight = binding.multiviewContent.height
                    if (totalWidth <= 0 || totalHeight <= 0) return@doOnLayout

                    val rightWidth = (totalWidth * LANDSCAPE_SIDE_WEIGHT).roundToInt()

                    // The video grid still uses the full screen here. Its
                    // rightmost 20% contains the second stream; the transparent
                    // special column overlays only the lower half with chat.
                    binding.videoGrid.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )

                    binding.specialRightColumn.layoutParams = FrameLayout.LayoutParams(
                        rightWidth,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.END,
                    )

                    val secondTileHeight = specialSecondTileHeight(
                        totalWidth - (LANDSCAPE_MAIN_FRACTION * totalWidth).roundToInt(),
                        totalHeight,
                    )
                    binding.chatContainer.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        totalHeight - secondTileHeight,
                        Gravity.BOTTOM,
                    )

                    positionVideoToolbar()
                    renderTileBounds()
                }
            } else {
                moveChatToRoot()
                binding.specialRightColumn.isVisible = false

                (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    if (latestState.chatVisible) {
                        width = 0
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        weight = 1f
                    } else {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        weight = 0f
                    }
                }?.also { binding.multiviewContent.layoutParams = it }

                (binding.chatContainer.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    if (latestState.chatVisible) {
                        width = 0
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        weight = LANDSCAPE_CHAT_WEIGHT
                    } else {
                        width = 0
                        height = 0
                        weight = 0f
                    }
                }?.also { binding.chatContainer.layoutParams = it }

                binding.videoGrid.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                moveControlsToVideo()
                binding.multiviewContent.doOnLayout { renderTileBounds() }
            }
        } else {
            moveChatToRoot()
            binding.specialRightColumn.isVisible = false
            binding.multiviewRoot.orientation = LinearLayout.VERTICAL

            // Compact portrait: the video area is only as tall as the streams need
            // (16:9 tiles, no letterbox). Everything left over goes to chat.
            val root = binding.multiviewRoot
            val availableHeight = root.height - root.paddingTop - root.paddingBottom
            val chatOn = latestState.chatVisible
            val videoHeight = portraitVideoHeight(root.width, availableHeight, chatOn)

            if (chatOn) {
                setLinearParams(binding.multiviewContent, ViewGroup.LayoutParams.MATCH_PARENT, videoHeight, 0f)
                setLinearParams(binding.chatContainer, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                setFrameParams(binding.videoGrid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                moveControlsToChatHeader()
            } else {
                setLinearParams(binding.multiviewContent, ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                setLinearParams(binding.chatContainer, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f)
                // streams stuck to the very top, nothing above them
                val gridH = if (latestState.fillVideo) ViewGroup.LayoutParams.MATCH_PARENT else videoHeight
                setFrameParams(binding.videoGrid, ViewGroup.LayoutParams.MATCH_PARENT, gridH)
                moveControlsToVideo()
            }

            binding.multiviewRoot.doOnLayout {
                binding.videoGrid.doOnLayout { renderTileBounds() }
            }
        }
    }

    /** Height (px) the portrait video area needs so every tile is a true 16:9. */
    private fun portraitVideoHeight(width: Int, availableHeight: Int, chatOn: Boolean): Int {
        if (width <= 0 || availableHeight <= 0) return ViewGroup.LayoutParams.MATCH_PARENT
        val wanted = (width * latestLayoutPlan.portraitHeightWidthRatio).roundToInt()
        val maxHeight = if (chatOn) {
            (availableHeight - dp(PORTRAIT_MIN_CHAT_DP)).coerceAtLeast(availableHeight / 3)
        } else {
            availableHeight
        }
        return wanted.coerceIn(1, maxHeight.coerceAtLeast(1))
    }

    /** Height (px) of the small stream in the 2-stream landscape chat-off layout. */
    private fun specialSecondTileHeight(tileWidth: Int, totalHeight: Int): Int {
        val wanted = (tileWidth * 9f / 16f).roundToInt()
        return wanted.coerceIn(1, (totalHeight * 0.6f).roundToInt().coerceAtLeast(1))
    }

    private fun setLinearParams(view: View, width: Int, height: Int, weight: Float) {
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.width != width || params.height != height || params.weight != weight) {
            params.width = width
            params.height = height
            params.weight = weight
            view.layoutParams = params
        }
    }

    private fun setFrameParams(view: View, width: Int, height: Int) {
        val gravity = Gravity.TOP or Gravity.START
        val params = view.layoutParams as? FrameLayout.LayoutParams
        if (params == null || params.width != width || params.height != height || params.gravity != gravity) {
            view.layoutParams = FrameLayout.LayoutParams(width, height, gravity)
        }
    }

    private fun displayName(stream: Stream): String {
        return stream.channelName?.takeIf { it.isNotBlank() }
            ?: stream.channelLogin?.takeIf { it.isNotBlank() }
            ?: stream.id.orEmpty()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    suspend fun resolveManualStream(login: String): Stream? = viewModel.resolveLiveStream(login)

    fun updateViewingMetadata(
        channelId: String?,
        channelLogin: String?,
        title: String?,
        categoryId: String?,
        categoryName: String?,
    ) {
        viewModel.playbackCoordinator.updateStreamMetadataForChannel(
            channelId = channelId,
            channelLogin = channelLogin,
            title = title,
            categoryId = categoryId,
            categoryName = categoryName,
        )
    }

    fun pauseForExternalPlayer() {
        suppressBackgroundOnNextStop = true
        viewModel.onStop(allowBackground = false)
    }
    fun resumeAfterExternalPlayer() {
        suppressBackgroundOnNextStop = false
        if (_binding != null) {
            enterImmersiveMode()
            (activity as? MainActivity)?.prepareMultiviewPictureInPicture()
            viewModel.onStart()
        }
    }

    fun calculateGridAspectRatio(): android.util.Rational {
        if (latestState.layoutMode == MultiviewLayoutMode.FOCUS && latestState.streams.size > 1 && pipEnteredFromPortrait) {
            val bottomCount = (latestState.streams.size - 1).coerceAtLeast(1)
            val ratioHeightToWidth = (9f / 16f) * (1f + 1f / bottomCount)
            val widthToHeight = 1f / ratioHeightToWidth
            val clamped = widthToHeight.coerceIn(1f / 2.38f, 2.38f)
            return android.util.Rational((clamped * 1000).toInt(), 1000)
        }
        val vg = _binding?.videoGrid
        val w = vg?.width ?: 0
        val h = vg?.height ?: 0
        if (w > 0 && h > 0) {
            val ratio = w.toFloat() / h.toFloat()
            val clamped = ratio.coerceIn(1f / 2.38f, 2.38f)
            return android.util.Rational((clamped * 1000).toInt(), 1000)
        }
        val streamCount = latestState.streams.size
        return if (streamCount == 1) android.util.Rational(16, 9) else android.util.Rational(1, 1)
    }

    fun getVideoGridRect(): android.graphics.Rect? {
        val vg = _binding?.videoGrid ?: return null
        if (vg.width <= 0 || vg.height <= 0) return null
        val location = IntArray(2)
        vg.getLocationOnScreen(location)
        return android.graphics.Rect(location[0], location[1], location[0] + vg.width, location[1] + vg.height)
    }

    fun prepareForPictureInPicture() {
        if (_binding == null) return
        pipEnteredFromPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        setControlsOverlayVisible(false)
        slotViews.values.forEach { it.setControlsVisible(false) }
        binding.chatContainer.isVisible = false
        binding.controlsOverlay.visibility = View.GONE
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (_binding == null) return
        controlsHandler.removeCallbacks(hideControls)
        controlsLockCount = 0
        if (isInPictureInPictureMode) {
            viewModel.onStart()
            setControlsOverlayVisible(false)
            slotViews.values.forEach { it.setControlsVisible(false) }
            binding.chatContainer.isVisible = false
            binding.controlsOverlay.visibility = View.GONE
        } else {
            pipEnteredFromPortrait = false
            binding.controlsOverlay.visibility = View.VISIBLE
            updateToolbar(latestState)
            updateChat(latestState)
            revealControls()
        }
    }

    companion object {
        const val ARG_STREAM = "multiview_stream"
        const val ARG_STREAMS = "multiview_streams"
        fun arguments(stream: Stream): Bundle = Bundle().apply { putParcelable(ARG_STREAM, stream) }
        fun arguments(streams: List<Stream>): Bundle = Bundle().apply {
            putParcelableArrayList(ARG_STREAMS, ArrayList(streams))
            if (streams.isNotEmpty()) putParcelable(ARG_STREAM, streams.first())
        }
        private const val SINGLE_CHAT_TAG = "multiview_single_chat_"
        private const val MAX_STREAMS = 4
        private const val CONTROLS_TIMEOUT_MS = 4_500L
        private const val LANDSCAPE_CHAT_WEIGHT = 0.25f
        private const val LANDSCAPE_SIDE_WEIGHT = 0.20f
        // Height of the chat header (streamer name, and the toolbar icons in portrait).
        private const val CHAT_HEADER_DP = 34
        // Keeps at least this much chat visible in portrait (e.g. with the keyboard open).
        private const val PORTRAIT_MIN_CHAT_DP = 160
        // Must match mainRight in MultiviewLayoutManager for 2 streams with chat off.
        private const val LANDSCAPE_MAIN_FRACTION = 0.80f

    }
}

private fun MultiviewLayoutMode.labelRes(): Int = when (this) {
    MultiviewLayoutMode.AUTO -> R.string.multiview_layout_auto
    MultiviewLayoutMode.GRID -> R.string.multiview_layout_grid
    MultiviewLayoutMode.FOCUS -> R.string.multiview_layout_focus
    MultiviewLayoutMode.TIERED -> R.string.multiview_layout_tiered
}

private fun MultiviewQualityMode.labelRes(): Int = when (this) {
    MultiviewQualityMode.AUTO -> R.string.multiview_quality_auto
    MultiviewQualityMode.QUALITY_360P -> R.string.multiview_quality_360p
    MultiviewQualityMode.QUALITY_480P -> R.string.multiview_quality_480p
    MultiviewQualityMode.QUALITY_720P -> R.string.multiview_quality_720p
    MultiviewQualityMode.QUALITY_1080P -> R.string.multiview_quality_1080p
}

private inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key)
    }
}

private inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
