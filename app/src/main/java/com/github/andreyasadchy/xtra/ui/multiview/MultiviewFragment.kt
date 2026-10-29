package com.github.andreyasadchy.xtra.ui.multiview
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutPlan

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.MenuItem
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
                viewModel.addStreams(listOf(stream))
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
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        latestState.identities.forEach { identity ->
            viewModel.playbackCoordinator.player(identity)?.let { player ->
                if (player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic) {
                    player.seekToDefaultPosition()
                }
            }
        }
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
            if (renderedChatKey == null && childFragmentManager.fragments.none { it.id == R.id.chatContent }) {
                return
            }
            renderedChatKey = null
            childFragmentManager.beginTransaction().apply {
                childFragmentManager.fragments
                    .filter { it.id == R.id.chatContent }
                    .forEach { fragment ->
                        releaseChatFragment(fragment)
                        remove(fragment)
                    }
            }.commit()
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
        if (key == renderedChatKey) return
        renderedChatKey = key

        val transaction = childFragmentManager.beginTransaction()
        val targetTag = "$SINGLE_CHAT_TAG$chatIdentity"
        val existingTarget = childFragmentManager.findFragmentByTag(targetTag)
        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .filterNot { it === existingTarget }
            .forEach { fragment ->
                releaseChatFragment(fragment)
                transaction.remove(fragment)
            }

        val tag = targetTag
        val fragment = existingTarget
            ?: ChatFragment.newInstance(
                singleStream.channelId,
                singleStream.channelLogin,
                displayName(singleStream),
                singleStream.id,
            ).also {
                transaction.add(R.id.chatContent, it, tag)
            }
        transaction.show(fragment)
        transaction.commit()
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

        var dialog: android.app.Dialog? = null

        fun buildContent(): View {
            val streams = latestState.streams

            // Outer wrapper with side and bottom margins so it floats detached from the screen edges
            val rootWrapper = FrameLayout(context).apply {
                val pSide = dp(12)
                val pTop = dp(16)
                val pBottom = dp(20)
                setPadding(pSide, pTop, pSide, pBottom)
            }

            // 3D Floating Hardware Chassis
            val chassis = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = dp(14)
                setPadding(p, p, p, p)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#0d0e17"))
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(2), android.graphics.Color.parseColor("#9146FF"))
                }
                background = bg
                elevation = dp(24).toFloat()
                clipToOutline = true
            }
            rootWrapper.addView(chassis)

            // Top Header: [:::] STREAM DECK STUDIO      [⏱️ SLEEP]   [✕]
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(10))
            }
            val deckIcon = android.widget.ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                setImageResource(R.drawable.ic_hw_stream_deck)
            }
            header.addView(deckIcon)

            val titleView = android.widget.TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
                text = "STREAM DECK STUDIO"
                setTextColor(android.graphics.Color.parseColor("#A970FF"))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            header.addView(titleView)

            // Sleep Timer button
            val sleepBtn = android.widget.TextView(context).apply {
                text = "⏱️ SLEEP"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.WHITE)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1c1d2b"))
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#34364e"))
                }
                background = bg
                setPadding(dp(8), dp(4), dp(8), dp(4))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                layoutParams = lp
                setOnClickListener {
                    SleepTimerDialog.newInstance((activity as? MainActivity)?.getSleepTimerTimeLeft() ?: 0).show(childFragmentManager, null)
                }
            }
            header.addView(sleepBtn)

            // Close [✕] button
            val closeBtn = android.widget.TextView(context).apply {
                text = "✕"
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#A0A2B8"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener { dialog?.dismiss() }
            }
            header.addView(closeBtn)
            chassis.addView(header)

            val autoLabel = getString(R.string.multiview_quality_auto)

            // MASTER GLOBAL QUALITY BAR
            val masterCard = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#141522"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                }
                background = bg
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
                layoutParams = lp
            }

            val masterLabel = android.widget.TextView(context).apply {
                text = "⚡ MASTER:"
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                setPadding(0, 0, dp(8), 0)
            }
            masterCard.addView(masterLabel)

            val masterScroll = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
            }
            val masterRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val standardQualities = listOf(autoLabel, "1080p", "720p", "480p", "360p", "160p")
            standardQualities.forEach { qual ->
                val pill = android.widget.TextView(context).apply {
                    text = if (qual == autoLabel) "Auto" else qual
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.WHITE)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1e2030"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#3a3e5e"))
                    }
                    background = bg
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(4)
                    }
                    layoutParams = lp
                    setOnClickListener {
                        streams.forEach { stream ->
                            val id = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEach
                            val targetLabel = if (qual == autoLabel) {
                                null
                            } else {
                                latestPlayback[id]?.availableQualities?.firstOrNull { it.contains(qual) } ?: qual
                            }
                            viewModel.setQualityOverride(id, targetLabel)
                        }
                        dialog?.setContentView(buildContent())
                    }
                }
                masterRow.addView(pill)
            }
            masterScroll.addView(masterRow)
            masterCard.addView(masterScroll)
            chassis.addView(masterCard)

            // Scrollable stream rows
            val streamScroll = android.widget.ScrollView(context).apply {
                isFillViewport = true
            }
            val streamListLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            streamScroll.addView(streamListLayout)

            // STREAM CARDS
            streams.forEachIndexed { index, stream ->
                val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return@forEachIndexed

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(10))
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#151623"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#2a2b3d"))
                    }
                    background = bg
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }
                    layoutParams = lp
                }

                // Top row: [#1] [Avatar] [Streamer Name] [VU] [▲] [▼] [MUTE] [%] [✕]
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                // Slot badge
                val slotBadge = android.widget.TextView(context).apply {
                    text = "#${index + 1}"
                    setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1a2b2f"))
                        cornerRadius = dp(4).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor("#00F5D4"))
                    }
                    background = badgeBg
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                }
                topRow.addView(slotBadge)

                // Avatar
                val avatar = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
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
                com.github.andreyasadchy.xtra.ui.common.loadStreamProfileImage(context, avatar, stream)
                topRow.addView(avatar)

                // Streamer Name
                val nameView = android.widget.TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    }
                    text = displayName(stream)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                topRow.addView(nameView)

                // Hardware 5-segment LED VU level meter
                val vuMeter = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), 0, dp(4), 0)
                }
                val ledSegments = (0 until 5).map {
                    android.view.View(context).apply {
                        val lp = LinearLayout.LayoutParams(dp(4), dp(11)).apply {
                            marginEnd = dp(2)
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
                var vol = viewModel.audioVolume(identity)
                updateVuMeter(vol)
                topRow.addView(vuMeter)

                // Reorder ▲
                val upBtn = android.widget.TextView(context).apply {
                    text = "▲"
                    setTextColor(if (index > 0) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#444654"))
                    textSize = 14f
                    setPadding(dp(6), dp(2), dp(4), dp(2))
                    if (index > 0) {
                        setOnClickListener {
                            viewModel.reorder(identity, index - 1)
                            dialog?.setContentView(buildContent())
                        }
                    }
                }
                topRow.addView(upBtn)

                // Reorder ▼
                val downBtn = android.widget.TextView(context).apply {
                    text = "▼"
                    setTextColor(if (index < streams.lastIndex) android.graphics.Color.parseColor("#A970FF") else android.graphics.Color.parseColor("#444654"))
                    textSize = 14f
                    setPadding(dp(4), dp(2), dp(6), dp(2))
                    if (index < streams.lastIndex) {
                        setOnClickListener {
                            viewModel.reorder(identity, index + 1)
                            dialog?.setContentView(buildContent())
                        }
                    }
                }
                topRow.addView(downBtn)

                val percentView = android.widget.TextView(context).apply {
                    text = "${(vol * 100).roundToInt()}%"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 12f
                    setPadding(dp(2), 0, dp(2), 0)
                }

                val muteBtn = android.widget.ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                    background = ContextCompat.getDrawable(context, android.R.drawable.screen_background_dark_transparent)
                    setImageResource(if (vol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    setColorFilter(if (vol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                }
                topRow.addView(muteBtn)
                topRow.addView(percentView)

                // Remove Stream [✕] button
                val removeStreamBtn = android.widget.TextView(context).apply {
                    text = "✕"
                    setTextColor(android.graphics.Color.parseColor("#FF5400"))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(8), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        viewModel.remove(identity)
                        if (latestState.streams.size <= 1) {
                            dialog?.dismiss()
                        } else {
                            dialog?.setContentView(buildContent())
                        }
                    }
                }
                topRow.addView(removeStreamBtn)

                card.addView(topRow)

                // Middle: Pro Studio Fader Section with 10% calibration notches and Pioneer DJ fader cap
                val faderLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6), 0, dp(2))
                }

                // 11-step calibration ruler (0% to 100% in 10% steps)
                val rulerLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(2), dp(16), 0)
                }

                // 0 label
                val label0 = android.widget.TextView(context).apply {
                    text = "0"
                    textSize = 9f
                    setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                rulerLayout.addView(label0)

                // 4 tick lines (10%, 20%, 30%, 40%)
                for (t in 1..4) {
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    }
                    rulerLayout.addView(spacer)
                    val tick = View(context).apply {
                        val lp = LinearLayout.LayoutParams(dp(1.5f), dp(5)).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        layoutParams = lp
                        setBackgroundColor(android.graphics.Color.parseColor("#4E5275"))
                    }
                    rulerLayout.addView(tick)
                }

                val spacerMid1 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                }
                rulerLayout.addView(spacerMid1)

                // 50 center label
                val label50 = android.widget.TextView(context).apply {
                    text = "50"
                    textSize = 9f
                    setTextColor(android.graphics.Color.parseColor("#A970FF"))
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                rulerLayout.addView(label50)

                // 4 tick lines (60%, 70%, 80%, 90%)
                for (t in 6..9) {
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                    }
                    rulerLayout.addView(spacer)
                    val tick = View(context).apply {
                        val lp = LinearLayout.LayoutParams(dp(1.5f), dp(5)).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        layoutParams = lp
                        setBackgroundColor(android.graphics.Color.parseColor("#4E5275"))
                    }
                    rulerLayout.addView(tick)
                }

                val spacerMid2 = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
                }
                rulerLayout.addView(spacerMid2)

                // 100 label
                val label100 = android.widget.TextView(context).apply {
                    text = "100"
                    textSize = 9f
                    setTextColor(android.graphics.Color.parseColor("#00F5D4"))
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                rulerLayout.addView(label100)

                faderLayout.addView(rulerLayout)

                // Pro Studio Slider with DJ Fader Cap & Sunken Metallic Groove
                val slider = Slider(context).apply {
                    valueFrom = 0f
                    valueTo = 100f
                    stepSize = 5f // Snaps smoothly every 5% / 10% like a hardware notched fader!
                    isTickVisible = false
                    trackHeight = dp(8) // Recessed sunken metallic fader groove!
                    trackActiveTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#9146FF"))
                    trackInactiveTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#181926"))
                    try {
                        val cap = ContextCompat.getDrawable(context, R.drawable.ic_dj_fader_cap)
                        if (cap != null) setCustomThumbDrawable(cap)
                    } catch (_: Exception) {
                        thumbRadius = dp(8)
                        thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00F5D4"))
                    }
                    value = (vol * 100f).coerceIn(0f, 100f)
                    addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                            val newVol = value / 100f
                            viewModel.setAudioVolume(identity, newVol)
                            updateVuMeter(newVol)
                            percentView.text = "${value.roundToInt()}%"
                            muteBtn.setImageResource(if (newVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                            muteBtn.setColorFilter(if (newVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                        }
                    }
                }

                muteBtn.setOnClickListener {
                    val currentVol = viewModel.audioVolume(identity)
                    val targetVol = if (currentVol > 0f) 0f else viewModel.defaultAudioVolume().takeIf { it > 0f } ?: 1f
                    viewModel.setAudioVolume(identity, targetVol)
                    updateVuMeter(targetVol)
                    slider.value = targetVol * 100f
                    percentView.text = "${(targetVol * 100).roundToInt()}%"
                    muteBtn.setImageResource(if (targetVol > 0f) R.drawable.baseline_volume_up_black_24 else R.drawable.baseline_volume_off_black_24)
                    muteBtn.setColorFilter(if (targetVol > 0f) android.graphics.Color.parseColor("#00F5D4") else android.graphics.Color.parseColor("#FF5400"))
                }
                faderLayout.addView(slider)
                card.addView(faderLayout)

                // Bottom: Quality Selector Pills with REAL ACTIVE QUALITY detection
                val available = listOf(autoLabel) + latestPlayback[identity]?.availableQualities.orEmpty()
                val overrideQuality = latestState.qualityOverrides[identity]
                val activePlayingQuality = latestPlayback[identity]?.qualityLabel

                val qualityScroll = android.widget.HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                }
                val qualityRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(2), 0, dp(2))
                }

                val pillViews = mutableListOf<android.widget.TextView>()

                fun updatePills(activeQualTag: String) {
                    pillViews.forEach { pill ->
                        val pillTag = pill.tag as? String ?: ""
                        val playingLabel = activePlayingQuality.orEmpty()
                        val isSelected = when {
                            pillTag == activeQualTag -> true
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
                        pill.background = pillBg
                        pill.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#A0A2B8"))
                    }
                }

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
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = dp(4)
                        }
                        layoutParams = lp
                        setOnClickListener {
                            viewModel.setQualityOverride(identity, qual.takeUnless { it == autoLabel })
                            dialog?.setContentView(buildContent())
                        }
                    }
                    pillViews.add(pill)
                    qualityRow.addView(pill)
                }

                val playingLabel = activePlayingQuality.orEmpty()
                val defaultActiveTag = overrideQuality
                    ?: available.firstOrNull { playingLabel.isNotBlank() && (it.contains(playingLabel, ignoreCase = true) || playingLabel.contains(it, ignoreCase = true)) }
                    ?: autoLabel
                updatePills(defaultActiveTag)

                qualityScroll.addView(qualityRow)
                card.addView(qualityScroll)

                streamListLayout.addView(card)
            }
            chassis.addView(streamScroll)

            // Bottom Macro Actions: [MUTE ALL] [EQUALIZE] [SOLO ACTIVE]
            val bottomMacros = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(2))
            }

            fun createMacroBtn(label: String, colorHex: String, onClick: () -> Unit): android.widget.TextView {
                return android.widget.TextView(context).apply {
                    text = label
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1c1d2b"))
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), android.graphics.Color.parseColor(colorHex))
                    }
                    background = bg
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                    }
                    layoutParams = lp
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        onClick()
                        dialog?.setContentView(buildContent())
                    }
                }
            }

            bottomMacros.addView(createMacroBtn("MUTE ALL", "#FF5400") {
                streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, 0f)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("EQUALIZE", "#00F5D4") {
                streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, 0.5f)
                    }
                }
            })

            bottomMacros.addView(createMacroBtn("SOLO ACTIVE", "#9146FF") {
                val active = latestState.activeIdentity
                streams.forEach { stream ->
                    MultiviewSessionReducer.stableIdentity(stream)?.let { id ->
                        viewModel.setAudioVolume(id, if (id.equals(active, true)) 1f else 0f)
                    }
                }
            })

            chassis.addView(bottomMacros)
            return rootWrapper
        }

        dialog = android.app.Dialog(context, R.style.MaterialDialog).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(buildContent())
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setGravity(Gravity.CENTER)
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            setOnDismissListener { unlockControls() }
        }

        dialog.show()
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
        for (index in 0 until binding.controlsOverlay.childCount) {
            val child = binding.controlsOverlay.getChildAt(index)
            child.layoutParams = child.layoutParams.apply {
                width = dp(sizeDp)
                height = dp(sizeDp)
            }
            if (child.id in CUSTOM_ICON_BUTTON_IDS) {
                // your own artwork: no inset, the whole button is the image
                child.setPadding(0, 0, 0, 0)
            } else {
                val padding = dp(if (sizeDp >= 56) 14 else if (sizeDp >= 40) 10 else 8)
                child.setPadding(padding, padding, padding, padding)
            }
        }
    }

    private fun updateOrientationLayout() {
        val binding = _binding ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && requireActivity().isInPictureInPictureMode) {
            binding.chatContainer.isVisible = false
            binding.specialRightColumn.isVisible = false
            binding.controlsOverlay.visibility = View.GONE
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
