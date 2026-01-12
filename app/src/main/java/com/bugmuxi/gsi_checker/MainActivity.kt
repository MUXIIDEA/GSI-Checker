@file:Suppress("SameParameterValue")

package com.bugmuxi.gsi_checker

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.Locale
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.topjohnwu.superuser.Shell

class MainActivity : AppCompatActivity() {
    //初始化与主题设置
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "GSICheckerPrefs"
        private const val KEY_THEME_COLOR = "theme_color"
    }

    private lateinit var prefs: SharedPreferences
    private var gsiLink: String = ""
    private var isDetected = false
    private var currentThemeColor: Int = 0

    //应用启动
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentThemeColor = prefs.getInt(KEY_THEME_COLOR, ContextCompat.getColor(this, R.color.primary_default))

        Log.d(TAG, "onCreate 开始")

        try {
            setContentView(R.layout.activity_main)
            applyThemeColor(currentThemeColor)
            initViews()
            detectGSI()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate 异常", e)
            showErrorDialog("错误", "启动失败: ${e.message}")
        }
    }

    //主题应用
    private fun applyThemeColor(color: Int) {
        findViewById<Button>(R.id.btnDetect)?.setBackgroundColor(color)
        findViewById<Button>(R.id.btnCopy)?.setBackgroundColor(color)
        findViewById<Button>(R.id.btnOpenDSU)?.setBackgroundColor(color)
        findViewById<Button>(R.id.btnDonate)?.setBackgroundColor(color)
        findViewById<TextView>(R.id.tvTitle)?.setTextColor(color)

        prefs.edit {
            putInt(KEY_THEME_COLOR, color)
        }
    }

    private fun initViews() {
        val tvResult = findViewById<TextView>(R.id.tvResult)
        val btnCopy = findViewById<Button>(R.id.btnCopy)
        val btnOpenDSU = findViewById<Button>(R.id.btnOpenDSU)
        val btnDonate = findViewById<Button>(R.id.btnDonate)
        val btnTheme = findViewById<ImageButton>(R.id.btnTheme)
        val btnRootMode = findViewById<Button>(R.id.btnRootMode)

        // 初始化UI交互
        btnCopy.isEnabled = false
        btnCopy.alpha = 0.5f

        // 根据是否 Root 决定是否显示 Root 按钮
        if (isRootAvailable()) {
            btnRootMode.visibility = View.VISIBLE
        } else {
            btnRootMode.visibility = View.GONE
        }

        findViewById<Button>(R.id.btnDetect).setOnClickListener {
            if (isDetected) {
                Toast.makeText(this, "正在检测...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            detectGSI()
        }

        btnCopy.setOnClickListener {
            if (!isDetected || gsiLink.isEmpty()) {
                Toast.makeText(this, "请先进行检测", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("GSI", gsiLink))
            tvResult.append("\n\n链接已复制！")
            Toast.makeText(this, "复制完成", Toast.LENGTH_SHORT).show()
        }

        // 与 DSU Sideloader 的集成
        btnOpenDSU.setOnClickListener {
            try {
                startActivity(packageManager.getLaunchIntentForPackage("com.vegabobo.dsusideloader")!!)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    "https://github.com/VegaBobo/DSU-Sideloader/releases".toUri()))
            }
        }

        btnDonate.setOnClickListener {
            try {
                showDonateDialog()
            } catch (_: Exception) {
                Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show()
            }
        }

        // 主题颜色选择器
        btnTheme.setOnClickListener { showColorPicker() }

        // Root 模式按钮
        btnRootMode.setOnClickListener {
            if (!isRootAvailable()) {
                Toast.makeText(this, "设备未 Root", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            detectRootInfo()
        }
    }

    // 主题颜色功能
    private fun showColorPicker() {
        val colors = intArrayOf(
            0xFF1a73e8.toInt(), // Google Blue
            0xFFe91e63.toInt(), // Pink
            0xFF9c27b0.toInt(), // Purple
            0xFF4caf50.toInt(), // Green
            0xFFff9800.toInt(), // Orange
            0xFFf44336.toInt()  // Red
        )
        val colorNames = arrayOf("Google Blue", "粉色", "紫色", "绿色", "橙色", "红色")

        AlertDialog.Builder(this)
            .setTitle("选择主题颜色")
            .setItems(colorNames) { _, which ->
                val selectedColor = colors[which]
                currentThemeColor = selectedColor
                applyThemeColor(selectedColor)
                Toast.makeText(this, "主题已更改", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ====== 正确的 A/B 分区检测 ======
    private fun isABDevice(): Boolean {
        return try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java)

            val abUpdate = getMethod.invoke(null, "ro.build.ab_update") as? String
            if (abUpdate == "true") return true

            val slotSuffix = getMethod.invoke(null, "ro.boot.slot_suffix") as? String
            if (!slotSuffix.isNullOrBlank() && slotSuffix.matches(Regex("_[ab]"))) {
                return true
            }

            false
        } catch (e: Exception) {
            Log.w(TAG, "A/B 分区检测失败，假设为 A-only", e)
            false
        }
    }

    @SuppressLint("SetTextI18n")
    //系统信息检测 GSI 检测与信息收集
    private fun detectGSI() {
        if (isDetected) return
        isDetected = true

        val tvResult = findViewById<TextView>(R.id.tvResult)
        val btnCopy = findViewById<Button>(R.id.btnCopy)

        try {
            val release = Build.VERSION.RELEASE
            val sdk = Build.VERSION.SDK_INT
            val abi = Build.SUPPORTED_ABIS[0]

            val brand = Build.BRAND
            val model = Build.MODEL

            val vndkRaw = System.getProperty("ro.vndk.version")
            val vndk = when {
                !vndkRaw.isNullOrBlank() -> vndkRaw
                sdk >= 29 -> (sdk - 1).toString()
                else -> "未知"
            }

            val firstApiRaw = System.getProperty("ro.product.VendorFirstApiLevel")
            val firstApi = when {
                !firstApiRaw.isNullOrBlank() -> firstApiRaw
                sdk >= 29 -> (sdk - 1).toString()
                else -> "未知"
            }

            // 使用修复后的 A/B 检测
            val isAB = isABDevice()

            gsiLink = when (sdk) {
                36 -> "https://developer.android.com/topic/generic-system-image"
                35 -> "https://dl.google.com/developers/gsi/android15-release/gsi-gms-arm64-ab-android15-release-20251105.zip"
                else -> "https://developer.android.com/topic/generic-system-image"
            }

            val lang = Locale.getDefault().language
            val report = if (lang == "ja") """
                ブランド：$brand
                モデル：$model
                システムバージョン：Android $release (SDK $sdk)
                VNDK：$vndk
                First API：$firstApi
                アーキテクチャ：$abi ${if (isAB) "(AB)" else "(A-only)"}

                推奨 GSI：
                $gsiLink
            """.trimIndent() else """
                品牌：$brand
                型号：$model
                系统版本：Android $release (SDK $sdk)
                VNDK：$vndk
                First API：$firstApi
                架构：$abi ${if (isAB) "(AB)" else "(A-only)"}

                推荐 GSI：
                $gsiLink
            """.trimIndent()

            tvResult.text = report
            btnCopy.isEnabled = true
            btnCopy.alpha = 1f

        } catch (e: Exception) {
            Log.e(TAG, "detectGSI 失败", e)
            tvResult.text = "检测失败: ${e.message}"
            isDetected = false
        }
    }

    // ====== Root 相关工具函数 ======暂时未实现
    private fun isRootAvailable(): Boolean {
        return try {
            Runtime.getRuntime().exec("which su").inputStream.readBytes().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun executeRootCommand(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            process.waitFor()

            if (process.exitValue() == 0 && output.isNotEmpty()) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root 命令执行失败: $command", e)
            null
        }
    }

    private fun detectRootManager(): String {
        val suPath = executeRootCommand("which su") ?: return "未知"
        return when {
            suPath.contains("magisk") -> "Magisk"
            suPath.contains("superuser") || suPath.contains("Superuser") -> "SuperSU"
            suPath.contains("ksu") -> "KernelSU"
            else -> "其他 ($suPath)"
        }
    }

    @SuppressLint("SetTextI18n")
    private fun detectRootInfo() {
        // 1. 先拿到 root（libsu 缓存，后续可复用）
        Shell.getShell {
            // 2. 真正耗时的命令放到 IO 线程
            lifecycleScope.launch(Dispatchers.IO) {
                val kernel   = Shell.cmd("uname -r").exec().out.firstOrNull() ?: "未知"
                val rootImpl = when (Shell.cmd("which su").exec().out.firstOrNull()) {
                    null -> "未知"
                    else -> {
                        val p = Shell.cmd("which su").exec().out.first()
                        when {
                            p.contains("magisk") -> "Magisk"
                            p.contains("ksu")    -> "KernelSU"
                            p.contains("superuser") -> "SuperSU"
                            else -> "其它 ($p)"
                        }
                    }
                }
                val selinux  = Shell.cmd("getenforce").exec().out.firstOrNull() ?: "未知"

                // Magisk 四项
                val magiskCode = Shell.cmd("magisk -V").exec().out.firstOrNull() ?: "?"
                val magiskName = Shell.cmd("magisk -v").exec().out.firstOrNull() ?: "?"
                val zygiskOn   = Shell.cmd("[[ -f /data/adb/zygisk/enabled ]] && echo 1").exec().out.firstOrNull() == "1"
                val ramExist   = Shell.cmd("[[ -f /data/adb/ramdisk.img ]] && echo 1").exec().out.firstOrNull() == "1"
                val pkgName    = Shell.cmd("cat /data/adb/magisk/magisk_app").exec().out.firstOrNull()
                    ?: "io.github.huskydg.magisk"

                val magiskInfo = """
                Magisk 版本：$magiskName ($magiskCode)
                Zygisk：${if (zygiskOn) "否" else "是"}
                Ramdisk：${if (ramExist) "否" else "是"}
                包名：$pkgName
            """.trimIndent()

                val rootInfo = """
                
                === Root 信息 ===
                内核版本：$kernel
                Root 管理器：$rootImpl
                SELinux 状态：$selinux
                
                === Magisk 信息 ===
                $magiskInfo
            """.trimIndent()

                // 3. 切回主线程刷新 UI
                withContext(Dispatchers.Main) {
                    val tv = findViewById<TextView>(R.id.tvResult)
                    if (!tv.text.contains("=== Root 信息 ==="))
                        tv.append(rootInfo)
                    Toast.makeText(this@MainActivity, "Root 信息已加载", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    //捐赠系统
    private fun showDonateDialog() {
        try {
            val dialogView = layoutInflater.inflate(
                R.layout.dialog_donate,
                window.decorView.findViewById(android.R.id.content),
                false
            ) ?: throw IllegalStateException("dialog_donate.xml 未找到")

            val dialog = AlertDialog.Builder(this).setView(dialogView).create()

            dialogView.findViewById<ImageButton>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
            dialogView.findViewById<ImageView>(R.id.imgWechat)?.setOnLongClickListener {
                saveImageToGallery(R.drawable.zanshang1, "zanshang_wechat")
                true
            }
            dialogView.findViewById<ImageView>(R.id.imgAlipay)?.setOnLongClickListener {
                saveImageToGallery(R.drawable.zanshang2, "zanshang_alipay")
                true
            }

            dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            dialog.show()
        } catch (_: Exception) {
            Toast.makeText(this, "加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(resId: Int, filename: String) {
        val bitmap = BitmapFactory.decodeResource(resources, resId)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GSI Checker")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        try {
            uri?.let {
                contentResolver.openOutputStream(it)?.use { it1 -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it1) }
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    //错误处理 showErrorDialog()
    private fun showErrorDialog(title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "错误: $message", Toast.LENGTH_LONG).show()
        }
    }
}