/*
 * Copyright (c) 2019 Proton Technologies AG
 * 
 * This file is part of ProtonVPN.
 * 
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.protonvpn.android.ui.home.countries

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.protonvpn.android.R
import com.protonvpn.android.api.NetworkLoader
import com.protonvpn.android.components.BaseFragmentV2
import com.protonvpn.android.components.ContentLayout
import com.protonvpn.android.components.LoaderUI
import com.protonvpn.android.components.NetworkFrameLayout
import com.protonvpn.android.databinding.FragmentCountryListBinding
import com.protonvpn.android.models.vpn.VpnCountry
import com.protonvpn.android.utils.Log
import com.xwray.groupie.ExpandableGroup
import com.xwray.groupie.Group
import com.xwray.groupie.GroupAdapter
import com.xwray.groupie.GroupieViewHolder
import me.proton.core.network.domain.ApiResult
import javax.inject.Inject

@ContentLayout(R.layout.fragment_country_list)
class CountryListFragment : BaseFragmentV2<CountryListViewModel, FragmentCountryListBinding>(), NetworkLoader {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory

    override fun initViewModel() {
        viewModel =
                ViewModelProviders.of(this, viewModelFactory).get(CountryListViewModel::class.java)
    }

    override fun onViewCreated() {
        initList()
        observeLiveEvents()
        binding.loadingContainer.setOnRefreshListener { viewModel.refreshServerList(this) }
    }

    private fun observeLiveEvents() {
        viewModel.userData.updateEvent.observe(viewLifecycleOwner) {
            updateListData()
            if (viewModel.userData.isFreeUser)
                binding.list.scrollToPosition(0)
        }
        viewModel.serverManager.updateEvent.observe(viewLifecycleOwner) {
            updateListData()
        }
    }

    private fun initList() = with(binding.list) {
        val groupAdapter = GroupAdapter<GroupieViewHolder>()
        val groupLayoutManager = GridLayoutManager(context, groupAdapter.spanCount).apply {
            spanSizeLookup = groupAdapter.spanSizeLookup
        }
        adapter = groupAdapter
        layoutManager = groupLayoutManager
        (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        updateListData()
    }

    private fun addCountriesGroup(
        groups: MutableList<Group>,
        @StringRes header: Int?,
        countries: List<VpnCountry>,
        expandedCountriesIds: Set<Long>
    ) {
        if (header != null)
            groups.add(HeaderItem(header, null))

        for (country in countries) {
            val expandableHeaderItem = object : CountryViewHolder(viewModel, country, viewLifecycleOwner) {
                override fun onExpanded(position: Int) {
                    this@CountryListFragment.binding.list.smoothScrollToPosition(
                            position + if (viewModel.userData.isSecureCoreEnabled) 1 else 2
                    )
                }
            }

            groups.add(ExpandableGroup(expandableHeaderItem).apply {
                isExpanded = expandableHeaderItem.id in expandedCountriesIds &&
                        country.hasAccessibleOnlineServer(viewModel.userData)
                viewModel.getMappedServersForCountry(country).forEach { (title, servers, infoKey) ->
                    title?.let { add(HeaderItem(it, infoKey)) }
                    servers.forEach {
                        add(CountryExpandedViewHolder(
                            viewModel, it, viewLifecycleOwner, title == R.string.listFastestServer))
                    }
                }
            })
        }
    }

    private fun updateListData() {
        val newGroups = mutableListOf<Group>()
        val groupAdapter = binding.list.adapter as GroupAdapter<GroupieViewHolder>

        val expandedCountriesIds = getExpandedCountriesIds(groupAdapter)
        if (viewModel.userData.isFreeUser && !viewModel.userData.isSecureCoreEnabled) {
            val (free, premium) = viewModel.getFreeAndPremiumCountries()
            addCountriesGroup(newGroups, R.string.listFreeCountries, free, expandedCountriesIds)
            addCountriesGroup(newGroups, R.string.listPremiumCountries, premium, expandedCountriesIds)
        } else {
            addCountriesGroup(newGroups, null, viewModel.getCountriesForList(), expandedCountriesIds)
        }
        groupAdapter.update(newGroups)
    }

    private fun getExpandedCountriesIds(groupAdapter: GroupAdapter<GroupieViewHolder>) = with(groupAdapter) {
        (0 until groupCount).asSequence()
                .filter { (getGroup(it) as? ExpandableGroup)?.isExpanded == true }
                .map { getItem(it).id }
                .toSet()
    }

    override fun getNetworkFrameLayout(): LoaderUI {
        try {
            return binding.loadingContainer
        } catch (e: IllegalStateException) {
            // FIXME: getNetworkFrameLayout is called from network callbacks that are unaware of
            //  views lifecycles, this needs to be refactored, for now return fake LoaderUI.
            Log.exception(e)
            return object : LoaderUI {
                override val state: NetworkFrameLayout.State =
                        NetworkFrameLayout.State.EMPTY

                override fun switchToLoading() {}
                override fun setRetryListener(listener: () -> Unit) {}
                override fun switchToEmpty() {}
                override fun switchToRetry(error: ApiResult.Error) {}
            }
        }
    }
}
