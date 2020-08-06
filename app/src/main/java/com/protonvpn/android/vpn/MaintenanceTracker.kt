package com.protonvpn.android.vpn

import android.os.SystemClock
import com.protonvpn.android.ProtonApplication
import com.protonvpn.android.R
import com.protonvpn.android.api.ProtonApiRetroFit
import com.protonvpn.android.appconfig.AppConfig
import com.protonvpn.android.components.NotificationHelper
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.ui.home.ServerListUpdater
import com.protonvpn.android.utils.ProtonLogger
import com.protonvpn.android.utils.ReschedulableTask
import com.protonvpn.android.utils.ServerManager
import kotlinx.coroutines.CoroutineScope
import me.proton.core.network.domain.ApiResult
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Singleton
class MaintenanceTracker(
    val scope: CoroutineScope,
    private val api: ProtonApiRetroFit,
    private val serverManager: ServerManager,
    private val serverListUpdater: ServerListUpdater,
    private val appConfig: AppConfig
) {

    private lateinit var stateMonitor: VpnStateMonitor

    private fun getScheduleDelay(): Long = TimeUnit.MINUTES.toMillis(appConfig.getMaintenanceTrackerDelay())

    private fun now() = SystemClock.elapsedRealtime()

    private val task = ReschedulableTask(scope, ::now) {
        if (stateMonitor.isConnected && appConfig.isMaintenanceTrackerEnabled()) {
            checkMaintenanceReconnect()
            scheduleIn(getScheduleDelay())
        }
    }

    fun initWithStateMonitor(vpnStateMonitor: VpnStateMonitor) {
        stateMonitor = vpnStateMonitor
        vpnStateMonitor.vpnStatus.observeForever { status ->
            if (status.state == VpnState.Connected) {
                task.scheduleIn(getScheduleDelay())
            }
        }
    }

    suspend fun checkMaintenanceReconnect(): Boolean {
        if (!appConfig.isMaintenanceTrackerEnabled())
            return false

        ProtonLogger.log("Check if server is not in maintenance")
        val result = api.getConnectingDomain(stateMonitor.connectionParams!!.connectingDomain.id)
        if (result is ApiResult.Success) {
            val connectingDomain = result.value.connectingDomain
            if (!connectingDomain.isOnline) {
                serverManager.updateServerDomainStatus(connectingDomain)
                serverListUpdater.updateServerList()
                val context = ProtonApplication.getAppContext()
                ProtonLogger.log("Maintenance detected in ${result.value.connectingDomain.entryDomain}", true)
                stateMonitor.connect(
                    context, Profile.getTempProfile(serverManager.getBestScoreServer(), serverManager)
                )
                NotificationHelper.showInformationNotification(
                    context, context.getString(R.string.onMaintenanceDetected)
                )
                return true
            }
        }
        return false
    }
}
