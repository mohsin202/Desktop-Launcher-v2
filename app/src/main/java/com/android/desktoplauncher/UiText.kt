package com.android.desktoplauncher

import android.content.Context
import android.view.View
import android.view.View.LAYOUT_DIRECTION_LTR
import android.view.View.LAYOUT_DIRECTION_RTL

/** Small built-in English/Urdu UI dictionary and independent layout-direction preference. */
object UiText {
    private val ur = mapOf(
        "Desktop Launcher" to "ڈیسک ٹاپ لانچر", "Apps" to "ایپس", "Settings" to "ترتیبات", "ICONS" to "آئیکنز",
        "Choose language and reading direction" to "زبان اور پڑھنے کی سمت منتخب کریں",
        "Icon size and app appearance" to "آئیکنز کا سائز اور ایپس کی ظاہری شکل",
        "Phone or desktop layout" to "فون یا ڈیسک ٹاپ لے آؤٹ",
        "Colours, blur and drawer behaviour" to "رنگ، بلر اور ایپ ڈراور کا انداز",
        "Taskbar, dock and navigation controls" to "ٹاسک بار، ڈوک اور نیویگیشن کنٹرولز",
        "Access needed by launcher features" to "لانچر کی سہولتوں کے لیے درکار اجازتیں",
        "Desktop items and wallpaper" to "ڈیسک ٹاپ کی اشیا اور وال پیپر",
        "Small" to "چھوٹے", "Normal" to "درمیانے", "Large" to "بڑے",
        "App drawer themed icons" to "ایپ ڈراور میں تھیم والے آئیکنز", "Uses the wallpaper colours for apps that provide a monochrome icon (Android 13+)." to "وال پیپر کے رنگ ان ایپس پر استعمال ہوں گے جو مونوکروم آئیکن فراہم کرتی ہیں (Android 13+).",
        "Show notification dots" to "نوٹیفکیشن ڈاٹس دکھائیں", "Allow notification access (needed for dots)" to "نوٹیفکیشن رسائی دیں (ڈاٹس کے لیے ضروری)",
        "LANGUAGE & DIRECTION" to "زبان اور سمت", "UI language" to "ایپ کی زبان", "English" to "انگریزی", "Urdu" to "اردو",
        "Layout direction" to "لے آؤٹ کی سمت", "System default" to "سسٹم کے مطابق", "Left to right (LTR)" to "بائیں سے دائیں", "Right to left (RTL)" to "دائیں سے بائیں",
        "Changes apply immediately where possible; reopen the launcher if an old screen does not update." to "تبدیلیاں جہاں ممکن ہو فوراً لاگو ہوں گی؛ پرانی اسکرین اپ ڈیٹ نہ ہو تو لانچر دوبارہ کھولیں۔",
        "LAYOUT" to "لے آؤٹ", "Automatic (600 dp or more = desktop taskbar)" to "خودکار (600 dp یا زیادہ = ڈیسک ٹاپ ٹاسک بار)", "Always phone (dock)" to "ہمیشہ فون ڈوک", "Always desktop (taskbar)" to "ہمیشہ ڈیسک ٹاپ ٹاسک بار",
        "APPEARANCE" to "ظاہری شکل", "Enable UI blur" to "انٹرفیس بلر فعال کریں", "Blurs only overlay windows such as the taskbar and menus. When off, solid Material You colours are used." to "صرف ٹاسک بار اور مینو جیسے اوورلے بلر ہوں گے۔ بند ہونے پر Material You کے ٹھوس رنگ استعمال ہوں گے۔",
        "Show keyboard when app drawer opens" to "ایپ ڈراور کھلنے پر کی بورڈ دکھائیں", "When off, the app drawer opens without automatically showing the keyboard. Back should hide the keyboard before closing the drawer." to "بند ہو تو ڈراور کھلنے پر کی بورڈ خود نہیں آئے گا۔ بیک سے پہلے کی بورڈ بند ہونا چاہیے۔",
        "TASKBAR / DOCK" to "ٹاسک بار / ڈوک", "Show taskbar / dock" to "ٹاسک بار / ڈوک دکھائیں", "Show Back / Home / Recents buttons" to "بیک / ہوم / حالیہ ایپس کے بٹن دکھائیں", "Show language switch icon while typing" to "ٹائپ کرتے وقت زبان بدلنے کا آئیکن دکھائیں", "Windows key opens the app drawer" to "Windows کی سے ایپ ڈراور کھولیں",
        "PERMISSIONS" to "اجازتیں", "All files access: allowed" to "تمام فائلوں کی رسائی: اجازت ہے", "Allow all files access (Desktop folder)" to "تمام فائلوں کی رسائی دیں (ڈیسک ٹاپ فولڈر)", "Contacts search: allowed" to "رابطوں کی تلاش: اجازت ہے", "Allow contacts (for search)" to "رابطوں کی اجازت دیں (تلاش کے لیے)", "Set as default home app" to "ڈیفالٹ ہوم ایپ مقرر کریں",
        "DESKTOP" to "ڈیسک ٹاپ", "Restore removed desktop items" to "ہٹائی گئی ڈیسک ٹاپ اشیا بحال کریں", "Restored" to "بحال ہوگیا", "Wallpaper & style" to "وال پیپر اور انداز", "Cannot open" to "نہیں کھل سکا", "Search" to "تلاش", "Taskbar height" to "ٹاسک بار کی اونچائی",
        "New folder" to "نیا فولڈر", "New file" to "نئی فائل", "Rename" to "نام تبدیل کریں", "Cut" to "کٹ کریں", "Copy" to "کاپی", "Paste" to "پیسٹ", "Delete" to "حذف کریں", "Open" to "کھولیں", "Add to desktop" to "ڈیسک ٹاپ پر شامل کریں", "Uninstall" to "ان انسٹال کریں", "Widgets" to "وجٹس", "View" to "دیکھیں", "Wallpaper" to "وال پیپر", "Create shortcut on desktop" to "ڈیسک ٹاپ پر شارٹ کٹ بنائیں", "All results" to "تمام نتائج دیکھیں", "File" to "فائل", "Folder" to "فولڈر", "New" to "نیا", "Wallpapers" to "وال پیپرز", "App Info" to "ایپ کی معلومات", "Share" to "شیئر", "Remove" to "ہٹائیں", "Unpin from Taskbar" to "ٹاسک بار سے اَن پن کریں", "Pin to Taskbar" to "ٹاسک بار پر پن کریں", "Add to Desktop" to "ڈیسک ٹاپ پر شامل کریں", "Copied" to "کاپی ہوگیا", "Added to desktop" to "ڈیسک ٹاپ پر شامل ہوگیا", "Shortcut created on Desktop" to "ڈیسک ٹاپ شارٹ کٹ بن گیا", "Could not create shortcut" to "شارٹ کٹ نہیں بن سکا", "See all files" to "تمام فائلیں دیکھیں", "Open Desktop folder" to "ڈیسک ٹاپ فولڈر کھولیں", "Settings" to "ترتیبات", "App shortcuts" to "ایپ شارٹ کٹس", "Contacts" to "رابطے", "Files" to "فائلیں", "In apps" to "ایپس میں", "Web" to "ویب", "Contact" to "رابطہ", "System settings" to "سسٹم ترتیبات", "Create shortcut on Desktop" to "ڈیسک ٹاپ پر شارٹ کٹ بنائیں", "Taskbar" to "ٹاسک بار", "Back" to "واپس", "Home" to "ہوم", "Recents" to "حالیہ ایپس",
        "Match this to the system navigation bar height so the taskbar covers it exactly." to "اسے سسٹم نیویگیشن بار کی اونچائی کے مطابق رکھیں تاکہ ٹاسک بار اسے پورا ڈھانپ لے۔",
        "Turn on taskbar (Accessibility settings)" to "ٹاسک بار فعال کریں (Accessibility ترتیبات)",
        "Set as default home app" to "ڈیفالٹ ہوم ایپ مقرر کریں", "All files access: allowed" to "تمام فائلوں کی رسائی: اجازت ہے", "Allow all files access (Desktop folder)" to "تمام فائلوں کی رسائی دیں (ڈیسک ٹاپ فولڈر)",
        "Contacts search: allowed" to "رابطوں کی تلاش: اجازت ہے", "Allow contacts (for search)" to "رابطوں کی اجازت دیں (تلاش کے لیے)",
        "Smallest width" to "کم سے کم چوڑائی", "tablet mode: the dock is merged into the taskbar (over all apps)." to "ٹیبلٹ موڈ: ڈوک تمام ایپس کے اوپر ٹاسک بار میں شامل ہے۔", "phone mode: normal dock on the home screen. At 600 dp or more the dock becomes the taskbar." to "فون موڈ: ہوم اسکرین پر عام ڈوک۔ 600 dp یا زیادہ پر ڈوک ٹاسک بار بن جاتا ہے۔",
        "Open Desktop folder" to "ڈیسک ٹاپ فولڈر کھولیں", "Search the web" to "ویب پر تلاش کریں", "Hide all files" to "تمام فائلیں چھپائیں", "Show only the first 5 results" to "صرف پہلی 5 فائلیں دکھائیں", "Show all matching results" to "تمام ملتے جلتے نتائج دکھائیں", "Include" to "شامل کریں", "No widgets found" to "کوئی ویجیٹ نہیں ملا", "Add to home screen" to "ہوم اسکرین پر شامل کریں", "widget" to "ویجیٹ", "widgets" to "ویجیٹس", "Clock (digital)" to "ڈیجیٹل گھڑی", "Clock (analog)" to "اینالاگ گھڑی", "Search bar" to "سرچ بار", "No free space on the desktop" to "ڈیسک ٹاپ پر جگہ نہیں ہے", "Cancel" to "منسوخ کریں"
    )
    fun text(c: Context, english: String): String = if (Prefs.language(c) == 1) ur[english] ?: english else english
    fun applyDirection(v: View, c: Context) {
        v.layoutDirection = when (Prefs.direction(c)) {
            1 -> LAYOUT_DIRECTION_LTR
            2 -> LAYOUT_DIRECTION_RTL
            else -> View.LAYOUT_DIRECTION_INHERIT
        }
    }
}
