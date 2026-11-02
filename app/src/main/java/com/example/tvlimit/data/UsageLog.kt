package com.example.tvlimit.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "usage_logs")
data class UsageLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val profileId: Int,
    val date: String, // ISO-8601 YYYY-MM-DD
    val totalUsageMinutes: Int
)
