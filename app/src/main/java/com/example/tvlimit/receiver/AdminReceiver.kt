package com.example.tvlimit.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.example.tvlimit.R

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("AdminReceiver", "Device Admin Enabled")
        Toast.makeText(context, "已开启设备管理权限", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("AdminReceiver", "Device Admin Disabled")
        Toast.makeText(context, "已关闭设备管理权限", Toast.LENGTH_SHORT).show()
    }
}
