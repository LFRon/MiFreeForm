package com.buildsession.betterYAMF.manager.applist

import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.buildsession.betterYAMF.R
import com.buildsession.betterYAMF.common.model.AppInfo
import com.buildsession.betterYAMF.common.ui.vibratePhone
import com.buildsession.betterYAMF.databinding.WindowAppListBinding
import com.buildsession.betterYAMF.manager.adapter.AppListAdapter
import com.buildsession.betterYAMF.manager.core.domain.repository.IReyamfRepository
import com.buildsession.betterYAMF.manager.core.gateway.AppCatalogGateway
import com.buildsession.betterYAMF.manager.core.gateway.WindowGateway
import com.buildsession.betterYAMF.manager.sidebar.core.AppListQuery
import com.buildsession.betterYAMF.manager.utils.animateScaleThenResize
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AppListWindow :  LifecycleService() {
    companion object {
        const val TAG = "reYAMF_AppListWindow"
    }

    @Inject
    lateinit var iReyamfRepository: IReyamfRepository

    private lateinit var binding: WindowAppListBinding
    private var apps: MutableList<AppInfo> = mutableListOf()
    private var showApps: MutableList<AppInfo> = mutableListOf()
    private lateinit var rvAdapter: AppListAdapter
    private lateinit var windowManager: WindowManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var searchJob: Job? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_Reyamf)
        val inflater = LayoutInflater.from(themedContext)
        binding = WindowAppListBinding.inflate(inflater)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        binding.root.let { layout ->
            val background = layout.background
            if (background is ColorDrawable) {
                val baseColor = background.color

                val colorWithTransparency = ColorUtils.setAlphaComponent(baseColor, (0.6f * 255).toInt())

                layout.setBackgroundColor(colorWithTransparency)
            } else {
                layout.setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, (0.6f * 255).toInt()))
            }

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(binding.root, params)
        }

        binding.root.setOnClickListener {
            animateScaleThenResize(
                binding.cvParent,
                1F, 1F,
                0F, 0F,
                0.5F, 0.5F,
                0, 0,
                this
            ) {
                binding.root.visibility = View.GONE
                close()
            }
        }
        binding.mcv.setOnTouchListener { _, _ -> true }

        binding.cvParent.post {
            val origWidth = binding.cvParent.width
            val origHeight = binding.cvParent.height
            val cvParams = binding.cvParent.layoutParams
            params.width = 0
            params.height = 0
            binding.cvParent.layoutParams = cvParams

            binding.cvParent.visibility  = View.VISIBLE
            animateScaleThenResize(
                binding.cvParent,
                0F, 0F,
                1F, 1F,
                0.5F, 0.5F,
                origWidth, origHeight,
                this
            ) {
                lifecycleScope.launch {
                    binding.pv.isVisible = true

                    AppCatalogGateway.loadAsync(
                        onChunk = { apps.addAll(it) },
                        onFinished = {
                            lifecycleScope.launch(Dispatchers.Main) {

                                val userIds = apps.map { it.userId }.distinct()

                                val userNames = userIds.map { id -> apps.firstOrNull { it.userId == id }?.userName ?: "Unknown" }

                                if (userIds.isNotEmpty()) {
                                    val selectedUserId = userIds[0]
                                    val selectedUserName = apps.firstOrNull { it.userId == selectedUserId }?.userName ?: "Unknown"

                                    binding.btnUser.text = selectedUserName

                                    showApps = AppListQuery.sortByLabel(
                                        apps.filter { it.userId == selectedUserId },
                                        labelOf = { it.activityInfo.loadLabel(this@AppListWindow.packageManager).toString() }
                                    ).toMutableList()
                                } else {
                                    showApps.clear()
                                }

                                binding.etSearch.addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                        searchJob?.cancel()
                                        searchJob = scope.launch {
                                            delay(300)
                                            val filteredApps = AppListQuery.filterByQuery(
                                                showApps,
                                                query = s.toString(),
                                                labelOf = { it.activityInfo.loadLabel(packageManager).toString() }
                                            )

                                            withContext(Dispatchers.Main) {
                                                rvAdapter.setData(filteredApps)
                                                rvAdapter.notifyDataSetChanged()
                                            }
                                        }
                                    }

                                    override fun afterTextChanged(s: Editable?) {}
                                })

                                binding.btnUser.setOnClickListener {
                                    val themedContext = ContextThemeWrapper(this@AppListWindow, androidx.appcompat.R.style.Theme_AppCompat)
                                    PopupMenu(themedContext, binding.btnUser).apply {
                                        userNames.forEachIndexed { index, name ->
                                            menu.add(name).setOnMenuItemClickListener {
                                                val selectedUserId = userIds[index]
                                                onSelectUser(selectedUserId)
                                                true
                                            }
                                        }
                                    }.show()
                                }

                                val clickListener: (AppInfo) -> Unit = {
                                    animateScaleThenResize(
                                        binding.cvParent,
                                        1F, 1F,
                                        0F, 0F,
                                        0.5F, 0.5F,
                                        0, 0,
                                        this@AppListWindow
                                    ) {
                                        binding.root.visibility = View.GONE

                                        WindowGateway.createWindow(it)
                                        close()
                                    }
                                }

                                val longClickListener: (AppInfo) -> Unit = {
                                    iReyamfRepository.insertAppInfo(it)
                                    vibratePhone(this@AppListWindow)
                                    Toast.makeText(
                                        this@AppListWindow,
                                        getString(R.string.app_added_to_sidebar),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }

                                binding.rv.layoutManager = GridLayoutManager(this@AppListWindow, 4)
                                rvAdapter = AppListAdapter(clickListener, arrayListOf(), longClickListener)
                                binding.rv.adapter = rvAdapter
                                rvAdapter.setData(showApps)
                                rvAdapter.notifyDataSetChanged()
                                binding.pv.isVisible = false
                                binding.etSearch.isEnabled = true
                            }
                        }
                    )
                }
            }
        }
    }

    private fun onSelectUser(userId: Int) {
        showApps = apps.filter { app -> app.userId == userId }.toMutableList()
        // 顺带修复：此前排序结果被丢弃，切换用户后列表未按名称排序
        showApps = AppListQuery.sortByLabel(
            showApps,
            labelOf = { it.activityInfo.loadLabel(this@AppListWindow.packageManager).toString() }
        ).toMutableList()

        rvAdapter.setData(showApps)
        rvAdapter.notifyDataSetChanged()
    }

    private fun close() {
        stopSelf()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        windowManager.removeView(binding.root)
        scope.cancel()
        super.onDestroy()
    }
}
