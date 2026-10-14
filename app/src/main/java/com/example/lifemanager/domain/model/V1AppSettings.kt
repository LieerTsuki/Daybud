package com.example.lifemanager.domain.model

import java.util.Currency

/** Unified application's preferences; neither legacy settings nor a backup DTO. */
data class V1AppSettings(val theme:ThemeMode=ThemeMode.SYSTEM,val dateFormat:DateFormat=DateFormat.YMD,
    val defaultCurrency:String="CNY",val planReminders:Boolean=true,val subscriptionReminders:Boolean=true,
    val defaultReminderDays:Set<Int> = emptySet())
enum class V1ReminderModule { PLAN, SUBSCRIPTION }
object V1SettingsRules {
    fun validate(value:V1AppSettings):String? = when {
        !validCurrency(value.defaultCurrency) -> "请输入有效的 ISO 4217 币种代码"
        value.defaultReminderDays.any { it !in setOf(1,3,7) } -> "订阅提醒仅支持提前 1、3、7 天"
        else -> null
    }
    private fun validCurrency(code:String)=try { Currency.getInstance(code).defaultFractionDigits>=0 } catch(_:IllegalArgumentException) { false }
}
