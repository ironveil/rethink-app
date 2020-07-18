/*
Copyright 2019 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.service

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.SystemClock
import android.preference.PreferenceManager
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.annotation.WorkerThread
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.R
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.net.manager.ConnectionTracer
import com.celzero.bravedns.ui.HomeScreenActivity
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized
import protect.Blocker
import protect.Protector
import java.util.*

class BraveVPNService:
    VpnService(), NetworkManager.NetworkListener, Protector, Blocker, OnSharedPreferenceChangeListener {

    @GuardedBy("vpnController")
    private var networkManager: NetworkManager? = null

    companion object{
        private const val LOG_TAG = "BraveVPNService"
        private const val DEBUG = false
        private const val SERVICE_ID = 1 // Only has to be unique within this app.

        private const val MAIN_CHANNEL_ID = "vpn"
        private const val WARNING_CHANNEL_ID = "warning"
        private const val NO_PENDING_CONNECTION = "This value is not a possible URL."
        private val vpnController : VpnController ?= VpnController.getInstance()
    }

    @GuardedBy("vpnController")
    private var url : String ?= null

    @GuardedBy("vpnController")
    private var vpnAdapter: GoVpnAdapter? = null

    private var networkConnected = false

    private lateinit var connTracer: ConnectionTracer

    enum class State {
        NEW, WORKING, FAILING
    }

    override fun block(protocol: Int, sourceAddress: String, destAddress: String): Boolean {
        val first = sourceAddress.split(":")
        val second = destAddress.split(":")
        try {
            val uid = connTracer.getUidQ(
                protocol,
                first[0],
                first[first.size-1].toInt(),
                second[0],
                second[second.size-1].toInt()
            )
            return isUidBlocked(uid)
        } catch (iex: Exception) {
            Log.e(LOG_TAG, iex.message, iex)
        }
        return false
    }

    private fun isUidBlocked(uid: Int): Boolean {
        val packageName = packageManager.getNameForUid(uid)
        if (DEBUG) Log.d(LOG_TAG, "uid: $uid / packageName: $packageName")
        // TODO: Implementation pending
        return false
    }

    override fun getResolvers(): String {
        val ips: MutableList<String?> = ArrayList()
        val ipsList = networkManager!!.getSystemResolvers()
        if (ipsList != null) {
            for (ip in ipsList) {
                val address = ip.hostAddress
                if (!GoVpnAdapter.FAKE_DNS_IP.equals(address)) {
                    ips.add(address)
                }
            }
        }
        return TextUtils.join(",", ips)
    }

    fun isOn(): Boolean {
        return vpnAdapter != null
    }

    fun newBuilder(): Builder? {
        var builder = Builder()
        if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            // Some WebRTC apps rely on the ability to bind to specific interfaces, which is only
            // possible if we allow bypass.

            builder = builder.allowBypass()
            try {
                // Workaround for any app incompatibility bugs.
                for (packageName in PersistantState.getExcludedPackages(this)!!) {
                    if(PersistantState.getFirewallMode(this) == 2) {
                        Log.w("BraveVPN","Allowed Apps in sink hole:"+packageName)
                        builder = builder.addAllowedApplication(packageName)
                    }
                    else
                        builder = builder.addDisallowedApplication(packageName)
                }
                // Play Store incompatibility is a known issue, so always exclude it.
                if(PersistantState.getFirewallMode(this) != 2) {
                    builder = builder.addDisallowedApplication("com.android.vending")
                    builder = builder.addDisallowedApplication(this.getPackageName())
                }
            } catch (e: PackageManager.NameNotFoundException) {
                //LogWrapper.logException(e)
                Log.e(LOG_TAG,"Failed to exclude an app",e)
            }
        }
        return builder
    }

    override fun onCreate() {
        connTracer = ConnectionTracer(this)
        vpnController!!.setBraveVpnService(this)
    }

    @InternalCoroutinesApi
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.i("VpnService",String.format("Starting DNS VPN service, url=%s", url))
        //val persistantState = PersistantState()
        //vpnController = vpnController!!.getInstance()
        if (vpnController != null) {
            synchronized(vpnController) {
                Log.i("VpnService",String.format("Starting DNS VPN service, url=%s", url))
                //TODO Move this hardcoded url to Persistent state
                //url = persistantState.getServerUrl(this)
                url = PersistantState.getServerUrl(this)
                Log.i("VpnService",String.format("Starting DNS VPN service, url=%s", url))
                // Registers this class as a listener for user preference changes.
                PreferenceManager.getDefaultSharedPreferences(this)
                    .registerOnSharedPreferenceChangeListener(this)

                if (networkManager != null) {
                    spawnServerUpdate()
                    return Service.START_REDELIVER_INTENT
                }

                // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
                // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
                // until we come online.

                // If we're online, |networkManager| immediately calls this.onNetworkConnected(), which in turn
                // calls startVpn() to actually start.  If we're offline, the startup actions will be delayed
                // until we come online.
                networkManager = NetworkManager(this,this)

                // Mark this as a foreground service.  This is normally done to ensure that the service
                // survives under memory pressure.  Since this is a VPN service, it is presumably protected
                // anyway, but the foreground service mechanism allows us to set a persistent notification,
                // which helps users understand what's going on, and return to the app if they want.

                // Mark this as a foreground service.  This is normally done to ensure that the service
                // survives under memory pressure.  Since this is a VPN service, it is presumably protected
                // anyway, but the foreground service mechanism allows us to set a persistent notification,
                // which helps users understand what's going on, and return to the app if they want.
                val mainActivityIntent = PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, HomeScreenActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

                var builder: Notification.Builder
                if (VERSION.SDK_INT >= VERSION_CODES.O) {
                    val name: CharSequence = getString(R.string.app_name_brave)
                    val description = getString(R.string.notification_content)
                    // LOW is the lowest importance that is allowed with startForeground in Android O.
                    val importance = NotificationManager.IMPORTANCE_LOW
                    val channel = NotificationChannel(MAIN_CHANNEL_ID,name,importance)
                    channel.description = description
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.createNotificationChannel(channel)
                    builder = Notification.Builder(this, MAIN_CHANNEL_ID)
                } else {
                    builder = Notification.Builder(this)
                    if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
                        // Min-priority notifications don't show an icon in the notification bar, reducing clutter.
                        // Only available in API >= 16.  Deprecated in API 26.
                        builder = builder.setPriority(Notification.PRIORITY_MIN)
                    }
                }

                builder.setSmallIcon(R.drawable.shield_green)
                    .setContentTitle(resources.getText(R.string.notification_title))
                    .setContentText(resources.getText(R.string.notification_content))
                    .setContentIntent(mainActivityIntent)

                if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                    // Secret notifications are not shown on the lock screen.  No need for this app to show there.
                    // Only available in API >= 21
                    builder = builder.setVisibility(Notification.VISIBILITY_SECRET)
                }

                startForeground(SERVICE_ID, builder.notification)
                updateQuickSettingsTile()
            }
        }
        return Service.START_REDELIVER_INTENT
    }

    @InternalCoroutinesApi
    private fun spawnServerUpdate() {
        if (vpnController != null) {
            synchronized(vpnController) {
                if (networkManager != null) {
                    Thread(
                        Runnable { updateServerConnection() }, "updateServerConnection-onStartCommand"
                    ).start()
                }
            }
        }
    }

    @WorkerThread
    private fun updateServerConnection() {
        vpnAdapter!!.updateDohUrl(HomeScreenActivity.GlobalVariable.dnsMode,HomeScreenActivity.GlobalVariable.firewallMode)
    }

    fun recordTransaction(transaction: Transaction) {
        Log.i("BraveDNS","New Transaction : " + transaction.name)
        transaction.responseTime = SystemClock.elapsedRealtime()
        transaction.responseCalendar = Calendar.getInstance()
        getTracker()!!.recordTransaction(this, transaction)
        val intent = Intent(InternalNames.RESULT.name)
        intent.putExtra(InternalNames.TRANSACTION.name, transaction)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        if (!networkConnected) {
            // No need to update the user-visible connection state while there is no network.
            return
        }

        // Update the connection state.  If the transaction succeeded, then the connection is working.
        // If the transaction failed, then the connection is not working.
        // If the transaction was canceled, then we don't have any new information about the status
        // of the connection, so we don't send an update.
        if (transaction.status === Transaction.Status.COMPLETE) {
            vpnController!!.onConnectionStateChanged(
                this,
                State.WORKING
            )
        } else if (transaction.status !== Transaction.Status.CANCELED) {
            vpnController!!.onConnectionStateChanged(
                this,
               State.FAILING
            )
        }
    }

    private fun getTracker(): QueryTracker? {
        return vpnController!!.getTracker(this)
    }

    @InternalCoroutinesApi
    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        //TODO Check on the PersistantState variable
        //val persistantState = PersistantState()
        if (PersistantState.APPS_KEY.equals(key) && vpnAdapter != null) {
            // Restart the VPN so the new app exclusion choices take effect immediately.
            restartVpn()
        }
        if((PersistantState.DNS_MODE.equals(key) || PersistantState.FIREWALL_MODE.equals(key))&&vpnAdapter != null){
            HomeScreenActivity.GlobalVariable.dnsMode = PersistantState.getDnsMode(this)
            HomeScreenActivity.GlobalVariable.firewallMode = PersistantState.getFirewallMode(this)
            restartVpn()
        }
        if (PersistantState.URL_KEY.equals(key)) {
            url = PersistantState.getServerUrl(this)
            spawnServerUpdate()
        }
    }

    fun signalStopService(userInitiated: Boolean) {
        Log.i("VpnService",String.format("Received stop signal. User initiated: %b", userInitiated))
        if (!userInitiated) {
            val vibrationPattern = longArrayOf(1000) // Vibrate for one second.
            // Show revocation warning
            var builder: Notification.Builder
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val name: CharSequence = getString(R.string.warning_channel_name)
                val description = getString(R.string.warning_channel_description)
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel( WARNING_CHANNEL_ID, name, importance)
                channel.description = description
                channel.enableVibration(true)
                channel.vibrationPattern = vibrationPattern
                notificationManager.createNotificationChannel(channel)
                builder = Notification.Builder(this,WARNING_CHANNEL_ID)
            } else {
                builder = Notification.Builder(this)
                builder.setVibrate(vibrationPattern)
                if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
                    // Only available in API >= 16.  Deprecated in API 26.
                    builder = builder.setPriority(Notification.PRIORITY_MAX)
                }
            }
            val mainActivityIntent = PendingIntent.getActivity(
                this, 0, Intent(this, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setSmallIcon(R.drawable.shield_green)
                .setContentTitle(resources.getText(R.string.warning_title))
                .setContentText(resources.getText(R.string.notification_content))
                .setContentIntent(mainActivityIntent) // Open the main UI if possible.
                .setAutoCancel(true)
            if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
                builder.setCategory(Notification.CATEGORY_ERROR)
            }
            notificationManager.notify(0, builder.notification)
        }
        stopVpnAdapter()
        stopSelf()
        updateQuickSettingsTile()
    }

    private fun updateQuickSettingsTile() {
        if (VERSION.SDK_INT >= VERSION_CODES.N) {
            TileService.requestListeningState(this,ComponentName(this, BraveTileService::class.java))
        }
    }

    private fun stopVpnAdapter() {
        if (vpnController != null) {
            kotlin.synchronized(vpnController) {
                if (vpnAdapter != null) {
                    vpnAdapter!!.close()
                    vpnAdapter = null
                    vpnController.onConnectionStateChanged(this, null)
                }
            }
        }
    }

    @InternalCoroutinesApi
    private fun restartVpn() {
        if (vpnController != null) {
            synchronized(vpnController) {

                // Attempt seamless handoff as described in the docs for VpnService.Builder.establish().
                val oldAdapter: GoVpnAdapter? = vpnAdapter
                vpnAdapter = makeVpnAdapter()
                oldAdapter!!.close()
                if (vpnAdapter != null) {
                    vpnAdapter!!.start(HomeScreenActivity.GlobalVariable.dnsMode,HomeScreenActivity.GlobalVariable.firewallMode)
                } else {
                    Log.i("VpnService",String.format("Restart failed"))

                }
            }
        }
    }

    private fun makeVpnAdapter(): GoVpnAdapter? {
        //GoVpnAdapter.establish(this)
        return GoVpnAdapter.establish(this)
    }

    override fun onNetworkConnected(networkInfo: NetworkInfo?) {
        Log.i("VpnService",String.format("Connected event."))
        setNetworkConnected(true)

        // This code is used to start the VPN for the first time, but startVpn is idempotent, so we can
        // call it every time. startVpn performs network activity so it has to run on a separate thread.
        Thread(
            object : Runnable {
                override fun run() {
                    //TODO Work on the order of the function call.
                    startVpn()
                    updateServerConnection()
                }
            }, "startVpn-onNetworkConnected"
        ).start()
    }

    override fun onNetworkDisconnected() {
        Log.i("VpnService",String.format("Disconnected event."))
        setNetworkConnected(false)
        vpnController!!.onConnectionStateChanged(this, null)
    }

    private fun setNetworkConnected(connected: Boolean) {
        networkConnected = connected
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            // Indicate that traffic will be sent over the current active network.
            // See e.g. https://issuetracker.google.com/issues/68657525
            val activeNetwork =
                getSystemService(ConnectivityManager::class.java).activeNetwork
            setUnderlyingNetworks(if (connected) arrayOf(activeNetwork) else null)
        }
    }

    @WorkerThread
    private fun startVpn() {
        kotlin.synchronized(vpnController!!) {
            if (vpnAdapter != null) {
                return
            }
            startVpnAdapter()
            vpnController.onStartComplete(this, vpnAdapter != null)
            if (vpnAdapter == null) {
                Log.w(LOG_TAG,"Failed to startVpn VPN adapter")
                stopSelf()
            }
        }
    }

    private fun startVpnAdapter() {
        kotlin.synchronized(vpnController!!) {
            if (vpnAdapter == null) {
                Log.w(LOG_TAG,"Starting VPN adapter")
                vpnAdapter = makeVpnAdapter()
                if (vpnAdapter != null) {
                    vpnAdapter!!.start(HomeScreenActivity.GlobalVariable.dnsMode,HomeScreenActivity.GlobalVariable.firewallMode)
                    //analytics.logStartVPN(vpnAdapter.getClass().getSimpleName())
                } else {
                    Log.w(LOG_TAG,"Failed to start VPN adapter!")
                }
            }
        }
    }

    override fun onDestroy() {
        kotlin.synchronized(vpnController!!) {
            Log.w(LOG_TAG,"Destroying DNS VPN service")

            PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this)
            if (networkManager != null) {
                networkManager!!.destroy()
            }
            vpnController!!.setBraveVpnService(null)
            stopForeground(true)
            if (vpnAdapter != null) {
                signalStopService(false)
            }
        }
    }

    override fun onRevoke() {
        Log.w(LOG_TAG,"VPN service revoked.")

        stopSelf()

        //TODO Check the below code
        // Disable autostart if VPN permission is revoked.
        //PersistentState.setVpnEnabled(this, false)
    }


}