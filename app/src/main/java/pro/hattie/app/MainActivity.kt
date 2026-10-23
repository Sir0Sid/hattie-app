package pro.hattie.app

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The whole app on one screen.
 *
 * TWO STATES AND NOTHING ELSE:
 *
 *   1. this tablet does not know its house yet — a small form: the house's
 *      address, and optionally the six digits the box is showing. Pairing
 *      is optional on purpose (see Hattie-Room-Voice-Plan.md, "Why pairing
 *      at all"): with a code the tablet keeps a device token; without one
 *      it simply shows her page and somebody signs in once;
 *   2. it knows its house — her page, full screen, no browser furniture,
 *      with the microphone already held by the ear service.
 *
 * There is no menu, no settings screen and no way out to Android: a tablet
 * in a kitchen should have nothing on it but her.
 */
class MainActivity : Activity() {

    private lateinit var house: House
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        house = House(this)
        keepAwake()
        /* The manifest asks for these too; asking here as well is what makes
         * an older Android behave the same, and means the page is her again
         * after a sleep with nobody unlocking anything. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        if (house.configured) showHouse() else showSetup()
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** A kitchen tablet is plugged in; the screen dimming is fine, sleeping is not. */
    private fun keepAwake() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        if (house.configured) goFullScreen()   /* a swipe can bring the bars back */
        if (house.configured) {
            EarService.start(this)
            rememberThisDevice()      /* until somebody has signed in on it */
        }
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    /**
     * The bars come back every time you leave her and return - Android is
     * being helpful, and it is wrong for a tablet on a wall. Re-hiding on
     * RESUME is not enough: the window is not attached yet when that runs,
     * so it is done again here, when the window really has the focus.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && house.configured) goFullScreen()
    }

    override fun onBackPressed() {
        /* Nothing behind this. A back button that leaves her page is a
         * support call waiting to happen. */
    }

    // ── state 1: the setup form ─────────────────────────────────────
    private fun showSetup() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        fun title(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 20f
        }
        fun hint(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.75f
            setPadding(0, pad / 3, 0, pad / 2)
        }

        column.addView(title(getString(R.string.setup_title)))
        column.addView(hint("Hattie " + appVersion()))
        column.addView(hint(getString(R.string.setup_what)))

        val where = EditText(this).apply {
            hint = "192.168.1.8 or hattie.local"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        column.addView(where)
        column.addView(hint(getString(R.string.setup_address_help)))

        val code = EditText(this).apply {
            hint = getString(R.string.setup_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        column.addView(code)
        column.addView(hint(getString(R.string.setup_code_help)))

        val say = TextView(this).apply {
            setPadding(0, pad / 2, 0, pad / 2)
            textSize = 14f
        }
        column.addView(say)

        fun button(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setOnClickListener { onClick() }
            }

        column.addView(button(getString(R.string.setup_pair)) {
            val address = House.normalise(where.text.toString())
            if (address.isEmpty()) {
                say.text = getString(R.string.setup_need_address)
                return@button
            }
            house.base = address
            val digits = code.text.toString().trim()
            if (digits.isEmpty()) {
                /* No code: the bench route. Show her page; somebody signs in
                 * once and the session is kept by Android. */
                house.token = ""
                showHouse()
                return@button
            }
            say.text = getString(R.string.setup_pairing)
            Thread {
                val problem = Box.pair(house, digits)
                runOnUiThread {
                    if (problem.isEmpty()) showHouse() else say.text = problem
                }
            }.start()
        })

        column.addView(button(getString(R.string.setup_skip)) {
            val address = House.normalise(where.text.toString())
            if (address.isEmpty()) {
                say.text = getString(R.string.setup_need_address)
                return@button
            }
            house.base = address
            house.token = ""
            showHouse()
        })

        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        askForMicrophone()
    }

    // ── state 2: her, on the wall ───────────────────────────────────
    private fun showHouse() {
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            /* Her voice is audio played by the page, and it must not wait
             * for a tap: nobody taps a tablet on a shelf. */
            settings.mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            settings.allowFileAccess = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                /* The page may ask for the microphone. It is granted here
                 * because the app already holds it — the permission the
                 * person granted is the one that counts. */
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }
            loadUrl(house.page())
        }
        web = view
        setContentView(view)
        goFullScreen()
        pinToThisApp()
        askForMicrophone()
        rememberThisDevice()
    }

    /**
     * Ask the box to remember this tablet, quietly, in the background.
     *
     * The family signs in on her page with their name, password and PIN —
     * that is the credential, and the box needs no second one. Until somebody
     * has signed in there is nothing to remember, so a failure here is silent
     * and it simply tries again next time the app wakes up.
     */
    private fun rememberThisDevice() {
        if (house.paired) return
        Thread {
            val problem = Box.enrol(house)
            if (problem.isEmpty()) runOnUiThread {
                android.widget.Toast.makeText(
                    this, getString(R.string.remembered), android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    /**
     * Her, and nothing else on the screen.
     *
     * TWO GENERATIONS OF API, because the old flags are deprecated from
     * Android 11 and are only half-honoured: the status bar went, the
     * navigation bar came back on a swipe - which is Android being sensible,
     * not a fault. From 11 the supported way is the window insets controller.
     * Either way the bars are TRANSIENT: swiping brings them back for a
     * moment, which is the escape a tablet needs while it is being set up.
     * Locking somebody IN is pinToThisApp() below.
     */
    private fun goFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { bars ->
                bars.hide(WindowInsets.Type.systemBars())
                bars.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            return
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * PIN HER TO THE SCREEN, so nobody wanders off into Android by accident.
     *
     * This is Android's own "lock task mode", and it needs ONE of two things
     * to be true: screen pinning switched on in Settings (once per tablet),
     * or this app being the device owner (what a shipped appliance should be).
     * Where neither is true yet, this does nothing at all rather than asking
     * again at every start - and the guide says how to switch it on.
     *
     * THE WAY OUT is Android's, not ours: hold Back and Recents together.
     * A kiosk that cannot be left is a kiosk you cannot fix.
     */
    private fun pinToThisApp() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val already = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (!already && am.isLockTaskPermitted(packageName)) {
            runCatching { startLockTask() }
        }
    }

    /**
     * Asked once, by the app, so the ear service can hold the microphone
     * without a surprise later. Android shows the dialog; the answer is the
     * person's, and a refusal is reported rather than hidden.
     */
    private fun askForMicrophone() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            EarService.start(this)
        }
    }
}
