package crazydude.com.telemetry.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.google.firebase.analytics.FirebaseAnalytics
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.PreferenceManager

class PrefsFragment : PreferenceFragmentCompat() {

    private lateinit var prefManager: PreferenceManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "settings"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        prefManager = PreferenceManager(context!!)

    }

    override fun onDestroy() {
        super.onDestroy()
    }
}