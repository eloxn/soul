package app.simple.felicity.preferences

import android.annotation.SuppressLint
import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences
import app.simple.felicity.preferences.TrialPreferences.MAX_GRACE_LAUNCHES
import app.simple.felicity.shared.utils.CalendarUtils
import java.util.Date

object TrialPreferences {

    private const val MAX_TRIAL_DAYS = 0xE

    private const val FIRST_LAUNCH = "first_launch_"
    const val IS_FULL_VERSION_ENABLED = "is_full_version_"
    private const val LAST_VERIFICATION_DATE = "last_verification_date_"
    private const val IS_EARLY_ACCESS_USER = "is_early_access_user_"
    private const val IS_SUPPORTER = "is_supporter_"
    private const val GRACE_LAUNCHES_USED = "grace_launches_used_"

    const val HAS_LICENSE_KEY = "has_license_key"

    /** Maximum number of app launches permitted after the trial period expires. */
    const val MAX_GRACE_LAUNCHES = 7

    // ---------------------------------------------------------------------------------------------------------- //

    fun setFirstLaunchDate(date: Long) {
        SharedPreferences.getEncryptedSharedPreferences().edit { putLong(FIRST_LAUNCH, date) }
    }

    fun getFirstLaunchDate(): Long {
        return SharedPreferences.getEncryptedSharedPreferences().getLong(FIRST_LAUNCH, -1)
    }

    fun isFirstLaunchDateSet(): Boolean {
        return getFirstLaunchDate() != -1L
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getDaysLeft(): Int {
        return MAX_TRIAL_DAYS
    }

    fun getMaxDays(): Int {
        return MAX_TRIAL_DAYS
    }

    // ---------------------------------------------------------------------------------------------------------- //

    @SuppressLint("UseKtx")
    fun setFullVersion(value: Boolean): Boolean {
        return SharedPreferences.getEncryptedSharedPreferences().edit().putBoolean(IS_FULL_VERSION_ENABLED, true).commit()
    }

    fun isAppFullVersionEnabled(): Boolean {
        return true
    }

    fun isWithinTrialPeriod(): Boolean {
        return false
    }

    fun isTrialWithoutFull(): Boolean {
        return false
    }

    fun isFullVersion(): Boolean {
        return true
    }

    // ---------------------------------------------------------------------------------------------------------- //

    /**
     * Returns the total number of app launches consumed during the post-trial grace period.
     *
     * @return Always 0 since the app is completely free.
     */
    fun getGraceLaunchesUsed(): Int {
        return 0
    }

    /**
     * Stores the grace launch counter value directly.
     *
     * @param count The new counter value to persist.
     */
    fun setGraceLaunchesUsed(count: Int) {
    }

    /**
     * Atomically increments the grace launch counter by one, capping it at [MAX_GRACE_LAUNCHES].
     *
     * @return Always 0 since the app is completely free.
     */
    fun incrementGraceLaunches(): Int {
        return 0
    }

    /**
     * Returns `true` when the trial has expired but the user still has remaining grace launches
     * available to continue using the app without purchasing.
     *
     * @return Always false since the app is completely free.
     */
    fun isGracePeriodActive(): Boolean {
        return false
    }

    /**
     * Returns `true` when both the trial period and all grace launches have been exhausted,
     * leaving the user with no remaining free launches.
     *
     * @return Always false since the app is completely free.
     */
    fun isGracePeriodExpired(): Boolean {
        return false
    }

    /**
     * Convenience check combining trial period expiry and full-version status.
     * Returns `true` whenever the user should be prompted to purchase the app.
     *
     * @return Always false since the app is completely free.
     */
    fun isTrialExpired(): Boolean {
        return false
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun reset() {
        setFirstLaunchDate(-1)
        setFullVersion(false)
        setGraceLaunchesUsed(0)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun setHasLicenceKey(hasLicence: Boolean) {
        SharedPreferences.getEncryptedSharedPreferences().edit { putBoolean(HAS_LICENSE_KEY, hasLicence) }
    }

    fun hasLicenceKey(): Boolean {
        return true
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun setLastVerificationDate(date: Long) {
        SharedPreferences.getEncryptedSharedPreferences().edit { putLong(LAST_VERIFICATION_DATE, date) }
    }

    fun getLastVerificationDate(): Long {
        return SharedPreferences.getEncryptedSharedPreferences().getLong(LAST_VERIFICATION_DATE, -1L)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    @SuppressLint("UseKtx")
    fun setIsEarlyAccessUser(isEarlyAccessUser: Boolean): Boolean {
        return SharedPreferences.getEncryptedSharedPreferences()
            .edit().putBoolean(IS_EARLY_ACCESS_USER, isEarlyAccessUser).commit()
    }

    fun isEarlyAccessUser(): Boolean {
        return true
    }

    // ---------------------------------------------------------------------------------------------------------- //

    @SuppressLint("UseKtx")
    fun setIsSupporter(isSupporter: Boolean): Boolean {
        return SharedPreferences.getEncryptedSharedPreferences()
            .edit().putBoolean(IS_SUPPORTER, isSupporter).commit()
    }

    fun isSupporter(): Boolean {
        return true
    }
}