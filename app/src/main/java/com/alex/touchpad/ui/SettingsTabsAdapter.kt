package com.alex.touchpad.ui

import androidx.annotation.LayoutRes
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

data class SettingsTabSpec(
    val title: String,
    @LayoutRes val layoutResId: Int,
)

class SettingsTabsAdapter(
    activity: FragmentActivity,
    private val tabs: List<SettingsTabSpec>,
) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = tabs.size

    override fun createFragment(position: Int) = SettingsPageFragment.newInstance(tabs[position].layoutResId)
}
