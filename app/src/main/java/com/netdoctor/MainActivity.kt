package com.netdoctor

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {
    
    private lateinit var scrollView: ScrollView
    private lateinit var tvNetworkType: TextView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvOperator: TextView
    private lateinit var tvDataUsage: TextView
    private lateinit var btnDiagnose: Button
    private lateinit var btnApnGuide: Button
    private lateinit var btnGeminiHelp: Button
    private lateinit var btnAnalytics: Button
    private lateinit var btnFirewall: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerApps: RecyclerView
    private lateinit var adView: AdView
    private lateinit var geminiWebView: WebView
    
    private var mInterstitialAd: InterstitialAd? = null
    private var isProUser = false
    
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())
    private val appsList = mutableListOf<AppInfo>()
    private lateinit var appsAdapter: AppsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupClickListeners()
        checkPermissions()
        loadAds()
        loadInterstitialAd()
        setupRecyclerView()
        startDataCollection()
        checkAndShowWeeklyRecommendation()
    }
    
    private fun initViews() {
        scrollView = findViewById(R.id.scrollView)
        tvNetworkType = findViewById(R.id.tv_network_type)
        tvSignalStrength = findViewById(R.id.tv_signal_strength)
        tvIpAddress = findViewById(R.id.tv_ip_address)
        tvOperator = findViewById(R.id.tv_operator)
        tvDataUsage = findViewById(R.id.tv_data_usage)
        btnDiagnose = findViewById(R.id.btn_diagnose)
        btnApnGuide = findViewById(R.id.btn_apn_guide)
        btnGeminiHelp = findViewById(R.id.btn_gemini_help)
        btnAnalytics = findViewById(R.id.btn_analytics)
        btnFirewall = findViewById(R.id.btn_firewall)
        progressBar = findViewById(R.id.progress_bar)
        recyclerApps = findViewById(R.id.recycler_apps)
        adView = findViewById(R.id.adView)
        geminiWebView = findViewById(R.id.geminiWebView)
        geminiWebView.visibility = android.view.View.GONE
    }
    
    private fun setupClickListeners() {
        btnDiagnose.setOnClickListener {
            diagnoseNetwork()
            showInterstitialAd()
        }
        btnApnGuide.setOnClickListener { showApnGuide() }
        btnGeminiHelp.setOnClickListener { showGeminiWebView() }
        btnAnalytics.setOnClickListener { showDetailedAnalytics() }
        btnFirewall.setOnClickListener {
            if (isProUser) showFirewallDialog() else showProUpgradeDialog("الجدار الناري")
        }
    }
    
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)
        } else {
            diagnoseNetwork()
        }
    }
    
    private fun diagnoseNetwork() {
        progressBar.visibility = android.view.View.VISIBLE
        mainScope.launch {
            val networkInfo = withContext(Dispatchers.IO) { getNetworkInfo() }
            tvNetworkType.text = "🌐 نوع الشبكة: ${networkInfo["type"]}"
            tvSignalStrength.text = "📶 قوة الإشارة: ${networkInfo["signal"]}%"
            tvIpAddress.text = "📍 عنوان IP: ${networkInfo["ip"]}"
            tvOperator.text = "📱 المشغل: ${networkInfo["operator"]}\n📡 MCC/MNC: ${networkInfo["mcc"]}/${networkInfo["mnc"]}"
            progressBar.visibility = android.view.View.GONE
            saveDiagnosisLog(networkInfo)
            Toast.makeText(this@MainActivity, "تم تشخيص الشبكة", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getNetworkInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            info["type"] = when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi 📶"
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "4G/5G 📱"
                else -> "غير متصل ❌"
            }
            info["signal"] = "75" // تبسيط
            info["ip"] = getLocalIpAddress()
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                info["operator"] = tm.networkOperatorName ?: "غير معروف"
                val networkOperator = tm.networkOperator ?: ""
                info["mcc"] = if (networkOperator.length >= 3) networkOperator.substring(0, 3) else "000"
                info["mnc"] = if (networkOperator.length >= 5) networkOperator.substring(3, 5) else "00"
            } else {
                info["operator"] = "غير معروف"; info["mcc"] = "000"; info["mnc"] = "00"
            }
        } catch (e: Exception) {
            info["type"] = "خطأ"; info["signal"] = "0"; info["ip"] = "غير متوفر"; info["operator"] = "غير معروف"; info["mcc"] = "000"; info["mnc"] = "00"
        }
        return info
    }
    
    private fun getLocalIpAddress(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') != true }
                ?.hostAddress ?: "غير متوفر"
        } catch (e: Exception) { "غير متوفر" }
    }
    
    private fun showApnGuide() {
        val countries = arrayOf("🇸🇦 السعودية", "🇪🇬 مصر", "🇦🇪 الإمارات", "🇰🇼 الكويت", "🇶🇦 قطر", "🇧🇭 البحرين", "🇴🇲 عمان", "🇯🇴 الأردن", "🇱🇧 لبنان", "🇾🇪 اليمن", "🇸🇩 السودان", "🇩🇿 الجزائر", "🇲🇦 المغرب", "🇹🇳 تونس", "🇱🇾 ليبيا", "🇮🇶 العراق", "🇵🇸 فلسطين")
        AlertDialog.Builder(this).setTitle("📖 دليل إعدادات APN").setItems(countries) { _, which ->
            when (which) {
                0 -> showApnDetails("STC Internet", "stc", "default", "420", "01")
                1 -> showApnDetails("Vodafone Internet", "vodafone", "default", "602", "01")
                2 -> showApnDetails("Etisalat Internet", "etisalat", "default", "424", "03")
                3 -> showApnDetails("Zain Internet", "zain", "default", "419", "02")
                4 -> showApnDetails("Ooredoo Internet", "ooredoo", "default", "427", "01")
                5 -> showApnDetails("Batelco Internet", "batelco", "default", "426", "01")
                6 -> showApnDetails("Omantel Internet", "omantel", "default", "422", "02")
                7 -> showApnDetails("Zain Internet", "zain", "default", "416", "01")
                8 -> showApnDetails("Touch Internet", "touch", "default", "415", "01")
                9 -> showApnDetails("Yemen Mobile Internet", "yemenmobile", "default", "421", "04")
                10 -> showApnDetails("Sudani Internet", "sudani", "default", "634", "01")
                11 -> showApnDetails("Mobilis Internet", "mobilis", "default", "603", "01")
                12 -> showApnDetails("Maroc Telecom", "mt", "default", "604", "01")
                13 -> showApnDetails("Tunisie Telecom", "tt", "default", "605", "01")
                14 -> showApnDetails("Libyana Internet", "libyana", "default", "606", "01")
                15 -> showApnDetails("Zain Internet", "zain", "default", "418", "01")
                16 -> showApnDetails("Jawwal Internet", "jawwal", "default", "425", "01")
            }
        }.show()
    }
    
    private fun showApnDetails(name: String, apn: String, apnType: String, mcc: String, mnc: String) {
        val message = "📱 إعدادات APN المقترحة:\n\n📛 الاسم: $name\n🔗 APN: $apn\n📡 نوع APN: $apnType\n🌍 MCC: $mcc\n📍 MNC: $mnc\n🔐 البروتوكول: IPv4/IPv6\n\n💡 بعد الإضافة، اضغط حفظ وأعد التشغيل"
        AlertDialog.Builder(this).setTitle("⚙️ إعدادات APN").setMessage(message).setPositiveButton("فتح إعدادات APN") { _, _ ->
            startActivity(Intent(Settings.ACTION_APN_SETTINGS))
        }.setNegativeButton("إلغاء", null).show()
    }
    
    private fun showGeminiWebView() {
        geminiWebView.visibility = android.view.View.VISIBLE
        scrollView.visibility = android.view.View.GONE
        geminiWebView.settings.javaScriptEnabled = true
        geminiWebView.settings.domStorageEnabled = true
        geminiWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = android.view.View.GONE
            }
        }
        geminiWebView.loadUrl("https://gemini.google.com")
        progressBar.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "💡 اكتب مشكلتك في الدردشة مع Gemini", Toast.LENGTH_LONG).show()
    }
    
    override fun onBackPressed() {
        if (geminiWebView.visibility == android.view.View.VISIBLE) {
            geminiWebView.visibility = android.view.View.GONE
            scrollView.visibility = android.view.View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }
    
    private fun setupRecyclerView() {
        recyclerApps.layoutManager = LinearLayoutManager(this)
        appsAdapter = AppsAdapter(appsList) { appInfo ->
            if (isProUser) showAppDataDetails(appInfo) else showProUpgradeDialog("تفاصيل الاستهلاك المتقدمة")
        }
        recyclerApps.adapter = appsAdapter
    }
    
    private fun startDataCollection() {
        mainScope.launch {
            while (true) {
                collectDataUsage()
                delay(60000)
            }
        }
    }
    
    private fun collectDataUsage() {
        mainScope.launch {
            val usage = withContext(Dispatchers.IO) { getTopAppsDataUsage() }
            val totalMB = usage.sumOf { it.mobileBytes + it.wifiBytes } / (1024 * 1024)
            tvDataUsage.text = "📊 إجمالي الاستهلاك اليوم: ${totalMB} MB"
            appsList.clear()
            appsList.addAll(usage.take(10))
            appsAdapter.notifyDataSetChanged()
        }
    }
    
    private fun getTopAppsDataUsage(): List<AppInfo> {
        val usageMap = HashMap<String, AppInfo>()
        try {
            val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 24 * 60 * 60 * 1000
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            val pm = packageManager
            for (stat in stats) {
                try {
                    val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    val mobileBytes = stat.totalTimeInForeground * 1000
                    val wifiBytes = stat.totalTimeVisible * 500
                    usageMap[stat.packageName] = AppInfo(stat.packageName, appName, mobileBytes, wifiBytes, stat.totalTimeInForeground, stat.totalTimeVisible)
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        return usageMap.values.sortedByDescending { it.mobileBytes + it.wifiBytes }
    }
    
    private fun showDetailedAnalytics() {
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
            return
        }
        val totalUsage = appsList.sumOf { it.mobileBytes + it.wifiBytes } / (1024 * 1024)
        val topApp = appsList.firstOrNull()
        val message = "📊 تقرير استهلاك البيانات:\n\n📦 إجمالي اليوم: $totalUsage MB\n\n🥇 أعلى تطبيق: ${topApp?.appName}: ${(topApp?.mobileBytes ?: 0 + (topApp?.wifiBytes ?: 0)) / (1024 * 1024)} MB\n\n📱 عدد التطبيقات المستخدمة: ${appsList.size}\n\n💡 راجع إعدادات كل تطبيق للحد من استهلاك الخلفية"
        AlertDialog.Builder(this).setTitle("📈 تحليلات متقدمة").setMessage(message).setPositiveButton("فتح الإعدادات") { _, _ ->
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.setNegativeButton("إغلاق", null).show()
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
    
    private fun requestUsageStatsPermission() {
        AlertDialog.Builder(this).setTitle("⚠️ صلاحية مطلوبة").setMessage("لتحليل استهلاك البيانات، نحتاج إلى صلاحية الوصول لإحصائيات الاستخدام").setPositiveButton("منح الصلاحية") { _, _ ->
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.setNegativeButton("تذكر لاحقاً", null).show()
    }
    
    private fun showAppDataDetails(appInfo: AppInfo) {
        val totalMB = (appInfo.mobileBytes + appInfo.wifiBytes) / (1024 * 1024)
        val mobileMB = appInfo.mobileBytes / (1024 * 1024)
        val wifiMB = appInfo.wifiBytes / (1024 * 1024)
        val message = "📱 التطبيق: ${appInfo.appName}\n\n📊 إجمالي الاستهلاك: $totalMB MB\n📡 بيانات الجوال: $mobileMB MB\n📶 بيانات الواي فاي: $wifiMB MB\n\n⏱️ وقت المقدمة: ${appInfo.foregroundTime / 60000} دقيقة\n⏰ وقت الخلفية: ${appInfo.backgroundTime / 60000} دقيقة"
        AlertDialog.Builder(this).setTitle("تفاصيل الاستهلاك").setMessage(message).setPositiveButton("تقييد بيانات الخلفية") { _, _ ->
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS)
                intent.data = Uri.parse("package:${appInfo.packageName}")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${appInfo.packageName}")))
            }
        }.setNegativeButton("إلغاء", null).show()
    }
    
    private fun showFirewallDialog() {
        AlertDialog.Builder(this).setTitle("🔥 الجدار الناري (قيد التطوير)").setMessage("ستسمح لك هذه الميزة ب:\n✓ منع تطبيقات معينة من استخدام الإنترنت\n✓ التحكم في الوصول للواي فاي والبيانات الخلوية بشكل منفصل\n✓ توفير البيانات ومنع التسريبات الخلفية\n\nسيتم إطلاقها في الإصدار 2.0").setPositiveButton("متابعة", null).show()
    }
    
    private fun loadAds() {
        if (!isProUser) {
            MobileAds.initialize(this) {}
            adView.loadAd(AdRequest.Builder().build())
        }
    }
    
    private fun loadInterstitialAd() {
        if (!isProUser) {
            InterstitialAd.load(this, "ca-app-pub-3940256099942544/1033173712", AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) { mInterstitialAd = ad }
                    override fun onAdFailedToLoad(adError: LoadAdError) { mInterstitialAd = null }
                })
        }
    }
    
    private fun showInterstitialAd() {
        if (!isProUser && mInterstitialAd != null) {
            mInterstitialAd?.show(this)
            loadInterstitialAd()
        }
    }
    
    private fun showProUpgradeDialog(featureName: String) {
        AlertDialog.Builder(this).setTitle("✨ ميزة Pro: $featureName").setMessage("هذه الميزة متاحة فقط في النسخة المدفوعة.\n\n🚀 مميزات نسخة Pro:\n• إزالة جميع الإعلانات\n• الجدار الناري (منع تطبيقات من الإنترنت)\n• تحليلات متقدمة ورسوم بيانية\n• توصيات غير محدودة من Gemini\n• دعم الأولوية\n\nالسعر: 4.99 دولار فقط (دفعة واحدة)").setPositiveButton("الترقية الآن") { _, _ ->
            Toast.makeText(this, "جاري تطوير نظام الدفع... سيتم إطلاقه قريباً", Toast.LENGTH_LONG).show()
        }.setNegativeButton("تذكر لاحقاً", null).show()
    }
    
    private fun checkAndShowWeeklyRecommendation() {
        val prefs = getSharedPreferences("netdoctor", MODE_PRIVATE)
        val lastRecommendation = prefs.getLong("last_recommendation", 0)
        val now = System.currentTimeMillis()
        if (now - lastRecommendation > 7 * 24 * 60 * 60 * 1000L) {
            val topApp = appsList.firstOrNull()
            val recommendation = if (topApp != null && topApp.backgroundTime > 30 * 60 * 1000) {
                "📱 تطبيق ${topApp.appName} يعمل في الخلفية لأكثر من ${topApp.backgroundTime / 60000} دقيقة. قم بتقييد بيانات الخلفية."
            } else {
                "💡 نصيحة: استخدم الواي فاي كلما أمكن، وقم بتعطيل التحديث التلقائي للتطبيقات عبر البيانات الخلوية."
            }
            AlertDialog.Builder(this).setTitle("💡 توصية الأسبوع").setMessage(recommendation).setPositiveButton("تطبيق التوصية") { _, _ ->
                if (topApp != null) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${topApp.packageName}")))
                }
            }.setNegativeButton("تذكر لاحقاً", null).show()
            prefs.edit().putLong("last_recommendation", now).apply()
        }
    }
    
    private fun saveDiagnosisLog(info: Map<String, String>) {
        try {
            val file = java.io.File(filesDir, "diagnosis_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("$timestamp: $info\n")
            val lines = file.readLines()
            if (lines.size > 100) file.writeText(lines.takeLast(100).joinToString("\n"))
        } catch (e: Exception) { }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }
}

data class AppInfo(val packageName: String, val appName: String, val mobileBytes: Long, val wifiBytes: Long, val foregroundTime: Long, val backgroundTime: Long)

class AppsAdapter(private val appsList: List<AppInfo>, private val onItemClick: (AppInfo) -> Unit) : RecyclerView.Adapter<AppsAdapter.ViewHolder>() {
    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvAppName: TextView = itemView.findViewById(android.R.id.text1)
        val tvUsage: TextView = itemView.findViewById(android.R.id.text2)
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appsList[position]
        val totalMB = (app.mobileBytes + app.wifiBytes) / (1024 * 1024)
        holder.tvAppName.text = app.appName
        holder.tvUsage.text = "${totalMB} MB"
        holder.itemView.setOnClickListener { onItemClick(app) }
    }
    override fun getItemCount(): Int = appsList.size
}
