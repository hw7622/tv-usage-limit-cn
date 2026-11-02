package com.example.tvlimit.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val isRestricted: Boolean, // True for Child, False for Parent
    val pin: String?, // 4 digit PIN, nullable for Child if we want no-pin access? But spec says PIN entry required for override.
    val dailyLimitMinutes: Int, // 0 or -1 for unlimited
    val sessionLimitMinutes: Int,
    val restDurationMinutes: Int,
    val restrictedApps: String? = null // Comma separated package names
)
