package uk.edge.move.handheld

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.edge.poslite.domain.pricing.*
import com.edge.poslite.domain.model.ServiceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class HandheldActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var draft: OrderingDraft
    private var catalogue: Catalogue? = null
    private var staff: Staff? = null
    private var screenId = ""
    private var departmentId: String? = null
    private var measure: String? = null
    private var extra: String? = null
    private var deal: MeasureButton? = null
    private val dealProducts = mutableListOf<MenuProduct>()
    private val dealChoices = mutableListOf<Map<String,List<String>>>()
    private var page = "login"
    private val selectedTable: String? get() = draft.orderReference
    private var loyaltyOptIn = false
    private var busy = false
    private var status = ""
    private val worker = Executors.newSingleThreadExecutor()
    private val uploads = Executors.newSingleThreadExecutor()
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)
    private val connectivity by lazy {getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager}
    private var internetLight: View? = null
    private var networkMonitoring = false
    private val networkAvailable=object:android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:android.net.Network) { refreshInternetLight(); uploadInBackground() }
        override fun onCapabilitiesChanged(network:android.net.Network, capabilities:android.net.NetworkCapabilities) { refreshInternetLight() }
        override fun onLost(network:android.net.Network) { refreshInternetLight() }
    }
    private fun refreshInternetLight() {
        runOnUiThread {
            if(!networkMonitoring || isFinishing || isDestroyed) return@runOnUiThread
            updateInternetLight()
        }
    }
    private fun updateInternetLight() {
        val capabilities=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val validated=capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        internetLight?.apply {
            background=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(if(validated) Color.rgb(36,158,75) else Color.rgb(170,170,170))
            }
            contentDescription=if(validated) "Internet connected" else "Internet not validated"
        }
    }
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var basketButton: Button
    private val lime = Color.rgb(201,255,24)
    private val ink = Color.rgb(26,26,26)
    private val paper = Color.rgb(248,248,248)
    private val textColor get() = if (store.dark) paper else ink
    private val surfaceColor get() = if (store.dark) ink else paper
    private val muted get() = if (store.dark) Color.LTGRAY else Color.DKGRAY

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try { store = DeviceStore(this, training=getSharedPreferences("handheld-ui",0).getBoolean("training",false)); catalogue = store.config?.let(Catalogue::parse); draft = store.parkSharedDraft(store.loadDraft()) }
        catch (e: Exception) {
            // Never discard a corrupt cache/draft silently or overwrite it with an empty draft.
            AlertDialog.Builder(this).setTitle("Saved data needs attention").setMessage(e.message)
                .setCancelable(false).setPositiveButton("Close") { _, _ -> finish() }.show()
            return
        }
        resetMenu()
        render()
    }
    private var displayedSaleId:String?=null
    private val outcomeHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var outcomeDeadline=0L
    private var timedOutcomeId:String?=null
    private var outcomeCountdown:TextView?=null
    private val outcomeTick=object:Runnable {
        override fun run() {
            if(page!="receipt" || staff==null || busy || isFinishing || isDestroyed) return
            val remaining=outcomeDeadline-android.os.SystemClock.elapsedRealtime()
            if(remaining<=0) {finishOutcome();return}
            outcomeCountdown?.text="Returning to till in ${(remaining+999)/1000}s"
            outcomeHandler.postDelayed(this,1000)
        }
    }
    private fun finishOutcome() {
        val outcome=store.trading.lastOutcome()
        val originalOrder=outcome?.takeIf {it.optString("type")=="REFUND"}?.optString("originalTransactionId")
        timedOutcomeId?.let {store.trading.dismissOutcome(it)}
        outcomeHandler.removeCallbacks(outcomeTick);outcomeDeadline=0;timedOutcomeId=null
        if(!originalOrder.isNullOrBlank()) {historySaleId=originalOrder;page="orderDetail"} else {page="sell";resetMenu()}
        status="";render()
    }
    override fun onPause() {
        // PAX visibility is device-wide: release it whenever another app takes over.
        moveInForeground=false
        setMoveNavigationHidden(false)
        outcomeHandler.removeCallbacks(outcomeTick);outcomeDeadline=0
        super.onPause()
    }
    private val tradingReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:android.content.Context,intent:android.content.Intent) { refreshTrading();uploadInBackground() }
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API25 fallback is protected by this APK’s signature permission.
    override fun onStart() {
        super.onStart()
        networkMonitoring=true
        connectivity.registerDefaultNetworkCallback(networkAvailable)
        updateInternetLight()
        val filter=android.content.IntentFilter("uk.edge.move.handheld.TRADING_CHANGED")
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(tradingReceiver,filter,RECEIVER_NOT_EXPORTED) else registerReceiver(tradingReceiver,filter,"$packageName.TRADING_INTERNAL",null)
    }
    override fun onStop() {networkMonitoring=false;connectivity.unregisterNetworkCallback(networkAvailable);unregisterReceiver(tradingReceiver);super.onStop()}
    override fun onResume() {
        super.onResume();moveInForeground=true;setMoveNavigationHidden(true)
        if(::store.isInitialized && ::draft.isInitialized) {
            refreshTrading()
            uploadInBackground()
        }
    }
    private val reportHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var moveInForeground=false
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && moveInForeground) setMoveNavigationHidden(true)
    }
    @Suppress("DEPRECATION")
    private fun setMoveNavigationHidden(hidden:Boolean) {
        // Keep Back enabled for Settings/other apps; hide only while Move owns the screen.
        if(android.os.Build.MANUFACTURER.contains("pax",ignoreCase=true)) runCatching {
            com.pax.neptunelite.api.NeptuneLiteUser.getInstance().getDalWithProcessSafe(applicationContext).sys.showNavigationBar(!hidden)
        }
        if(android.os.Build.VERSION.SDK_INT>=30) {
            window.insetsController?.apply {
                systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if(hidden) hide(android.view.WindowInsets.Type.navigationBars()) else show(android.view.WindowInsets.Type.navigationBars())
            }
        } else {
            window.decorView.systemUiVisibility=if(hidden) View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    private val reportTick=Runnable {uploadInBackground()}
    private fun uploadInBackground() {
        if(!::store.isInitialized || uploads.isShutdown || !uploading.compareAndSet(false,true)) return
        val reportingStore=store
        uploads.execute {
            runCatching {reportingStore.uploadSales()}
            uploading.set(false)
            runOnUiThread {
                if(!isFinishing && !isDestroyed) {
                    reportHandler.removeCallbacks(reportTick)
                    if(store.pendingReports>0) reportHandler.postDelayed(reportTick,(store.retryAt-System.currentTimeMillis()).coerceAtLeast(1000L))
                    if(page=="settings" && !busy) render()
                }
            }
        }
    }
    private fun refreshTrading() {
        if(!::store.isInitialized || !::draft.isInitialized || busy) return
        store.trading.consumeDayEndResult(moveInForeground)?.let {result ->
            if(page=="dayFinish") {staff=null;page="login";cashCountKey=""}
            status="Day closed"
            result.optJSONObject("report")?.let {report ->
                status=if(runCatching {android.util.Log.i("MoveApp2App","Printing Move end-day totals after POSitive report return");CashUpPrinter.print(this,dayReceipt(report))}.isSuccess)
                    "Day closed · totals printed" else "Day closed · print totals from Settings"
            }
            render();return
        }
        store.trading.recoverRecordedResult()
        val pending=store.trading.pending()
        draft=store.loadDraft()
        val outcome=store.trading.lastOutcome()
        if(pending!=null) page="checkout"
        else if(outcome!=null) {displayedSaleId=outcome.getString("id");page="receipt"}
        else if(store.trading.cashParts().isNotEmpty()) page="checkout"
        render()
    }
    override fun onDestroy() { reportHandler.removeCallbacks(reportTick); worker.shutdown(); uploads.shutdown(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(textColor); setPadding(dp(3),dp(3),dp(3),dp(3))
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun border(fill: Int = surfaceColor) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(10).toFloat(); setStroke(dp(1), if (store.dark) Color.DKGRAY else Color.rgb(224,224,218))
    }
    private fun button(label: String, selected: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; minHeight = dp(36); minimumHeight = dp(36); minWidth = 0; minimumWidth = 0; includeFontPadding = false
        // Flat brand borders avoid repeated elevation-shadow work on older terminal GPUs.
        stateListAnimator = null; elevation = 0f
        setTextColor(if (selected) ink else textColor); background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(selected) 0x55000000 else if(store.dark) 0x55C9FF18 else 0x33000000), border(if (selected) lime else surfaceColor), border(Color.WHITE))
        setPadding(dp(6),dp(4),dp(6),dp(4)); setOnClickListener { if (!busy) action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }
    }
    private fun render() {
        LoyaltyBridge.retry(this)
        if(page!="checkout") loyaltyOptIn=false
        outcomeHandler.removeCallbacks(outcomeTick)
        if(page!="receipt" || staff==null || busy) {outcomeDeadline=0;timedOutcomeId=null}
        window.statusBarColor = ink; window.navigationBarColor = ink
        root = column().apply { setBackgroundColor(surfaceColor); setPadding(dp(6),dp(3),dp(6),dp(4)) }
        if (android.os.Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(6), dp(3) + insets.systemWindowInsetTop, dp(6), dp(4) + insets.systemWindowInsetBottom); insets
        }
        setContentView(root)
        val heading = row()
        heading.addView(button("☰") { menu() }, LinearLayout.LayoutParams(dp(36),dp(36)))
        heading.addView(button(if (staff == null) "Move" else "${selectedTable ?: draft.serviceMode.label()} ▾") {
            if (staff != null) { page = "tables"; render() }
        }.apply { textSize = 16f; setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0,dp(36),1f))
        if(staff!=null && page!="day" && page!="dayFinish" && page!="cash" && page!="basket" && page!="menu" && page!="history" && page!="orderDetail" && page!="connection" && (page!="checkout" || store.trading.pending()!=null)) root.addView(heading)
        val connectionRow=row()
        connectionRow.addView(text(if(store.training) "TRAINING · NO REAL SALES" else if(staff != null || store.pairing == null || store.blocked) {
            if(BuildConfig.DEBUG && store.config?.optBoolean("handheldFixture")==true) "SIMULATED DATA / BENCHMARK" else if(BuildConfig.IS_STAGING) "LOYALTY TEST · STAGING · REAL CARD PAYMENTS" else ""
        } else "",9f).apply {setPadding(dp(3),dp(1),dp(3),dp(1))},LinearLayout.LayoutParams(0,dp(18),1f))
        internetLight=View(this).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES}
        connectionRow.addView(internetLight,LinearLayout.LayoutParams(dp(8),dp(8)).apply {setMargins(dp(4),0,dp(6),0)})
        root.addView(connectionRow)
        updateInternetLight()
        if (busy) root.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
        if (status.isNotEmpty()) root.addView(text(status, 13f).apply { setTextColor(muted) })
        content = column()
        root.addView(content, LinearLayout.LayoutParams(-1,0,1f))
        if(page=="loginSettings") loginSettings() else if (staff == null || store.blocked) login() else when (page) {
            "tables" -> standaloneContext()
            "receipt" -> receiptPage()
            "history" -> salesHistory()
            "orderDetail" -> orderDetail()
            "menu" -> menuPage()
            "connection" -> connectionPage()
            "day" -> tradingDayPage()
            "dayFinish" -> {
                content.addView(text("End day sent",24f,true))
                content.addView(text("POSitive prints first. Move totals will print when it finishes and returns here.",18f),LinearLayout.LayoutParams(-1,0,1f))
                content.addView(button("Return to sign in") {staff=null;page="login";status="Day closed · totals saved";render()},LinearLayout.LayoutParams(-1,dp(64)))
            }
            "localTables" -> localTables()
            "checkout" -> checkout()
            "settings" -> settings()
            "savedRecovery" -> savedOrderRecovery()
            "basket" -> basket()
            "cash" -> cashPage()
            else -> sell()
        }
    }
    private fun menu() {page="menu";status="";render()}
    private fun section(parent:LinearLayout,label:String) {
        parent.addView(text(label.uppercase(Locale.UK),11f,true).apply {setTextColor(muted);setPadding(dp(8),dp(16),dp(8),dp(6));letterSpacing=.08f})
    }
    private fun navigationCard(parent:LinearLayout,title:String,subtitle:String,action:()->Unit) {
        val frame=android.widget.FrameLayout(this)
        frame.addView(button("") {action()}.apply {contentDescription=title},android.widget.FrameLayout.LayoutParams(-1,-1))
        val labels=column().apply {setPadding(dp(14),dp(8),dp(12),dp(8));gravity=Gravity.CENTER_VERTICAL}
        labels.addView(text("$title  ›",17f,true))
        if(subtitle.isNotBlank()) labels.addView(text(subtitle,12f).apply {setTextColor(muted)})
        frame.addView(labels,android.widget.FrameLayout.LayoutParams(-1,-1))
        parent.addView(frame,LinearLayout.LayoutParams(-1,dp(if(subtitle.isBlank()) 52 else 68)).apply {setMargins(0,dp(3),0,dp(3))})
    }
    private fun menuPage() {
        content.addView(text("Settings",26f,true))
        content.addView(text("${catalogue?.merchant.orEmpty()} · ${catalogue?.terminal.orEmpty()}",12f).apply {setTextColor(muted)})
        val c=scrollColumn()
        section(c,"Trading")
        navigationCard(c,"Orders","Today's sales, receipts and refunds") {historyToday=true;page="history";render()}
        navigationCard(c,if(store.trading.openDay()==null) "Start day" else "End day",if(store.trading.openDay()==null) "Enter your opening float" else "Review totals and cash up") {cashCountKey="";page="day";render()}
        navigationCard(c,"Order type",draft.serviceMode.label()) {page="tables";render()}
        navigationCard(c,"View basket","${draft.itemCount} items · ${money(draft.total)}") {page="basket";render()}
        section(c,"Device")
        navigationCard(c,"Sync & device","Published menu, pairing and saved reports") {page="settings";render()}
        navigationCard(c,"Wi-Fi settings","Connect to a wireless network") {openNetworkSettings(false)}
        navigationCard(c,"Mobile network settings","SIM, mobile data and roaming") {openNetworkSettings(true)}
        navigationCard(c,"Test acquiring connection","Check connection to Elavon") {page="connection";status="";render()}
        navigationCard(c,"Appearance",if(store.dark) "Dark · tap for light" else "Light · tap for dark") {store.dark=!store.dark;render()}
        section(c,"Signed in")
        navigationCard(c,"Sign out",staff?.name.orEmpty()) {staff=null;page="login";render()}
        content.addView(button("Back to till",true) {page=startPage();render()},LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun openNetworkSettings(mobile:Boolean) {
        setMoveNavigationHidden(false)
        if(android.os.Build.MANUFACTURER.contains("pax",ignoreCase=true)) {
            val backAvailable=runCatching {
                val sys=com.pax.neptunelite.api.NeptuneLiteUser.getInstance().getDalWithProcessSafe(applicationContext).sys
                sys.enableNavigationBar(true)
                sys.enableNavigationKey(com.pax.dal.entity.ENavigationKey.BACK,true)
                sys.showNavigationBar(true)
                sys.isNavigationBarEnabled && sys.isNavigationBarVisible && sys.isNavigationKeyEnabled(com.pax.dal.entity.ENavigationKey.BACK)
            }.getOrDefault(false)
            if(!backAvailable) {
                setMoveNavigationHidden(true)
                notice("Back button unavailable","PayDroid has hidden or restricted the Back button. Network settings cannot be opened safely until terminal navigation is enabled.")
                return
            }
        }

        val actions=if(mobile) listOf(
            android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS,
            android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS,
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS
        ) else listOf(
            android.provider.Settings.ACTION_WIFI_SETTINGS,
            android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS
        )
        for(action in actions) {
            try {
                // Launch directly: package visibility on Android 11+ can hide handlers from queries.
                startActivity(android.content.Intent(action))
                return
            } catch (_:android.content.ActivityNotFoundException) {
                // PayDroid builds may omit individual Android settings screens.
            } catch (_:SecurityException) {
                // A managed terminal may restrict access; try its public settings entry point.
            }
        }
        setMoveNavigationHidden(true)
        notice("Settings unavailable","This terminal does not allow Move to open ${if(mobile) "mobile network" else "Wi-Fi"} settings. Contact your terminal administrator.")
    }
    private var acquiringResult="Not tested"
    private fun connectionPage() {
        content.addView(text("Test acquiring connection",24f,true))
        val c=scrollColumn()
        section(c,"Terminal connection")
        val networks=connectivity.allNetworks.mapNotNull {connectivity.getNetworkCapabilities(it)}
        c.addView(text("Wi-Fi connected: ${if(networks.any {it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)}) "Yes" else "No"}",16f))
        c.addView(text("Mobile connected: ${if(networks.any {it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)}) "Yes" else "No"}",16f))
        section(c,"Elavon")
        c.addView(text(acquiringResult,23f,true))
        c.addView(text("ipconex.elavon.com:7703",14f))
        c.addView(text("Checks the host connection from this terminal.",14f))
        content.addView(button("Test connection",true) {
            network("Testing Elavon connection…") {
                acquiringResult=if(runCatching {java.net.Socket().use {it.connect(java.net.InetSocketAddress("ipconex.elavon.com",7703),5000)}}.isSuccess) "Connected" else "Connection failed"
                ""
            }
        }.apply {textSize=18f;isEnabled=!busy},LinearLayout.LayoutParams(-1,dp(56)))
        content.addView(button("Close") {menu()},LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun publishedTables() = store.config?.optJSONObject("goCatalog")?.objects("tables").orEmpty().filter { it.optBoolean("active", true) }
    private fun tablesEnabled() = store.config?.optJSONObject("goCatalog")?.optJSONObject("capabilities")?.optBoolean("tables", false) == true
    private fun startPage() = if(store.trading.pending()!=null || store.trading.cashParts().isNotEmpty()) "checkout" else "sell"
    private fun resetMenu() {
        val cat = catalogue
        val first = cat?.shortcuts?.firstOrNull { tab ->
            tab.screen.isNotBlank() && cat.screens.any { it.id == tab.screen } ||
                tab.department.isNotBlank() && cat.departments.any { it.id == tab.department }
        }
        screenId = first?.screen?.takeIf { it.isNotBlank() } ?: cat?.home.orEmpty()
        departmentId = first?.department?.takeIf { first.screen.isBlank() && it.isNotBlank() }
        measure = null; extra = null
    }
    private fun persistChange(afterSave: () -> Unit = {}, change: () -> Unit) {
        if (!basketEditable()) return
        val before = draft.encode()
        try { change(); status=""; store.saveDraft(draft); afterSave(); render() }
        catch (e: Exception) { draft = runCatching {store.loadDraft()}.getOrElse {decodeDraft(before)}; notice("Basket unchanged",e.message ?: "Could not save the basket") }
    }
    private fun basketEditable(allowCashParts:Boolean=false): Boolean {
        if (busy) return false
        if(store.trading.pending()!=null) { notice("Card payment in progress","Finish the payment in POSitive before changing this basket.");return false }
        if(!allowCashParts && store.trading.cashParts().isNotEmpty()) {notice("Cash recorded","Finish the remaining balance or return the cash from checkout before changing this basket.");page="checkout";render();return false}
        if (draft.sharedState != null) { notice("Old shared draft", "This legacy draft is retained for review in Saved order recovery."); return false }
        return true
    }
    private fun setOrderContext(mode: ServiceMode, table: String? = null, reference: String? = null) {
        if (!basketEditable()) return
        if (deal != null) { notice("Finish your selection", "Complete or cancel the current deal first."); return }
        persistChange { draft.changeContext(mode,table,reference); page = "sell" }
    }
    private fun nameOrder() {
        val dialog = android.app.Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = column().apply { background=border(); setPadding(dp(12),dp(12),dp(12),dp(12)) }
        panel.addView(text("Order reference",20f,true))
        val field = EditText(this).apply {
            hint="Name or reference";setText(draft.orderReference.orEmpty());setTextColor(textColor);setHintTextColor(muted)
            isSingleLine=true;showSoftInputOnFocus=false;isFocusable=false;isCursorVisible=false
            setOnClickListener { showMoveKeyboard(this,false,"Order reference") }
        }
        panel.addView(field)
        val error=text("",12f);panel.addView(error)
        val actions=row()
        actions.addView(button("Cancel") { dialog.dismiss() },LinearLayout.LayoutParams(0,dp(60),1f))
        actions.addView(button("Use reference",true) {
            try { val reference=validateOpenName(field.text.toString());setOrderContext(ServiceMode.RESTAURANT,null,reference);dialog.dismiss() }
            catch(e:Exception) { error.text=e.message }
        },LinearLayout.LayoutParams(0,dp(60),2f))
        panel.addView(actions);dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show();dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(16),WindowManager.LayoutParams.WRAP_CONTENT)
    }
    private fun standaloneContext() {
        content.addView(button("Back to order") {page="sell";render()})
        if(catalogue?.serviceModesEnabled==true) ServiceMode.values().forEach {mode -> content.addView(button(mode.label(),draft.serviceMode==mode) {setOrderContext(mode)})}
        content.addView(button("Name this order") {nameOrder()})
        if(tablesEnabled()) content.addView(button("Tables on this Handheld") {page="localTables";render()})
    }
    private fun saveDeviceTable() {
        if(!basketEditable() || deal!=null) return
        try {store.saveDraft(draft);store.trading.saveLocalTable(store.trading.draft {error("Missing basket")});draft=store.loadDraft();page="sell";status="Table saved on this Handheld";render()}
        catch(e:Exception) {notice("Table",e.message.orEmpty())}
    }
    private fun localTables() {
        content.addView(text("Tables on this Handheld",20f,true))
        content.addView(button("Back to order") {page="sell";render()})
        if(draft.tableId!=null) content.addView(button("Save ${draft.orderReference.orEmpty()} and leave",true) {saveDeviceTable()})
        val c=scrollColumn()
        publishedTables().chunked(3).forEach {tables ->
            val r=row();tables.forEach {table ->
                val id=table.getString("id");val label=table.getString("label")
                val saved=store.trading.localTable(id)
                r.addView(button("$label\n${saved?.let {money(decodeDraft(it).total)} ?: "Available"}") {
                    if(!basketEditable()) return@button
                    try {store.trading.openLocalTable(id,label);draft=store.loadDraft();page="sell";status="";render()}
                    catch(e:Exception) {notice("Table",e.message.orEmpty())}
                },LinearLayout.LayoutParams(0,dp(60),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})
            };c.addView(r)
        }
    }
    private fun checkout() {
        val pending=store.trading.pending()
        if(pending!=null) {
            content.addView(text("${draft.itemCount} items · ${money(draft.total)}",26f,true))
            content.addView(text(if(pending.optString("type")=="REFUND") "Waiting for card machine · refund" else "Waiting for card machine",20f,true))
            content.addView(ProgressBar(this).apply {isIndeterminate=true})
            content.addView(text("${money(pending.optLong("cardRequestAmountMinor",pending.getLong("amountMinor")))} · ${pending.optString("reference")}",17f,true))
            content.addView(text(pending.optString("loyaltyReconciliationError").ifBlank { "Finish on the card machine. Move will update when it returns the result." }))
            if(BuildConfig.IS_LOYALTY_TEST) {
                val result=pending.optJSONObject("processorResult")
                fun savedAmount(key:String):String = if(result==null || result.isNull(key)) "not returned" else "${result.opt(key)} minor units"
                content.addView(text("Saved payment diagnostics",16f,true))
                content.addView(text("Basket: ${pending.getLong("amountMinor")} minor units\nPAX amount: ${savedAmount("amountMinor")}\nPAX discount: ${savedAmount("discountMinor")}\nPAX status: ${result?.optString("status") ?: "not returned"}",14f))
                val amountLines=result?.optString("receipt").orEmpty().lineSequence()
                    .filter {Regex("(?i)\\b(amount|total|discount)\\b").containsMatchIn(it)}
                    .take(12).toList()
                content.addView(text("Saved PAX receipt amount lines:\n${amountLines.joinToString("\n").ifBlank {"No amount lines saved."}}",14f))
            }
            return
        }
        val title=row()
        title.addView(text("Basket",23f,true),LinearLayout.LayoutParams(0,-2,1f))
        title.addView(text(catalogue?.location.orEmpty(),12f))
        content.addView(title)
        content.addView(basketSummary(true),LinearLayout.LayoutParams(-1,dp(112)).apply {setMargins(dp(3),dp(10),dp(3),dp(8))})
        val parts=store.trading.cashParts()
        if(parts.isNotEmpty()) {
            content.addView(text("Cash recorded ${money(parts.sumOf {it.getLong("amountMinor")})} · Remaining ${money(remainingBalance())}",18f,true))
            content.addView(button("Return recorded cash") {
                AlertDialog.Builder(this).setTitle("Return ${money(parts.sumOf {it.getLong("amountMinor")})} cash?").setMessage("Confirm after giving the recorded cash back to the customer. The basket will remain open.").setNegativeButton("Keep",null).setPositiveButton("Cash returned") {_,_->
                    try {require(staff?.allowed("takePaymentEnabled")==true);store.trading.returnCash(requireNotNull(staff).id);status="Cash returned";render()} catch(e:Exception) {notice("Cash",e.message.orEmpty())}
                }.show()
            })
        }
        content.addView(android.view.View(this),LinearLayout.LayoutParams(-1,0,1f))
        if(draft.tableId!=null) content.addView(button("Save to ${draft.orderReference.orEmpty()}") {saveDeviceTable()}.apply {textSize=16f})
        else if(tablesEnabled()) content.addView(button("Save to table") {page="localTables";render()}.apply {textSize=16f})
        val actions=row()
        actions.addView(button("Back") {page="sell";render()}.apply {textSize=18f},LinearLayout.LayoutParams(0,dp(48),1f).apply {setMargins(dp(3),dp(3),dp(3),dp(5))})
        actions.addView(button("Void") {cancelDraft()}.apply {textSize=18f;setTextColor(if(store.dark) Color.rgb(255,140,140) else Color.rgb(178,30,30));isEnabled=parts.isEmpty() && draft.lines.isNotEmpty()},LinearLayout.LayoutParams(0,dp(48),1f).apply {setMargins(dp(3),dp(3),dp(3),dp(5))})
        content.addView(actions)
        val tenders=store.config?.objects("tenders").orEmpty().filter {it.optBoolean("enabled") && (!BuildConfig.IS_LOYALTY_TEST || it.optString("type")=="CARD")}
        fun tender(type:String,icon:Int,primary:Boolean,height:Int) {
            val t=tenders.firstOrNull {it.optString("type")==type} ?: return
            content.addView(button(t.optString("displayName",if(type=="CARD") "Card" else "Cash"),primary) {
                if(type=="CARD") cardPayment() else cashPayment()
            }.apply {
                textSize=22f;setTypeface(null,Typeface.BOLD)
                isEnabled=draft.lines.isNotEmpty() && deal==null && !busy && !(store.training && type=="CARD")
                setCompoundDrawablesWithIntrinsicBounds(0,icon,0,0);compoundDrawablePadding=dp(4)
                compoundDrawableTintList=android.content.res.ColorStateList.valueOf(if(primary) ink else textColor)
            },LinearLayout.LayoutParams(-1,dp(height)).apply {setMargins(dp(3),dp(5),dp(3),dp(3))})
        }
        if(store.training) content.addView(text("Card payments disabled in training",14f,true))
        if(BuildConfig.IS_LOYALTY_TEST && LoyaltyBridge.installed(this)) {
            content.addView(android.widget.CheckBox(this).apply {
                text="Customer agreed · enrol this card in loyalty"
                isChecked=loyaltyOptIn
                setOnCheckedChangeListener {_,checked->loyaltyOptIn=checked}
            })
        }
        tender("CARD",R.drawable.tender_card,true,82)
        tender("CASH",R.drawable.tender_cash,false,68)
    }
    private fun basketSummary(editable:Boolean):android.view.View {
        val frame=android.widget.FrameLayout(this)
        val control=button("") {if(editable) {page="basket";render()}}
        control.isClickable=editable
        control.contentDescription="${draft.itemCount} ${if(draft.itemCount==1) "item" else "items"}, ${money(draft.total)}${if(editable) ", View and amend items" else ""}"
        frame.addView(control,android.widget.FrameLayout.LayoutParams(-1,-1))
        val details=column().apply {setPadding(dp(12),dp(10),dp(12),dp(8));gravity=Gravity.CENTER_VERTICAL}
        val totals=row()
        totals.addView(text("${draft.itemCount} ${if(draft.itemCount==1) "item" else "items"}",20f,true),LinearLayout.LayoutParams(0,-2,1f))
        totals.addView(text(money(draft.total),30f,true))
        details.addView(totals)
        if(editable) details.addView(text("View & amend items",14f,true).apply {setPadding(dp(3),dp(10),dp(3),0)})
        frame.addView(details,android.widget.FrameLayout.LayoutParams(-1,-1))
        return frame
    }
    private fun remainingBalance()=draft.total-store.trading.cashParts().sumOf {it.getLong("amountMinor")}
    private var cashDigits=""
    private var cashReplace=true
    private var cashEntryId=""
    private fun cashPayment() {
        if(!basketEditable(true)) return
        cashDigits=remainingBalance().toString();cashReplace=true
        cashEntryId=java.util.UUID.randomUUID().toString()
        status="";page="cash";render()
    }
    private fun cashPage() {
        val due=remainingBalance()
        val amount=cashDigits.toLongOrNull() ?: 0L
        val title=row()
        title.addView(text("Cash",27f,true),LinearLayout.LayoutParams(0,-2,1f))
        title.addView(text("Total due\n${money(due)}",20f,true).apply {gravity=Gravity.RIGHT})
        content.addView(title)
        val summary=row().apply {background=border();setPadding(dp(10),dp(12),dp(10),dp(12))}
        val received=column();received.addView(text("Cash received",14f));received.addView(text(money(amount),32f,true))
        summary.addView(received,LinearLayout.LayoutParams(0,-2,1.4f))
        val change=column()
        change.addView(text(when {amount>due->"Change\n${money(amount-due)}";amount<due->"Remaining\n${money(due-amount)}";else->"No change due"},15f,true).apply {gravity=Gravity.CENTER})
        change.addView(button("Exact cash") {cashDigits=due.toString();cashReplace=true;render()},LinearLayout.LayoutParams(-1,dp(48)))
        summary.addView(change,LinearLayout.LayoutParams(0,-2,1f));content.addView(summary)
        val keypad=column()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("00","0","Del")).forEach {keys->
            val line=row()
            keys.forEach {label->line.addView(button(label) {
                cashDigits=if(label=="Del") cashDigits.dropLast(1) else {
                    val next=((if(cashReplace) "" else cashDigits)+label).trimStart('0')
                    if((next.toLongOrNull() ?: 0L)<=100_000_000L && next.length<=9) next else cashDigits
                }
                cashReplace=false;render()
            }.apply {textSize=25f;setTypeface(null,Typeface.BOLD);if(label=="Del") setTextColor(Color.rgb(184,40,40))},LinearLayout.LayoutParams(0,-1,1f).apply {setMargins(dp(2),dp(3),dp(2),dp(3))})}
            keypad.addView(line,LinearLayout.LayoutParams(-1,0,1f))
        }
        content.addView(keypad,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=dp(6);bottomMargin=dp(6)})
        val actions=row()
        actions.addView(button("Back") {page="checkout";render()}.apply {textSize=20f},LinearLayout.LayoutParams(0,dp(60),1f).apply {setMargins(dp(2),0,dp(2),0)})
        val confirm=when {amount==due->"Exact cash";amount<due->"Part payment";else->"Confirm cash"}
        actions.addView(button(confirm,true) {confirmCash(amount,due)}.apply {textSize=20f;isEnabled=amount>0 && due>0 && !busy},LinearLayout.LayoutParams(0,dp(60),1f).apply {setMargins(dp(2),0,dp(2),0)})
        content.addView(actions)
    }
    private fun confirmCash(amount:Long,due:Long) {
        if(!basketEditable(true)) return
        val user=staff ?: return
        if(amount<=0 || amount>100_000_000 || due!=remainingBalance()) {notice("Cash","Check the cash amount and balance.");return}
        network("Recording cash sale…") {
            if(amount<due) {
                store.recordCashPart(draft,user,amount,cashEntryId);page="checkout"
                return@network "Cash ${money(amount)} recorded · remaining ${money(remainingBalance())}"
            }
            val sale=store.beginSale(draft,requireNotNull(catalogue),user,"CASH",amount)
            store.trading.finish(sale.getString("id"),"APPROVED","CASH-${sale.getString("id")}")
            draft=store.loadDraft();displayedSaleId=sale.getString("id");page="receipt"
            uploadInBackground();"Change ${money(sale.getLong("changeMinor"))}"
        }
    }
    private fun cardPayment() {
        if(!basketEditable(true)) return
        val user=staff ?: return
        try {
            val sale=store.beginSale(draft,requireNotNull(catalogue),user,"CARD")
            val enrol=loyaltyOptIn;loyaltyOptIn=false
            fun launch() {
                try {PaxTrading.launch(this,store,sale);page="checkout";status="";render()}
                catch(e:Exception) {LoyaltyBridge.close(this,sale.getString("id"));notice("Card payment",e.message.orEmpty());render()}
            }
            if(LoyaltyBridge.installed(this)) {
                busy=true;render()
                LoyaltyBridge.prepare(this,sale,user.id,enrol) {ready ->
                    busy=false
                    if(ready) launch() else {
                        store.trading.cancelLaunch(sale.getString("id"),"Loyalty setup could not start. No payment sent.")
                        notice("Loyalty","Setup could not start. No payment was sent to POSitive.");render()
                    }
                }
            } else launch()
        } catch(e:Exception) {notice("Card payment",e.message ?: "Payment could not start");render()}
    }
    private var cashCount=CashCount().apply {denominations=false}
    private var cashCountKey=""
    private var dayConfirmed=false
    private var enteringCash=false
    private var cashEntered=false
    private fun tradingDayPage() {
        val user=staff ?: return
        val day=store.trading.openDay()
        val ending=day!=null
        val key="${user.id}:${day?.optString("id") ?: "start"}"
        if(cashCountKey!=key) {cashCountKey=key;cashCount=CashCount().apply {denominations=false};dayConfirmed=false;enteringCash=false;cashEntered=false}
        content.addView(text(if(ending) "End Day" else "Start Day",22f,true))
        if(!user.allowed(if(ending) "zReportEnabled" else "startDayEnabled")) {
            content.addView(text("${if(ending) "End" else "Start"} day permission is required."))
            content.addView(button("Back to till") {page="sell";render()});return
        }
        if(ending && !dayConfirmed) {
            content.addView(text("Are you sure you want to end day?",20f,true),LinearLayout.LayoutParams(-1,0,1f))
            dayActions("No",{page="sell";render()},"Yes",{dayConfirmed=true;render()});return
        }
        if(ending && !enteringCash) {
            val report=requireNotNull(store.trading.currentDayReport())
            val c=scrollColumn()
            cashUpExpectedValues(c,report)
            c.addView(button(if(cashEntered) "Actual cash  ${money(cashCount.total)} · edit" else "Enter actual cash · optional",false) {enteringCash=true;render()},LinearLayout.LayoutParams(-1,dp(60)))
            if(cashEntered) {
                c.addView(button("Skip cash count") {cashEntered=false;render()})
                val difference=cashCount.total-report.getLong("expectedCashMinor")
                c.addView(text(if(difference==0L) "Cash balanced" else "${if(difference<0) "Short" else "Over"} ${money(kotlin.math.abs(difference))}",18f,true))
            }
            dayActions("Cancel",{cashCountKey="";page="sell";render()},"End day",{
                try {
                    if(store.training) {
                        store.trading.closeDay(requireNotNull(day).getString("id"),if(cashEntered) cashCount.total else null,user.id,user.name,"Training only")
                        staff=null;page="login";cashCountKey="";status="Training day ended"
                    } else {
                        PaxTrading.endDay(this,store,user,requireNotNull(day).getString("id"),if(cashEntered) cashCount.total else null)
                        page="dayFinish";cashCountKey="";status=""
                    }
                    render()
                } catch(e:Exception) {notice("End day",e.message.orEmpty());render()}
            });return
        }
        content.addView(text(if(ending) "Enter actual cash total" else "Enter float amount",18f,true))
        content.addView(text(money(cashCount.total),36f,true).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,0,1f))
        listOf(listOf("7","8","9"),listOf("4","5","6"),listOf("1","2","3"),listOf("Clear","0","Delete")).forEach {keys->
            val line=row();keys.forEach {label->line.addView(button(label) {cashCount.key(label);render()}.apply {textSize=21f},LinearLayout.LayoutParams(0,dp(54),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})};content.addView(line)
        }
        content.addView(button("Cancel") {if(ending) enteringCash=false else {cashCountKey="";page="sell"};render()})
        content.addView(button(if(ending) "Save cash total" else "Start",true) {
            if(ending) {cashEntered=true;enteringCash=false;render()}
            else try {store.startDay(user,cashCount.total);cashCountKey="";page="sell";status="Day started";render()}
            catch(e:Exception) {notice("Start day",e.message.orEmpty())}
        },LinearLayout.LayoutParams(-1,dp(56)))
    }
    private fun dayActions(left:String,onLeft:()->Unit,right:String,onRight:()->Unit) {
        val actions=row()
        actions.addView(button(left,false,onLeft).apply {textSize=18f},LinearLayout.LayoutParams(0,dp(68),1f).apply {setMargins(0,dp(8),dp(4),dp(4))})
        actions.addView(button(right,true,onRight).apply {textSize=18f},LinearLayout.LayoutParams(0,dp(68),1f).apply {setMargins(dp(4),dp(8),0,dp(4))})
        content.addView(actions)
    }
    private fun cashUpExpectedValues(c:LinearLayout,report:org.json.JSONObject) {
        c.addView(text("Expected values",18f,true))
        listOf("Sales" to "totalValueMinor","Refunds" to "refundedTotalMinor","Card sales" to "cardTotalMinor","Card refunds" to "cardRefundTotalMinor","Cash sales" to "cashTotalMinor","Cash refunds" to "cashRefundTotalMinor","Start day float" to "openingFloatMinor","Expected cash" to "expectedCashMinor").forEach {(label,field)->
            val line=row();line.addView(text(label,15f),LinearLayout.LayoutParams(0,-2,1f));line.addView(text(money(report.optLong(field)),16f,true));c.addView(line)
        }
    }
    private var historyToday=true
    private var historySaleId:String?=null
    private fun salesHistory() {
        content.addView(text("Orders",26f,true))
        if(staff?.allowed("transactionsEnabled")!=true) {
            content.addView(text("Orders permission is required."));content.addView(button("Back") {menu()});return
        }
        val date=tradingDate(store.config ?: org.json.JSONObject())
        val filters=row()
        listOf("Today" to true,"Recent" to false).forEach {(label,today)->filters.addView(button(label,historyToday==today) {historyToday=today;render()},LinearLayout.LayoutParams(0,dp(44),1f).apply {setMargins(dp(2),dp(4),dp(2),dp(4))})}
        content.addView(filters)
        val sales=store.trading.history(if(historyToday) date else null)
        content.addView(text("${sales.size} orders · ${if(historyToday) date else "latest 100"} · this device",12f).apply {setTextColor(muted)})
        val c=scrollColumn()
        if(sales.isEmpty()) {
            c.addView(text("No orders yet",21f,true).apply {setPadding(dp(8),dp(32),dp(8),dp(8))})
            c.addView(text("Completed orders will appear here. Refunds update the original order.",14f))
        }
        sales.forEach {sale->
            val refundStatus=sale.optString("refundStatus")
            val time=SimpleDateFormat(if(historyToday) "HH:mm" else "dd MMM · HH:mm",Locale.UK).format(Date(sale.getLong("createdAt")))
            val tenders=sale.objects("tenders").joinToString(" + ") {it.optString("label",it.optString("type"))}
            navigationCard(c,"${money(sale.getLong("amountMinor"))}${if(refundStatus.isNotBlank()) " · $refundStatus" else ""}","$time · $tenders · ${sale.optString("reference")}"+(if(sale.optLong("refundedMinor")>0) "\nRefunded ${money(sale.getLong("refundedMinor"))} · Remaining ${money(sale.getLong("remainingRefundableMinor"))}" else "")) {
                historySaleId=sale.getString("id");page="orderDetail";render()
            }
        }
        content.addView(button("Back to till",true) {page=startPage();render()},LinearLayout.LayoutParams(-1,dp(52)))
    }
    private fun orderReceiptData(entry:org.json.JSONObject):org.json.JSONObject {
        if(entry.optString("status","APPROVED")!="APPROVED") return entry
        val original=if(entry.optString("type")=="REFUND")
            store.trading.sale(entry.optString("originalTransactionId")) ?: return entry else entry
        return updatedOrderReceipt(original,store.trading.refundsFor(original.getString("id")))
    }
    private fun orderDetail() {
        if(staff?.allowed("transactionsEnabled")!=true) {page="history";salesHistory();return}
        val sale=historySaleId?.let {store.trading.sale(it)} ?: run {page="history";salesHistory();return}
        content.addView(button("‹  Orders") {page="history";render()},LinearLayout.LayoutParams(-1,dp(42)))
        val refund=sale.optString("type")=="REFUND"
        val title=row();title.addView(text(if(refund) "Refund" else "Order",24f,true),LinearLayout.LayoutParams(0,-2,1f));title.addView(text(money(sale.getLong("amountMinor")),26f,true));content.addView(title)
        content.addView(text("${sale.optString("reference")} · ${SimpleDateFormat("dd MMM, HH:mm",Locale.UK).format(Date(sale.getLong("createdAt")))}",12f).apply {setTextColor(muted)})
        val previous=if(refund) emptyList() else store.trading.refundsFor(sale.getString("id"))
        val remaining=(sale.getLong("amountMinor")-previous.sumOf {it.getLong("amountMinor")}).coerceAtLeast(0)
        val actions=row()
        actions.addView(button("Print receipt") {
            network("Printing…") {PaxTrading.print(this,orderReceiptData(sale));"Receipt printed"}
        }.apply {textSize=16f},LinearLayout.LayoutParams(0,dp(56),1f))
        if(!BuildConfig.IS_LOYALTY_TEST && !refund && staff?.allowed("refundsEnabled")==true) {
            if(remaining>0) actions.addView(button("Refund order",true) {refundChoices(sale)}.apply {textSize=16f},LinearLayout.LayoutParams(0,dp(56),1f).apply {leftMargin=dp(6)})
        }
        content.addView(actions)
        if(!refund && previous.isNotEmpty()) content.addView(text(if(remaining==0L) "Fully refunded" else "Remaining refundable ${money(remaining)}",16f,true))
        val c=scrollColumn()
        section(c,"Items")
        sale.objects("saleItems").forEach {item->
            val card=column().apply {background=border();setPadding(dp(10),dp(8),dp(10),dp(8))}
            val line=row();line.addView(text("${item.optInt("quantity")} × ${item.optString("productName")}",16f,true),LinearLayout.LayoutParams(0,-2,1f));line.addView(text(money(item.optLong("grossMinor")),16f,true));card.addView(line)
            item.objects("modifiers").forEach {card.addView(text(it.optString("name"),13f).apply {setTextColor(muted)})}
            val refundedQuantity=previous.sumOf {entry -> entry.objects("saleItems").filter {it.optString("id")==item.optString("id")}.sumOf {it.optInt("quantity")} }
            if(refundedQuantity>0) card.addView(text("Refunded $refundedQuantity of ${item.optInt("quantity")}",13f,true).apply {setTextColor(muted)})
            c.addView(card,LinearLayout.LayoutParams(-1,-2).apply {setMargins(0,dp(3),0,dp(3))})
        }
        section(c,"Payment")
        fun detail(label:String,value:String) {val line=row();line.addView(text(label,14f),LinearLayout.LayoutParams(0,-2,1f));line.addView(text(value,15f,true));c.addView(line)}
        if(sale.optBoolean("loyaltyReconciled")) {detail("Basket subtotal",money(sale.getLong("originalBasketMinor")));detail("Loyalty discount",money(sale.getLong("loyaltyDiscountMinor")))}
        sale.objects("tenders").forEach {detail(it.optString("label",it.optString("type")),money(it.optLong("amountMinor")))}
        if(sale.optLong("cashReceivedMinor")>0) {detail("Cash received",money(sale.optLong("cashReceivedMinor")));detail("Change",money(sale.optLong("changeMinor")))}
        detail("Served by",sale.optString("staffName"))
        if(previous.isNotEmpty()) {
            section(c,"Refunds on this order")
            detail("Original sale",money(sale.getLong("amountMinor")))
            detail("Refunded",money(previous.sumOf {it.getLong("amountMinor")}))
            detail("Remaining",money(remaining))
            previous.forEach {entry ->
                val time=SimpleDateFormat("dd MMM, HH:mm",Locale.UK).format(Date(entry.getLong("createdAt")))
                c.addView(text("$time · ${money(entry.getLong("amountMinor"))}",16f,true).apply {setPadding(0,dp(12),0,0)})
                c.addView(text(entry.objects("tenders").joinToString(" + ") {"${it.optString("label",it.optString("type"))} ${money(it.getLong("amountMinor"))}"},14f))
                c.addView(text("${entry.optString("refundReason")} · ${entry.optString("staffName")}",13f).apply {setTextColor(muted)})
            }
        }
        if(refund) c.addView(text("Reason: ${sale.optString("refundReason")}",14f))
    }
    private fun refundChoices(sale:org.json.JSONObject) {
        val previous=store.trading.refundsFor(sale.getString("id"))
        val remaining=refundableQuantities(sale,previous)
        if(remaining.values.none {it>0}) {notice("Refund","This order has been fully refunded.");return}
        val amount=selectedRefundLines(sale,previous,remaining).sumOf {it.getLong("grossMinor")}
        val choices=column().apply {setPadding(dp(16),dp(8),dp(16),dp(8))}
        val dialog=AlertDialog.Builder(this).setTitle("Refund completed order").setView(choices).setNegativeButton("Keep order",null).create()
        choices.addView(button("Refund all · ${money(amount)}") {dialog.dismiss();refundSale(sale,remaining)}.apply {setTextColor(Color.rgb(184,40,40))},LinearLayout.LayoutParams(-1,dp(64)))
        choices.addView(text("Refund the full remaining order value.",14f))
        choices.addView(button("Refund items") {dialog.dismiss();refundItemPicker(sale)} ,LinearLayout.LayoutParams(-1,dp(64)))
        choices.addView(text("Choose items and quantities to refund.",14f))
        dialog.show()
    }
    private fun refundItemPicker(sale:org.json.JSONObject) {
        val previous=store.trading.refundsFor(sale.getString("id"))
        val remaining=refundableQuantities(sale,previous)
        val quantities=mutableMapOf<String,Int>()
        val body=column().apply {setPadding(dp(12),0,dp(12),0)}
        val scroll=ScrollView(this).apply {addView(body)}
        val total=text("Selected · ${money(0)}",18f,true);body.addView(total)
        val dialog=AlertDialog.Builder(this).setTitle("Refund items").setView(scroll).setNegativeButton("Back") {_,_->refundChoices(sale)}.setPositiveButton("Continue",null).create()
        fun updateTotal() {
            val selected=quantities.filterValues {it>0}
            val amount=if(selected.isEmpty()) 0L else selectedRefundLines(sale,previous,selected).sumOf {it.getLong("grossMinor")}
            total.text="Selected · ${money(amount)}"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled=amount>0
        }
        sale.objects("saleItems").forEach {item ->
            val id=item.getString("id");val available=remaining.getValue(id)
            body.addView(text(item.getString("productName"),16f,true))
            body.addView(text(if(available==0) "Already refunded" else "$available remaining",13f))
            if(available>0) {
                val controls=row();val count=text("0",20f,true).apply {gravity=Gravity.CENTER}
                controls.addView(button("−") {quantities[id]=((quantities[id] ?: 0)-1).coerceAtLeast(0);count.text=quantities[id].toString();updateTotal()},LinearLayout.LayoutParams(0,dp(52),1f))
                controls.addView(count,LinearLayout.LayoutParams(0,dp(52),1f))
                controls.addView(button("+") {quantities[id]=((quantities[id] ?: 0)+1).coerceAtMost(available);count.text=quantities[id].toString();updateTotal()},LinearLayout.LayoutParams(0,dp(52),1f))
                body.addView(controls)
            }
        }
        dialog.setOnShowListener {updateTotal();dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {dialog.dismiss();refundSale(sale,quantities.filterValues {it>0})}}
        dialog.show()
    }
    private fun refundSale(sale:org.json.JSONObject,quantities:Map<String,Int>) {
        val amount=try {selectedRefundLines(sale,store.trading.refundsFor(sale.getString("id")),quantities).sumOf {it.getLong("grossMinor")}} catch(e:Exception) {notice("Refund",e.message ?: "Refund unavailable");return}
        if(store.trading.pending()!=null) {page="checkout";render();return}
        val allocation=try {refundTenderAllocation(sale,store.trading.refundsFor(sale.getString("id")),amount)} catch(e:Exception) {notice("Refund",e.message ?: "Refund unavailable");return}
        val cash=allocation.filter {it.getString("type")=="CASH"}.sumOf {it.getLong("amountMinor")}
        val card=allocation.filter {it.getString("type")=="CARD"}.sumOf {it.getLong("amountMinor")}
        val dialog=android.app.Dialog(this)
        val panel=column().apply {background=border();setPadding(dp(16),dp(16),dp(16),dp(16))}
        panel.addView(text("Confirm refund",24f,true))
        val count=quantities.values.sum()
        panel.addView(text("$count ${if(count==1) "item" else "items"} · Order ${sale.optString("reference")}",12f).apply {setTextColor(muted)})
        val body=scrollColumn(panel)
        body.addView(text(money(amount),38f,true).apply {setPadding(0,dp(12),0,dp(8))})
        allocation.forEach {tender ->
            val line=row()
            line.addView(text("${tender.getString("label")} refund",16f),LinearLayout.LayoutParams(0,-2,1f))
            line.addView(text(money(tender.getLong("amountMinor")),18f,true))
            body.addView(line)
        }
        val instructions=if(card>0) "Complete the card refund in POSitive."+(if(cash>0) " Return ${money(cash)} cash only after card approval." else "") else "Return ${money(cash)} cash to the customer when you confirm."
        body.addView(text(instructions,14f).apply {setTextColor(muted);setPadding(0,dp(8),0,dp(12))})
        body.addView(text("Reason for refund",16f,true))
        val reason=EditText(this).apply {
            hint="Or enter another reason";setSingleLine(false);maxLines=2;textSize=15f;setTextColor(textColor);setHintTextColor(muted)
            background=border();setPadding(dp(10),dp(10),dp(10),dp(10));showSoftInputOnFocus=false
            setOnClickListener {showMoveKeyboard(this,false,"Refund reason")}
        }
        val presets=listOf("Customer changed mind","Order mistake","Item unavailable","Duplicate sale")
        val reasonButtons=mutableListOf<Pair<String,Button>>()
        presets.chunked(2).forEach {labels ->
            val line=row()
            labels.forEach {label ->
                val choice=button(label) {reason.setText(label)}.apply {textSize=13f}
                reasonButtons+=label to choice
                line.addView(choice,LinearLayout.LayoutParams(0,dp(48),1f).apply {setMargins(dp(2),dp(3),dp(2),dp(3))})
            }
            body.addView(line)
        }
        body.addView(reason,LinearLayout.LayoutParams(-1,dp(62)).apply {topMargin=dp(8)})
        val footer=row().apply {setPadding(0,dp(12),0,0)}
        footer.addView(button("Cancel") {dialog.dismiss()}.apply {textSize=16f},LinearLayout.LayoutParams(0,dp(56),1f).apply {rightMargin=dp(6)})
        val confirm=button("Confirm refund",true) {
            if(reason.text.toString().isNotBlank()) {
            try {
                val refund=store.beginRefund(sale,requireNotNull(staff),reason.text.toString(),quantities)
                dialog.dismiss()
                if(refund.optLong("cardAmountMinor")==0L) {
                    store.trading.finish(refund.getString("id"),"APPROVED","CASH-${refund.getString("id")}")
                    draft=store.loadDraft();page="receipt";uploadInBackground()
                } else {PaxTrading.launch(this,store,refund);page="checkout"}
                render()
            } catch(e:Exception) {notice("Refund",e.message ?: "Refund could not start")}
            }
        }.apply {textSize=16f;isEnabled=false;alpha=0.45f}
        footer.addView(confirm,LinearLayout.LayoutParams(0,dp(56),1.6f))
        panel.addView(footer)
        reason.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                confirm.isEnabled=!s.isNullOrBlank();confirm.alpha=if(confirm.isEnabled) 1f else 0.45f
                reasonButtons.forEach {(label,view) ->
                    val chosen=s?.toString()==label
                    view.setTextColor(if(chosen) ink else textColor)
                    view.background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000),border(if(chosen) lime else surfaceColor),border(Color.WHITE))
                }
            }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(24),(resources.displayMetrics.heightPixels*0.84).toInt())
    }
    private fun receiptPage() {
        val sale=store.trading.lastOutcome() ?: store.trading.lastSale() ?: run {page="sell";sell();return}
        val id=sale.getString("id")
        val result=sale.optString("status","APPROVED")
        val approved=result=="APPROVED"
        val refund=sale.optString("type")=="REFUND"
        val card=sale.objects("tenders").any {it.optString("type")=="CARD"}
        val title=when(result) {"APPROVED" -> if(card) "Approved" else if(refund) "Refund complete" else "Cash received";"DECLINED" -> "Declined";"CANCELLED" -> "Cancelled";else -> "Not completed"}
        val accent=if(approved) lime else if(store.dark) Color.rgb(255,155,145) else Color.rgb(170,35,30)
        val body=column().apply {setPadding(dp(12),dp(18),dp(12),dp(12))}
        content.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        body.addView(text(if(refund) "Refund" else if(card) "Card payment" else "Cash payment",14f,true).apply {setTextColor(muted)})
        body.addView(text(title,34f,true).apply {
            setTextColor(if(approved) ink else accent);background=border(if(approved) lime else surfaceColor)
            setPadding(dp(14),dp(12),dp(14),dp(12))
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12);bottomMargin=dp(16)})
        body.addView(text(money(if(card && approved && sale.optBoolean("loyaltyReconciled")) sale.getLong("cardAmountMinor") else if(card) sale.optLong("cardRequestAmountMinor",sale.getLong("amountMinor")) else sale.getLong("amountMinor")),38f,true))
        if(approved && sale.optBoolean("loyaltyReconciled")) {
            body.addView(text("Basket ${money(sale.getLong("originalBasketMinor"))}\nLoyalty discount ${money(sale.getLong("loyaltyDiscountMinor"))}\nCard paid ${money(sale.getLong("cardAmountMinor"))}",18f,true))
        }
        if(approved && refund && sale.optLong("cashAmountMinor")>0) {
            body.addView(text("Return cash to customer",18f,true));body.addView(text(money(sale.getLong("cashAmountMinor")),32f,true))
        } else if(approved && sale.optLong("changeMinor")>0) {
            body.addView(text("Change due",16f,true));body.addView(text(money(sale.getLong("changeMinor")),32f,true))
        } else if(!approved) body.addView(text(if(refund) "Refund not completed."+(if(sale.optLong("cashAmountMinor")>0) " Do not return cash." else "") else "Basket kept open.",16f))
        body.addView(text("Reference ${sale.optString("reference")}",12f).apply {setTextColor(muted)})
        if(approved && sale.getLong("amountMinor")!=sale.optLong("cardRequestAmountMinor",sale.getLong("amountMinor"))) body.addView(text("${if(refund) "Refund" else "Sale"} total ${money(sale.getLong("amountMinor"))}",14f))
        val hasReceipt=approved || sale.optString("cardholderReceipt").let {it.isNotBlank() && it!="null"}
        content.addView(button("Print receipt") {
            outcomeDeadline=0;outcomeHandler.removeCallbacks(outcomeTick)
            network("Printing receipt…") {PaxTrading.print(this,orderReceiptData(sale));"Receipt printed"}
        }.apply {
            textSize=20f;setTypeface(null,Typeface.BOLD);isEnabled=hasReceipt && !busy
            val icon=getDrawable(R.drawable.receipt_outline)?.mutate()
            icon?.setTint(textColor);icon?.setBounds(0,0,dp(26),dp(26));setCompoundDrawables(icon,null,null,null)
            compoundDrawablePadding=dp(12);setPadding(dp(20),dp(10),dp(20),dp(10))
        },LinearLayout.LayoutParams(-1,dp(70)).apply {setMargins(dp(10),dp(4),dp(10),dp(6))})
        content.addView(button(if(approved && !refund) "New sale" else "Done",true) {finishOutcome()}.apply {
            textSize=22f;setTypeface(null,Typeface.BOLD)
        },LinearLayout.LayoutParams(-1,dp(70)).apply {setMargins(dp(10),dp(4),dp(10),dp(6))})
        outcomeCountdown=text(if(hasReceipt) "" else "No receipt returned by the card machine",12f).apply {gravity=Gravity.CENTER;setTextColor(muted)}
        content.addView(outcomeCountdown,LinearLayout.LayoutParams(-1,dp(30)))
        if(!busy) {
            if(timedOutcomeId!=id || outcomeDeadline==0L) outcomeDeadline=android.os.SystemClock.elapsedRealtime()+15000
            timedOutcomeId=id;outcomeTick.run()
        }
    }
    private fun footerLabel() = "${money(draft.total)} · ${draft.itemCount} items\nPay & basket  ›"
    private fun cancelDraft() {
        if (!basketEditable()) return
        if (draft.lines.isEmpty() && deal == null) { page = startPage(); render(); return }
        AlertDialog.Builder(this).setTitle("Cancel this basket?").setNegativeButton("Keep", null)
            .setPositiveButton("Cancel basket") { _, _ -> persistChange { draft.clear(); deal = null; dealProducts.clear(); dealChoices.clear(); page = startPage() } }.show()
    }
    private fun footer() {
        val actions = row()
        basketButton = button(footerLabel(), true) { if (deal != null) notice("Finish your selection", "Complete or cancel the current deal first.") else { page = "checkout"; render() } }
        basketButton.textSize=18f
        actions.addView(button("Cancel") { cancelDraft() }.apply {textSize=18f}, LinearLayout.LayoutParams(0,dp(72),1f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        actions.addView(basketButton, LinearLayout.LayoutParams(0,dp(72),2f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        content.addView(actions)
    }
    private fun scrollColumn(parent: LinearLayout = content): LinearLayout {
        val inner = column(); parent.addView(ScrollView(this).apply { isFillViewport = true; addView(inner) }, LinearLayout.LayoutParams(-1,0,1f)); return inner
    }
    private fun loginSettings() {
        content.addView(text("Settings",24f,true))
        val c=scrollColumn()
        c.addView(button("Wi-Fi settings") {openNetworkSettings(false)})
        c.addView(button("Mobile network settings") {openNetworkSettings(true)})
        c.addView(text(if(store.training) "Training mode · practice sales stay on this device. Card payments are disabled." else "Training uses your menu with separate practice orders. No card payments or sales sent to back office."))
        c.addView(button(if(store.training) "Exit training mode" else "Enter training mode") {switchTraining(!store.training)})
        c.addView(button("Unpair terminal") {unpairWithPasscode()})
        content.addView(button("Back to login",true) {page="login";render()})
    }
    private fun switchTraining(enabled:Boolean) {
        try {
            check(!busy && !uploading.get()) {"Wait for the current operation to finish."}
            check(store.config!=null) {"Pair and sync a menu before training."}
            reportHandler.removeCallbacks(reportTick)
            val next=DeviceStore(this,training=enabled)
            val nextDraft=next.loadDraft()
            check(getSharedPreferences("handheld-ui",0).edit().putBoolean("training",enabled).commit())
            store.trading.close();store=next;draft=nextDraft;staff=null;deal=null;dealProducts.clear();dealChoices.clear()
            cashCountKey="";displayedSaleId=null;page="login";status=if(enabled) "Training mode · card payments disabled" else "Training ended · real trading restored"
            resetMenu();render();uploadInBackground()
        } catch(e:Exception) {notice("Training mode",e.message.orEmpty())}
    }
    private fun unpairWithPasscode() {
        val input=EditText(this).apply {
            hint="Unpair passcode";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters=arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this).setTitle("Unpair terminal").setView(input).setNegativeButton("Cancel",null)
            .setPositiveButton("Continue") {_,_->
                val code=input.text.toString()
                fun ready() {
                    check(!busy && !uploading.get()) {"Wait for the current operation to finish."}
                    store.requireReadyForReassignment(code)
                }
                try {
                    ready()
                    AlertDialog.Builder(this).setTitle("Remove pairing?")
                        .setMessage("Clear this app's pairing, menu and local history so it can be paired with another client. Uploaded back-office history stays intact. Move will close; reopen it to pair. POSitive is unchanged.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Unpair") {_,_->
                            try {ready();check((getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()) {"Could not remove pairing."}}
                            catch(e:Exception) {notice("Unpair terminal",e.message.orEmpty())}
                        }.show()
                } catch(e:Exception) {notice("Unpair terminal",e.message.orEmpty())}
            }.show()
    }
    private fun login() {
        val c = scrollColumn()

        if (store.pairing == null || store.blocked) {
            c.addView(text(if (store.blocked) "Reconnect this device" else "Pair your Handheld", 21f, true))
            c.addView(text("In Move back office, create a Handheld device and assign its store, location and published layout. Enter that device’s code below."))
            val field = EditText(this).apply {
                hint = "Pairing code"; setTextColor(textColor); setHintTextColor(muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                filters = arrayOf(android.text.InputFilter.LengthFilter(6)); isSingleLine = true
            }
            c.addView(field)
            c.addView(button("Pair device", true) {
                val code = field.text.toString().trim().uppercase(Locale.ROOT)
                network("Pairing device…") { store.pair(code); "Paired. Manually sync the assigned layout to continue." }
            })
        } else {
            c.setPadding(dp(18),dp(10),dp(18),dp(6))
            val brand = row()
            brand.addView(ImageView(this).apply {
                setImageResource(if(store.dark) R.drawable.move_wordmark_dark else R.drawable.move_wordmark); contentDescription = "Move Handheld · settings"
                setOnClickListener {page="loginSettings";render()}
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = border(surfaceColor); setPadding(dp(8),dp(4),dp(8),dp(4))
            }, LinearLayout.LayoutParams(dp(142),dp(52)))
            brand.addView(text("HANDHELD",10f,true).apply {
                letterSpacing = .14f; gravity = Gravity.END; setTextColor(muted)
            },LinearLayout.LayoutParams(0,dp(40),1f))
            brand.addView(button("◐") { store.dark = !store.dark; render() }.apply {
                contentDescription = if(store.dark) "Use light appearance" else "Use dark appearance"
                textSize = 24f
            },LinearLayout.LayoutParams(dp(44),dp(44)).apply {leftMargin=dp(8)})
            c.addView(brand)
            c.addView(text(catalogue?.merchant ?: "Your Handheld",22f,true).apply {
                setPadding(dp(2),dp(12),dp(2),dp(1)); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
            })
            c.addView(text(catalogue?.let { "${it.location} · Device ${it.terminal}" } ?: "Sync your menu to get started",12f).apply {setTextColor(muted)})
            c.addView(text("LOYALTY TEST · STAGING · ${BuildConfig.VERSION_NAME}",10f,true).apply {setTextColor(muted)})
            c.addView(View(this).apply {setBackgroundColor(lime)},LinearLayout.LayoutParams(dp(32),dp(3)).apply {topMargin=dp(12);bottomMargin=dp(12)})
            if (catalogue == null) {
                c.addView(button("Sync assigned layout", true) { sync() })
            } else {
                c.addView(text("Welcome back",24f,true))
                val instruction = text("Enter your staff PIN to sign in",12f).apply {setTextColor(muted)}
                c.addView(instruction)
                var enteredPin = ""
                val pin = text("",24f,true).apply {
                    gravity=Gravity.CENTER; background=border(); letterSpacing=.24f
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                fun updatePin() {
                    pin.text = if(enteredPin.isEmpty()) "○ ○ ○ ○" else "●".repeat(enteredPin.length)
                    pin.setTextColor(if(enteredPin.isEmpty()) muted else textColor)
                    pin.contentDescription="Staff PIN, ${enteredPin.length} digits entered"
                }
                updatePin()
                c.addView(pin,LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(8);bottomMargin=dp(8)})
                pin.post {(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(pin.windowToken,0)}
                lateinit var signIn: Button
                listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("Clear","0","⌫")).forEach { keys ->
                    val r=row()
                    keys.forEach { key ->
                        r.addView(button(key) {
                            enteredPin=when(key) {"Clear" -> "";"⌫" -> enteredPin.dropLast(1);else -> (enteredPin+key).take(12)}
                            updatePin(); signIn.isEnabled=enteredPin.isNotEmpty(); signIn.alpha=if(signIn.isEnabled) 1f else .5f
                            instruction.text="Enter your staff PIN to sign in";instruction.setTextColor(muted)
                        }.apply {
                            textSize=if(key=="Clear") 13f else 23f
                            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                            if(key=="⌫") contentDescription="Delete last PIN digit"
                        },LinearLayout.LayoutParams(0,dp(48),1f).apply {setMargins(dp(3),dp(3),dp(3),dp(3))})
                    }
                    c.addView(r)
                }
                signIn=button("Sign in  →", true) {
                    val match = catalogue?.staff?.singleOrNull { it.pin.isNotBlank() && it.pin == enteredPin }
                    if (match == null || !match.allowed("takePaymentEnabled")) {
                        instruction.text=if(match==null) "PIN not recognised. Try again." else "This staff member does not have till access."
                        instruction.setTextColor(if(store.dark) Color.rgb(255,155,145) else Color.rgb(170,35,30))
                        enteredPin="";updatePin();signIn.isEnabled=false;signIn.alpha=.5f
                    } else {
                        staff = match; resetMenu(); page = if(store.trading.pending()==null && store.trading.cashParts().isEmpty() && store.trading.openDay()==null) "day" else startPage(); status = ""; render(); uploadInBackground()
                    }
                }.apply {textSize=17f;setTypeface(null,Typeface.BOLD);isEnabled=false;alpha=.5f}
                content.addView(signIn,LinearLayout.LayoutParams(-1,dp(56)).apply {setMargins(dp(18),dp(8),dp(18),dp(10))})
            }
            c.addView(text(if(BuildConfig.DEBUG && store.config?.optBoolean("handheldFixture")==true) "SIMULATED DATA · BENCHMARK" else "${if(BuildConfig.IS_STAGING) "STAGING · " else ""}${BuildConfig.VERSION_NAME.removeSuffix("-staging")}",9f).apply {
                gravity=Gravity.CENTER;letterSpacing=.12f;setTextColor(muted);setPadding(0,dp(12),0,0)
            })
        }

    }
    private fun sync() {
        uploadInBackground() // Sales upload is independent of permission/config-download failure.
        if (draft.lines.isNotEmpty() || deal != null) { notice("Keep your draft safe", "Clear the local draft and finish or cancel the selection before changing the catalogue."); return }
        if (catalogue != null && staff?.allowed("syncNowEnabled") != true) { notice("Permission required", "Sign in with a staff PIN that permits Sync now."); return }
        network("Downloading the complete published layout…") {
            val firstSync = catalogue == null
            val c = store.sync(); if (firstSync && draft.lines.isEmpty()) { draft.changeContext(c.defaultServiceMode); store.saveDraft(draft) }; catalogue = c; resetMenu(); uploadInBackground()
            "Synced ${c.layoutName} · v${c.version} · ${c.screens.size} screens · ${c.products.size} products"
        }
    }
    private fun network(message: String, work: () -> String) {
        if (busy) return
        busy = true; status = message; render()
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    busy = false; status = result.getOrElse { it.message ?: "Connection failed. Try again manually." }
                    // A successful atomic download can outlive a failed acknowledgement.
                    catalogue = store.config?.let { runCatching { Catalogue.parse(it) }.getOrNull() }
                    // A saved catalogue applies staff revocations even when its acknowledgement failed.
                    staff = reauthorizeStaff(staff, catalogue?.staff.orEmpty())
                    if (store.blocked) staff = null
                    render()
                }
            }
        }
    }
    private fun savedOrderRecovery() {
        content.addView(button("Back to device settings") {page="settings";render()})
        if(staff?.allowed("terminalSettingsEnabled")!=true) {content.addView(text("Device settings permission required."));return}
        content.addView(text("Saved order recovery",22f,true))
        val c=scrollColumn()
        val pending=store.legacySharedSave
        if(pending!=null) {
            c.addView(text("Retained legacy shared save",18f,true))
            c.addView(text("Order ${pending.optString("orderId")}\nThis old request may already exist in back office. It is retained for review and will not be sent again. New local orders are separate."))
        }
        val saved=store.parkedSharedDraft
        if(saved==null) {c.addView(text(if(pending==null) "No parked drafts or pending shared saves." else "No separate parked draft."));return}
        val old=decodeDraft(saved)
        c.addView(text(old.orderReference ?: old.tableId ?: "Old shared-order draft",20f,true))
        c.addView(text("${old.lines.sumOf {it.quantity}} items · ${money(old.total)}",20f,true))
        c.addView(text("Local copy retained when this Handheld switched to standalone trading. It is separate from completed sales in Orders.",14f))
        old.lines.forEach {line->
            c.addView(text("${line.quantity} × ${line.productName}   ${money(line.totalPence.toLong())}",16f,true))
            line.modifiers.forEach {c.addView(text(it.selectedItemName,14f))}
        }
        if(pending==null) content.addView(button("Discard this local draft") {
            AlertDialog.Builder(this).setTitle("Discard parked local draft?")
                .setMessage("Remove only this ${money(old.total)} local copy and any unsaved edits it contains. This does not cancel a back-office order, change payments, delete sales or remove pairing.")
                .setNegativeButton("Keep draft",null).setPositiveButton("Discard local draft") {_,_->
                    try {store.discardParkedSharedDraft(requireNotNull(staff),saved);status="Parked local draft removed";page="settings";render()}
                    catch(e:Exception) {notice("Saved order",e.message.orEmpty())}
                }.show()
        }.apply {setTextColor(Color.rgb(184,40,40))})
    }
    private fun settings() {
        val c = scrollColumn(); val cat = requireNotNull(catalogue)
        c.addView(text("Your Handheld",24f,true))
        c.addView(text("${cat.terminal}\n${cat.merchant}\n${cat.location}"))
        c.addView(text("Pairing code  ${store.code}",21f,true))
        c.addView(text("${cat.layoutName}\nVersion ${cat.version} · ${cat.screens.size} screens · ${cat.products.size} products"))
        val whenSynced = SimpleDateFormat("dd MMM, HH:mm",Locale.UK).format(Date(store.syncedAt))
        c.addView(text("Saved on device: $whenSynced\nBack-office confirmation: ${if (store.acknowledged == cat.version) "confirmed" else "pending — retries automatically"}"))
        c.addView(button("Sync now",true) { sync() })
        if(packageName=="uk.edge.move.handheld.staging" && staff?.allowed("terminalSettingsEnabled")==true) {
            store.trading.pending()?.let {attempt ->
                c.addView(button("Archive staging test attempt") {
                    AlertDialog.Builder(this).setTitle("Archive ${money(attempt.getLong("amountMinor"))} staging attempt?")
                        .setMessage("Reference ${attempt.optString("reference")}. This releases the local test lock and keeps the basket. It does not cancel, approve or refund a processor payment. Use only for an operator-authorised staging reset.")
                        .setNegativeButton("Keep attempt",null).setPositiveButton("Archive test attempt") {_,_->
                            try {
                                store.archiveStagingAttempt(attempt.getString("id"),requireNotNull(staff))
                                draft=store.loadDraft();page="sell";status="Staging test attempt archived · basket retained";render()
                            } catch(e:Exception) {notice("Staging reset",e.message.orEmpty())}
                        }.show()
                })
            }
        }
        if(BuildConfig.IS_LOYALTY_TEST && LoyaltyBridge.available(this) && staff?.allowed("terminalSettingsEnabled")==true) {
            c.addView(text("Loyalty confirmations waiting: ${LoyaltyBridge.pendingCount(this)}",14f))
            c.addView(button("Loyalty programme & data") {LoyaltyBridge.manage(this)})
        }
        c.addView(text("Standalone trading",20f,true))
        c.addView(text("Cash and card sales are saved on this device and uploaded to Move back office. Card uses POSitive App2App. Tables are parked."))
        c.addView(text("Pending uploads: ${store.pendingReports}\nLast successful upload: ${if(store.lastUploadAt==0L) "None yet" else SimpleDateFormat("dd MMM, HH:mm",Locale.UK).format(Date(store.lastUploadAt))}",13f))
        if(store.retryAt>System.currentTimeMillis()) c.addView(text("Next retry: ${SimpleDateFormat("HH:mm:ss",Locale.UK).format(Date(store.retryAt))}",13f))
        if(store.uploadProblem.isNotBlank()) c.addView(text(store.uploadProblem,12f))
        c.addView(button("Retry uploads") {uploadInBackground()})
        c.addView(button("Orders") {historyToday=true;page="history";render()})
        c.addView(button("Start / end day") {page="day";render()})
        if(staff?.allowed("zReportEnabled")==true) store.trading.lastDayClose()?.let {report ->
            c.addView(button("Print last cash-up") {network("Printing cash-up…") {CashUpPrinter.print(this,dayReceipt(report));"Cash-up printed"}})
        }
        c.addView(text("App data: ${"%.1f".format(store.dataBytes/1024.0)} KB since this update\nMenu downloads are manual. Queued reports retry with outage backoff. Payload estimate excludes encryption, headers and POSitive data.",12f))
        c.addView(button("Last receipt") {page="receipt";render()})
        c.addView(text("Signed in: ${staff?.name}\n${BuildConfig.VERSION_NAME}",13f))
        if(staff?.allowed("terminalSettingsEnabled")==true) c.addView(button("Saved order recovery") {page="savedRecovery";render()})
        c.addView(button("Remove pairing") {unpairWithPasscode()})
        if(store.training) c.addView(button("Exit training mode") {switchTraining(false)})
        c.addView(button("Sign out") { staff = null; page = "login"; status = ""; deal = null; dealProducts.clear(); dealChoices.clear(); render() })
    }
    private fun chips(parent: LinearLayout, entries: List<Pair<String, () -> Unit>>) {
        if (entries.isEmpty()) return
        val strip = row(); entries.forEach { (name,click) -> strip.addView(button(name, action = click), LinearLayout.LayoutParams(-2,dp(36)).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }) }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
    }
    private fun sell() {
        val cat = requireNotNull(catalogue)
        val screen = cat.screens.find { it.id == screenId } ?: cat.screens.first { it.id == cat.home }
        val shortcuts = cat.shortcuts
        if (shortcuts.isNotEmpty()) {
            val strip = row()
            shortcuts.forEach { tile ->
                strip.addView(button(tile.label, tile.screen == screen.id || (tile.department.isNotBlank() && tile.department == departmentId)) { open(tile) }.apply {
                    textSize = 13f; setTypeface(null, Typeface.BOLD); setPadding(dp(4),dp(5),dp(4),dp(5))
                }, LinearLayout.LayoutParams(0,dp(44),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(3)) })
            }
            content.addView(strip)
        }
        if (departmentId != null && shortcuts.none { it.screen == screen.id }) content.addView(button("‹ Departments · ${cat.departments.find { it.id == departmentId }?.name.orEmpty()}") {
            departmentId = null; measure = null; extra = null; render()
        }.apply { minHeight = dp(30); minimumHeight = dp(30) })
        val products = if (departmentId != null) cat.departmentGrid(requireNotNull(departmentId)).filterNotNull() else screen.tiles.mapNotNull { t -> cat.products.find { it.remoteId == t.product } }.distinctBy { it.remoteId }
        val grid = ListView(this).apply { divider = null; dividerHeight = dp(2); setItemsCanFocus(true) }
        val entries: List<Pair<String,()->Unit>?> = if (departmentId != null) cat.departmentGrid(requireNotNull(departmentId)).map { p ->
            if (p == null) return@map null
            val displayed = p.displayPrice(measure,extra,draft.serviceMode)
            "${p.model.shortName?.ifBlank { p.model.name } ?: p.model.name}\n${if (p.blockedReason != null) "Unavailable" else if (p.openPrice) "Enter amount" else if (displayed == null) "Unavailable" else (if(p.builder != null) "From " else "") + money(displayed.toLong())}" to { select(p) }
        } else screen.displayTiles().map { t ->
            if (t == null) return@map null
            val p = cat.products.find { it.remoteId == t.product }
            (t.label.ifBlank { p?.model?.name ?: "Open" } + if (p != null) "\n${if (p.blockedReason != null) "Unavailable" else if (p.openPrice) "Enter amount" else p.displayPrice(measure,extra,draft.serviceMode)?.let { (if(p.builder != null) "From " else "") + money(it.toLong()) } ?: "Unavailable"}" else "  ›") to { open(t) }
        }
        val columns = 4
        val departmentGrid = departmentId == null && screen.contentMode != "PRODUCTS" && screen.tiles.any { it.department.isNotBlank() || it.screen.isNotBlank() }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = (entries.size + columns - 1) / columns
            override fun getItem(position: Int) = entries.drop(position * columns).take(columns)
            override fun getItemId(position: Int) = position.toLong() + 1
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val cells = convertView as? LinearLayout ?: row().apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    repeat(columns) { addView(button("") {}.apply { textSize = if (departmentGrid) 13f else 12f; setTypeface(null, Typeface.BOLD); setPadding(dp(3),dp(4),dp(3),dp(4)) }, LinearLayout.LayoutParams(0,dp(if (departmentGrid) 84 else 72),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) }) }
                }
                repeat(columns) { col ->
                    val entry = entries.getOrNull(position * columns + col)
                    (cells.getChildAt(col) as Button).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        visibility = if (entry == null) View.INVISIBLE else View.VISIBLE
                        text = entry?.first.orEmpty(); contentDescription = entry?.first
                        gravity = Gravity.CENTER
                        setOnClickListener { if (!busy) entry?.second?.invoke() }
                    }
                }
                return cells
            }
        }
        content.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        if (entries.isEmpty()) content.addView(text("No tiles on this published screen. Choose another top tab or update the layout in back office.",13f))
        val parent = draft.lines.firstOrNull { it.id == draft.selectedProductLineId }
        val source = parent?.let { line -> cat.products.firstOrNull { it.model.id == line.productId } }
        // Keep the department's configured options stable, even with an empty basket.
        val selectionProducts = departmentSelectionProducts(departmentId,cat.products)
        if (selectionProducts.isNotEmpty()) {
            val eligibleParent=parent!=null && source!=null && source.department==departmentId && deal==null
            content.addView(text(if(eligibleParent) "Add to ${parent!!.productName}" else "Select an item to add extras",13f,true))
            val additions = row()
            selectionProducts.forEach { product ->
                val enabled=eligibleParent && source!!.selectionProductIds.contains(product.remoteId) && product.blockedReason==null && product.model.isAvailableOnTill
                additions.addView(button(product.model.name) {
                    if(enabled) persistChange { draft.addSelection(product,requireNotNull(parent),requireNotNull(source)) }
                }.apply {
                    textSize=13f;setPadding(dp(2),0,dp(2),0);isEnabled=enabled;alpha=if(enabled) 1f else 0.45f
                },LinearLayout.LayoutParams(0,dp(48),1f))
            }
            content.addView(additions)
        }
        val bars = products.filter { it.blockedReason == null }.mapNotNull { it.bar }
        val buttons = bars.flatMap { it.buttons }.filter { it.showInBar }.distinctBy { it.id }
        if (buttons.isNotEmpty()) {
            val strip = row()
            buttons.forEach { b ->
                val selected = when(b.kind) { "MEASURE" -> b.id == measure; "EXTRA" -> b.id == extra; else -> b.id == deal?.id }
                strip.addView(button(b.label,selected) {
                    if (deal != null) { notice("Finish your deal", "Finish or cancel this selection first."); return@button }
                    when(b.kind) {
                        "MEASURE" -> measure = if (measure == b.id) null else b.id
                        "EXTRA" -> extra = if (extra == b.id) null else b.id
                        "DEAL" -> {
                            if (!canStartSelectionGroup(b,cat.products.filter { it.blockedReason == null && it.model.isAvailableOnTill }.map { it.model.id })) {
                                notice("Deal unavailable","There are not enough available products for this deal."); return@button
                            }
                            measure = null; extra = null; deal = b; dealProducts.clear(); dealChoices.clear()
                        }
                    }; render()
                }.apply { textSize = 15f; setPadding(dp(12),dp(6),dp(12),dp(6)) },LinearLayout.LayoutParams(-2,dp(46)).apply { setMargins(dp(2),0,dp(2),0) })
            }
            content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        deal?.let { b ->
            content.addView(text("${b.label} · ${dealProducts.size} of ${b.selectionCount} selected",14f,true))
            content.addView(button("Cancel selection") { deal = null; dealProducts.clear(); dealChoices.clear(); render() })
        }
        footer()
    }
    private fun changeScreen(id: String) {
        screenId = id
        departmentId = null
        measure = null; extra = null; render()
    }
    private fun open(tile: Tile) {
        when {
            tile.kind == "product" -> catalogue?.products?.find { it.remoteId == tile.product }?.let { select(it) }
                ?: notice("Product unavailable","This tile has no matching product in the published catalogue.")
            tile.screen.isNotBlank() -> changeScreen(tile.screen)
            tile.department.isNotBlank() -> { departmentId = tile.department; measure = null; extra = null; render() }
            else -> notice("Tile unavailable","This tile has no supported destination.")
        }
    }
    private fun showMoveKeyboard(field: EditText, numeric: Boolean, title: String = if (numeric) "Amount (£)" else "Product name") {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken,0)
        val keyboard = android.app.Dialog(this)
        keyboard.requestWindowFeature(Window.FEATURE_NO_TITLE)
        var value = field.text.toString()
        var uppercase = false
        var symbols = false
        val panel = column().apply { background = border(); setPadding(dp(8),dp(8),dp(8),dp(8)) }
        panel.addView(text(title,18f,true))
        val preview = text(value.ifBlank { " " },18f,true)
        panel.addView(preview)
        val keys = column(); panel.addView(keys)
        fun refreshKeys() {
            keys.removeAllViews()
            val rows = if (numeric) listOf("123","456","789",".0⌫") else if (symbols) listOf("1234567890","-/'&.,!?()") else listOf("qwertyuiop","asdfghjkl","zxcvbnm")
            rows.forEach { letters ->
                val line = row()
                letters.forEach { raw ->
                    val char = if (uppercase) raw.uppercaseChar() else raw
                    line.addView(button(char.toString()) {
                        value = when {
                            char == '⌫' -> value.dropLast(1)
                            numeric && char == '.' && '.' in value -> value
                            value.length >= (if (numeric) 10 else 60) -> value
                            else -> value + char
                        }; preview.text = value.ifBlank { " " }
                    }.apply { textSize = if (numeric) 20f else 15f; setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,dp(if (numeric) 48 else 40),1f).apply { setMargins(dp(1),dp(2),dp(1),dp(2)) })
                }
                keys.addView(line)
            }
            if (!numeric) {
                val controls = row()
                listOf("⇧" to { uppercase = !uppercase; refreshKeys() }, "123 / ABC" to { symbols = !symbols; refreshKeys() }, "Space" to { if (value.length < 60) value += " "; preview.text = value }, "⌫" to { value = value.dropLast(1); preview.text = value.ifBlank { " " } }).forEach { (label, action) ->
                    controls.addView(button(label,action=action).apply { textSize = 12f; setPadding(0,0,0,0) },LinearLayout.LayoutParams(0,dp(40),1f).apply { setMargins(dp(1),dp(2),dp(1),dp(2)) })
                }
                keys.addView(controls)
            }
        }
        refreshKeys()
        val controls = row()
        listOf("Cancel" to { keyboard.dismiss() }, "Clear" to { value = ""; preview.text = " " }, "Done" to { field.setText(value); field.setSelection(field.length()); keyboard.dismiss() }).forEach { (label, action) ->
            controls.addView(button(label,label == "Done",action),LinearLayout.LayoutParams(0,dp(44),1f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        }
        panel.addView(controls)
        keyboard.setContentView(panel)
        keyboard.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        keyboard.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        keyboard.show()
        keyboard.window?.setGravity(Gravity.BOTTOM)
        keyboard.window?.setLayout(resources.displayMetrics.widthPixels - dp(12),WindowManager.LayoutParams.WRAP_CONTENT)
    }
    private fun editProductName(target:EditText) {
        val panel=column().apply {setPadding(dp(16),dp(16),dp(16),dp(12));background=border()}
        val editor=EditText(this).apply {
            setText(target.text);setSelection(length());hint="Product name"
            setTextColor(textColor);setHintTextColor(muted);textSize=20f;isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters=arrayOf(android.text.InputFilter.LengthFilter(60))
        }
        val dialog=android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        panel.addView(text("Product name",20f,true))
        panel.addView(editor,LinearLayout.LayoutParams(-1,dp(56)))
        fun close(save:Boolean) {
            if(save) target.setText(editor.text.toString())
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken,0)
            dialog.dismiss()
        }
        val actions=row()
        actions.addView(button("Cancel") {close(false)},LinearLayout.LayoutParams(0,dp(52),1f))
        actions.addView(button("Done",true) {close(true)},LinearLayout.LayoutParams(0,dp(52),1f).apply {leftMargin=dp(6)})
        panel.addView(actions)
        editor.setOnEditorActionListener {_,action,_ ->
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {close(true);true} else false
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(16),WindowManager.LayoutParams.WRAP_CONTENT)
        editor.requestFocus()
        editor.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) }
    }
    private fun configureProduct(p: MenuProduct) {
        val choices = p.groups.associate { it.id to mutableListOf<String>() }
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = column().apply { background = border(); setPadding(dp(12),dp(10),dp(12),dp(10)) }
        panel.addView(text(p.model.name,20f,true))
        val body = column()
        panel.addView(ScrollView(this).apply { addView(body); isFillViewport = false }, LinearLayout.LayoutParams(-1,0,1f))
        val openName = if (p.openPrice) EditText(this).apply {
            hint = "Product name · tap to enter"; textSize = 16f; setTextColor(textColor); setHintTextColor(muted); isSingleLine = true
            showSoftInputOnFocus = false; isFocusable = false; isFocusableInTouchMode = false; isCursorVisible = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            setOnClickListener { editProductName(this) }
        }.also { body.addView(it) } else null
        val amount = if (p.openPrice) EditText(this).apply {
            hint = "0.00"; textSize = 32f; gravity = Gravity.END; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(textColor); setHintTextColor(muted); isSingleLine = true
            showSoftInputOnFocus = false; isFocusable = false; isFocusableInTouchMode = false; isCursorVisible = false
        }.also { field ->
            body.addView(text("Amount (£)",13f,true).apply {setTextColor(muted)})
            body.addView(field,LinearLayout.LayoutParams(-1,dp(56)))
            listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf(".","0","⌫")).forEach { keys ->
                val keyRow=row()
                keys.forEach { key ->
                    keyRow.addView(button(key) {
                        val current=field.text.toString()
                        val next=when {
                            key=="⌫" -> current.dropLast(1)
                            key=="." && '.' in current -> current
                            key=="." && current.isEmpty() -> "0."
                            current.length>=10 -> current
                            '.' in current && current.substringAfter('.').length>=2 -> current
                            else -> current+key
                        }
                        field.setText(next)
                    }.apply {textSize=24f;setPadding(0,0,0,0)},LinearLayout.LayoutParams(0,dp(50),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))})
                }
                body.addView(keyRow)
            }
        } else null
        val error = text("",12f).apply { visibility = View.GONE }
        val total = text("",18f,true)
        val refreshOptions = mutableListOf<() -> Unit>()
        var addAction:Button?=null
        fun refresh() {
            error.visibility = View.GONE
            refreshOptions.forEach { it() }
            val base = if (p.openPrice) runCatching { parseOpenAmount(amount?.text.toString()).let { value ->
                if (draft.serviceMode.usesTakeawayPricing()) value - calculateTakeawayDiscountPence(value,p.takeawayBasisPoints) else value
            } }.getOrNull() else p.builder?.let { productBuilderBasePricePence(it.tiers,choices[it.groupId].orEmpty().size) } ?: p.displayPrice(measure,extra,draft.serviceMode)
            val additions = p.groups.sumOf { g -> choices.getValue(g.id).mapIndexed { index,id ->
                modifierPriceDeltaPence(index,g.included,g.options.single { it.id == id }.price).toLong()
            }.sum() }
            if(p.openPrice) addAction?.let { action ->
                action.isEnabled=runCatching {
                    parseOpenAmount(amount?.text.toString())
                    validateOpenName(openName?.text.toString())
                    configuredModifiers(p,choices.mapValues {it.value.toList()})
                }.isSuccess
                action.alpha=if(action.isEnabled) 1f else .45f
            }
            total.text = base?.let { "Total ${money(it + additions)}" } ?: "Enter an amount"
        }
        amount?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        openName?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {refresh()}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        p.groups.forEach { group ->
            val picked = choices.getValue(group.id)
            val groupHeading = text(group.name,15f,true)
            body.addView(groupHeading)
            body.addView(text("${if (group.min > 0) "Choose at least ${group.min}" else "Optional"}${if (group.max != Int.MAX_VALUE) " · up to ${group.max}" else ""}${if (group.included > 0) " · ${group.included} included" else ""}",11f).apply { setTextColor(muted) })
            refreshOptions += { groupHeading.text = "${group.name}${if (picked.isNotEmpty()) " · ${picked.size} selected" else ""}" }
            group.options.chunked(2).forEach { options ->
                val optionRow = row()
                options.forEach { option ->
                    val cell = column()
                    val tile = button("") {
                        if (!group.repeats && option.id in picked) picked.remove(option.id)
                        else if (modifierSelectionCanGrow(picked.size,group.max)) picked.add(option.id)
                        else {
                            error.text = "${group.name}: choose no more than ${group.max}. Deselect an option first."
                            error.visibility = View.VISIBLE
                            return@button
                        }
                        refresh()
                    }.apply { textSize = 12f }
                    cell.addView(tile,LinearLayout.LayoutParams(-1,dp(62)).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
                    val quantity = if (group.repeats) row() else null
                    val quantityLabel = if (group.repeats) text("",13f,true).apply { gravity = Gravity.CENTER } else null
                    val decrease = if (group.repeats) button("−") {
                        val index = picked.lastIndexOf(option.id)
                        if (index >= 0) picked.removeAt(index)
                        refresh()
                    }.apply { contentDescription = "Remove one ${option.name}" } else null
                    val increase = if (group.repeats) button("+") { tile.performClick() }.apply {
                        contentDescription = "Add one ${option.name}"
                    } else null
                    if (quantity != null) {
                        quantity.addView(decrease,LinearLayout.LayoutParams(dp(40),dp(36)))
                        quantity.addView(quantityLabel,LinearLayout.LayoutParams(0,dp(36),1f))
                        quantity.addView(increase,LinearLayout.LayoutParams(dp(40),dp(36)))
                        cell.addView(quantity,LinearLayout.LayoutParams(-1,dp(36)).apply { setMargins(dp(2),0,dp(2),dp(2)) })
                    }
                    refreshOptions += {
                        val count = picked.count { it == option.id }
                        tile.text = "${option.name}\n${if (option.price == 0) "No extra charge" else "+${money(option.price.toLong())}"}${if (count > 0) "  ✓${if (count > 1) " ×$count" else ""}" else ""}"
                        tile.background = border(if (count > 0) lime else surfaceColor)
                        tile.setTextColor(if (count > 0) ink else textColor)
                        quantity?.visibility = if (count > 0) View.VISIBLE else View.GONE
                        quantityLabel?.text = count.toString()
                        increase?.isEnabled = modifierSelectionCanGrow(picked.size,group.max)
                    }
                    optionRow.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
                }
                if (options.size == 1) optionRow.addView(Space(this),LinearLayout.LayoutParams(0,1,1f))
                body.addView(optionRow)
            }
        }
        panel.addView(error)
        panel.addView(total)
        val actions = row()
        actions.addView(button("Cancel") { dialog.dismiss() },LinearLayout.LayoutParams(0,dp(52),1f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        actions.addView(button("Add to basket",true) {
            try {
                val entered = if (p.openPrice) parseOpenAmount(amount?.text.toString()) else null
                val selected = choices.mapValues { it.value.toList() }
                configuredModifiers(p,selected)
                select(p,entered,selected,true,if (p.openPrice) validateOpenName(openName?.text.toString()) else null)
                dialog.dismiss()
            } catch (e: Exception) {
                error.text = e.message ?: "Choose the required options."; error.visibility = View.VISIBLE
            }
        }.also {addAction=it},LinearLayout.LayoutParams(0,dp(52),2f).apply { setMargins(dp(2),dp(4),dp(2),0) })
        panel.addView(actions)
        refresh()
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - dp(16), (resources.displayMetrics.heightPixels * .86).toInt())
    }
    private fun select(p: MenuProduct, openAmount: Int? = null, choices: Map<String,List<String>> = emptyMap(), configured: Boolean = false, openName: String? = null) {
        if (!basketEditable()) return
        val startedAt = android.os.SystemClock.elapsedRealtimeNanos()
        val previous = draft.encode()
        try {
            require(p.model.isAvailableOnTill && p.blockedReason == null) { p.blockedReason ?: "Product unavailable" }
            if (!configured && (p.openPrice || p.groups.isNotEmpty())) {
                require(deal == null || (!p.openPrice && p.builder == null)) { "Finish or cancel the deal before choosing an open or tiered product" }
                deal?.let { validateSelectionGroup(it,(dealProducts+p).map { item -> item.model.id },false) }
                configureProduct(p); return
            }
            val b = deal
            if (b != null) {
                validateSelectionGroup(b,(dealProducts + p).map { it.model.id },false)
                configuredModifiers(p,choices)
                dealProducts += p; dealChoices += choices
                if (dealProducts.size == b.selectionCount) { draft.addDeal(b,dealProducts,dealChoices); status=""; store.saveDraft(draft); deal = null; dealProducts.clear(); dealChoices.clear() }
                render()
            } else {
                draft.add(p,measure,extra,openAmount,choices,openName); status=""; store.saveDraft(draft)
                if (measure != null || extra != null) { measure = null; extra = null; render() }
                else if (::basketButton.isInitialized && page == "sell") basketButton.text = footerLabel()
                else render()
            }
            if (BuildConfig.DEBUG) root.postOnAnimation {
                android.util.Log.i("HandheldPerf", "tap_to_next_frame_ms=${(android.os.SystemClock.elapsedRealtimeNanos()-startedAt)/1000000.0} lines=${draft.lines.size}")
            }
        } catch (e: Exception) {
            draft = decodeDraft(previous)
            if (deal != null && dealProducts.size >= requireNotNull(deal).selectionCount) { dealProducts.clear(); dealChoices.clear() }
            notice("Cannot add this item",e.message ?: "Check the published configuration.")
        }
    }
    private fun basket() {
        content.addView(button("Back to payment") {page="checkout";render()}.apply {textSize=16f})
        content.addView(basketSummary(false),LinearLayout.LayoutParams(-1,dp(76)).apply {setMargins(dp(3),dp(6),dp(3),dp(6))})
        val list=scrollColumn()
        if(draft.lines.isEmpty()) list.addView(text("Your basket is empty."))
        draft.mealApplications.groupBy { it.name }.forEach { (name, applications) ->
            list.addView(text("$name · You save ${money(applications.sumOf { it.discount }.toLong())}",14f,true))
        }
        val editable=store.trading.pending()==null && store.trading.cashParts().isEmpty()
        draft.paidLines.forEach {l ->
            val box=column().apply {background=border();setPadding(dp(8),dp(7),dp(8),dp(7))}
            val heading=row()
            heading.addView(text(l.productName,16f,true),LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(text(money(l.totalPence.toLong()),16f,true))
            box.addView(heading)
            if(l.modifierSummary.isNotBlank()) box.addView(text(l.modifierSummary,12f))
            box.addView(text("Qty ${l.quantity} · ${money((l.unitPricePence+l.modifierTotalPence).toLong())} each · ${if(l.vatRatePercent==0) "No VAT" else "${l.vatRatePercent}% VAT"}",11f).apply {setTextColor(muted)})
            val controls=row()
            val additionSource = catalogue?.products?.firstOrNull { it.model.id == l.productId && it.selectionProductIds.isNotEmpty() }
            if (additionSource != null && l.id !in draft.productParents && l.quantity == 1 && selectionGroupToken(l) == null) {
                controls.addView(button("Add products") { persistChange(afterSave = { page="sell" }) { draft.selectedProductLineId=l.id } }.apply {isEnabled=editable},LinearLayout.LayoutParams(-2,dp(40)))
            }
            if(selectionGroupToken(l)==null) {
                controls.addView(button("−") {persistChange {if(l.quantity==1) draft.remove(l) else draft.setQuantity(l,l.quantity-1)}}.apply {isEnabled=editable;contentDescription="Decrease ${l.productName}"},LinearLayout.LayoutParams(dp(40),dp(40)))
                controls.addView(text(l.quantity.toString(),15f,true).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(32),dp(40)))
                controls.addView(button("+") {persistChange {draft.setQuantity(l,l.quantity+1)}}.apply {isEnabled=editable;contentDescription="Increase ${l.productName}"},LinearLayout.LayoutParams(dp(40),dp(40)))
            }
            controls.addView(android.view.View(this),LinearLayout.LayoutParams(0,1,1f))
            controls.addView(button(if(selectionGroupToken(l)!=null) "Remove deal" else "Remove") {persistChange {draft.remove(l)}}.apply {isEnabled=editable;setTextColor(if(store.dark) Color.rgb(255,140,140) else Color.rgb(178,30,30));contentDescription="Remove ${l.productName}"},LinearLayout.LayoutParams(dp(100),dp(40)))
            box.addView(controls)
            list.addView(box,LinearLayout.LayoutParams(-1,-2).apply {setMargins(dp(3),dp(4),dp(3),dp(4))})
        }
        content.addView(button("Back to payment",true) {page="checkout";render()}.apply {textSize=18f;setTypeface(null,Typeface.BOLD)},LinearLayout.LayoutParams(-1,dp(58)))
    }
    private fun money(pence: Long) = formatMinor(pence)
    private fun notice(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show() }
    @Deprecated("Native back navigation")
    override fun onBackPressed() {
        if (staff == null) super.onBackPressed()
        else if (page == "dayFinish") {staff=null;page="login";status="Day closed · totals saved";render()}
        else if (page == "orderDetail") {page="history";render()}
        else if (page == "basket" || page == "cash") { page = "checkout"; render() }
        else if (page != "sell") { page = "sell"; render() }
        else if (departmentId != null) { departmentId = null; render() }
        else { page = startPage(); render() }
    }
}
