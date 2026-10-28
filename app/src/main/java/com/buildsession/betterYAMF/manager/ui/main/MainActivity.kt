package com.buildsession.betterYAMF.manager.ui.main

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.slider.Slider
import com.buildsession.betterYAMF.BuildConfig
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.model.Config
import com.buildsession.betterYAMF.databinding.ActivityMainBinding
import com.buildsession.betterYAMF.manager.core.gateway.ActivationDetectionSupport
import com.buildsession.betterYAMF.manager.core.gateway.ModuleActivation
import com.buildsession.betterYAMF.manager.core.gateway.ModuleInfoGateway
import com.buildsession.betterYAMF.manager.core.gateway.WindowGateway
import com.buildsession.betterYAMF.manager.core.settings.SettingsRepository
import com.buildsession.betterYAMF.manager.sidebar.SidebarLauncher
import com.buildsession.betterYAMF.manager.ui.setting.SettingActivity

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding
    lateinit var config: Config
    private var applyingSettings = false
    private var serviceWasConnected = false
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) SidebarLauncher.start(this)
        else Toast.makeText(this, R.string.sidebar_overlay_permission_message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "reYAMF_MainActivity"
    }

    private val openCountListener: (Int) -> Unit = { count ->
        binding?.tvOpenCount?.text = count.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val typedValue = TypedValue()
        this@MainActivity.theme.resolveAttribute(android.R.attr.textColor, typedValue, true)
        binding?.toolbar?.overflowIcon?.setTint(typedValue.data)

        setSupportActionBar(binding?.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        WindowGateway.registerOpenCountListener(openCountListener)
        initUi()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ModuleInfoGateway.serviceConnected.collect { onServiceConnectionChanged(it) } }
                launch { ModuleInfoGateway.activation.collect { onActivationChanged(it) } }
            }
        }

    }

    private fun initUi() {
        config = SettingsRepository.load()
        when (ModuleInfoGateway.activationDetection) {
            ActivationDetectionSupport.SUPPORTED -> binding?.apply {
                tvHookSummary.setText(R.string.module_detection_connecting)
                tvActive.setText(R.string.not_activated)
                tvVersion.setText(R.string.module_detection_connecting_hint)
                ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
            }
        }
        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            binding?.systemVersion?.text = buildString {
                append(Build.VERSION.CODENAME)
                append("Preview (API ${Build.VERSION.SDK_INT})")
            }
        } else {
            binding?.systemVersion?.text = buildString {
                append(Build.VERSION.RELEASE)
                append("(API ${Build.VERSION.SDK_INT})")
            }
        }
        binding?.tvBuildType?.text = BuildConfig.BUILD_TYPE

        binding?.apply {
            btLaunchSideBar.setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) SidebarLauncher.start(this@MainActivity)
                else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()))
            }

            msSideBar.isChecked = config.launchSideBarAtBoot
            msSideBar.setOnCheckedChangeListener { _, isChecked ->
                if (!applyingSettings) SettingsRepository.setLaunchSideBarAtBoot(isChecked)
            }

            msSideBarPosition.isChecked = config.sidebarPosition
            msSideBarPosition.setOnCheckedChangeListener { _, isChecked ->
                if (applyingSettings) return@setOnCheckedChangeListener
                SettingsRepository.setSidebarPosition(isChecked)
                // 重启侧边栏以应用左右（镜像）变更：先停后启（宽限 1s 待服务完全退出）
                SidebarLauncher.restart(this@MainActivity, 1000L)
            }

            if (config.enableSidebar) {
                innerClSidebar.visibility = View.VISIBLE
            } else {
                innerClSidebar.visibility = View.GONE
            }

            msEnableSideBar.isChecked = config.enableSidebar

            msEnableSideBar.setOnCheckedChangeListener { _, isChecked ->
                if (applyingSettings) return@setOnCheckedChangeListener
                if (isChecked) {
                    innerClSidebar.visibility = View.VISIBLE
                } else {
                    innerClSidebar.visibility = View.GONE
                }

                SettingsRepository.setEnableSidebar(isChecked)
            }

            sliderTransparency.value = config.sidebarTransparency.toFloat()
            tvTransparencyValue.text = config.sidebarTransparency.toString()
            sliderTransparency.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}

                override fun onStopTrackingTouch(slider: Slider) {
                    tvTransparencyValue.text = "${slider.value.toInt()}"
                    SettingsRepository.setSidebarTransparency(slider.value.toInt())
                    // 重启侧边栏以应用透明度（先停后启，宽限 500ms）
                    SidebarLauncher.restart(this@MainActivity, 500L)
                }
            })
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleInfoGateway.requestServiceConnection()
    }

    private fun onActivationChanged(status: ModuleActivation?) {
        val view = binding ?: return
        if (status == null) {
            view.tvHookSummary.setText(R.string.module_detection_framework_absent)
            view.tvActive.setText(R.string.not_activated)
            view.tvVersion.setText(R.string.module_detection_framework_absent_hint)
            view.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
            return
        }
        view.tvHookSummary.text = getString(
            R.string.module_detection_framework,
            status.frameworkName, status.frameworkVersion, status.apiVersion,
        )
        view.tvActive.setText(if (status.injected) R.string.activated else R.string.not_activated)
        view.tvVersion.text = buildScopeText(status)
        view.ivIcon.setImageResource(
            if (status.injected && status.scopeComplete) R.drawable.ic_round_check_circle_24
            else R.drawable.ic_warning_amber_24
        )
    }

    /** Shows every required scope with its state, so the manager can be fixed without guessing. */
    private fun buildScopeText(status: ModuleActivation): CharSequence = buildString {
        append(getString(R.string.module_detection_scope_heading))
        status.requirements.forEach { requirement ->
            append('\n')
            append(if (requirement.satisfied) "✓ " else "✗ ")
            append(requirement.label)
        }
        append('\n')
        append(
            getString(
                R.string.module_detection_running_targets,
                if (status.runningTargets.isEmpty()) "—" else status.runningTargets.joinToString(", "),
            )
        )
    }

    private fun onServiceConnectionChanged(connected: Boolean) {
        val view = binding ?: return
        view.mcvSideBar.visibility = if (connected) View.VISIBLE else View.GONE
        if (connected && !serviceWasConnected) {
            applyingSettings = true
            try {
                config = SettingsRepository.load()
                view.msSideBar.isChecked = config.launchSideBarAtBoot
                view.msSideBarPosition.isChecked = config.sidebarPosition
                view.msEnableSideBar.isChecked = config.enableSidebar
                view.innerClSidebar.visibility = if (config.enableSidebar) View.VISIBLE else View.GONE
                view.sliderTransparency.value = config.sidebarTransparency.toFloat()
                view.tvTransparencyValue.text = config.sidebarTransparency.toString()
            } finally { applyingSettings = false }
        }
        serviceWasConnected = connected
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.channel -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://t.me/+HjGegWE9jBM0N2Rl".toUri()
                })
                true
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingActivity::class.java))
                true
            }
            R.id.github -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/JuanArton/reYAMF".toUri()
                })
                true
            }
            R.id.donate -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://linktr.ee/jxuanlie".toUri()
                })
                true
            }
            R.id.donate2 -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://duzhaokun123.github.io/donate.html".toUri()
                })
                true
            }
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WindowGateway.unregisterOpenCountListener(openCountListener)
        _binding = null
    }
}
