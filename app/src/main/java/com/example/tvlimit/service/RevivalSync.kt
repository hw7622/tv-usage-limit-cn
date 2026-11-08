package com.example.tvlimit.service

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * 第二条复活通道：同步框架（SyncAdapter）。
 *
 * 为什么要两条：Android 9 起，`ro.config.low_ram=true` 的电视（小米的部分机型）
 * 会拒绝一切通知监听服务，[Revival.Listener] 根本绑不上；而同步框架没有这个限制，
 * 账号和同步任务都属于系统，`force-stop` 清不掉，系统在系统设置被写入后
 * （省电管家杀进程、ADB 开关变化都算）大约 30 秒会来跑一次同步，顺手把我们的进程拉起来。
 *
 * 同步本身什么也不做，只确认监控服务还在。参考开源电视桌面 Aerial 的同名实现。
 */
object RevivalSync {

    private const val TAG = "TvLimit"
    /** 借用系统设置的 authority：它的每次写入都会触发一次网络同步。 */
    private const val AUTHORITY = "settings"

    fun ensure(context: Context) {
        val app = context.applicationContext
        runCatching {
            val type = app.packageName
            val am = AccountManager.get(app)
            val account = am.getAccountsByType(type).firstOrNull()
                ?: Account("电视时间管家", type).also {
                    if (!am.addAccountExplicitly(it, null, null)) return
                    Log.i(TAG, "revive: sync account created")
                }
            if (ContentResolver.getIsSyncable(account, AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            }
            if (!ContentResolver.getSyncAutomatically(account, AUTHORITY)) {
                ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            }
            if (!ContentResolver.getMasterSyncAutomatically()) {
                Log.w(TAG, "revive: master sync is off on this TV, sync revival unavailable")
            }
        }.onFailure { Log.w(TAG, "revive: could not set up sync: ${it.message}") }
    }
}

/** 同步服务的空壳：系统 bind 我们的时候，只确认监控服务还活着。 */
class RevivalSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }

    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(
            account: Account?,
            extras: Bundle?,
            authority: String?,
            provider: ContentProviderClient?,
            syncResult: SyncResult?
        ) {
            runCatching { Revival.reviveIfNeeded(context, "sync") }
        }
    }
}

/** 上面那个同步账号要的认证器。它登录不了任何东西，纯粹为了让账号能建出来。 */
class RevivalAccountService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
        override fun addAccount(
            response: AccountAuthenticatorResponse?, accountType: String?,
            authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?
        ): Bundle? = null

        override fun confirmCredentials(
            response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?
        ): Bundle? = null

        override fun getAuthToken(
            response: AccountAuthenticatorResponse?, account: Account?,
            authTokenType: String?, options: Bundle?
        ): Bundle? = null

        override fun getAuthTokenLabel(authTokenType: String?): String? = null

        override fun updateCredentials(
            response: AccountAuthenticatorResponse?, account: Account?,
            authTokenType: String?, options: Bundle?
        ): Bundle? = null

        override fun hasFeatures(
            response: AccountAuthenticatorResponse?, account: Account?,
            features: Array<out String>?
        ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
