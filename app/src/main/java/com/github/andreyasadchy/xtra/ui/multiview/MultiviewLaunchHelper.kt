package com.github.andreyasadchy.xtra.ui.multiview

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewReplaceDialog

object MultiviewLaunchHelper {
    private const val MAX_STREAMS = 4

    fun openMultiview(fragment: Fragment, stream: Stream) {
        val mainActivity = fragment.activity as? MainActivity ?: return
        if (mainActivity.isFinishing || mainActivity.isDestroyed || !fragment.isAdded) return

        val multiviewVm = runCatching {
            ViewModelProvider(
                mainActivity,
                MultiviewViewModel.MultiviewViewModelFactory,
            )[MultiviewViewModel::class.java]
        }.getOrNull()

        val activeStreams = multiviewVm?.state?.value?.streams.orEmpty()
        val playingStream = mainActivity.currentPlayingStream()
        val clickedIsPlaying = playingStream != null && matches(playingStream, stream)
        val clickedInMultiview = activeStreams.any { matches(it, stream) }
        val extraMiniPlayer = playingStream?.takeIf { mini ->
            !clickedIsPlaying && activeStreams.none { active -> matches(active, mini) }
        }

        val incomingStreams = buildList {
            if (extraMiniPlayer != null) add(extraMiniPlayer)
            if (!clickedInMultiview) add(stream)
        }

        if (multiviewVm == null || activeStreams.size + incomingStreams.size <= MAX_STREAMS) {
            navigateToMultiview(fragment, mainActivity, incomingStreams)
            return
        }

        val dialogContext = fragment.context ?: mainActivity
        val plan: MultiviewReplaceDialog.Plan = when {
            activeStreams.size < MAX_STREAMS && extraMiniPlayer != null && !clickedInMultiview -> {
                MultiviewReplaceDialog.Plan.ThreeStreamsOverflow(
                    clickedStream = stream,
                    miniPlayerStream = extraMiniPlayer,
                    activeStreams = activeStreams,
                    onAddClickedToFreeSlotOnly = {
                        multiviewVm.addStreams(listOf(stream))
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                    onReplaceSlotWithMiniAndAddClicked = { slotIndex ->
                        val targetId = multiviewVm.state.value.identities.getOrNull(slotIndex)
                        if (targetId != null) {
                            multiviewVm.replaceStream(targetId, extraMiniPlayer)
                        }
                        multiviewVm.addStreams(listOf(stream))
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                )
            }

            clickedInMultiview && extraMiniPlayer != null -> {
                MultiviewReplaceDialog.Plan.HandleMiniPlayerOnly(
                    miniPlayerStream = extraMiniPlayer,
                    activeStreams = activeStreams,
                    onOpenWithoutMiniPlayer = {
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                    onReplaceSlotWithMiniPlayer = { slotIndex ->
                        val targetId = multiviewVm.state.value.identities.getOrNull(slotIndex)
                            ?: multiviewVm.state.value.identities.lastOrNull()
                        if (targetId != null) {
                            multiviewVm.replaceStream(targetId, extraMiniPlayer)
                        }
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                )
            }

            extraMiniPlayer != null -> {
                MultiviewReplaceDialog.Plan.FourStreamsWithMiniPlayer(
                    clickedStream = stream,
                    miniPlayerStream = extraMiniPlayer,
                    activeStreams = activeStreams,
                    onReplaceOnlyClicked = { slotIndex ->
                        val targetId = multiviewVm.state.value.identities.getOrNull(slotIndex)
                            ?: multiviewVm.state.value.identities.lastOrNull()
                        if (targetId != null) {
                            multiviewVm.replaceStream(targetId, stream)
                        }
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                    onReplaceBoth = { clickedSlotIndex, miniPlayerSlotIndex ->
                        val identities = multiviewVm.state.value.identities
                        val clickedTargetId = identities.getOrNull(clickedSlotIndex)
                        val miniTargetId = identities.getOrNull(miniPlayerSlotIndex)
                        if (clickedTargetId != null) {
                            multiviewVm.replaceStream(clickedTargetId, stream)
                        }
                        if (miniTargetId != null && !miniTargetId.equals(clickedTargetId, ignoreCase = true)) {
                            multiviewVm.replaceStream(miniTargetId, extraMiniPlayer)
                        }
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                )
            }

            else -> {
                MultiviewReplaceDialog.Plan.PickSlotForClicked(
                    clickedStream = stream,
                    activeStreams = activeStreams,
                    onPickSlot = { slotIndex ->
                        val targetId = multiviewVm.state.value.identities.getOrNull(slotIndex)
                            ?: multiviewVm.state.value.identities.lastOrNull()
                        if (targetId != null) {
                            multiviewVm.replaceStream(targetId, stream)
                        }
                        navigateToMultiview(fragment, mainActivity, emptyList())
                    },
                )
            }
        }

        MultiviewReplaceDialog.show(dialogContext, plan)
    }

    private fun matches(a: Stream, b: Stream): Boolean {
        val idA = MultiviewSessionReducer.stableIdentity(a)
        val idB = MultiviewSessionReducer.stableIdentity(b)
        if (idA != null && idB != null && idA.equals(idB, ignoreCase = true)) {
            return true
        }
        return !a.id.isNullOrBlank() && a.id == b.id
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
