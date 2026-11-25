// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import dev.qingzhu.bthome.R
import dev.qingzhu.bthome.ui.advertiser.AdvertiserScreen
import dev.qingzhu.bthome.ui.scanner.ScannerScreen

/**
 * 一个 APK 两个角色，靠顶部两个页签切换。
 *
 * 页签切换只替换 Fragment，不销毁整个界面；从端的状态放在 ViewModel 里，
 * 所以切到“主端”页看扫描结果时，广播还在继续。
 */
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    private var showingAdvertiser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<android.view.View>(R.id.tab_scanner).setOnClickListener { show(false) }
        findViewById<android.view.View>(R.id.tab_advertiser).setOnClickListener { show(true) }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ScannerScreen())
                .commitNow()
        }
        showingAdvertiser = savedInstanceState?.getBoolean(KEY_TAB) ?: false
        renderTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TAB, showingAdvertiser)
    }

    private fun show(advertiser: Boolean) {
        if (advertiser == showingAdvertiser) return
        showingAdvertiser = advertiser
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, if (advertiser) AdvertiserScreen() else ScannerScreen())
            .commit()
        renderTabs()
    }

    private fun renderTabs() {
        val scannerTab = findViewById<android.view.View>(R.id.tab_scanner)
        val advertiserTab = findViewById<android.view.View>(R.id.tab_advertiser)
        val on = getColor(R.color.accent)
        val off = getColor(R.color.bar_sub)
        scannerTab.setBackgroundResource(if (showingAdvertiser) 0 else R.drawable.bg_tab_on)
        advertiserTab.setBackgroundResource(if (showingAdvertiser) R.drawable.bg_tab_on else 0)
        (scannerTab as android.widget.TextView).setTextColor(if (showingAdvertiser) off else on)
        (advertiserTab as android.widget.TextView).setTextColor(if (showingAdvertiser) on else off)
    }

    private companion object {
        const val KEY_TAB = "showing_advertiser"
    }
}
