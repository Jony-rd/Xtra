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

    private fun showStreamDeckPanel() {
        val context = context ?: return
        if (latestState.streams.isEmpty()) return
        lockControls()

        try {
            var dialog: android.app.Dialog? = null
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val isFloatingWindow = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity?.isInMultiWindowMode == true) ||
                resources.configuration.screenHeightDp < 520
            val isCompactLayout = isLandscape || isFloatingWindow

            // 4-second auto-timeout on inactivity; touch anywhere on the panel resets it
            val autoDismissHandler = Handler(Looper.getMainLooper())
            val dismissRunnable = Runnable {
                try {
                    if (dialog?.isShowing == true) {
                        dialog?.dismiss()
                    }
                } catch (_: Throwable) {}
            }
            fun resetDismissTimer() {
                autoDismissHandler.removeCallbacks(dismissRunnable)
                autoDismissHandler.postDelayed(dismissRunnable, 4000L)
            }

            // 3D tactile keycap: shadow plate under the cap and a face with a lit top
            // edge. Stateful, so the cap visibly sinks and darkens while pressed.
            fun make3DKeycap(
                topColorHex: String,
                bottomColorHex: String,
                shadowColorHex: String,
                strokeColorHex: String,
                cornerRadiusDp: Float = 4.5f,
                shadowDepthDp: Float = 2.5f,
            ): android.graphics.drawable.Drawable {
                fun shade(c: Int, f: Float): Int = android.graphics.Color.rgb(
                    (android.graphics.Color.red(c) * f).toInt().coerceIn(0, 255),
                    (android.graphics.Color.green(c) * f).toInt().coerceIn(0, 255),
                    (android.graphics.Color.blue(c) * f).toInt().coerceIn(0, 255),
                )
                fun build(depthPx: Int, pressed: Boolean): android.graphics.drawable.Drawable {
                    val shadow = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor(shadowColorHex))
                        cornerRadius = dp(cornerRadiusDp).toFloat()
                    }
                    val top = android.graphics.Color.parseColor(topColorHex)
                    val bottom = android.graphics.Color.parseColor(bottomColorHex)
                    val cols = if (pressed) {
                        intArrayOf(shade(top, 0.80f), shade(bottom, 0.80f))
                    } else {
                        intArrayOf(shade(top, 1.22f), top, bottom)
                    }
                    val face = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        cols,
                    ).apply {
                        cornerRadius = dp(cornerRadiusDp).toFloat()
                        setStroke(
                            dp(1),
                            android.graphics.Color.parseColor(if (pressed) "#FFFFFF" else strokeColorHex),
                        )
                    }
                    val layer = android.graphics.drawable.LayerDrawable(arrayOf(shadow, face))
                    layer.setLayerInset(0, 0, depthPx, 0, 0)
                    layer.setLayerInset(1, 0, 0, 0, depthPx)
                    return layer
                }
                return android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), build(0, true))
                    addState(intArrayOf(), build(dp(shadowDepthDp), false))
                }
            }

            // Strict text centering helper to prevent Android's setBackground from offsetting text
            fun centerKeycapText(tv: android.widget.TextView) {
                tv.gravity = Gravity.CENTER
                tv.textAlignment = View.TEXT_ALIGNMENT_CENTER
                tv.includeFontPadding = false
                tv.setPadding(0, 0, 0, dp(1))
            }

            // Soft press feedback for deck controls (standard RippleDrawable)
            fun deckRipple(base: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#66C8FF00")),
                    base,
                    null,
                )

            fun createRivet(): android.widget.TextView = android.widget.TextView(context).apply {
                text = "⨁"
                textSize = 8.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#475069"))
            }

            // Cyberpunk Deck Chassis with optional subtle background artwork
            val bgRes = try {
                resources.getIdentifier("bg_cyber_deck", "drawable", context.packageName)
            } catch (_: Throwable) { 0 }

            val deckBgDrawable = if (bgRes != 0) {
                try {
                    context.getDrawable(bgRes)?.mutate()?.apply {
                        alpha = 85
                    }
                } catch (_: Throwable) { null }
            } else null

            val chassisBase = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    android.graphics.Color.parseColor("#1B0A2F"),
                    android.graphics.Color.parseColor("#0B0918"),
                    android.graphics.Color.parseColor("#062028"),
                ),
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1.4f), android.graphics.Color.parseColor("#FF2E88"))
            }

            val chassis = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pSide = dp(if (isLandscape) 7 else 8)
                val pTop = dp(if (isLandscape) 5 else 6)
                setPadding(pSide, pTop, pSide, pTop)
                background = if (deckBgDrawable != null) {
                    android.graphics.drawable.LayerDrawable(arrayOf(chassisBase, deckBgDrawable))
                } else {
                    chassisBase
                }
                elevation = dp(20).toFloat()
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                    }
                }
                clipToOutline = true
            }

            // Intercepts ALL touches anywhere on the panel to reset the 4-second timer
            val chassisContainer = object : FrameLayout(context) {
                override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                    resetDismissTimer()
                    return super.dispatchTouchEvent(ev)
                }
            }
            chassisContainer.addView(chassis)

            // Top Header: [⨁] [icon] STREAM DECK [STUDIO]  [Avatar] [CIRI]  [⏱️ SLEEP]  [✕]
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(if (isLandscape) 3 else 4), dp(6), dp(if (isLandscape) 3 else 4))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        android.graphics.Color.parseColor("#1B1F2C"),
                        android.graphics.Color.parseColor("#141722"),
                    ),
                ).apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#2E344A"))
                }
            }

            header.addView(createRivet().apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(5)
                }
                layoutParams = lp
            })

            val deckIcon = android.widget.ImageView(context).apply {
                val iconSize = dp(if (isLandscape) 17 else 19)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                setImageResource(R.drawable.ic_hw_stream_deck)
            }
            header.addView(deckIcon)

            val titleView = android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(5)
                }
                text = "STREAM DECK"
                setTextColor(android.graphics.Color.WHITE)
                textSize = if (isLandscape) 11.5f else 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            header.addView(titleView)

            val proBadge = android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5)
                }
                text = "STUDIO"
                setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                textSize = if (isLandscape) 8.5f else 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            header.addView(proBadge)

            // Power LED: cyan glowing hardware indicator
            header.addView(android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply {
                    marginEnd = dp(6)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#00E5FF"))
                }
            })

            // Portrait slot: Cyberpunk Ciri framed holo-ID avatar
            val portraitRes = try {
                resources.getIdentifier("sd_portrait", "drawable", context.packageName)
            } catch (_: Throwable) { 0 }

            if (portraitRes != 0) {
                val portraitAvatar = android.widget.ImageView(context).apply {
                    val pSize = dp(if (isLandscape) 28 else 36)
                    layoutParams = LinearLayout.LayoutParams(pSize, pSize).apply {
                        marginEnd = dp(6)
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(5).toFloat())
                        }
                    }
                    setImageResource(portraitRes)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1.2f), android.graphics.Color.parseColor("#00E5FF"))
                    }
                }
                header.addView(portraitAvatar)
            }

            // Cyberpunk CIRI Badge Button
            val ciriBtn = android.widget.TextView(context).apply {
                text = "CIRI"
                textSize = if (isLandscape) 8.5f else 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                val bg = make3DKeycap("#0E2235", "#06131F", "#02070B", "#00E5FF", 3.5f, 2f)
                background = deckRipple(bg)
                centerKeycapText(this)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (isLandscape) 22 else 24)).apply {
                    marginEnd = dp(6)
                }
                layoutParams = lp
            }
            header.addView(ciriBtn)

            // Sleep Timer button
            val sleepBtn = android.widget.TextView(context).apply {
                text = "⏱️ SLEEP"
                textSize = if (isLandscape) 8.5f else 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#E2E8F0"))
                val bg = make3DKeycap("#2A1F3A", "#170F26", "#050308", "#5B3C8A", 3.5f, 2f)
                background = deckRipple(bg)
                centerKeycapText(this)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (isLandscape) 22 else 24)).apply {
                    marginEnd = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    try {
                        SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0).show(childFragmentManager, null)
                    } catch (_: Throwable) {}
                }
            }
            header.addView(sleepBtn)

            // Close [✕] button
            val closeBtn = android.widget.TextView(context).apply {
                text = "✕"
                textSize = if (isLandscape) 12f else 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#FF4D6D"))
                val bg = make3DKeycap("#3A0E24", "#22060F", "#0A0206", "#FF2E88", 3.5f, 2f)
                background = deckRipple(bg)
                centerKeycapText(this)
                val btnSize = dp(if (isLandscape) 22 else 24)
                val lp = LinearLayout.LayoutParams(btnSize, btnSize)
                layoutParams = lp
                setOnClickListener {
                    try { dialog?.dismiss() } catch (_: Throwable) {}
                }
            }
            header.addView(closeBtn)

            // Subtle dark hairline separator
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    bottomMargin = dp(3)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#33204F"))
            }

            chassis.addView(header)
            chassis.addView(divider)

            // Hazard tape under the header
            chassis.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isLandscape) 3 else 4)).apply {
                    bottomMargin = dp(if (isLandscape) 4 else 5)
                }
                background = HazardStripeDrawable()
            })

            val autoLabel = getString(R.string.multiview_quality_auto)
            val cardVolumeUpdaters = mutableMapOf<String, (Float) -> Unit>()
            val cardPillUpdaters = mutableListOf<() -> Unit>()

            // ==========================================
            // PRO MASTER BUS (Tactile Hardware Section)
            // ==========================================
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pSide = dp(if (isLandscape) 7 else 8)
                val pTop = dp(if (isLandscape) 4 else 6)
                setPadding(pSide, pTop, pSide, pTop)
                val bg = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        android.graphics.Color.parseColor("#320F4E"),
                        android.graphics.Color.parseColor("#12081F"),
                    ),
                ).apply {
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1.4f), android.graphics.Color.parseColor("#FF2E88"))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(if (isLandscape) 3 else 5)
                }
                layoutParams = lp
            }

            // Master Header: [● MASTER BUS]                  [ 100% ]
            val masterHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(if (isLandscape) 3 else 5)
                }
            }
            val masterLedBezel = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#0B1A0B"))
                    cornerRadius = dp(3.5f).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#4C6B00"))
                }
                background = bg
                setPadding(dp(5), dp(2), dp(6), dp(2))
            }
            val ledDot = android.widget.TextView(context).apply {
                text = "●"
                textSize = 9f
                setTextColor(android.graphics.Color.parseColor("#C8FF00"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
            }
            masterLedBezel.addView(ledDot)
            val masterBusTitle = android.widget.TextView(context).apply {
                text = "MASTER BUS"
                textSize = if (isLandscape) 8.5f else 9.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#C8FF00"))
            }
            masterLedBezel.addView(masterBusTitle)
            masterHeader.addView(masterLedBezel)

            val headerSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
            }
            masterHeader.addView(headerSpacer)

            // Recessed Digital OLED Readout Display Pill
            val masterPercentView = android.widget.TextView(context).apply {
                text = "100%"
                setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                textSize = if (isLandscape) 10f else 11.5f
                typeface = android.graphics.Typeface.MONOSPACE
                val oledBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#04070A"))
                    cornerRadius = dp(3.5f).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#07505C"))
                }
                background = oledBg
                setPadding(dp(6), dp(1.5f), dp(6), dp(1.5f))
            }
            masterHeader.addView(masterPercentView)
            masterCard.addView(masterHeader)

            // Master Quality Selection Keycaps
            val masterQualityRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(if (isLandscape) 3 else 5)
                }
            }
            val standardQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
            val masterPillViews = mutableListOf<android.widget.TextView>()

            fun resolveActiveMasterQuality(): String {
                val resList = latestState.streams.mapNotNull { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@mapNotNull null
                    val ov = latestState.qualityOverrides[id]
                    val act = latestPlayback[id]?.qualityLabel.orEmpty()
                    ov ?: act.takeIf { it.isNotBlank() }
                }
                return when {
                    resList.any { it.contains("360") } -> "360p"
                    resList.any { it.contains("480") } -> "480p"
                    resList.any { it.contains("720") } -> "720p"
                    resList.any { it.contains("1080") } -> "1080p"
                    resList.any { it.contains("160") } -> "160p"
                    else -> autoLabel
                }
            }

            var selectedMasterQuality = resolveActiveMasterQuality()

            fun refreshMasterPills() {
                masterPillViews.forEach { pill ->
                    val pillQual = pill.tag as? String ?: ""
                    val isSelected = pillQual == selectedMasterQuality
                    val bg = if (isSelected) {
                        make3DKeycap(
                            topColorHex = "#FF2E88",
                            bottomColorHex = "#C40E5E",
                            shadowColorHex = "#3A0019",
                            strokeColorHex = "#FFB0D2",
                            cornerRadiusDp = 4.5f,
                            shadowDepthDp = 2.5f,
                        )
                    } else {
                        make3DKeycap(
                            topColorHex = "#2E2142",
                            bottomColorHex = "#1A1128",
                            shadowColorHex = "#050308",
                            strokeColorHex = "#5A4580",
                            cornerRadiusDp = 4.5f,
                            shadowDepthDp = 2.5f,
                        )
                    }
                    pill.background = deckRipple(bg)
                    pill.setTextColor(if (isSelected) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#E2E8F0"))
                    centerKeycapText(pill)
                }
            }

            standardQualities.forEachIndexed { qIdx, qual ->
                val pill = android.widget.TextView(context).apply {
                    tag = qual
                    text = if (qual == autoLabel) "Auto" else qual
                    textSize = if (isLandscape) 8.5f else 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val lp = LinearLayout.LayoutParams(0, dp(if (isLandscape) 24 else 31), 1f).apply {
                        if (qIdx < standardQualities.lastIndex) marginEnd = dp(3)
                    }
                    layoutParams = lp
                    centerKeycapText(this)
                    setOnClickListener {
                        selectedMasterQuality = qual
                        latestState.streams.forEach { stream ->
                            val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                            val targetLabel = if (qual == autoLabel) {
                                null
                            } else {
                                latestPlayback[id]?.availableQualities?.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                            }
                            viewModel.setQualityOverride(id, targetLabel)
                        }
                        refreshMasterPills()
                        cardPillUpdaters.forEach { it.invoke() }
                    }
                }
                masterPillViews.add(pill)
                masterQualityRow.addView(pill)
            }
            refreshMasterPills()
            masterCard.addView(masterQualityRow)

            // Fader Row: Master Mute Button + Pro Fader
            val masterFaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val masterMuteBtn = android.widget.ImageButton(context).apply {
                val btnSize = dp(if (isLandscape) 20 else 24)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(5)
                }
                background = deckRipple(make3DKeycap("#2A1F3A", "#170F26", "#050308", "#5B3C8A", 4f, 2f))
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setImageResource(R.drawable.baseline_volume_up_black_24)
                setColorFilter(android.graphics.Color.parseColor("#00E5FF"))
            }
            masterFaderRow.addView(masterMuteBtn)

            var masterVol = latestState.streams.firstOrNull()?.let { s ->
                MultiviewSessionReducer.stableIdentity(s)?.let { viewModel.audioVolume(it) }
            } ?: 1f
            masterPercentView.text = "${(masterVol * 100).roundToInt()}%"

            val masterFader = DjFaderView(context, masterVol, isCompact = isLandscape) { newVol ->
                masterVol = newVol
                masterPercentView.text = "${(newVol * 100).roundToInt()}%"
                masterMuteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                val activeColor = if (newVol > 0f) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#FF4D6D")
                masterMuteBtn.setColorFilter(activeColor)
                latestState.streams.forEach { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                    viewModel.setAudioVolume(id, newVol)
                    cardVolumeUpdaters[id]?.invoke(newVol)
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(if (isLandscape) 18 else 20), 1f)
            }
            masterFaderRow.addView(masterFader)

            masterMuteBtn.setOnClickListener {
                val target = if (masterVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                masterFader.setVolume(target)
            }
            masterCard.addView(masterFaderRow)
            chassis.addView(masterCard)

            // ==========================================
            // STREAM CARDS CONTAINER (2-Column Studio Mixer in Landscape, 1-Column in Portrait)
            // ==========================================
            val streamScroll = object : android.widget.ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maxH = if (isLandscape) dp(180) else dp(480)
                    val hSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || MeasureSpec.getSize(heightMeasureSpec) > maxH) {
                        MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                    } else {
                        heightMeasureSpec
                    }
                    super.onMeasure(widthMeasureSpec, hSpec)
                }
            }.apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val streamListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            streamScroll.addView(streamListLayout)

            var populateCards: () -> Unit = {}

            // Builder for each channel strip
            fun buildStreamCard(index: Int, stream: com.github.andreyasadchy.xtra.data.multiview.MultiviewStream, isCompactMode: Boolean): View {
                val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return View(context)

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pSide = dp(if (isCompactMode) 6 else 7)
                    val pTop = dp(if (isCompactMode) 3 else 5)
                    setPadding(pSide, pTop, pSide, pTop)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#170F26"))
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1.2f), android.graphics.Color.parseColor("#3E2A6B"))
                    }
                    background = bg
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(if (isCompactMode) 3 else 4)
                    }
                    layoutParams = lp
                }

                // ROW 1: [#1] [Avatar] [Streamer Name FULL] ... [▲][▼] [VU] [Mute] [100%] [⛶] [✕]
                val row1 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isCompactMode) 19 else 22))
                }

                // Slot badge [#1]
                val slotBadge = android.widget.TextView(context).apply {
                    text = "#${index + 1}"
                    setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                    textSize = if (isCompactMode) 8f else 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#061722"))
                        cornerRadius = dp(3.5f).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#00E5FF"))
                    }
                    background = badgeBg
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                }
                row1.addView(slotBadge)

                // Avatar
                val avatar = android.widget.ImageView(context).apply {
                    val avSize = dp(if (isCompactMode) 17 else 20)
                    layoutParams = LinearLayout.LayoutParams(avSize, avSize).apply {
                        marginStart = dp(4)
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, dp(3).toFloat())
                        }
                    }
                    setImageResource(R.drawable.ic_person_placeholder)
                }
                try {
                    com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage(context, avatar, stream)
                } catch (_: Throwable) {}
                row1.addView(avatar)

                // Streamer Name
                val nameView = android.widget.TextView(context).apply {
                    text = displayName(stream)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = if (isCompactMode) 10.5f else 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(3)
                    }
                    layoutParams = lp
                }
                row1.addView(nameView)

                val btnSize = dp(if (isCompactMode) 16 else 18)
                val streams = latestState.streams.toList()

                // Reorder ▲
                val upBtn = android.widget.TextView(context).apply {
                    text = "▲"
                    setTextColor(if (index > 0) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#3C4155"))
                    textSize = if (isCompactMode) 8f else 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    centerKeycapText(this)
                    val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(2) }
                    layoutParams = lp
                    if (index > 0) {
                        background = deckRipple(make3DKeycap("#241B36", "#141021", "#050308", "#4A3B73", 3f, 1.5f))
                        setOnClickListener {
                            viewModel.reorder(identity, index - 1)
                            populateCards()
                        }
                    }
                }
                row1.addView(upBtn)

                // Reorder ▼
                val downBtn = android.widget.TextView(context).apply {
                    text = "▼"
                    setTextColor(if (index < streams.lastIndex) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#3C4155"))
                    textSize = if (isCompactMode) 8f else 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    centerKeycapText(this)
                    val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginEnd = dp(2) }
                    layoutParams = lp
                    if (index < streams.lastIndex) {
                        background = deckRipple(make3DKeycap("#241B36", "#141021", "#050308", "#4A3B73", 3f, 1.5f))
                        setOnClickListener {
                            viewModel.reorder(identity, index + 1)
                            populateCards()
                        }
                    }
                }
                row1.addView(downBtn)

                // VU Meter (3 bars)
                val vuMeter = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(2)
                    }
                    layoutParams = lp
                }
                val vuBars = mutableListOf<View>()
                val vuColors = listOf("#00E5FF", "#C8FF00", "#FF2E88")
                vuColors.forEach { col ->
                    val bar = View(context).apply {
                        val lp = LinearLayout.LayoutParams(dp(2.5f), dp(if (isCompactMode) 7 else 9)).apply { marginEnd = dp(1) }
                        layoutParams = lp
                        setBackgroundColor(android.graphics.Color.parseColor("#262B3D"))
                    }
                    vuBars.add(bar)
                    vuMeter.addView(bar)
                }
                row1.addView(vuMeter)

                var vol = viewModel.audioVolume(identity)
                fun updateVuMeter(v: Float) {
                    val litCount = when {
                        v <= 0f -> 0
                        v < 0.45f -> 1
                        v < 0.85f -> 2
                        else -> 3
                    }
                    vuBars.forEachIndexed { i, bar ->
                        bar.setBackgroundColor(
                            if (i < litCount) android.graphics.Color.parseColor(vuColors[i])
                            else android.graphics.Color.parseColor("#262B3D")
                        )
                    }
                }
                updateVuMeter(vol)

                // Mute button
                val muteBtn = android.widget.ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        marginEnd = dp(2)
                    }
                    background = deckRipple(make3DKeycap("#241B36", "#141021", "#050308", "#4A3B73", 3f, 1.5f))
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setImageResource(if (vol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    setColorFilter(if (vol > 0f) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#FF4D6D"))
                }
                row1.addView(muteBtn)

                // Digital Percent Readout
                val percentView = android.widget.TextView(context).apply {
                    text = "${(vol * 100).roundToInt()}%"
                    setTextColor(android.graphics.Color.parseColor("#00E5FF"))
                    textSize = if (isCompactMode) 8.5f else 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    val oledBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#04070A"))
                        cornerRadius = dp(3).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#07505C"))
                    }
                    background = oledBg
                    setPadding(dp(3), dp(1), dp(3), dp(1))
                    val lp = LinearLayout.LayoutParams(dp(if (isCompactMode) 32 else 38), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(2)
                    }
                    gravity = Gravity.CENTER
                    layoutParams = lp
                }
                row1.addView(percentView)

                // Open Single Player [⛶]
                val openPlayerBtn = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    }
                    setImageResource(R.drawable.ic_pref_fullscreen)
                    setColorFilter(android.graphics.Color.parseColor("#9CA3AF"))
                    setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
                    contentDescription = getString(R.string.multiview_open_player)
                    setOnClickListener {
                        try { dialog?.dismiss() } catch (_: Throwable) {}
                        openNormalPlayer(identity)
                    }
                }
                row1.addView(openPlayerBtn)

                // Close Stream [✕]
                val closeStreamBtn = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(android.graphics.Color.parseColor("#FF4D6D"))
                    setPadding(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
                    contentDescription = "Remove stream"
                    setOnClickListener {
                        viewModel.remove(identity)
                        populateCards()
                    }
                }
                row1.addView(closeStreamBtn)
                card.addView(row1)

                // ROW 2: FULL DJ SLIDER
                val row2 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(if (isCompactMode) 1 else 2)
                        bottomMargin = dp(if (isCompactMode) 1 else 2)
                    }
                }
                val zeroLabel = android.widget.TextView(context).apply {
                    text = "0%"
                    textSize = if (isCompactMode) 7f else 8f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.parseColor("#64748B"))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(2)
                    }
                    layoutParams = lp
                }
                row2.addView(zeroLabel)

                val fader = DjFaderView(context, vol, isCompact = isCompactMode) { newVol ->
                    vol = newVol
                    viewModel.setAudioVolume(identity, newVol)
                    updateVuMeter(newVol)
                    percentView.text = "${(newVol * 100).roundToInt()}%"
                    muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#FF4D6D"))
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(if (isCompactMode) 16 else 18), 1f)
                }
                row2.addView(fader)

                val hundredLabel = android.widget.TextView(context).apply {
                    text = "100%"
                    textSize = if (isCompactMode) 7f else 8f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.parseColor("#64748B"))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(2)
                    }
                    layoutParams = lp
                }
                row2.addView(hundredLabel)

                cardVolumeUpdaters[identity] = { newVol ->
                    vol = newVol
                    fader.setVolume(newVol)
                    updateVuMeter(newVol)
                    percentView.text = "${(newVol * 100).roundToInt()}%"
                    muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#FF4D6D"))
                }

                muteBtn.setOnClickListener {
                    val current = vol
                    val targetVol = if (current > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                    vol = targetVol
                    fader.setVolume(targetVol)
                    viewModel.setAudioVolume(identity, targetVol)
                    updateVuMeter(targetVol)
                    percentView.text = "${(targetVol * 100).roundToInt()}%"
                    muteBtn.setImageResource(if (targetVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    muteBtn.setColorFilter(if (targetVol > 0f) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#FF4D6D"))
                }
                card.addView(row2)

                // ROW 3: QUALITY KEYCAPS
                val row3 = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val available = latestPlayback[identity]?.availableQualities.orEmpty()
                val displayQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")

                val pillViews = mutableListOf<android.widget.TextView>()
                fun refreshThisPills() {
                    val overrideQuality = latestState.qualityOverrides[identity]
                    val activePlayingQuality = latestPlayback[identity]?.qualityLabel.orEmpty()
                    val currentRes = overrideQuality ?: activePlayingQuality

                    pillViews.forEach { pill ->
                        val qual = pill.tag as? String ?: ""
                        val isSelected = when {
                            currentRes.contains("1080") -> qual.contains("1080")
                            currentRes.contains("720") -> qual.contains("720")
                            currentRes.contains("480") -> qual.contains("480")
                            currentRes.contains("360") -> qual.contains("360")
                            currentRes.contains("160") -> qual.contains("160")
                            currentRes.isBlank() -> qual == autoLabel
                            else -> qual.contains(currentRes, ignoreCase = true) || currentRes.contains(qual, ignoreCase = true)
                        }
                        val pillBg = if (isSelected) {
                            make3DKeycap(
                                topColorHex = "#00E5FF",
                                bottomColorHex = "#0090C2",
                                shadowColorHex = "#00303D",
                                strokeColorHex = "#B4F6FF",
                                cornerRadiusDp = 4f,
                                shadowDepthDp = 2.5f,
                            )
                        } else {
                            make3DKeycap(
                                topColorHex = "#241B36",
                                bottomColorHex = "#141021",
                                shadowColorHex = "#050308",
                                strokeColorHex = "#4A3B73",
                                cornerRadiusDp = 4f,
                                shadowDepthDp = 2.5f,
                            )
                        }
                        pill.background = deckRipple(pillBg)
                        pill.setTextColor(if (isSelected) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#E2E8F0"))
                        centerKeycapText(pill)
                    }
                }

                cardPillUpdaters.add(::refreshThisPills)

                displayQualities.forEachIndexed { qIdx, qual ->
                    val shortName = when {
                        qual == autoLabel -> "Auto"
                        qual.contains("1080") -> "1080p"
                        qual.contains("720") -> "720p"
                        qual.contains("480") -> "480p"
                        qual.contains("360") -> "360p"
                        qual.contains("160") -> "160p"
                        else -> qual.split(" ").firstOrNull() ?: qual
                    }
                    val pill = android.widget.TextView(context).apply {
                        tag = qual
                        text = shortName
                        textSize = if (isCompactMode) 8f else 9.5f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        val lp = LinearLayout.LayoutParams(0, dp(if (isCompactMode) 22 else 25), 1f).apply {
                            if (qIdx < displayQualities.lastIndex) marginEnd = dp(2)
                        }
                        layoutParams = lp
                        centerKeycapText(this)
                        setOnClickListener {
                            val match = if (qual == autoLabel) {
                                null
                            } else {
                                available.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                            }
                            viewModel.setQualityOverride(identity, match)
                            refreshThisPills()
                        }
                    }
                    pillViews.add(pill)
                    row3.addView(pill)
                }

                refreshThisPills()
                card.addView(row3)
                return card
            }

            populateCards = {
                streamListLayout.removeAllViews()
                cardPillUpdaters.clear()
                cardVolumeUpdaters.clear()

                val streams = latestState.streams.toList()
                val useTwoColumns = isLandscape && streams.size > 1

                if (useTwoColumns) {
                    val gridRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val col1 = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(3)
                        }
                    }
                    val col2 = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(3)
                        }
                    }
                    gridRow.addView(col1)
                    gridRow.addView(col2)
                    streamListLayout.addView(gridRow)

                    streams.forEachIndexed { index, stream ->
                        val card = buildStreamCard(index, stream, isCompactMode = true)
                        if (index % 2 == 0) {
                            col1.addView(card)
                        } else {
                            col2.addView(card)
                        }
                    }
                } else {
                    streams.forEachIndexed { index, stream ->
                        val card = buildStreamCard(index, stream, isCompactMode = false)
                        streamListLayout.addView(card)
                    }
                }
            }

            populateCards()
            chassis.addView(streamScroll)

            // ==========================================
            // BOTTOM MACRO ACTIONS: [MUTE ALL] [EQUALIZE] [SOLO ACTIVE] [REMOVE ALL]
            // ==========================================
            val bottomMacros = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(if (isLandscape) 4 else 6), dp(4), dp(if (isLandscape) 3 else 4))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#160B26"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#3E2A6B"))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(if (isLandscape) 3 else 4)
                }
            }

            fun createMacroBtn(
                label: String,
                topHex: String,
                bottomHex: String,
                shadowHex: String,
                strokeHex: String,
                textHex: String,
                onClick: () -> Unit,
            ): android.widget.TextView {
                return android.widget.TextView(context).apply {
                    text = label
                    textSize = if (isLandscape) 8f else 9.5f
                    maxLines = 1
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.parseColor(textHex))
                    val bg = make3DKeycap(topHex, bottomHex, shadowHex, strokeHex, 4f, 2.5f)
                    background = deckRipple(bg)
                    centerKeycapText(this)
                    val lp = LinearLayout.LayoutParams(0, dp(if (isLandscape) 28 else 33), 1f).apply {
                        marginStart = dp(2)
                        marginEnd = dp(2)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        onClick()
                        populateCards()
                    }
                }
            }

            bottomMacros.addView(createMacroBtn("MUTE ALL", "#FF2E63", "#C40E42", "#3A0014", "#FFB0C2", "#12060A") {
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, 0f)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("EQUALIZE", "#00E5FF", "#0090C2", "#00303D", "#B4F6FF", "#04141A") {
                val count = latestState.streams.size.coerceAtLeast(1)
                val equalVol = (1.0f / count).coerceIn(0.15f, 1f)
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, equalVol)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("SOLO ACTIVE", "#C8FF00", "#8FC400", "#2A3300", "#F0FFB0", "#0E1400") {
                val active = latestState.activeIdentity ?: latestState.streams.firstOrNull()?.let { MultiviewSessionReducer.stableIdentity(it) }
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        val target = if (id == active) 1f else 0f
                        viewModel.setAudioVolume(id, target)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("REMOVE ALL", "#FF2E88", "#B00F5C", "#3A0020", "#FFB4D4", "#12060A") {
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.remove(id)
                    }
                }
                dialog?.dismiss()
            })

            chassis.addView(bottomMacros)

            // Floating Dialog Window Configuration
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.MaterialDialog)
            dialog = android.app.Dialog(themedContext).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(chassisContainer)
                window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val displayWidth = context.resources.displayMetrics.widthPixels
                    val cardWidth = when {
                        isLandscape -> (displayWidth * 0.92f).toInt()
                        isFloatingWindow -> (displayWidth * 0.96f).toInt()
                        else -> (displayWidth * 0.94f).toInt()
                    }
                    setLayout(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val p = attributes
                    if (isLandscape || isFloatingWindow) {
                        setGravity(Gravity.CENTER)
                        p.y = 0
                    } else {
                        setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                        p.y = dp(20)
                    }
                    attributes = p
                }
                setOnDismissListener {
                    autoDismissHandler.removeCallbacks(dismissRunnable)
                    viewModel.persistSession()
                    unlockControls()
                }
            }
            dialog?.show()
            resetDismissTimer()

            // Crisp smooth entrance animation (NO scaling hack!)
            chassis.alpha = 0f
            chassis.translationY = dp(14).toFloat()
            chassis.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } catch (_: Throwable) {
            unlockControls()
        }
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

class DjFaderView(
    context: android.content.Context,
    initialVolume: Float,
    private val isCompact: Boolean = false,
    private val onVolumeChanged: (Float) -> Unit,
) : android.view.View(context) {

    constructor(
        context: android.content.Context,
        initialVolume: Float,
        onVolumeChanged: (Float) -> Unit,
    ) : this(context, initialVolume, false, onVolumeChanged)

    private var volume: Float = initialVolume.coerceIn(0f, 1f)

    private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#080910")
        style = android.graphics.Paint.Style.FILL
    }
    private val trackBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#3E4562")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val majorTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        strokeWidth = if (isCompact) 2.2f else 2.8f
    }
    private val midTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        strokeWidth = if (isCompact) 2.2f else 2.8f
    }
    private val medTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#64748B")
        strokeWidth = if (isCompact) 1.5f else 1.8f
    }
    private val minorTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#334155")
        strokeWidth = if (isCompact) 1.2f else 1.5f
    }
    private val knobShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#99000000")
        style = android.graphics.Paint.Style.FILL
    }
    private val capBodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00E5FF")
        style = android.graphics.Paint.Style.FILL
    }
    private val capBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#E0F7FA")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val knobGripPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#0097A7")
        strokeWidth = 1.3f
    }
    private val capGlowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#FFFFFF")
        strokeWidth = if (isCompact) 2.5f else 3.5f
    }

    private val capWidth = dpToPx(if (isCompact) 14f else 17f)
    private val capHeight = dpToPx(if (isCompact) 19f else 25f)
    private val capRadius = dpToPx(if (isCompact) 2.5f else 3.5f)

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setVolume(newVol: Float) {
        volume = newVol.coerceIn(0f, 1f)
        invalidate()
    }

    fun getVolume(): Float = volume

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dpToPx(if (isCompact) 18f else 34f).toInt()
        val w = android.view.View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = capWidth / 2f + dpToPx(if (isCompact) 4f else 6f)
        val trackW = w - (pad * 2f)
        if (trackW <= 0) return

        val centerY = h / 2f
        val trackTop = centerY - dpToPx(if (isCompact) 2f else 3f)
        val trackBottom = centerY + dpToPx(if (isCompact) 2f else 3f)

        // Hardware Calibration Ticks (Dense 51 graduations, every 2% step)
        val tickTopMajor = centerY - dpToPx(if (isCompact) 8.5f else 13f)
        val tickTopMed = centerY - dpToPx(if (isCompact) 7f else 10f)
        val tickTopMinor = centerY - dpToPx(if (isCompact) 5.5f else 7.5f)
        val tickBottom = centerY - dpToPx(if (isCompact) 3.5f else 5f)

        for (i in 0..50) {
            val tickX = pad + (trackW * (i / 50f))
            val paint = when {
                i == 0 || i == 50 -> majorTickPaint
                i == 25 -> midTickPaint
                i % 5 == 0 -> medTickPaint
                else -> minorTickPaint
            }
            val tTop = when {
                i == 0 || i == 25 || i == 50 -> tickTopMajor
                i % 5 == 0 -> tickTopMed
                else -> tickTopMinor
            }
            canvas.drawLine(tickX, tTop, tickX, tickBottom, paint)
        }

        // Recessed Fader Track
        val trackRect = android.graphics.RectF(pad, trackTop, pad + trackW, trackBottom)
        val trackRadius = dpToPx(if (isCompact) 1.5f else 2f)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackBorderPaint)

        // DJ Fader Cap
        val knobCenterX = pad + (trackW * volume)
        val knobLeft = knobCenterX - (capWidth / 2f)
        val knobRight = knobCenterX + (capWidth / 2f)
        val knobTop = centerY - (capHeight / 2f) + dpToPx(if (isCompact) 1f else 2f)
        val knobBottom = knobTop + capHeight

        // 3D Drop Shadow behind Fader Cap
        val shadowOffset = dpToPx(if (isCompact) 1.5f else 2.5f)
        val knobShadowRect = android.graphics.RectF(knobLeft, knobTop + shadowOffset, knobRight, knobBottom + shadowOffset)
        canvas.drawRoundRect(knobShadowRect, capRadius, capRadius, knobShadowPaint)

        // Cap Body & Bevel
        val knobRect = android.graphics.RectF(knobLeft, knobTop, knobRight, knobBottom)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBodyPaint)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBorderPaint)

        // Tactile Grip Grooves
        val gripInset = dpToPx(if (isCompact) 3.5f else 5f)
        val gripSpacing = dpToPx(if (isCompact) 2.5f else 3.5f)
        canvas.drawLine(knobCenterX - gripSpacing, knobTop + gripInset, knobCenterX - gripSpacing, knobBottom - gripInset, knobGripPaint)
        canvas.drawLine(knobCenterX + gripSpacing, knobTop + gripInset, knobCenterX + gripSpacing, knobBottom - gripInset, knobGripPaint)

        // Center Indicator Stripe (Glowing Electric Orange)
        val stripeInset = dpToPx(if (isCompact) 2f else 3f)
        canvas.drawLine(knobCenterX, knobTop + stripeInset, knobCenterX, knobBottom - stripeInset, capGlowPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val pad = capWidth / 2f + dpToPx(if (isCompact) 4f else 6f)
        val trackW = width.toFloat() - (pad * 2f)
        if (trackW <= 0) return super.onTouchEvent(event)

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, pad, trackW, snap = false)
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                updateFromTouch(event.x, pad, trackW, snap = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(touchX: Float, pad: Float, trackW: Float, snap: Boolean) {
        var rawRatio = ((touchX - pad) / trackW).coerceIn(0f, 1f)
        // Precise snap to every 2% step (0.00, 0.02, 0.04, ..., 1.00)
        val nearestStep = Math.round(rawRatio * 50f) / 50f
        if (snap || Math.abs(rawRatio - nearestStep) <= 0.008f) {
            rawRatio = nearestStep
        }
        val rounded = (Math.round(rawRatio * 100f) / 100f).coerceIn(0f, 1f)
        if (rounded != volume) {
            volume = rounded
            invalidate()
            onVolumeChanged(volume)
        }
    }
}


/** Diagonal hazard tape used as the deck's graphic accent. */
private class HazardStripeDrawable : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = android.graphics.Color.parseColor("#C8FF00")
        canvas.drawRect(bounds, paint)
        paint.color = android.graphics.Color.parseColor("#0B0918")
        val step = h * 1.5f
        var x = -h
        while (x < w + h) {
            val path = android.graphics.Path()
            path.moveTo(x, h)
            path.lineTo(x + step * 0.42f, h)
            path.lineTo(x + step * 0.42f + h, 0f)
            path.lineTo(x + h, 0f)
            path.close()
            canvas.drawPath(path, paint)
            x += step
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
