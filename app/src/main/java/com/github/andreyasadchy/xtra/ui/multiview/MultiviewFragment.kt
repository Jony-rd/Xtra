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

        val specialTwoStream = isTwoStreamLandscapeChatOff()
        val firstIdentity = latestLayoutPlan.placements.firstOrNull()?.identity

        latestLayoutPlan.placements.forEach { placement ->
            val slotView = slotViews[placement.identity] ?: return@forEach

            val left = (placement.left * parentWidth).roundToInt()
            val top = (placement.top * parentHeight).roundToInt()
            val right = (placement.right * parentWidth).roundToInt()
            var bottom = (placement.bottom * parentHeight).roundToInt()
            if (specialTwoStream && placement.identity != firstIdentity) {
                // 2 streams, landscape, chat off: the small stream is exactly as tall
                // as 16:9 needs, flush top-right. Chat takes everything under it.
                bottom = top + specialSecondTileHeight(right - left, parentHeight)
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

            // Soft press feedback for deck controls (standard RippleDrawable)
            fun deckRipple(base: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#33A970FF")),
                    base,
                    null,
                )

            fun createRivet(): android.widget.TextView = android.widget.TextView(context).apply {
                text = "⨁"
                textSize = 8.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#484d6b"))
            }

            // 3D Floating Hardware Chassis
            val chassis = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = if (isCompactLayout) dp(6) else dp(12)
                setPadding(p, p, p, p)
                val bg = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#0e0f18"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(2), android.graphics.Color.parseColor("#9146FF"))
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        cornerRadius = dp(7).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                    },
                )).apply { setLayerInset(1, dp(3), dp(3), dp(3), dp(3)) }
                background = bg
                elevation = dp(24).toFloat()
                clipToOutline = true
            }

            // Top Header: [:::] STREAM DECK+ [STUDIO]       [⏱️ SLEEP]   [✕]
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, if (isCompactLayout) dp(4) else dp(8))
            }

            val deckIcon = android.widget.ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                setImageResource(R.drawable.ic_hw_stream_deck)
            }
            header.addView(deckIcon)

            val titleView = android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
                text = "STREAM DECK+"
                setTextColor(android.graphics.Color.parseColor("#A970FF"))
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            header.addView(titleView)

            val proBadge = android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
                text = "STUDIO"
                setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            header.addView(proBadge)

            // Sleep Timer button
            val sleepBtn = android.widget.TextView(context).apply {
                text = "⏱️ SLEEP"
                textSize = 10.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1c1d2b"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#34364e"))
                }
                background = bg
                setPadding(dp(7), dp(3), dp(7), dp(3))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#A0A2B8"))
                setPadding(dp(5), dp(2), dp(5), dp(2))
                setOnClickListener {
                    try { dialog?.dismiss() } catch (_: Throwable) {}
                }
            }
            header.addView(closeBtn)

            // Cyberpunk gradient strip
            val neonStrip = android.view.View(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
                ).apply { bottomMargin = if (isCompactLayout) dp(4) else dp(8) }
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(
                        android.graphics.Color.parseColor("#00F5D4"),
                        android.graphics.Color.parseColor("#00E5FF"),
                        android.graphics.Color.parseColor("#A970FF"),
                        android.graphics.Color.parseColor("#FF2D55"),
                    ),
                ).apply { cornerRadius = dp(1).toFloat() }
            }
            chassis.addView(header)
            chassis.addView(neonStrip)

            val autoLabel = getString(R.string.multiview_quality_auto)
            val cardVolumeUpdaters = mutableMapOf<String, (Float) -> Unit>()
            val cardPillUpdaters = mutableListOf<() -> Unit>()

            // PRO HARDWARE MASTER CONSOLE CARD (Teenage Engineering K.O. II + Sony Studio inspired)
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pTopBottom = if (isCompactLayout) dp(5) else dp(7)
                val pLeftRight = if (isCompactLayout) dp(7) else dp(9)
                setPadding(pLeftRight, pTopBottom, pLeftRight, pTopBottom)
                val bg = android.graphics.drawable.LayerDrawable(arrayOf(
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#10111a"))
                        cornerRadius = dp(7).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#9146FF"))
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                    },
                )).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)) }
                background = bg
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = if (isCompactLayout) dp(4) else dp(6)
                }
                layoutParams = lp
            }

            // Top Status Header: [⨁] [● MASTER BUS LINK] [1-4 LINKED] ... [ 100% ] [⨁]
            val masterHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = if (isCompactLayout) dp(4) else dp(5)
                }
            }

            val topRivetLeft = createRivet().apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
                layoutParams = lp
            }
            masterHeader.addView(topRivetLeft)

            val ledDot = android.widget.TextView(context).apply {
                text = "●"
                textSize = 8.5f
                setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
            }
            masterHeader.addView(ledDot)

            val masterTitle = android.widget.TextView(context).apply {
                text = "MASTER BUS LINK"
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(5)
                }
                layoutParams = lp
            }
            masterHeader.addView(masterTitle)

            val masterTag = android.widget.TextView(context).apply {
                text = "1-4 LINKED"
                textSize = 8.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#A970FF"))
                val tagBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1b1730"))
                    cornerRadius = dp(3).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#3f3366"))
                }
                background = tagBg
                setPadding(dp(4), dp(1), dp(4), dp(1))
            }
            masterHeader.addView(masterTag)

            val headerSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
            }
            masterHeader.addView(headerSpacer)

            // Recessed Digital OLED Readout Display Pill
            val masterPercentView = android.widget.TextView(context).apply {
                text = "100%"
                setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                textSize = 10.5f
                typeface = android.graphics.Typeface.MONOSPACE
                val oledBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#05060b"))
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#1f263d"))
                }
                background = oledBg
                setPadding(dp(6), dp(1), dp(6), dp(1))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(4)
                }
                layoutParams = lp
            }
            masterHeader.addView(masterPercentView)

            val topRivetRight = createRivet()
            masterHeader.addView(topRivetRight)
            masterCard.addView(masterHeader)

            // Quality Row: [⚡ RES:] [Auto] [1080p] [720p] [480p] [360p] [160p]
            val masterQualityRowContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = if (isCompactLayout) dp(4) else dp(5)
                }
                layoutParams = lp
            }

            val qualityPrefix = android.widget.TextView(context).apply {
                text = "⚡ RES:"
                textSize = 9f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(android.graphics.Color.parseColor("#A970FF"))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(5)
                }
                layoutParams = lp
            }
            masterQualityRowContainer.addView(qualityPrefix)

            val masterScroll = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val masterRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val standardQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
            standardQualities.forEach { qual ->
                val pill = android.widget.TextView(context).apply {
                    text = if (qual == autoLabel) "Auto" else qual
                    textSize = 9.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.WHITE)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1e2133"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#383d59"))
                    }
                    background = deckRipple(bg)
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(4)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        latestState.streams.forEach { stream ->
                            val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                            val targetLabel = if (qual == autoLabel) {
                                null
                            } else {
                                latestPlayback[id]?.availableQualities?.firstOrNull { it.contains(qual, ignoreCase = true) } ?: qual
                            }
                            viewModel.setQualityOverride(id, targetLabel)
                        }
                        cardPillUpdaters.forEach { it.invoke() }
                    }
                }
                masterRow.addView(pill)
            }
            masterScroll.addView(masterRow)
            masterQualityRowContainer.addView(masterScroll)
            masterCard.addView(masterQualityRowContainer)

            // Fader Row: Pro Studio Fader Bay (Tactile Mute Button + Pro Fader, ZERO text labels!)
            val masterFaderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val masterMuteBtn = android.widget.ImageButton(context).apply {
                val btnSize = if (isCompactLayout) dp(23) else dp(26)
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = dp(6)
                }
                val muteBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#151827"))
                    cornerRadius = dp(btnSize / 2).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                }
                background = muteBg
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setImageResource(R.drawable.baseline_volume_up_black_24)
                setColorFilter(android.graphics.Color.parseColor("#00F5D4"))
            }
            masterFaderRow.addView(masterMuteBtn)

            var masterVol = latestState.streams.firstOrNull()?.let { s ->
                MultiviewSessionReducer.stableIdentity(s)?.let { viewModel.audioVolume(it) }
            } ?: 1f
            masterPercentView.text = "${(masterVol * 100).roundToInt()}%"

            val masterFader = DjFaderView(context, masterVol, isCompact = isCompactLayout) { newVol ->
                masterVol = newVol
                masterPercentView.text = "${(newVol * 100).roundToInt()}%"
                masterMuteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                val activeColor = if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400")
                masterMuteBtn.setColorFilter(activeColor)
                (masterMuteBtn.background as? android.graphics.drawable.GradientDrawable)?.setStroke(dp(1), activeColor)
                latestState.streams.forEach { stream ->
                    val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                    viewModel.setAudioVolume(id, newVol)
                    cardVolumeUpdaters[id]?.invoke(newVol)
                }
            }
            masterFaderRow.addView(masterFader)

            masterMuteBtn.setOnClickListener {
                val target = if (masterVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                masterFader.setVolume(target)
            }
            masterCard.addView(masterFaderRow)
            chassis.addView(masterCard)

            // Stream Cards Container (Zero-Scroll Studio Strips)
            val streamScroll = object : android.widget.ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maxH = if (isCompactLayout) {
                        (resources.displayMetrics.heightPixels * 0.75f).toInt()
                    } else {
                        (resources.displayMetrics.heightPixels * 0.58f).toInt()
                    }
                    val customHeightSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec, customHeightSpec)
                }
            }.apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val streamListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            streamScroll.addView(streamListLayout)

            fun populateCards() {
                streamListLayout.removeAllViews()
                cardPillUpdaters.clear()
                cardVolumeUpdaters.clear()
                val streams = latestState.streams.toList()

                streams.forEachIndexed { index, stream ->
                    val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEachIndexed

                    if (isCompactLayout) {
                        // OPTION A: 1U STUDIO RACK STRIP (Single-line row, fits all 4 streams simultaneously with ZERO scrolling)
                        val stripCard = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            val pSide = dp(6)
                            val pTop = dp(2)
                            setPadding(pSide, pTop, pSide, pTop)
                            val bg = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#131522"))
                                cornerRadius = dp(5).toFloat()
                                setStroke(dp(1), android.graphics.Color.parseColor("#25283b"))
                            }
                            background = deckRipple(bg)
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
                                bottomMargin = dp(3)
                            }
                            layoutParams = lp
                        }

                        // Slot badge [#1]
                        val slotBadge = android.widget.TextView(context).apply {
                            text = "#${index + 1}"
                            setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                            textSize = 10.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#132528"))
                                cornerRadius = dp(3).toFloat()
                                setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                            }
                            background = badgeBg
                            setPadding(dp(4), dp(1), dp(4), dp(1))
                        }
                        stripCard.addView(slotBadge)

                        // Avatar
                        val avatar = android.widget.ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
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
                        stripCard.addView(avatar)

                        // Streamer Name
                        val nameView = android.widget.TextView(context).apply {
                            text = displayName(stream)
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 11.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            val lp = LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(4)
                            }
                            layoutParams = lp
                        }
                        stripCard.addView(nameView)

                        // Reorder ▲
                        val upBtn = android.widget.TextView(context).apply {
                            text = "▲"
                            setTextColor(if (index > 0) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#383b4c"))
                            textSize = 11f
                            setPadding(dp(3), dp(2), dp(2), dp(2))
                            if (index > 0) {
                                setOnClickListener {
                                    viewModel.reorder(identity, index - 1)
                                    populateCards()
                                }
                            }
                        }
                        stripCard.addView(upBtn)

                        // Reorder ▼
                        val downBtn = android.widget.TextView(context).apply {
                            text = "▼"
                            setTextColor(if (index < streams.lastIndex) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#383b4c"))
                            textSize = 11f
                            setPadding(dp(2), dp(2), dp(3), dp(2))
                            if (index < streams.lastIndex) {
                                setOnClickListener {
                                    viewModel.reorder(identity, index + 1)
                                    populateCards()
                                }
                            }
                        }
                        stripCard.addView(downBtn)

                        // Mini Quality Pills Scroll
                        val available = listOf(autoLabel) + latestPlayback[identity]?.availableQualities.orEmpty()
                        val qualityScroll = android.widget.HorizontalScrollView(context).apply {
                            isHorizontalScrollBarEnabled = false
                            val lp = LinearLayout.LayoutParams(dp(115), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(2)
                                marginEnd = dp(3)
                            }
                            layoutParams = lp
                        }
                        val qualityRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val pillViews = mutableListOf<android.widget.TextView>()
                        fun refreshThisPills() {
                            val overrideQuality = latestState.qualityOverrides[identity]
                            val activePlayingQuality = latestPlayback[identity]?.qualityLabel
                            val playingLabel = activePlayingQuality.orEmpty()
                            val targetTag = overrideQuality
                                ?: available.firstOrNull { playingLabel.isNotBlank() && (it.contains(playingLabel, ignoreCase = true) || playingLabel.contains(it, ignoreCase = true)) }
                                ?: autoLabel

                            pillViews.forEach { pill ->
                                val pillTag = pill.tag as? String ?: ""
                                val isSelected = when {
                                    pillTag == targetTag -> true
                                    overrideQuality != null && pillTag == overrideQuality -> true
                                    overrideQuality == null && playingLabel.isNotBlank() && (pillTag.contains(playingLabel, ignoreCase = true) || playingLabel.contains(pillTag, ignoreCase = true)) -> true
                                    overrideQuality == null && playingLabel.isBlank() && pillTag == autoLabel -> true
                                    else -> false
                                }
                                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = dp(3).toFloat()
                                    if (isSelected) {
                                        setColor(android.graphics.Color.parseColor("#9146FF"))
                                        setStroke(dp(1), android.graphics.Color.parseColor("#D3B3FF"))
                                    } else {
                                        setColor(android.graphics.Color.parseColor("#1a1b28"))
                                        setStroke(dp(1), android.graphics.Color.parseColor("#2e3046"))
                                    }
                                }
                                pill.background = deckRipple(pillBg)
                                pill.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#9ea0b4"))
                            }
                        }
                        cardPillUpdaters.add(::refreshThisPills)

                        available.forEach { qual ->
                            val shortName = when {
                                qual == autoLabel -> "Auto"
                                qual.contains("1080") -> "1080"
                                qual.contains("720") -> "720"
                                qual.contains("480") -> "480"
                                qual.contains("360") -> "360"
                                qual.contains("160") -> "160"
                                else -> qual.split(" ").firstOrNull() ?: qual
                            }
                            val pill = android.widget.TextView(context).apply {
                                tag = qual
                                text = shortName
                                textSize = 9f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setPadding(dp(5), dp(1), dp(5), dp(1))
                                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    marginEnd = dp(2)
                                }
                                layoutParams = lp
                                setOnClickListener {
                                    viewModel.setQualityOverride(identity, qual.takeUnless { it == autoLabel })
                                    refreshThisPills()
                                }
                            }
                            pillViews.add(pill)
                            qualityRow.addView(pill)
                        }
                        refreshThisPills()
                        qualityScroll.addView(qualityRow)
                        stripCard.addView(qualityScroll)

                        // VU Meter (4 segments)
                        val vuMeter = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(2), 0, dp(3), 0)
                        }
                        val ledSegments = (0 until 4).map {
                            android.view.View(context).apply {
                                val lp = LinearLayout.LayoutParams(dp(3), dp(9)).apply {
                                    marginEnd = dp(1)
                                }
                                layoutParams = lp
                            }
                        }
                        ledSegments.forEach { vuMeter.addView(it) }

                        fun updateVuMeter(volume: Float) {
                            val litCount = when {
                                volume <= 0.01f -> 0
                                volume < 0.33f -> 1
                                volume < 0.66f -> 2
                                volume < 0.90f -> 3
                                else -> 4
                            }
                            ledSegments.forEachIndexed { idx, led ->
                                val isLit = idx < litCount
                                val colorHex = when (idx) {
                                    0, 1 -> if (isLit) "#00F5D4" else "#142523"
                                    2 -> if (isLit) "#FFD600" else "#2b2513"
                                    else -> if (isLit) "#FF1744" else "#2d161a"
                                }
                                val bg = android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.parseColor(colorHex))
                                    cornerRadius = dp(1.5f).toFloat()
                                }
                                led.background = bg
                            }
                        }

                        var vol = viewModel.audioVolume(identity).coerceIn(0f, 1f)
                        updateVuMeter(vol)
                        stripCard.addView(vuMeter)

                        // Mute button
                        val muteBtn = android.widget.ImageButton(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                            background = null
                            setImageResource(if (vol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            setColorFilter(if (vol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }
                        stripCard.addView(muteBtn)

                        // Fader
                        var fader: DjFaderView? = null
                        fader = DjFaderView(context, vol, isCompact = true) { newVol ->
                            vol = newVol
                            viewModel.setAudioVolume(identity, newVol)
                            updateVuMeter(newVol)
                            muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }.apply {
                            val lp = LinearLayout.LayoutParams(dp(65), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = dp(3)
                                marginEnd = dp(3)
                            }
                            layoutParams = lp
                        }
                        stripCard.addView(fader)

                        cardVolumeUpdaters[identity] = { newVol ->
                            vol = newVol
                            fader.setVolume(newVol)
                            updateVuMeter(newVol)
                            muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }

                        muteBtn.setOnClickListener {
                            val currentVol = fader.getVolume()
                            val targetVol = if (currentVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                            fader.setVolume(targetVol)
                            viewModel.setAudioVolume(identity, targetVol)
                            updateVuMeter(targetVol)
                            muteBtn.setImageResource(if (targetVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (targetVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }

                        // Remove Stream [✕]
                        val removeStreamBtn = android.widget.TextView(context).apply {
                            text = "✕"
                            setTextColor(android.graphics.Color.parseColor("#FF5400"))
                            textSize = 12f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(dp(4), dp(2), dp(2), dp(2))
                            setOnClickListener {
                                viewModel.remove(identity)
                                if (latestState.streams.size <= 1) {
                                    try { dialog?.dismiss() } catch (_: Throwable) {}
                                } else {
                                    populateCards()
                                }
                            }
                        }
                        stripCard.addView(removeStreamBtn)

                        streamListLayout.addView(stripCard)
                    } else {
                        // Portrait Mode Clean 2-Row Card
                        val card = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            val bg = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#151623"))
                                cornerRadius = dp(6).toFloat()
                                setStroke(dp(1), android.graphics.Color.parseColor("#2a2b3d"))
                            }
                            background = deckRipple(bg)
                            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin = dp(5)
                            }
                            layoutParams = lp
                        }

                        // Row 1: [#1] [Avatar] [Streamer Name] [VU] [▲] [▼] [MUTE] [%] [⛶] [✕]
                        val topRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val slotBadge = android.widget.TextView(context).apply {
                            text = "#${index + 1}"
                            setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                            textSize = 11.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#1a2b2f"))
                                cornerRadius = dp(4).toFloat()
                                setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                            }
                            background = badgeBg
                            setPadding(dp(4), dp(1), dp(4), dp(1))
                        }
                        topRow.addView(slotBadge)

                        val avatar = android.widget.ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                                marginStart = dp(6)
                            }
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            clipToOutline = true
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: android.graphics.Outline) {
                                    outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
                                }
                            }
                            setImageResource(R.drawable.ic_person_placeholder)
                        }
                        try {
                            com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage(context, avatar, stream)
                        } catch (_: Throwable) {}
                        topRow.addView(avatar)

                        val nameView = android.widget.TextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = dp(6)
                            }
                            text = displayName(stream)
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 12.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        topRow.addView(nameView)

                        val vuMeter = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(3), 0, dp(3), 0)
                        }
                        val ledSegments = (0 until 5).map {
                            android.view.View(context).apply {
                                val lp = LinearLayout.LayoutParams(dp(3.5f), dp(10)).apply {
                                    marginEnd = dp(1.5f)
                                }
                                layoutParams = lp
                            }
                        }
                        ledSegments.forEach { vuMeter.addView(it) }

                        fun updateVuMeter(volume: Float) {
                            val litCount = when {
                                volume <= 0.01f -> 0
                                volume < 0.25f -> 1
                                volume < 0.50f -> 2
                                volume < 0.75f -> 3
                                volume < 0.90f -> 4
                                else -> 5
                            }
                            ledSegments.forEachIndexed { idx, led ->
                                val isLit = idx < litCount
                                val colorHex = when (idx) {
                                    0, 1, 2 -> if (isLit) "#00F5D4" else "#142523"
                                    3 -> if (isLit) "#FFD600" else "#2b2513"
                                    else -> if (isLit) "#FF1744" else "#2d161a"
                                }
                                val bg = android.graphics.drawable.GradientDrawable().apply {
                                    setColor(android.graphics.Color.parseColor(colorHex))
                                    cornerRadius = dp(2).toFloat()
                                }
                                led.background = bg
                            }
                        }

                        var vol = viewModel.audioVolume(identity).coerceIn(0f, 1f)
                        updateVuMeter(vol)
                        topRow.addView(vuMeter)

                        val upBtn = android.widget.TextView(context).apply {
                            text = "▲"
                            setTextColor(if (index > 0) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#444654"))
                            textSize = 13f
                            setPadding(dp(5), dp(2), dp(3), dp(2))
                            if (index > 0) {
                                setOnClickListener {
                                    viewModel.reorder(identity, index - 1)
                                    populateCards()
                                }
                            }
                        }
                        topRow.addView(upBtn)

                        val downBtn = android.widget.TextView(context).apply {
                            text = "▼"
                            setTextColor(if (index < streams.lastIndex) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#444654"))
                            textSize = 13f
                            setPadding(dp(3), dp(2), dp(5), dp(2))
                            if (index < streams.lastIndex) {
                                setOnClickListener {
                                    viewModel.reorder(identity, index + 1)
                                    populateCards()
                                }
                            }
                        }
                        topRow.addView(downBtn)

                        val percentView = android.widget.TextView(context).apply {
                            text = "${(vol * 100).roundToInt()}%"
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 11.5f
                            setPadding(dp(2), 0, dp(2), 0)
                        }

                        val muteBtn = android.widget.ImageButton(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                            background = null
                            setImageResource(if (vol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            setColorFilter(if (vol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }
                        topRow.addView(muteBtn)
                        topRow.addView(percentView)

                        val openPlayerBtn = android.widget.ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                                marginStart = dp(5)
                                marginEnd = dp(2)
                            }
                            setImageResource(R.drawable.ic_pref_fullscreen)
                            setColorFilter(android.graphics.Color.parseColor("#00F5D4"))
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                            contentDescription = getString(R.string.multiview_open_player)
                            setOnClickListener {
                                try { dialog?.dismiss() } catch (_: Throwable) {}
                                openNormalPlayer(identity)
                            }
                        }
                        topRow.addView(openPlayerBtn)

                        val removeStreamBtn = android.widget.TextView(context).apply {
                            text = "✕"
                            setTextColor(android.graphics.Color.parseColor("#FF5400"))
                            textSize = 13.5f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setPadding(dp(6), dp(2), dp(2), dp(2))
                            setOnClickListener {
                                viewModel.remove(identity)
                                if (latestState.streams.size <= 1) {
                                    try { dialog?.dismiss() } catch (_: Throwable) {}
                                } else {
                                    populateCards()
                                }
                            }
                        }
                        topRow.addView(removeStreamBtn)
                        card.addView(topRow)

                        // Row 2: Fader + Quality Pills
                        val bottomRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(4), 0, dp(2))
                        }

                        var fader: DjFaderView? = null
                        fader = DjFaderView(context, vol, isCompact = false) { newVol ->
                            vol = newVol
                            viewModel.setAudioVolume(identity, newVol)
                            updateVuMeter(newVol)
                            percentView.text = "${(newVol * 100).roundToInt()}%"
                            muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                                marginEnd = dp(6)
                            }
                        }
                        bottomRow.addView(fader)

                        cardVolumeUpdaters[identity] = { newVol ->
                            vol = newVol
                            fader.setVolume(newVol)
                            updateVuMeter(newVol)
                            percentView.text = "${(newVol * 100).roundToInt()}%"
                            muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }

                        muteBtn.setOnClickListener {
                            val currentVol = fader.getVolume()
                            val targetVol = if (currentVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                            fader.setVolume(targetVol)
                            viewModel.setAudioVolume(identity, targetVol)
                            updateVuMeter(targetVol)
                            percentView.text = "${(targetVol * 100).roundToInt()}%"
                            muteBtn.setImageResource(if (targetVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (targetVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }

                        val available = listOf(autoLabel) + latestPlayback[identity]?.availableQualities.orEmpty()
                        val qualityScroll = android.widget.HorizontalScrollView(context).apply {
                            isHorizontalScrollBarEnabled = false
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val qualityRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val pillViews = mutableListOf<android.widget.TextView>()
                        fun refreshThisPills() {
                            val overrideQuality = latestState.qualityOverrides[identity]
                            val activePlayingQuality = latestPlayback[identity]?.qualityLabel
                            val playingLabel = activePlayingQuality.orEmpty()
                            val targetTag = overrideQuality
                                ?: available.firstOrNull { playingLabel.isNotBlank() && (it.contains(playingLabel, ignoreCase = true) || playingLabel.contains(it, ignoreCase = true)) }
                                ?: autoLabel

                            pillViews.forEach { pill ->
                                val pillTag = pill.tag as? String ?: ""
                                val isSelected = when {
                                    pillTag == targetTag -> true
                                    overrideQuality != null && pillTag == overrideQuality -> true
                                    overrideQuality == null && playingLabel.isNotBlank() && (pillTag.contains(playingLabel, ignoreCase = true) || playingLabel.contains(pillTag, ignoreCase = true)) -> true
                                    overrideQuality == null && playingLabel.isBlank() && pillTag == autoLabel -> true
                                    else -> false
                                }
                                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = dp(4).toFloat()
                                    if (isSelected) {
                                        setColor(android.graphics.Color.parseColor("#9146FF"))
                                        setStroke(dp(1), android.graphics.Color.parseColor("#D3B3FF"))
                                    } else {
                                        setColor(android.graphics.Color.parseColor("#1c1d2b"))
                                        setStroke(dp(1), android.graphics.Color.parseColor("#34364e"))
                                    }
                                }
                                pill.background = deckRipple(pillBg)
                                pill.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#A0A2B8"))
                            }
                        }
                        cardPillUpdaters.add(::refreshThisPills)

                        available.forEach { qual ->
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
                                textSize = 9.5f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                setPadding(dp(6), dp(2), dp(6), dp(2))
                                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    marginEnd = dp(3)
                                }
                                layoutParams = lp
                                setOnClickListener {
                                    viewModel.setQualityOverride(identity, qual.takeUnless { it == autoLabel })
                                    refreshThisPills()
                                }
                            }
                            pillViews.add(pill)
                            qualityRow.addView(pill)
                        }
                        refreshThisPills()
                        qualityScroll.addView(qualityRow)
                        bottomRow.addView(qualityScroll)
                        card.addView(bottomRow)

                        streamListLayout.addView(card)
                    }
                }
            }

            populateCards()
            chassis.addView(streamScroll)

            // Bottom Macro Actions: [MUTE ALL] [EQUALIZE] [SOLO ACTIVE]
            val bottomMacros = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, if (isCompactLayout) dp(3) else dp(5), 0, dp(1))
            }

            fun createMacroBtn(label: String, colorHex: String, onClick: () -> Unit): android.widget.TextView {
                return android.widget.TextView(context).apply {
                    text = label
                    textSize = if (isCompactLayout) 10f else 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1c1d2b"))
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor(colorHex))
                    }
                    background = deckRipple(bg)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    }
                    layoutParams = lp
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        onClick()
                        populateCards()
                    }
                }
            }

            bottomMacros.addView(createMacroBtn("MUTE ALL", "#FF5400") {
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, 0f)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("EQUALIZE", "#00F5D4") {
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, 0.5f)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("SOLO ACTIVE", "#9146FF") {
                val active = latestState.activeIdentity
                latestState.streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, if (id.equals(active, true)) 1f else 0f)
                    }
                }
            })

            chassis.addView(bottomMacros)

            // Floating Dialog Window Configuration
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.MaterialDialog)
            dialog = android.app.Dialog(themedContext).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(chassis)
                window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val displayWidth = context.resources.displayMetrics.widthPixels
                    val cardWidth = when {
                        isLandscape -> (displayWidth * 0.58f).toInt()
                        isFloatingWindow -> (displayWidth * 0.94f).toInt()
                        else -> (displayWidth * 0.92f).toInt()
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
                setOnDismissListener { unlockControls() }
            }
            dialog?.show()

            chassis.post {
                chassis.alpha = 0f
                chassis.scaleX = 0.94f
                chassis.scaleY = 0.94f
                chassis.translationY = dp(20).toFloat()
                chassis.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
                    .start()
            }
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
                setFrameParams(binding.videoGrid, ViewGroup.LayoutParams.MATCH_PARENT, videoHeight)
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
        color = android.graphics.Color.parseColor("#0A0B14")
        style = android.graphics.Paint.Style.FILL
    }
    private val trackBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#25283D")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val majorTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00F5D4")
        strokeWidth = if (isCompact) 2.5f else 3f
    }
    private val midTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#A970FF")
        strokeWidth = if (isCompact) 2.5f else 3f
    }
    private val medTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#6B7094")
        strokeWidth = if (isCompact) 1.8f else 2f
    }
    private val minorTickPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#34374E")
        strokeWidth = if (isCompact) 1.2f else 1.5f
    }
    private val capBodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#1C1E2D")
        style = android.graphics.Paint.Style.FILL
    }
    private val capBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#5A5E82")
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val capGlowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#00F5D4")
        strokeWidth = if (isCompact) 3f else 4f
    }

    private val capWidth = dpToPx(if (isCompact) 13f else 16f)
    private val capHeight = dpToPx(if (isCompact) 18f else 24f)
    private val capRadius = dpToPx(if (isCompact) 2.5f else 3f)

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

        val knobRect = android.graphics.RectF(knobLeft, knobTop, knobRight, knobBottom)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBodyPaint)
        canvas.drawRoundRect(knobRect, capRadius, capRadius, capBorderPaint)

        // Center Indicator Stripe
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
