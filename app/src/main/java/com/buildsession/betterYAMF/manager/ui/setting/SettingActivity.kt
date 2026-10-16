package com.buildsession.betterYAMF.manager.ui.setting

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.databinding.ActivitySettingBinding
import com.buildsession.betterYAMF.manager.core.settings.SettingsRepository
import com.buildsession.betterYAMF.common.model.Config as YAMFConfig

class SettingActivity : AppCompatActivity() {

    private var _binding: ActivitySettingBinding? = null
    private val binding get() = _binding

    lateinit var config: YAMFConfig

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
            config = SettingsRepository.load()
            applyConfigToViews()

            btnResetConfig.setOnClickListener {
                MaterialAlertDialogBuilder(this@SettingActivity)
                    .setTitle(R.string.reset_config_title)
                    .setMessage(R.string.reset_config_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        SettingsRepository.resetToDefaults()
                        config = SettingsRepository.current()
                        applyConfigToViews()
                    }
                    .show()
            }

            sliderRounded.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    tvRoundedValue.text = "${slider.value.toInt()}"
                    config.windowRoundedCorner = slider.value.toInt()
                    SettingsRepository.setWindowRoundedCorner(slider.value.toInt())
                }
            })

            sliderBarRounded.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    tvBarRoundedValue.text = "${slider.value.toInt()}"
                    config.barRoundedCorner = slider.value.toInt()
                    SettingsRepository.setBarRoundedCorner(slider.value.toInt())
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
                    SettingsRepository.setAnimationSpeed(slider.value)
                }
            })
        }
    }

    private fun applyConfigToViews() {
        binding?.apply {
            etSizeH.hint = getString(R.string.window_size_auto)
            etSizeH.setText(if (config.defaultWindowHeight > 0) config.defaultWindowHeight.toString() else "")
            etSizeW.setText(config.defaultWindowWidth.toString())
            sliderRounded.value = config.windowRoundedCorner.toFloat()
            sliderBarRounded.value = config.barRoundedCorner.toFloat()
            sliderAnimationSpeed.value = config.animationSpeed
            tvRoundedValue.text = "${config.windowRoundedCorner}"
            tvBarRoundedValue.text = "${config.barRoundedCorner}"
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
            val height = etSizeH.text.toString().toIntOrNull() ?: 0
            val width = etSizeW.text.toString().toIntOrNull()
            // 读-改-写：其他字段取模块最新值（单写者语义）
            SettingsRepository.update { cfg ->
                cfg.defaultWindowHeight = height
                if (width != null) cfg.defaultWindowWidth = width
            }
        }
    }
}
