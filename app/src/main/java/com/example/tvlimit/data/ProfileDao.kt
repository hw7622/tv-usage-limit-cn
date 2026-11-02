package com.example.tvlimit.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles")
    fun getAllProfiles(): Flow<List<Profile>>

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun getProfileCount(): Int

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Int): Profile?

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun getProfileByName(name: String): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: Profile)

    @Update
    suspend fun updateProfile(profile: Profile): Int

    /**
     * 只改「限制哪些应用」这一列。
     * 选应用页点「选好了」就立刻调它落库，不依赖上一页的「保存」按钮 —— 那条链路
     * 一旦被系统回收 / 返回键打断，家长勾了半天的应用就静默丢了。
     */
    @Query("UPDATE profiles SET restrictedApps = :apps WHERE id = :id")
    suspend fun updateRestrictedApps(id: Int, apps: String): Int

    // Usage Logs
    @Query("SELECT * FROM usage_logs WHERE profileId = :profileId AND date = :date LIMIT 1")
    suspend fun getUsageLog(profileId: Int, date: String): UsageLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsageLog(usageLog: UsageLog)

    @Query("UPDATE usage_logs SET totalUsageMinutes = :minutes WHERE id = :id")
    suspend fun updateUsageMinutes(id: Int, minutes: Int)

    /**
     * 把某一天的已用量清零。
     * 「限制哪些应用」这个口径换了（整机 ↔ 某几个应用）时用：旧数字是按另一套口径
     * 算出来的，直接搬到新口径上，孩子一进去就会被判「额度用完了」。
     */
    @Query("UPDATE usage_logs SET totalUsageMinutes = 0 WHERE profileId = :profileId AND date = :date")
    suspend fun resetUsageLog(profileId: Int, date: String)
}
