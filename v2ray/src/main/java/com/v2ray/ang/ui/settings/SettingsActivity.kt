package com.v2ray.ang.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar

class SettingsActivity : BaseComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        SettingsScreen(
            viewModel = viewModel,
            onBackClick = { finish() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    var uiSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var coreSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var muxSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var fragmentSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var observatorySettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedSettingsExpanded by rememberSaveable { mutableStateOf(true) }


    var mux by rememberMmkvBool(AppConfig.PREF_MUX_ENABLED, false)
    var muxConcurrency by rememberMmkvString(AppConfig.PREF_MUX_CONCURRENCY, "8")
    var muxXudpConcurrency by rememberMmkvString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY)
    var muxXudpQuic by rememberMmkvString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")

    var fragment by rememberMmkvBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
    var fragmentPackets by rememberMmkvString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello")
    var fragmentLength by rememberMmkvString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
    var fragmentInterval by rememberMmkvString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    var fragmentMaxSplit by rememberMmkvString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10")
    var observatoryLeastPingInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
    var observatoryLeastLoadInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
    var observatoryLeastLoadMethod by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
    var observatoryLeastLoadSampling by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING)
    var observatoryLeastLoadTimeout by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)

    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)
    var confirmRemove by rememberMmkvBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    var doubleColumnDisplay by rememberMmkvBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    var groupAllDisplay by rememberMmkvBool(AppConfig.PREF_GROUP_ALL_DISPLAY, false)
    var ipv6Enabled by rememberMmkvBool(AppConfig.PREF_IPV6_ENABLED, false)
    var preferIpv6 by rememberMmkvBool(AppConfig.PREF_PREFER_IPV6, false)
    var sniffingEnabled by rememberMmkvBool(AppConfig.PREF_SNIFFING_ENABLED, true)
    var routeOnlyEnabled by rememberMmkvBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
    var remoteDns by rememberMmkvString(AppConfig.PREF_REMOTE_DNS, "")
    var domesticDns by rememberMmkvString(AppConfig.PREF_DOMESTIC_DNS, "")
    var dnsHosts by rememberMmkvString(AppConfig.PREF_DNS_HOSTS, "")
    var coreLogLevel by rememberMmkvString(AppConfig.PREF_LOGLEVEL, "warning")
    var outboundResolveMethod by rememberMmkvString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD)

    var delayTestUrl by rememberMmkvString(AppConfig.PREF_DELAY_TEST_URL, "")
    var realPingConcurrency by rememberMmkvString(AppConfig.PREF_REAL_PING_CONCURRENCY, "16")
    var ipApiUrl by rememberMmkvString(AppConfig.PREF_IP_API_URL, "")

    val muxXudpConcurrencyInt = muxXudpConcurrency.toIntOrNull() ?: AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY.toInt()

    val coreLogLevelEntries = stringArrayResource(R.array.core_loglevel).toList()
    val coreLogLevelValues = stringArrayResource(R.array.core_loglevel).toList()
    val outboundResolveEntries = stringArrayResource(R.array.outbound_domain_resolve_method).toList()
    val outboundResolveValues = stringArrayResource(R.array.outbound_domain_resolve_method_value).toList()
    val xudpQuicEntries = stringArrayResource(R.array.mux_xudp_quic_entries).toList()
    val xudpQuicValues = stringArrayResource(R.array.mux_xudp_quic_value).toList()
    val fragmentPacketsEntries = stringArrayResource(R.array.fragment_packets).toList()
    val fragmentPacketsValues = stringArrayResource(R.array.fragment_packets).toList()
    val observatoryLeastLoadMethodEntries = stringArrayResource(R.array.observatory_least_load_method).toList()
    val observatoryLeastLoadMethodValues = stringArrayResource(R.array.observatory_least_load_method).toList()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_settings),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_ui_settings),
                expanded = uiSettingsExpanded,
                onExpandedChange = { uiSettingsExpanded = it }
            )
            if (uiSettingsExpanded) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_speed_enabled),
                    summary = stringResource(R.string.summary_pref_speed_enabled),
                    checked = speedEnabled,
                    onCheckedChange = { speedEnabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_confirm_remove),
                    summary = stringResource(R.string.summary_pref_confirm_remove),
                    checked = confirmRemove,
                    onCheckedChange = { confirmRemove = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_double_column_display),
                    summary = stringResource(R.string.summary_pref_double_column_display),
                    checked = doubleColumnDisplay,
                    onCheckedChange = {
                        doubleColumnDisplay = it
                        SettingsChangeManager.makeSetupGroupTab()
                    }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_group_all_display),
                    summary = stringResource(R.string.summary_pref_group_all_display),
                    checked = groupAllDisplay,
                    onCheckedChange = {
                        groupAllDisplay = it
                        SettingsChangeManager.makeSetupGroupTab()
                    }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_core_settings),
                expanded = coreSettingsExpanded,
                onExpandedChange = { coreSettingsExpanded = it }
            )
            if (coreSettingsExpanded) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_ipv6_enabled),
                    summary = stringResource(R.string.summary_pref_ipv6_enabled),
                    checked = ipv6Enabled,
                    onCheckedChange = { ipv6Enabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_prefer_ipv6),
                    summary = stringResource(R.string.summary_pref_prefer_ipv6),
                    checked = preferIpv6,
                    onCheckedChange = { preferIpv6 = it }
                )

                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_sniffing_enabled),
                    summary = stringResource(R.string.summary_pref_sniffing_enabled),
                    checked = sniffingEnabled,
                    onCheckedChange = { sniffingEnabled = it }
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_route_only_enabled),
                    summary = stringResource(R.string.summary_pref_route_only_enabled),
                    checked = routeOnlyEnabled,
                    onCheckedChange = { routeOnlyEnabled = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_remote_dns),
                    value = remoteDns,
                    onValueChanged = { remoteDns = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_domestic_dns),
                    value = domesticDns,
                    onValueChanged = { domesticDns = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_dns_hosts),
                    value = dnsHosts,
                    onValueChanged = { dnsHosts = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_core_loglevel),
                    entries = coreLogLevelEntries,
                    values = coreLogLevelValues,
                    selectedValue = coreLogLevel,
                    onSelected = { coreLogLevel = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_outbound_domain_resolve_method),
                    entries = outboundResolveEntries,
                    values = outboundResolveValues,
                    selectedValue = outboundResolveMethod,
                    onSelected = { outboundResolveMethod = it }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_mux_settings),
                expanded = muxSettingsExpanded,
                onExpandedChange = { muxSettingsExpanded = it }
            )
            if (muxSettingsExpanded) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_mux_enabled),
                    summary = stringResource(R.string.summary_pref_mux_enabled),
                    checked = mux,
                    onCheckedChange = { mux = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_mux_concurrency),
                    value = muxConcurrency,
                    enabled = mux,
                    keyboardNumber = true,
                    onValueChanged = { muxConcurrency = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_mux_xudp_concurrency),
                    value = muxXudpConcurrency,
                    enabled = mux,
                    keyboardNumber = true,
                    onValueChanged = { muxXudpConcurrency = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_mux_xudp_quic),
                    entries = xudpQuicEntries,
                    values = xudpQuicValues,
                    selectedValue = muxXudpQuic,
                    enabled = mux && muxXudpConcurrencyInt >= 0,
                    onSelected = { muxXudpQuic = it }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_fragment_settings),
                expanded = fragmentSettingsExpanded,
                onExpandedChange = { fragmentSettingsExpanded = it }
            )
            if (fragmentSettingsExpanded) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_fragment_enabled),
                    checked = fragment,
                    onCheckedChange = { fragment = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_fragment_packets),
                    entries = fragmentPacketsEntries,
                    values = fragmentPacketsValues,
                    selectedValue = fragmentPackets,
                    enabled = fragment,
                    onSelected = { fragmentPackets = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_length),
                    value = fragmentLength,
                    enabled = fragment,
                    onValueChanged = { fragmentLength = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_interval),
                    value = fragmentInterval,
                    enabled = fragment,
                    onValueChanged = { fragmentInterval = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_fragment_maxsplit),
                    value = fragmentMaxSplit,
                    enabled = fragment,
                    keyboardNumber = true,
                    onValueChanged = { fragmentMaxSplit = it }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_observatory_settings),
                expanded = observatorySettingsExpanded,
                onExpandedChange = { observatorySettingsExpanded = it }
            )
            if (observatorySettingsExpanded) {
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_ping_interval),
                    value = observatoryLeastPingInterval,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastPingInterval = value
                        }
                    }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_interval),
                    value = observatoryLeastLoadInterval,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastLoadInterval = value
                        }
                    }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_method),
                    entries = observatoryLeastLoadMethodEntries,
                    values = observatoryLeastLoadMethodValues,
                    selectedValue = observatoryLeastLoadMethod,
                    onSelected = { observatoryLeastLoadMethod = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_sampling),
                    value = observatoryLeastLoadSampling,
                    keyboardNumber = true,
                    onValueChanged = {
                        viewModel.validateObservatorySampling(it)?.let { value ->
                            observatoryLeastLoadSampling = value
                        }
                    }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_observatory_least_load_timeout),
                    value = observatoryLeastLoadTimeout,
                    onValueChanged = {
                        viewModel.validateObservatoryDuration(it)?.let { value ->
                            observatoryLeastLoadTimeout = value
                        }
                    }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_advanced),
                expanded = advancedSettingsExpanded,
                onExpandedChange = { advancedSettingsExpanded = it }
            )
            if (advancedSettingsExpanded) {
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_delay_test_url),
                    value = delayTestUrl,
                    onValueChanged = { delayTestUrl = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_real_ping_concurrency),
                    value = realPingConcurrency,
                    keyboardNumber = true,
                    onValueChanged = { realPingConcurrency = it }
                )
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_ip_api_url),
                    value = ipApiUrl,
                    onValueChanged = { ipApiUrl = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }
}
