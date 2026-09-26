package com.github.andreyasadchy.xtra.ui.multiview
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutPlan

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
            insets
        }

                enterImmersiveMode()

        binding.backButton.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.addStreamButton.setOnClickListener { showAddStreamSheet() }
        binding.chatButton.setOnClickListener { toggleChat() }
        binding.combinedChatButton.setOnClickListener { toggleCombinedChat() }
        binding.layoutButton.setOnClickListener { showLayoutMenu() }
        binding.moreButton.setOnClickListener { showMoreMenu(binding.moreButton) }
        binding.pipButton.setOnClickListener { (activity as? MainActivity)?.minimizeMultiview() }
        binding.seekToLiveButton.setOnClickListener { seekAllToLive() }

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

        val initialStream = requireArguments().parcelable<Stream>(ARG_STREAM)
        viewModel.initialize(initialStream)
        // The multiview ViewModel is activity-scoped so playback can survive
        // navigation away from this screen. If multiview is opened again with
        // a different initial stream, add that stream to the preserved session
        // instead of losing the new selection.
        initialStream?.let { stream ->
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

    private fun createSlotView(identity: String): MultiviewSlotView {
        return MultiviewSlotView(requireContext()).apply {
            onTap = {
                viewModel.setActive(identity)
                if (latestState.chatVisible && !latestState.combinedChat) {
                    viewModel.setChat(true, combined = false, identity = identity)
                }
                revealControls(this)
            }
            onDoubleTap = {
                viewModel.setFocus(if (latestState.focusedIdentity.equals(identity, true)) null else identity)
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
            onRetry = { viewModel.playbackCoordinator.retry(identity) }
        }
    }

    private var latestLayoutPlan: MultiviewLayoutPlan = MultiviewLayoutPlan(
        placements = emptyList(),
        portraitHeightWidthRatio = 1f,
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

        latestLayoutPlan.placements.forEach { placement ->
            val slotView = slotViews[placement.identity] ?: return@forEach

            val left = (placement.left * parentWidth).roundToInt()
            val top = (placement.top * parentHeight).roundToInt()
            val right = (placement.right * parentWidth).roundToInt()
            val bottom = (placement.bottom * parentHeight).roundToInt()

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
        val audible = state.streams.filter { stream ->
            MultiviewSessionReducer.stableIdentity(stream)?.let(viewModel::audioVolume)?.let { it > 0f } == true
        }
        val specialTwoStreamChat = isTwoStreamLandscapeChatOff(state)

        binding.activeAudio.isVisible = audible.isNotEmpty()
        binding.activeAudio.text = when {
            audible.size == 1 -> getString(R.string.multiview_audio, displayName(audible.first()))
            audible.size > 1 -> getString(R.string.multiview_audio_multiple, audible.size)
            else -> null
        }
        binding.addStreamButton.isVisible = state.streams.size < MAX_STREAMS
        binding.pipButton.isVisible = (activity as? MainActivity)?.canMinimizeMultiview() == true &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && requireActivity().isInPictureInPictureMode)
        binding.chatButton.isVisible = !requireContext().prefs().getBoolean(C.CHAT_DISABLE, false) && active != null
        binding.seekToLiveButton.isVisible = state.streams.isNotEmpty()
        binding.chatContainer.isVisible = state.chatVisible || specialTwoStreamChat
        binding.combinedChatButton.isVisible = state.chatVisible && state.streams.size > 1
        binding.chatTitle.text = if (state.combinedChat) {
            getString(R.string.multiview_all_chats)
        } else {
            val identity = state.chatIdentity ?: if (specialTwoStreamChat) state.activeIdentity else null
            identity?.let { chatIdentity ->
                state.streams.firstOrNull {
                    MultiviewSessionReducer.stableIdentity(it).equals(chatIdentity, true)
                }
            }?.let(::displayName) ?: getString(R.string.multiview_chat)
        }
        binding.chatButton.contentDescription = getString(
            if (state.chatVisible) R.string.multiview_hide_chat else R.string.multiview_chat,
        )
        binding.combinedChatButton.contentDescription = getString(
            if (state.combinedChat) R.string.multiview_channel_chat else R.string.multiview_all_chats,
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

        val chatIdentity = state.chatIdentity ?: if (specialTwoStreamChat) state.activeIdentity else null
        val singleStream = if (!state.combinedChat) {
            state.streams.firstOrNull {
                MultiviewSessionReducer.stableIdentity(it).equals(chatIdentity, true)
            } ?: run {
                renderedChatKey = null
                return
            }
        } else {
            null
        }

        val key = if (state.combinedChat) {
            "all:${state.identities.joinToString(",")}"
        } else {
            "single:$chatIdentity"
        }
        if (key == renderedChatKey) return
        renderedChatKey = key

        val transaction = childFragmentManager.beginTransaction()
        val targetTag = if (state.combinedChat) {
            COMBINED_CHAT_TAG
        } else {
            "$SINGLE_CHAT_TAG$chatIdentity"
        }
        val existingTarget = childFragmentManager.findFragmentByTag(targetTag)
        childFragmentManager.fragments
            .filter { it.id == R.id.chatContent }
            .filterNot { it === existingTarget }
            .forEach { fragment ->
                releaseChatFragment(fragment)
                transaction.remove(fragment)
            }

        if (state.combinedChat) {
            val tag = COMBINED_CHAT_TAG
            val fragment = childFragmentManager.findFragmentByTag(tag)
            if (fragment is CombinedChatFragment) fragment.updateStreams(state.streams)
            val target = fragment ?: CombinedChatFragment.newInstance(state.streams).also {
                transaction.add(R.id.chatContent, it, tag)
            }
            transaction.show(target)
        } else {
            val stream = singleStream ?: return
            val tag = targetTag
            val fragment = existingTarget
                ?: ChatFragment.newInstance(
                    stream.channelId,
                    stream.channelLogin,
                    displayName(stream),
                    stream.id,
                ).also {
                    transaction.add(R.id.chatContent, it, tag)
                }
            transaction.show(fragment)
        }
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

    private fun toggleCombinedChat() {
        if (latestState.combinedChat) {
            val identity = latestState.activeIdentity ?: latestState.identities.firstOrNull() ?: return
            viewModel.setChat(true, combined = false, identity = identity)
        } else if (latestState.streams.size > 1) {
            viewModel.setChat(true, combined = true, identity = null)
        }
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

    private fun showMoreMenu(anchor: View) {
        revealControls()
        lockControls()
        PopupMenu(requireContext(), anchor, Gravity.END).apply {
            menu.add(R.string.multiview_quality_mode).setOnMenuItemClickListener {
                showQualityModeMenu()
                true
            }
            menu.add(if (latestState.fillVideo) R.string.multiview_fit else R.string.multiview_fill).setOnMenuItemClickListener {
                showAspectMenu()
                true
            }
            menu.add(R.string.multiview_reorder).setOnMenuItemClickListener {
                showReorderMenu()
                true
            }
            menu.add(R.string.multiview_open_active_player).setOnMenuItemClickListener {
                latestState.activeIdentity?.let(::openNormalPlayer)
                true
            }
            setOnDismissListener { unlockControls() }
            show()
        }
    }

    private fun showQualityModeMenu() {
        lockControls()
        val modes = MultiviewQualityMode.entries
        val labels = modes.map { getString(it.labelRes()) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_quality_mode)
            .setSingleChoiceItems(labels, modes.indexOf(latestState.qualityMode)) { dialog, which ->
                viewModel.setQualityMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showAspectMenu() {
        lockControls()
        val options = arrayOf(getString(R.string.multiview_fit), getString(R.string.multiview_fill))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_aspect)
            .setSingleChoiceItems(options, if (latestState.fillVideo) 1 else 0) { dialog, which ->
                viewModel.setFillVideo(which == 1)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showReorderMenu() {
        lockControls()
        val streams = latestState.streams
        val labels = streams.map(::displayName).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.multiview_reorder)
            .setItems(labels) { _, which -> showMoveMenu(streams[which], which) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { unlockControls() }
            .show()
    }

    private fun showMoveMenu(stream: Stream, index: Int) {
        val identity = MultiviewSessionReducer.stableIdentity(stream) ?: return
        val actions = buildList {
            if (index > 0) add(getString(R.string.multiview_move_earlier))
            if (index < latestState.streams.lastIndex) add(getString(R.string.multiview_move_later))
        }.toTypedArray()
        if (actions.isEmpty()) return
        lockControls()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(displayName(stream))
            .setItems(actions) { _, which ->
                val target = if (index > 0 && which == 0) index - 1 else index + 1
                viewModel.reorder(identity, target)
            }
            .setOnDismissListener { unlockControls() }
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
            menu.add(
                if (latestState.focusedIdentity.equals(identity, true)) R.string.multiview_unfocus
                else R.string.multiview_focus,
            ).setOnMenuItemClickListener {
                viewModel.setFocus(if (latestState.focusedIdentity.equals(identity, true)) null else identity)
                true
            }
            menu.add(R.string.multiview_open_player).setOnMenuItemClickListener {
                openNormalPlayer(identity)
                true
            }
            val index = latestState.identities.indexOfFirst { it.equals(identity, true) }
            if (index > 0) {
                menu.add(R.string.multiview_move_earlier).setOnMenuItemClickListener {
                    viewModel.reorder(identity, index - 1)
                    true
                }
            }
            if (index in 0 until latestState.streams.lastIndex) {
                menu.add(R.string.multiview_move_later).setOnMenuItemClickListener {
                    viewModel.reorder(identity, index + 1)
                    true
                }
            }
            if (latestState.streams.size > 1) {
                menu.add(R.string.multiview_remove_stream).setOnMenuItemClickListener {
                    viewModel.remove(identity)
                    true
                }
            }
            setOnDismissListener { unlockControls(slot) }
            show()
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
        binding.controlsOverlay.isVisible = visible

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

    private fun updateOrientationLayout() {
        val binding = _binding ?: return
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

                binding.multiviewContent.doOnLayout {
                    val totalWidth = binding.multiviewContent.width
                    val totalHeight = binding.multiviewContent.height
                    if (totalWidth <= 0 || totalHeight <= 0) return@doOnLayout

                    val rightWidth = (totalWidth * LANDSCAPE_SIDE_WEIGHT).roundToInt()
                    val videoWidth = totalWidth - rightWidth

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

                    binding.chatContainer.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        totalHeight / 2,
                        Gravity.BOTTOM,
                    )

                    binding.controlsOverlay.layoutParams = FrameLayout.LayoutParams(
                        videoWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ).apply {
                        setMargins(dp(8), dp(8), 0, 0)
                    }

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
                binding.controlsOverlay.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START,
                ).apply {
                    setMargins(dp(8), dp(8), dp(8), 0)
                }
                binding.multiviewContent.doOnLayout { renderTileBounds() }
            }
        } else {
            moveChatToRoot()
            binding.specialRightColumn.isVisible = false
            binding.multiviewRoot.orientation = LinearLayout.VERTICAL

            if (latestState.chatVisible) {
                val plan = latestLayoutPlan
                val rootWidth = binding.multiviewRoot.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                val desiredVideoHeight = (rootWidth * plan.portraitHeightWidthRatio)
                    .roundToInt()
                    .coerceAtLeast(dp(1))

                (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = desiredVideoHeight
                    weight = 0f
                }?.also { binding.multiviewContent.layoutParams = it }

                (binding.chatContainer.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 1f
                }?.also { binding.chatContainer.layoutParams = it }
            } else {
                (binding.multiviewContent.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 1f
                }?.also { binding.multiviewContent.layoutParams = it }

                (binding.chatContainer.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 0
                    weight = 0f
                }?.also { binding.chatContainer.layoutParams = it }
            }

            binding.videoGrid.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            binding.controlsOverlay.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                setMargins(dp(8), dp(8), dp(8), 0)
            }

            binding.multiviewRoot.doOnLayout {
                binding.videoGrid.doOnLayout { renderTileBounds() }
            }
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
        if (_binding != null) viewModel.onStop(allowBackground = false)
    }

    fun resumeAfterExternalPlayer() {
        suppressBackgroundOnNextStop = false
        if (_binding != null) {
            enterImmersiveMode()
            (activity as? MainActivity)?.prepareMultiviewPictureInPicture()
            viewModel.onStart()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (_binding == null || (activity as? MainActivity)?.playerFragment != null) return
        controlsHandler.removeCallbacks(hideControls)
        controlsLockCount = 0
        if (isInPictureInPictureMode) {
            viewModel.onStart()
            setControlsOverlayVisible(false)
            slotViews.values.forEach { it.setControlsVisible(false) }
            binding.chatContainer.isVisible = false
        } else {
            updateToolbar(latestState)
            updateChat(latestState)
            revealControls()
        }
    }

    companion object {
        const val ARG_STREAM = "multiview_stream"
        private const val COMBINED_CHAT_TAG = "multiview_combined_chat"
        private const val SINGLE_CHAT_TAG = "multiview_single_chat_"
        private const val MAX_STREAMS = 4
        private const val CONTROLS_TIMEOUT_MS = 4_500L
        private const val LANDSCAPE_CHAT_WEIGHT = 0.25f
        private const val LANDSCAPE_SIDE_WEIGHT = 0.20f

        fun arguments(stream: Stream): Bundle = Bundle().apply { putParcelable(ARG_STREAM, stream) }
    }
}

private fun MultiviewLayoutMode.labelRes(): Int = when (this) {
    MultiviewLayoutMode.AUTO -> R.string.multiview_layout_auto
    MultiviewLayoutMode.GRID -> R.string.multiview_layout_grid
    MultiviewLayoutMode.FOCUS -> R.string.multiview_layout_focus
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
