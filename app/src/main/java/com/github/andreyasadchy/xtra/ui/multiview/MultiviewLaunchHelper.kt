package com.github.andreyasadchy.xtra.ui.multiview

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object MultiviewLaunchHelper {
    private const val MAX_STREAMS = 4

    fun openMultiview(fragment: Fragment, stream: Stream) {
        val mainActivity = fragment.activity as? MainActivity ?: return
        if (mainActivity.isFinishing || mainActivity.isDestroyed || !fragment.isAdded) return

        val playingStream = mainActivity.currentPlayingStream()
        val clickedId = MultiviewSessionReducer.stableIdentity(stream)
        val playingId = playingStream?.let(MultiviewSessionReducer::stableIdentity)
        val streamsToLaunch = if (
            playingStream != null &&
            (playingId == null || !playingId.equals(clickedId, ignoreCase = true)) &&
            playingStream.id != stream.id
        ) {
            listOf(playingStream, stream)
        } else {
            listOf(stream)
        }

        val multiviewVm = runCatching {
            ViewModelProvider(
                mainActivity,
                MultiviewViewModel.MultiviewViewModelFactory,
            )[MultiviewViewModel::class.java]
        }.getOrNull()

        val activeStreams = multiviewVm?.state?.value?.streams.orEmpty()
        val launchIds = streamsToLaunch.mapNotNull { MultiviewSessionReducer.stableIdentity(it)?.lowercase() }.toSet()
        val alreadyInMultiview = activeStreams.any { active ->
            val activeId = MultiviewSessionReducer.stableIdentity(active)?.lowercase()
            (activeId != null && activeId in launchIds) || streamsToLaunch.any { it.id == active.id }
        }

        if (activeStreams.size >= MAX_STREAMS && !alreadyInMultiview && multiviewVm != null) {
            val streamNames = activeStreams.map {
                (it.channelName?.takeIf { n -> n.isNotBlank() } ?: it.channelLogin).orEmpty()
            }.toTypedArray()

            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("Multiview full. Replace which stream?")
                .setItems(streamNames) { _, which ->
                    val targetIdentity = multiviewVm.state.value.identities.getOrNull(which)
                        ?: multiviewVm.state.value.identities.lastOrNull()
                    if (targetIdentity != null) {
                        multiviewVm.replaceStream(targetIdentity, stream)
                    }
                    navigateToMultiview(fragment, mainActivity, emptyList())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        navigateToMultiview(fragment, mainActivity, streamsToLaunch)
    }

    private fun navigateToMultiview(
        fragment: Fragment,
        mainActivity: MainActivity,
        streams: List<Stream>,
    ) {
        if (mainActivity.isFinishing || mainActivity.isDestroyed || !fragment.isAdded) return
        mainActivity.closePlayer()
        runCatching {
            fragment.findNavController().navigate(
                R.id.multiviewFragment,
                MultiviewFragment.arguments(streams),
            )
        }
    }
}
