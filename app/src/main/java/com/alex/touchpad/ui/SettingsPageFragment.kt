package com.alex.touchpad.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

class SettingsPageFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutResId = requireArguments().getInt(ARG_LAYOUT_RES_ID)
        return inflater.inflate(layoutResId, container, false)
    }

    companion object {
        private const val ARG_LAYOUT_RES_ID = "layout_res_id"

        fun newInstance(@LayoutRes layoutResId: Int): SettingsPageFragment {
            return SettingsPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_LAYOUT_RES_ID, layoutResId)
                }
            }
        }
    }
}
