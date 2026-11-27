package com.github.andreyasadchy.xtra.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentMediaPagerBinding
import com.github.andreyasadchy.xtra.ui.common.FragmentHost
import com.github.andreyasadchy.xtra.ui.common.PlayerOverlayTabAvoidance
import com.github.andreyasadchy.xtra.ui.common.Scrollable
import com.github.andreyasadchy.xtra.ui.common.Sortable
import com.github.andreyasadchy.xtra.ui.common.dispatchPagerScrollState
import com.github.andreyasadchy.xtra.ui.games.GamesFragment
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.SearchPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.top.TopStreamsFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.configureForSmoothPaging
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator

class BrowsePagerFragment : Fragment(), Scrollable, FragmentHost {

    private var _binding: FragmentMediaPagerBinding? = null
    private var firstLaunch = true
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabLayoutMediator: TabLayoutMediator? = null
    private val configuredRecyclerViews = mutableSetOf<RecyclerView>()
    private var playerOverlayTabAvoidance: PlayerOverlayTabAvoidance? = null
    private var appBarOffsetListener: AppBarLayout.OnOffsetChangedListener? = null
    private var lastAppBarVerticalOffset = 0
    private var cumulativeScrollDownPx = 0
    private var cumulativeScrollUpPx = 0
    private var statusBarsHiddenByBrowse = false

    private val pageLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (_binding == null) return
            hideNestedToolbar(f)
            if (f === currentFragment) configurePage(f)
        }
    }

    override val currentFragment: Fragment?
        get() {
            val pageBinding = _binding ?: return null
            if (!isAdded) return null
            return childFragmentManager.findFragmentByTag("f${pageBinding.viewPager.currentItem}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMediaPagerBinding.inflate(inflater, container, false)
        return requireNotNull(_binding).root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val pageBinding = requireNotNull(_binding)
        with(pageBinding) {
            val activity = requireActivity() as MainActivity
            val navController = findNavController()
            val appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.rootGamesFragment,
                    R.id.rootTopFragment,
                    R.id.followPagerFragment,
                    R.id.followMediaFragment,
                    R.id.savedPagerFragment,
                    R.id.savedMediaFragment,
                )
            )
            val isLoggedIn = !TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank()

            toolbar.setupWithNavController(navController, appBarConfiguration)
            toolbar.menu.findItem(R.id.login).title = if (isLoggedIn) getString(R.string.log_out) else getString(R.string.log_in)
            toolbar.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.search -> {
                        navController.navigate(SearchPagerFragmentDirections.actionGlobalSearchPagerFragment())
                        true
                    }
                    R.id.settings -> {
                        activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
                        true
                    }
                    R.id.login -> {
                        if (isLoggedIn) {
                            activity.getAlertDialogBuilder().apply {
                                setTitle(getString(R.string.logout_title))
                                requireContext().tokenPrefs().getString(C.USERNAME, null)?.let { setMessage(getString(R.string.logout_msg, it)) }
                                setNegativeButton(getString(R.string.no), null)
                                setPositiveButton(getString(R.string.yes)) { _, _ ->
                                    activity.logoutResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java).putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true))
                                }
                            }.show()
                        } else {
                            activity.loginResultLauncher?.launch(Intent(activity, TwitchWebLoginActivity::class.java))
                        }
                        true
                    }
                    else -> false
                }
            }

            // Allow the entire Browse header (status bar padding, toolbar, tabs, and sort bar)
            // to collapse cleanly off-screen without inset-induced layout jumps.
            appBar.fitsSystemWindows = false
            appBar.statusBarForeground = null
            appBar.setPadding(0, 0, 0, 0)
            (sortBarHost.layoutParams as? AppBarLayout.LayoutParams)?.let { params ->
                params.scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
                sortBarHost.layoutParams = params
            }
            val topBarContainer = toolbar.parent as? View
            val ignoreCutouts = requireContext().prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
            ViewCompat.setOnApplyWindowInsetsListener(coordinatorLayout) { _, windowInsets ->
                val statusBarTop = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
                val topInset = if (ignoreCutouts) {
                    statusBarTop
                } else {
                    maxOf(statusBarTop, windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).top)
                }
                topBarContainer?.let { container ->
                    if (container.paddingTop != topInset) {
                        container.updatePadding(top = topInset)
                    }
                }
                windowInsets
            }
            ViewCompat.requestApplyInsets(coordinatorLayout)

            val offsetListener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
                if (_binding !== pageBinding) return@OnOffsetChangedListener
                lastAppBarVerticalOffset = verticalOffset
                if (!isBrowseShowingToUser()) return@OnOffsetChangedListener
                val totalRange = appBarLayout.totalScrollRange
                if (verticalOffset == 0) {
                    cumulativeScrollDownPx = 0
                    setBrowseStatusBarsHidden(false)
                } else if (totalRange > 0 && -verticalOffset >= (totalRange * 0.45f).toInt() && cumulativeScrollUpPx == 0) {
                    setBrowseStatusBarsHidden(true)
                }
            }
            appBarOffsetListener = offsetListener
            appBar.addOnOffsetChangedListener(offsetListener)

            val adapter = BrowsePagerAdapter(this@BrowsePagerFragment)
            viewPager.adapter = adapter
            viewPager.configureForSmoothPaging()
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    if (_binding !== pageBinding) return
                    dispatchPagerScrollState(state != ViewPager2.SCROLL_STATE_IDLE)
                }

                override fun onPageSelected(position: Int) {
                    if (_binding !== pageBinding) return
                    cumulativeScrollDownPx = 0
                    cumulativeScrollUpPx = 0
                    viewPager.doOnLayout {
                        if (_binding !== pageBinding) return@doOnLayout
                        configureCurrentPage(pageBinding)
                        viewPager.post {
                            if (_binding !== pageBinding) return@post
                            configureCurrentPage(pageBinding)
                            val activeRecycler = currentFragment?.view?.findViewById<RecyclerView>(R.id.recyclerView)
                            if (activeRecycler == null || !activeRecycler.canScrollVertically(-1)) {
                                pageBinding.appBar.setExpanded(true, true)
                                setBrowseStatusBarsHidden(false)
                            }
                        }
                    }
                }
            }
            pageChangeCallback = callback
            viewPager.registerOnPageChangeCallback(callback)
            childFragmentManager.registerFragmentLifecycleCallbacks(pageLifecycleCallbacks, false)
            if (firstLaunch) {
                viewPager.setCurrentItem(0, false)
                firstLaunch = false
            }
            tabLayoutMediator = TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = getString(if (position == 0) R.string.following_categories else R.string.channels)
            }.also { it.attach() }
            viewPager.doOnLayout { configureCurrentPage(pageBinding) }
        }
        playerOverlayTabAvoidance?.close()
        playerOverlayTabAvoidance = requireActivity().findViewById<View>(R.id.playerContainer)?.let { playerContainer ->
            PlayerOverlayTabAvoidance(pageBinding.appBar, pageBinding.tabLayout, pageBinding.viewPager, playerContainer)
        }
    }

    private fun configureCurrentPage(pageBinding: FragmentMediaPagerBinding) {
        if (_binding !== pageBinding) return
        configurePage(currentFragment)
    }

    private fun configurePage(fragment: Fragment?) {
        fragment ?: return
        val pageBinding = _binding ?: return
        hideNestedToolbar(fragment)
        val density = resources.displayMetrics.density
        val hideThresholdPx = (12f * density).toInt().coerceAtLeast(1)
        val showThresholdPx = (8f * density).toInt().coerceAtLeast(1)
        fragment.view?.findViewById<RecyclerView>(R.id.recyclerView)?.let { recyclerView ->
            pageBinding.appBar.setLiftOnScrollTargetView(recyclerView)
            val lifted = recyclerView.canScrollVertically(-1)
            if (pageBinding.appBar.isLifted != lifted) pageBinding.appBar.isLifted = lifted
            if (configuredRecyclerViews.add(recyclerView)) {
                recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (_binding === pageBinding && fragment === currentFragment) {
                            val canScrollUp = recyclerView.canScrollVertically(-1)
                            if (pageBinding.appBar.isLifted != canScrollUp) pageBinding.appBar.isLifted = canScrollUp
                            if (!isBrowseShowingToUser()) return
                            if (dy > 0) {
                                cumulativeScrollUpPx = 0
                                cumulativeScrollDownPx += dy
                                if (cumulativeScrollDownPx >= hideThresholdPx && canScrollUp) {
                                    setBrowseStatusBarsHidden(true)
                                }
                            } else if (dy < 0) {
                                cumulativeScrollDownPx = 0
                                cumulativeScrollUpPx += -dy
                                if (cumulativeScrollUpPx >= showThresholdPx || !canScrollUp) {
                                    setBrowseStatusBarsHidden(false)
                                }
                            } else if (!canScrollUp && lastAppBarVerticalOffset == 0) {
                                setBrowseStatusBarsHidden(false)
                            }
                        }
                    }
                })
                recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    if (_binding === pageBinding && fragment === currentFragment) {
                        val canScrollUp = recyclerView.canScrollVertically(-1)
                        if (pageBinding.appBar.isLifted != canScrollUp) pageBinding.appBar.isLifted = canScrollUp
                    }
                }
            }
        }
        if (fragment is Sortable) {
            fragment.setupSortBar(pageBinding.sortBar)
        } else {
            pageBinding.sortBar.root.visibility = View.GONE
        }
    }

    private fun hideNestedToolbar(fragment: Fragment) {
        if (fragment is GamesFragment || fragment is TopStreamsFragment) {
            fragment.view?.findViewById<View>(R.id.appBar)?.visibility = View.GONE
            fragment.view?.findViewById<View>(R.id.toolbar)?.visibility = View.GONE
        }
    }

    private fun isBrowseShowingToUser(): Boolean {
        if (_binding == null || !isAdded || !isResumed || isHidden) return false
        val mainActivity = activity as? MainActivity ?: return false
        if (mainActivity.playerFragment != null || mainActivity.supportFragmentManager.findFragmentById(R.id.playerContainer) != null) {
            return false
        }
        return runCatching { findNavController().currentDestination?.id }.getOrNull() != R.id.multiviewFragment
    }

    private fun setBrowseStatusBarsHidden(hidden: Boolean) {
        if (hidden) {
            if (!isBrowseShowingToUser() || statusBarsHiddenByBrowse) return
            val window = activity?.window ?: return
            statusBarsHiddenByBrowse = true
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
            ViewCompat.requestApplyInsets(window.decorView)
        } else {
            if (!statusBarsHiddenByBrowse) return
            statusBarsHiddenByBrowse = false
            val mainActivity = activity as? MainActivity
            if (mainActivity != null && (mainActivity.playerFragment != null || mainActivity.supportFragmentManager.findFragmentById(R.id.playerContainer) != null)) {
                return
            }
            if (isAdded && runCatching { findNavController().currentDestination?.id }.getOrNull() == R.id.multiviewFragment) {
                return
            }
            val window = activity?.window ?: return
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
            ViewCompat.requestApplyInsets(window.decorView)
        }
    }

    override fun onResume() {
        super.onResume()
        val pageBinding = _binding ?: return
        if (isBrowseShowingToUser()) {
            val totalRange = pageBinding.appBar.totalScrollRange
            val shouldHide = totalRange > 0 && -lastAppBarVerticalOffset >= (totalRange * 0.45f).toInt()
            if (shouldHide) {
                setBrowseStatusBarsHidden(true)
            } else {
                setBrowseStatusBarsHidden(false)
            }
        }
    }

    override fun onPause() {
        cumulativeScrollDownPx = 0
        cumulativeScrollUpPx = 0
        setBrowseStatusBarsHidden(false)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            cumulativeScrollDownPx = 0
            cumulativeScrollUpPx = 0
            setBrowseStatusBarsHidden(false)
        } else if (isBrowseShowingToUser()) {
            val pageBinding = _binding ?: return
            val totalRange = pageBinding.appBar.totalScrollRange
            if (totalRange > 0 && -lastAppBarVerticalOffset >= (totalRange * 0.45f).toInt()) {
                setBrowseStatusBarsHidden(true)
            }
        }
    }

    override fun scrollToTop() {
        val pageBinding = _binding ?: return
        cumulativeScrollDownPx = 0
        cumulativeScrollUpPx = 0
        setBrowseStatusBarsHidden(false)
        pageBinding.appBar.setExpanded(true, true)
        currentFragment?.takeIf { it.view != null }?.let { (it as? Scrollable)?.scrollToTop() }
    }

    override fun onDestroyView() {
        cumulativeScrollDownPx = 0
        cumulativeScrollUpPx = 0
        setBrowseStatusBarsHidden(false)
        appBarOffsetListener?.let { _binding?.appBar?.removeOnOffsetChangedListener(it) }
        appBarOffsetListener = null
        pageChangeCallback?.let { _binding?.viewPager?.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        childFragmentManager.unregisterFragmentLifecycleCallbacks(pageLifecycleCallbacks)
        dispatchPagerScrollState(false)
        configuredRecyclerViews.clear()
        playerOverlayTabAvoidance?.close()
        playerOverlayTabAvoidance = null
        _binding = null
        super.onDestroyView()
    }
}
