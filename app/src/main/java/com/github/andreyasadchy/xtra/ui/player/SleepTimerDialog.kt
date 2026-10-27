package com.github.andreyasadchy.xtra.ui.player

import android.app.Dialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogSleepTimerBinding
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

class SleepTimerDialog : DialogFragment() {

    companion object {
        private const val KEY_TIME_LEFT = "timeLeft"

        fun newInstance(timeLeft: Long): SleepTimerDialog {
            return SleepTimerDialog().apply {
                arguments = Bundle().apply {
                    putLong(KEY_TIME_LEFT, timeLeft)
                }
            }
        }
    }

    private var _binding: DialogSleepTimerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSleepTimerBinding.inflate(layoutInflater)
        val builder = requireContext().getAlertDialogBuilder()
            .setTitle(getString(R.string.sleep_timer))
            .setView(binding.root)
        with(binding) {
            hours.apply {
                minValue = 0
                maxValue = 23
            }
            minutes.apply {
                minValue = 0
                maxValue = 59
            }
            val positiveListener: (dialog: DialogInterface, which: Int) -> Unit = { _, _ ->
                // NumberPicker commits keyboard edits when its input loses focus.
                hours.clearFocus()
                minutes.clearFocus()
                val durationMs = hours.value * 3600_000L + minutes.value * 60_000L
                val player = parentFragment as? Media3PlayerFragment
                if (player != null) {
                    player.onSleepTimerChanged(durationMs, hours.value, minutes.value, lockCheckbox.isChecked)
                } else {
                    (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.setSleepTimer(durationMs)
                }
                requireContext().prefs().edit {
                    putInt(C.SLEEP_TIMER_MINUTES, hours.value * 60 + minutes.value)
                }
                dismiss()
            }
            val timeLeft = requireArguments().getLong(KEY_TIME_LEFT)
            if (timeLeft < 0L) {
                val savedValue = requireContext().prefs().getInt(C.SLEEP_TIMER_MINUTES, 15)
                hours.value = savedValue / 60
                minutes.value = savedValue % 60
                builder.setPositiveButton(getString(R.string.start), positiveListener)
                builder.setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }
            } else {
                // Round up so reopening a running timer never turns its last
                // partial minute into the zero-duration cancellation value.
                val minutesLeft = ((timeLeft + 59_999L) / 60_000L).coerceIn(0L, 23 * 60 + 59L)
                hours.value = (minutesLeft / 60).toInt()
                minutes.value = (minutesLeft % 60).toInt()
                builder.setPositiveButton(getString(R.string.set), positiveListener)
                builder.setNegativeButton(getString(R.string.stop)) { _, _ ->
                    val player = parentFragment as? Media3PlayerFragment
                    if (player != null) {
                        player.onSleepTimerChanged(-1L, 0, 0, lockCheckbox.isChecked)
                    } else {
                        (activity as? com.github.andreyasadchy.xtra.ui.main.MainActivity)?.setSleepTimer(-1L)
                    }
                    dismiss()
                }
                builder.setNeutralButton(android.R.string.cancel) { _, _ -> dismiss() }
            }
            val devicePolicyManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(requireContext(), DeviceAdminReceiver::class.java)
            if (devicePolicyManager.isAdminActive(admin)) {
                lockCheckbox.apply {
                    isChecked = requireContext().prefs().getBoolean(C.SLEEP_TIMER_LOCK, false)
                    text = getString(R.string.sleep_timer_lock)
                }
            } else {
                lockCheckbox.apply {
                    isChecked = false
                    text = getString(R.string.sleep_timer_lock_permissions)
                    setOnClickListener {
                        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        }
                        startActivity(intent)
                        dismiss()
                    }
                }
            }
        }
        return builder.create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
