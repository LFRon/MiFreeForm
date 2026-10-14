package com.buildsession.betterYAMF.manager.ui.setting

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.gson
import com.buildsession.betterYAMF.databinding.ActivitySettingBinding
import com.buildsession.betterYAMF.manager.services.YAMFManagerProxy
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

class SettingActivity : AppCompatActivity() {

    private var _binding: ActivitySettingBinding? = null
    private val binding get() = _binding

    lateinit var config: YAMFConfig
    private val preference: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _binding = ActivitySettingBinding.inflate(layoutInflater)
        setContentView(binding?.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initData()
    }

    private fun initData() {
        binding?.apply {
            config = gson.fromJson(YAMFManagerProxy.configJson, YAMFConfig::class.java)
            applyConfigToViews()

            btnResetConfig.setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingActivity)
                    .setTitle(R.string.reset_config_title)
                    .setMessage(R.string.reset_config_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        config = YAMFConfig()
                        preference.edit().putBoolean("useAppList", true).apply()
                        applyConfigToViews()
                        YAMFManagerProxy.updateConfig(gson.toJson(config))
                    }
                    .show()
            }

            sliderRounded.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    tvRoundedValue.text = "${slider.value.toInt()}"
                    config.windowRoundedCorner = slider.value.toInt()
                    YAMFManagerProxy.updateConfig(gson.toJson(config))
                }
            })

            sliderAnimationSpeed.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    tvAnimationSpeedValue.text = if (slider.value == 5100f){
                        getString(R.string.default_speed)
                    } else{
                        "${slider.value.toInt()}"
                    }
                    config.animationSpeed = slider.value
                    YAMFManagerProxy.updateConfig(gson.toJson(config))
                }
            })
        }
    }

    private fun applyConfigToViews() {
        binding?.apply {
            sColoerd.isChecked = config.coloredController
            sBackHome.isChecked = config.recentBackHome
            etSizeH.hint = getString(R.string.window_size_auto)
            etSizeH.setText(if (config.defaultWindowHeight > 0) config.defaultWindowHeight.toString() else "")
            etSizeW.setText(config.defaultWindowWidth.toString())
            sHookLauncherHookRecents.isChecked = config.hookLauncher.hookRecents
            sHookLauncherHookTaskbar.isChecked = config.hookLauncher.hookTaskbar
            sHookLauncherHookPopup.isChecked = config.hookLauncher.hookPopup
            sHookLauncherHookTransientTaskbar.isChecked = config.hookLauncher.hookTransientTaskbar
            sUseAppList.isChecked = preference.getBoolean("useAppList", true)
            sForceShowIME.isChecked = config.showForceShowIME
            sForcePreventRelaunch.isChecked = config.forcePreventRelaunch
            sliderRounded.value = config.windowRoundedCorner.toFloat()
            sliderAnimationSpeed.value = config.animationSpeed
            tvRoundedValue.text = "${config.windowRoundedCorner}"
            tvAnimationSpeedValue.text = if (config.animationSpeed == 5100f) {
                getString(R.string.default_speed)
            } else {
                "${config.animationSpeed.toFloat()}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding?.apply {
            config.coloredController = sColoerd.isChecked
            config.recentBackHome = sBackHome.isChecked
            config.defaultWindowHeight = etSizeH.text.toString().toIntOrNull() ?: 0
            config.defaultWindowWidth = etSizeW.text.toString().toIntOrNull() ?: config.defaultWindowWidth
            config.hookLauncher.hookRecents = sHookLauncherHookRecents.isChecked
            config.hookLauncher.hookTaskbar = sHookLauncherHookTaskbar.isChecked
            config.hookLauncher.hookPopup = sHookLauncherHookPopup.isChecked
            config.hookLauncher.hookTransientTaskbar = sHookLauncherHookTransientTaskbar.isChecked
            config.showForceShowIME = sForceShowIME.isChecked
            config.forcePreventRelaunch = sForcePreventRelaunch.isChecked
            preference.edit().putBoolean("useAppList", sUseAppList.isChecked).apply()
            YAMFManagerProxy.updateConfig(gson.toJson(config))
        }
    }
}
