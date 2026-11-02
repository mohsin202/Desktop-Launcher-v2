package com.android.desktoplauncher

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/** A country with its news languages (first one is the default). */
class NewsCountry(val code: String, val en: String, val ur: String, val langs: List<String>)

object NewsData {
    private fun c(code: String, en: String, ur: String, langs: String) = NewsCountry(code, en, ur, langs.split(","))

    val countries: List<NewsCountry> = listOf(
        c("PK", "Pakistan", "پاکستان", "ur,en"), c("IN", "India", "بھارت", "hi,en,ta,te,bn,ml,mr"),
        c("US", "United States", "امریکہ", "en,es"), c("GB", "United Kingdom", "برطانیہ", "en"),
        c("SA", "Saudi Arabia", "سعودی عرب", "ar,en"), c("AE", "United Arab Emirates", "متحدہ عرب امارات", "ar,en"),
        c("QA", "Qatar", "قطر", "ar"), c("KW", "Kuwait", "کویت", "ar"), c("OM", "Oman", "عمان", "ar"),
        c("BH", "Bahrain", "بحرین", "ar"), c("EG", "Egypt", "مصر", "ar"), c("JO", "Jordan", "اردن", "ar"),
        c("LB", "Lebanon", "لبنان", "ar"), c("IQ", "Iraq", "عراق", "ar"), c("MA", "Morocco", "مراکش", "ar,fr"),
        c("DZ", "Algeria", "الجزائر", "ar,fr"), c("TN", "Tunisia", "تیونس", "ar,fr"), c("LY", "Libya", "لیبیا", "ar"),
        c("TR", "Turkey", "ترکی", "tr"), c("IR", "Iran", "ایران", "fa"), c("AF", "Afghanistan", "افغانستان", "fa,en"),
        c("BD", "Bangladesh", "بنگلہ دیش", "bn,en"), c("LK", "Sri Lanka", "سری لنکا", "en,ta"),
        c("NP", "Nepal", "نیپال", "en"), c("MY", "Malaysia", "ملائیشیا", "ms,en"), c("ID", "Indonesia", "انڈونیشیا", "id"),
        c("SG", "Singapore", "سنگاپور", "en"), c("TH", "Thailand", "تھائی لینڈ", "th"), c("VN", "Vietnam", "ویتنام", "vi"),
        c("PH", "Philippines", "فلپائن", "en"), c("CN", "China", "چین", "zh"), c("TW", "Taiwan", "تائیوان", "zh"),
        c("HK", "Hong Kong", "ہانگ کانگ", "zh,en"), c("JP", "Japan", "جاپان", "ja"), c("KR", "South Korea", "جنوبی کوریا", "ko"),
        c("AU", "Australia", "آسٹریلیا", "en"), c("NZ", "New Zealand", "نیوزی لینڈ", "en"),
        c("CA", "Canada", "کینیڈا", "en,fr"), c("MX", "Mexico", "میکسیکو", "es"), c("BR", "Brazil", "برازیل", "pt"),
        c("AR", "Argentina", "ارجنٹائن", "es"), c("CL", "Chile", "چلی", "es"), c("CO", "Colombia", "کولمبیا", "es"),
        c("PE", "Peru", "پیرو", "es"), c("VE", "Venezuela", "وینزویلا", "es"), c("CU", "Cuba", "کیوبا", "es"),
        c("DE", "Germany", "جرمنی", "de"), c("FR", "France", "فرانس", "fr"), c("IT", "Italy", "اٹلی", "it"),
        c("ES", "Spain", "اسپین", "es"), c("PT", "Portugal", "پرتگال", "pt"), c("NL", "Netherlands", "نیدرلینڈز", "nl"),
        c("BE", "Belgium", "بیلجیم", "fr,nl"), c("CH", "Switzerland", "سوئٹزرلینڈ", "de,fr,it"), c("AT", "Austria", "آسٹریا", "de"),
        c("SE", "Sweden", "سویڈن", "sv"), c("NO", "Norway", "ناروے", "no"), c("DK", "Denmark", "ڈنمارک", "da"),
        c("FI", "Finland", "فن لینڈ", "fi"), c("IE", "Ireland", "آئرلینڈ", "en"), c("PL", "Poland", "پولینڈ", "pl"),
        c("CZ", "Czechia", "چیکیا", "cs"), c("HU", "Hungary", "ہنگری", "hu"), c("RO", "Romania", "رومانیہ", "ro"),
        c("GR", "Greece", "یونان", "el"), c("UA", "Ukraine", "یوکرین", "uk,ru"), c("RU", "Russia", "روس", "ru"),
        c("IL", "Israel", "اسرائیل", "he,en"), c("ZA", "South Africa", "جنوبی افریقہ", "en"), c("NG", "Nigeria", "نائیجیریا", "en"),
        c("KE", "Kenya", "کینیا", "en"), c("ET", "Ethiopia", "ایتھوپیا", "en"), c("GH", "Ghana", "گھانا", "en"),
        c("TZ", "Tanzania", "تنزانیہ", "en"), c("UG", "Uganda", "یوگنڈا", "en"), c("SN", "Senegal", "سینیگال", "fr")
    )

    private val langNames = mapOf(
        "ur" to "اردو", "en" to "English", "hi" to "हिन्दी", "ta" to "தமிழ்", "te" to "తెలుగు", "bn" to "বাংলা", "ml" to "മലയാളം",
        "mr" to "मराठी", "ar" to "العربية", "fr" to "Français", "es" to "Español", "tr" to "Türkçe", "fa" to "فارسی",
        "ms" to "Melayu", "id" to "Indonesia", "th" to "ไทย", "vi" to "Tiếng Việt", "zh" to "中文", "ja" to "日本語",
        "ko" to "한국어", "pt" to "Português", "de" to "Deutsch", "it" to "Italiano", "nl" to "Nederlands", "sv" to "Svenska",
        "no" to "Norsk", "da" to "Dansk", "fi" to "Suomi", "pl" to "Polski", "cs" to "Čeština", "hu" to "Magyar",
        "ro" to "Română", "el" to "Ελληνικά", "uk" to "Українська", "ru" to "Русский", "he" to "עברית"
    )
    fun langName(l: String) = langNames[l] ?: l

    fun byCode(code: String) = countries.firstOrNull { it.code == code }

    /** Topics: label key, Google News section (or null = front page) and search text (for the search based ones). */
    class Topic(val label: String, val section: String?, val query: String? = null)
    val topics = listOf(
        Topic("Top stories", null), Topic("World", "WORLD"), Topic("Business", "BUSINESS"), Topic("Sports", "SPORTS"),
        Topic("Technology", "TECHNOLOGY"), Topic("Entertainment", "ENTERTAINMENT"), Topic("Science", "SCIENCE"),
        Topic("Health", "HEALTH"), Topic("Gold rate", null, "gold"), Topic("Currency rates", null, "currency")
    )

    private fun queryText(t: Topic, lang: String): String = when (t.query) {
        "gold" -> when (lang) { "ur" -> "سونے کا ریٹ"; "hi" -> "सोने का भाव"; "ar" -> "سعر الذهب"; "fa" -> "قیمت طلا"; "tr" -> "altın fiyatı"
            "es" -> "precio del oro"; "fr" -> "prix de l'or"; "de" -> "Goldpreis"; "id" -> "harga emas"; "bn" -> "সোনার দাম"; else -> "gold price" }
        else -> when (lang) { "ur" -> "کرنسی ریٹ ڈالر"; "hi" -> "डॉलर रुपया"; "ar" -> "سعر الصرف الدولار"; else -> "currency exchange rate dollar" }
    }

    fun url(cc: NewsCountry, lang: String, t: Topic): String {
        val code = cc.code
        val hl = when (lang) {
            "en" -> "en-$code"
            "zh" -> if (code == "CN") "zh-CN" else "zh-TW"
            "pt" -> if (code == "PT") "pt-PT" else "pt-BR"
            else -> lang
        }
        val ceLang = when (lang) {
            "zh" -> if (code == "CN") "zh-Hans" else "zh-Hant"
            "pt" -> if (code == "PT") "pt-150" else "pt-419"
            else -> lang
        }
        val tail = "hl=$hl&gl=$code&ceid=$code:$ceLang"
        return when {
            t.query != null -> "https://news.google.com/rss/search?q=" + URLEncoder.encode(queryText(t, lang), "UTF-8") + "&$tail"
            t.section != null -> "https://news.google.com/rss/headlines/section/topic/${t.section}?$tail"
            else -> "https://news.google.com/rss?$tail"
        }
    }

    class Article(val title: String, val source: String, val link: String, val time: Long)

    private val cache = HashMap<String, Pair<Long, List<Article>>>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, force: Boolean, done: (List<Article>?) -> Unit) {
        val hit = cache[url]
        if (!force && hit != null && System.currentTimeMillis() - hit.first < 10 * 60_000L) { done(hit.second); return }
        pool.execute {
            val res = try { fetch(url) } catch (_: Throwable) { null }
            if (res != null) cache[url] = System.currentTimeMillis() to res
            main.post { done(res) }
        }
    }

    private fun fetch(u: String): List<Article> {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DesktopLauncher")
        try {
            val p = Xml.newPullParser()
            p.setInput(conn.inputStream, "UTF-8")
            val out = ArrayList<Article>()
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            var inItem = false
            var title = ""; var link = ""; var src = ""; var date = 0L
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> { inItem = true; title = ""; link = ""; src = ""; date = 0L }
                        "title" -> if (inItem) title = p.nextText().trim()
                        "link" -> if (inItem) link = p.nextText().trim()
                        "source" -> if (inItem) src = p.nextText().trim()
                        "pubDate" -> if (inItem) date = try { fmt.parse(p.nextText().trim())?.time ?: 0L } catch (_: Exception) { 0L }
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    var t = title
                    if (src.isNotEmpty() && t.endsWith(" - $src")) t = t.removeSuffix(" - $src")
                    if (t.isNotEmpty() && link.isNotEmpty()) out += Article(t, src, link, date)
                }
                ev = p.next()
            }
            return out
        } finally { conn.disconnect() }
    }
}

/** Small globe drawn in code: circle, equator and meridians. */
class GlobeIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat() }
    init { isClickable = true }
    override fun onDraw(canvas: Canvas) {
        p.color = Colors.iconBg(context)
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2; val r = minOf(w, h) * 0.30f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
        canvas.drawLine(cx, cy - r, cx, cy + r, p)
        canvas.drawOval(cx - r * 0.5f, cy - r, cx + r * 0.5f, cy + r, p)
        canvas.drawArc(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f, 0f, 180f, false, p)
    }
}

/** The "Discover" screen: news of the chosen country in its languages (Google News feeds). */
class NewsView(private val act: LauncherActivity) : FrameLayout(act) {
    private val list = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val subtitle = TextView(act)
    private val langRow = LinearLayout(act)
    private val langScroll = HorizontalScrollView(act)
    private val topicRow = LinearLayout(act)
    private var country: NewsCountry
    private var lang: String
    private var topic = 0
    private var picker: View? = null

    private companion object { const val K = "newsc" }

    init {
        val saved = Prefs.getStr(act, K)?.split("|")
        val loc = Locale.getDefault().country
        country = NewsData.byCode(saved?.getOrNull(0) ?: loc) ?: NewsData.byCode("PK")!!
        lang = saved?.getOrNull(1)?.takeIf { it in country.langs } ?: country.langs[0]
        layoutDirection = View.LAYOUT_DIRECTION_LTR

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(14), act.dp(8), act.dp(14), 0)

        // header: globe on the left, title next to it
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val globe = GlobeIcon(act)
        globe.background = Colors.rounded(Colors.iconFg(act), act.dp(22).toFloat())
        globe.setOnClickListener { showPicker() }
        head.addView(globe, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        val tbox = LinearLayout(act)
        tbox.orientation = LinearLayout.VERTICAL
        val title = TextView(act)
        title.text = UiText.text(act, "News")
        title.textSize = 26f
        title.setTextColor(Colors.text(act))
        tbox.addView(title)
        subtitle.textSize = 12f
        subtitle.setTextColor(Colors.text(act) and 0x00FFFFFF or 0xB3000000.toInt())
        tbox.addView(subtitle)
        head.addView(tbox, LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginStart = act.dp(12) })
        val refresh = TextView(act)
        refresh.text = "↻"
        refresh.textSize = 22f
        refresh.gravity = Gravity.CENTER
        refresh.setTextColor(Colors.iconBg(act))
        refresh.background = Colors.rounded(Colors.iconFg(act), act.dp(22).toFloat())
        refresh.setOnClickListener { reload(true) }
        head.addView(refresh, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        langScroll.isHorizontalScrollBarEnabled = false
        langRow.orientation = LinearLayout.HORIZONTAL
        langScroll.addView(langRow)
        col.addView(langScroll, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = act.dp(8) })

        val ts = HorizontalScrollView(act)
        ts.isHorizontalScrollBarEnabled = false
        topicRow.orientation = LinearLayout.HORIZONTAL
        ts.addView(topicRow)
        col.addView(ts, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = act.dp(6) })

        scroll.isVerticalScrollBarEnabled = false
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, act.dp(8), 0, act.dp(16))
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        col.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(col, LayoutParams(MATCH, MATCH))
        isClickable = true
        buildChips()
        reload(false)
    }

    private fun chip(text: String, on: Boolean, tap: () -> Unit): TextView {
        val t = TextView(act)
        t.text = text
        t.textSize = 13f
        t.gravity = Gravity.CENTER
        t.setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6))
        t.setTextColor(if (on) Colors.iconBg(act) else Colors.text(act))
        t.background = Colors.rounded(if (on) Colors.iconFg(act) else (Colors.surfaceHigh(act) and 0x00FFFFFF) or 0xCC000000.toInt(), act.dp(16).toFloat())
        t.setOnClickListener { tap() }
        return t
    }

    private fun buildChips() {
        subtitle.text = country.en + " · " + country.ur
        langRow.removeAllViews()
        langScroll.visibility = if (country.langs.size > 1) View.VISIBLE else View.GONE
        for (l in country.langs) langRow.addView(chip(NewsData.langName(l), l == lang) { lang = l; save(); buildChips(); reload(false) },
            LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = act.dp(6) })
        topicRow.removeAllViews()
        NewsData.topics.forEachIndexed { i, t ->
            topicRow.addView(chip(UiText.text(act, t.label), i == topic) { topic = i; buildChips(); reload(false) },
                LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = act.dp(6) })
        }
    }

    private fun save() { Prefs.setStr(act, K, country.code + "|" + lang, fire = false) }

    private fun note(text: String) {
        list.removeAllViews()
        val t = TextView(act)
        t.text = text
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        t.setTextColor(Colors.text(act))
        t.setPadding(act.dp(16), act.dp(40), act.dp(16), 0)
        list.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun reload(force: Boolean) {
        note(UiText.text(act, "Loading..."))
        val url = NewsData.url(country, lang, NewsData.topics[topic])
        NewsData.load(url, force) { res ->
            if (!isAttachedToWindow) return@load
            when {
                res == null -> note(UiText.text(act, "Could not load the news. Check the internet connection."))
                res.isEmpty() -> note(UiText.text(act, "No news found for this choice."))
                else -> show(res)
            }
        }
    }

    private fun ago(t: Long): String {
        if (t <= 0) return ""
        val m = (System.currentTimeMillis() - t) / 60000
        return when {
            m < 1 -> UiText.text(act, "just now")
            m < 60 -> "$m " + UiText.text(act, "min")
            m < 1440 -> "${m / 60} " + UiText.text(act, "h")
            else -> "${m / 1440} " + UiText.text(act, "d")
        }
    }

    private fun show(items: List<NewsData.Article>) {
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        for (a in items.take(60)) {
            val card = LinearLayout(act)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            card.background = Colors.panel(act, (Colors.surface(act) and 0x00FFFFFF) or 0xD9000000.toInt(), act.dp(18).toFloat())
            val t = TextView(act)
            t.text = a.title
            t.textSize = 16f
            t.setTextColor(Colors.text(act))
            t.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            t.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            card.addView(t, LinearLayout.LayoutParams(MATCH, WRAP))
            val m = TextView(act)
            m.text = listOf(a.source, ago(a.time)).filter { it.isNotEmpty() }.joinToString(" • ")
            m.textSize = 12f
            m.setTextColor(Colors.accent(act))
            m.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            m.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            card.addView(m, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = act.dp(6) })
            card.setOnClickListener {
                try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { act.toast("Cannot open") }
            }
            list.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = act.dp(10) })
        }
    }

    // ------------------------------------------------------------ country list with search

    private fun showPicker() {
        if (picker != null) return
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(Colors.surfaceSolid(act))
        box.setPadding(act.dp(14), act.dp(10), act.dp(14), 0)
        box.isClickable = true
        val search = EditText(act)
        search.hint = UiText.text(act, "Search country")
        search.setSingleLine()
        search.textSize = 16f
        search.setTextColor(Colors.text(act))
        search.setHintTextColor(Colors.text(act) and 0x00FFFFFF or 0x80000000.toInt())
        search.background = Colors.rounded(Colors.surfaceHigh(act), act.dp(26).toFloat())
        search.setPadding(act.dp(18), act.dp(10), act.dp(18), act.dp(10))
        box.addView(search, LinearLayout.LayoutParams(MATCH, act.dp(52)))
        val sv = ScrollView(act)
        sv.isVerticalScrollBarEnabled = false
        val items = LinearLayout(act)
        items.orientation = LinearLayout.VERTICAL
        sv.addView(items, FrameLayout.LayoutParams(MATCH, WRAP))
        box.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).also { it.topMargin = act.dp(8) })

        fun fill(q: String) {
            items.removeAllViews()
            val s = q.trim().lowercase()
            for (c in NewsData.countries) {
                if (s.isNotEmpty() && !(c.en.lowercase().contains(s) || c.ur.contains(s) || c.code.lowercase() == s)) continue
                val row = TextView(act)
                row.text = c.en + "   " + c.ur
                row.textSize = 17f
                row.setTextColor(if (c.code == country.code) Colors.iconBg(act) else Colors.text(act))
                row.setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(14))
                row.background = Colors.rounded(if (c.code == country.code) Colors.iconFg(act) else 0, act.dp(16).toFloat())
                row.setOnClickListener {
                    country = c
                    lang = c.langs[0]
                    save(); hidePicker(); buildChips(); reload(false)
                }
                items.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { fill(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        addView(box, LayoutParams(MATCH, MATCH))
        picker = box
        search.requestFocus()
        search.post { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0) }
    }

    private fun hidePicker() {
        picker?.let {
            (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            removeView(it)
        }
        picker = null
    }
}
