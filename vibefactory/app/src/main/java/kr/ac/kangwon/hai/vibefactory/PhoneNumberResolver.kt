package kr.ac.kangwon.hai.vibefactory

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

internal object PhoneNumberResolver {
    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    fun readFromSim(context: Context): String? {
        if (!hasPermission(context)) return null

        return runCatching {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return null
            val rawNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                val subscriptionId = SubscriptionManager.getDefaultSubscriptionId()
                subscriptionManager
                    ?.takeIf { subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
                    ?.getPhoneNumber(subscriptionId)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { readLegacyLine1Number(telephonyManager) }
            } else {
                readLegacyLine1Number(telephonyManager)
            }
            normalize(rawNumber)
        }.getOrNull()
    }

    fun normalize(raw: String?): String? {
        val digits = raw.orEmpty().filter(Char::isDigit)
        if (digits.isBlank()) return null
        return when {
            digits.startsWith("82") && digits.length >= 11 -> "0" + digits.drop(2)
            else -> digits
        }.takeIf(String::isNotBlank)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("HardwareIds", "MissingPermission")
    private fun readLegacyLine1Number(telephonyManager: TelephonyManager): String {
        return telephonyManager.line1Number?.trim().orEmpty()
    }
}
