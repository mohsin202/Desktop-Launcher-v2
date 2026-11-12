package com.android.desktopfiles

import java.util.Locale

/** Interface language. Texts are written in English in the code; when Urdu is on, [t] gives the Urdu text (unknown texts stay as they are). */
object T {
    @Volatile var ur = false

    /** The interface runs right-to-left when Urdu is on. */
    val rtl: Boolean get() = ur

    /** [setting]: "system", "en" or "ur". */
    fun init(setting: String) { ur = when (setting) { "ur" -> true; "en" -> false; else -> Locale.getDefault().language == "ur" } }

    private const val IT = "(?:items?|item\\(s\\))"

    private val words = mapOf(
        "Home" to "ہوم", "Quick access" to "فوری رسائی", "Drives" to "ڈرائیوز", "Recent files" to "حالیہ فائلیں",
        "Internal storage" to "اندرونی اسٹوریج", "Root" to "روٹ", "Trash" to "ٹریش", "Documents" to "دستاویزات",
        "Pictures" to "تصاویر", "Music" to "موسیقی", "Videos" to "ویڈیوز", "Download" to "ڈاؤن لوڈز", "Downloads" to "ڈاؤن لوڈز",
        "Desktop" to "ڈیسک ٹاپ", "Movies" to "فلمیں", "Apps" to "ایپس", "Tags" to "ٹیگز", "Tag" to "ٹیگ",
        "documents" to "دستاویزات", "pictures" to "تصاویر", "music" to "موسیقی", "videos" to "ویڈیوز",
        "Network locations (SMB) will come in a later update." to "نیٹ ورک مقامات (SMB) بعد کی اپڈیٹ میں آئیں گے۔",
        "Disable" to "بند کریں",
        "Get root access for the root directory" to "روٹ ڈائریکٹری کے لیے روٹ رسائی حاصل کریں",
        "Checking root access…" to "روٹ رسائی جانچی جا رہی ہے…", "Root access available" to "روٹ رسائی دستیاب ہے",
        "Files you open will appear here." to "آپ کی کھولی ہوئی فائلیں یہاں نظر آئیں گی۔",
        "+ New ▾" to "+ نیا ▾", "Cut" to "کاٹیں", "Copy" to "کاپی", "Paste" to "پیسٹ", "Rename" to "نام بدلیں", "Share" to "شیئر",
        "Delete" to "ڈیلیٹ", "Sort ▾" to "ترتیب ▾", "View ▾" to "ویو ▾", "Show all files" to "تمام فائلیں دکھائیں",
        "Name" to "نام", "Date modified" to "تبدیلی کی تاریخ", "Size" to "سائز", "Type" to "قسم", "Modified" to "تبدیلی",
        "Ascending" to "صعودی", "Descending" to "نزولی", "Details" to "تفصیلات", "Grid" to "گرڈ", "Columns" to "کالمز",
        "Group by" to "گروپ بنائیں", "None" to "کوئی نہیں", "Show hidden files" to "چھپی فائلیں دکھائیں",
        "Folders" to "فولڈرز", "Today" to "آج", "Yesterday" to "کل", "Earlier this week" to "اس ہفتے پہلے",
        "Earlier this month" to "اس مہینے پہلے", "Earlier this year" to "اس سال پہلے", "A long time ago" to "بہت پہلے",
        "Empty" to "خالی", "Tiny (under 16 KB)" to "بہت چھوٹی (16 KB سے کم)", "Small (under 1 MB)" to "چھوٹی (1 MB سے کم)",
        "Medium (under 128 MB)" to "درمیانی (128 MB سے کم)", "Large (under 1 GB)" to "بڑی (1 GB سے کم)", "Huge (1 GB and more)" to "بہت بڑی (1 GB یا زیادہ)",
        "Open" to "کھولیں", "Compress to…" to "کمپریس کریں…", "ZIP file" to "ZIP فائل", "Copy as path" to "پاتھ کاپی کریں", "Path copied" to "پاتھ کاپی ہو گیا", "Send to" to "بھیجیں", "Show more options" to "مزید آپشنز دکھائیں", "Select all" to "سب منتخب کریں", "Open with…" to "اس کے ساتھ کھولیں…", "Open in new tab" to "نئے ٹیب میں کھولیں", "Open in new window" to "نئی ونڈو میں کھولیں",
        "Open all files" to "تمام فائلیں کھولیں", "Paste into folder" to "فولڈر میں پیسٹ کریں", "Delete permanently" to "ہمیشہ کے لیے ڈیلیٹ",
        "Compress to ZIP" to "ZIP میں سمیٹیں", "Extract to folder" to "فولڈر میں نکالیں", "Extract here" to "یہیں نکالیں",
        "Copy to other pane" to "دوسرے پین میں کاپی", "Move to other pane" to "دوسرے پین میں منتقل", "New tag…" to "نیا ٹیگ…", "New tag" to "نیا ٹیگ",
        "Create shortcut on Desktop" to "ڈیسک ٹاپ پر شارٹ کٹ بنائیں", "Copy to Desktop" to "ڈیسک ٹاپ پر کاپی کریں", "Move to Desktop" to "ڈیسک ٹاپ پر منتقل کریں",
        "Permissions…" to "اجازتیں…", "Properties" to "خصوصیات", "Pin to Quick access" to "فوری رسائی میں پن کریں",
        "Pin this folder to Quick access" to "یہ فولڈر فوری رسائی میں پن کریں", "Select all" to "سب منتخب کریں", "Refresh" to "تازہ کریں",
        "New" to "نیا", "Folder" to "فولڈر", "Text file" to "ٹیکسٹ فائل", "New folder" to "نیا فولڈر", "New text file" to "نئی ٹیکسٹ فائل",
        "Unpin" to "ان پن", "Unpin from Quick access" to "فوری رسائی سے ہٹائیں", "Pinned" to "پن ہو گیا", "Unpinned" to "پن ہٹا دیا",
        "Rename tag" to "ٹیگ کا نام بدلیں", "Rename using a template" to "ٹیمپلیٹ سے نام بدلیں", "Find and replace text" to "تلاش کر کے بدلیں", "Hide system apps" to "سسٹم ایپس چھپائیں", "Show system apps" to "سسٹم ایپس دکھائیں", "Change colour" to "رنگ بدلیں", "Tag colour" to "ٹیگ کا رنگ", "Save" to "محفوظ کریں", "Delete tag" to "ٹیگ ڈیلیٹ کریں", "Cancel" to "منسوخ", "OK" to "ٹھیک ہے", "Create" to "بنائیں",
        "Best fit" to "بہترین فٹ", "Stretch" to "کھینچیں", "Crop" to "کراپ", "Original size (100%)" to "اصل سائز (100%)",
        "This device cannot change speed" to "یہ ڈیوائس رفتار نہیں بدل سکتی", "Audio only" to "صرف آڈیو", "Video on" to "ویڈیو آن",
        "Rotation locked" to "گھماؤ مقفل", "Rotation unlocked" to "گھماؤ کھلا", "No file picker available" to "کوئی فائل منتخب کنندہ دستیاب نہیں",
        "Subtitle loaded" to "ذیلی عنوان لوڈ ہوگیا", "Could not read this subtitle file" to "یہ ذیلی عنوان فائل نہیں پڑھی جا سکی",
        "Repeat on" to "دہرانا آن", "Repeat off" to "دہرانا آف",
        "This format or file could not be played" to "یہ فارمیٹ یا فائل نہیں چل سکی", "Repeat" to "دہرائیں",
        "Subtitles…" to "ذیلی عنوانات…", "Playback speed…" to "چلانے کی رفتار…", "Playback speed" to "چلانے کی رفتار",
        "Picture in picture" to "تصویر میں تصویر",
        "Play" to "چلائیں", "Pause" to "روکیں", "Speed" to "رفتار", "Delay" to "تاخیر", "Now playing" to "اب چل رہا ہے",
        "Playing in the background" to "پس منظر میں چل رہا ہے", "Select playback speed" to "چلانے کی رفتار منتخب کریں",
        "Select audio track" to "آڈیو ٹریک منتخب کریں", "Select subtitle track" to "سب ٹائٹل ٹریک منتخب کریں",
        "Open local subtitle" to "مقامی سب ٹائٹل کھولیں", "No other audio track in this file" to "اس فائل میں کوئی اور آڈیو ٹریک نہیں",
        "This device cannot switch the audio track" to "یہ ڈیوائس آڈیو ٹریک نہیں بدل سکتی",
        "Apply" to "لاگو کریں", "Restore" to "بحال کریں", "Empty Trash" to "ٹریش خالی کریں", "Allow access" to "رسائی کی اجازت دیں",
        "Allow access to all files so Desktop Files can show and manage your folders." to "Desktop Files کو آپ کے فولڈر دکھانے اور سنبھالنے کے لیے تمام فائلوں تک رسائی کی اجازت دیں۔",
        "Theme" to "تھیم", "System" to "سسٹم", "Light" to "روشن", "Dark" to "تاریک", "Language" to "زبان",
        "Single click to open" to "ایک کلک سے کھولیں", "Details pane (Alt+P)" to "تفصیلی پین (Alt+P)", "Dual pane" to "دوہرا پین",
        "New window" to "نئی ونڈو", "Search" to "تلاش", "Status Center" to "اسٹیٹس سینٹر", "No running operations" to "کوئی کام نہیں چل رہا",
        "Cancelled" to "منسوخ ہوا", "Done" to "مکمل", "Installed" to "انسٹال ہوگئی", "a counter" to "ایک گنتی", "extension" to "ایکسٹینشن", "No changes" to "کوئی تبدیلی نہیں", "Two of the new names are the same" to "دو نئے نام ایک جیسے ہیں",
        "Select a file to see its details" to "تفصیل دیکھنے کے لیے فائل منتخب کریں", "Total size" to "کل سائز", "Location" to "مقام",
        "Preview" to "پری ویو", "calculating…" to "حساب ہو رہا ہے…", "The Trash is empty" to "ٹریش خالی ہے", "Loading…" to "لوڈ ہو رہا ہے…", "Show in folder" to "فولڈر میں دکھائیں",
        "Send" to "بھیجیں", "Send to nearby device" to "قریبی ڈیوائس کو بھیجیں", "Sending " to "بھیجا جا رہا ہے ", "Sending contacts" to "رابطے بھیجے جا رہے ہیں", "Sending messages" to "پیغامات بھیجے جا رہے ہیں", "Sending folder" to "فولڈر بھیجا جا رہا ہے", "Receiving " to "وصول ہو رہا ہے ", "Contacts" to "رابطے", "Messages" to "پیغامات", "No device is connected" to "کوئی ڈیوائس کنکٹ نہیں", "Incoming files" to "آنے والی فائلیں", "item(s)" to "چیزیں", "Accept" to "قبول کریں", "Decline" to "مسترد کریں", "Always accept from this device" to "اس ڈیوائس سے ہمیشہ قبول کریں", "Waiting for the other device" to "دوسری ڈیوائس کے قبول کرنے کا انتظار", "Declined" to "مسترد", "Failed" to "ناکام", "Sent to" to "بھیجا گیا", "Received from" to "وصول ہوا", "From" to "از", "Nothing has been sent or received yet" to "ابھی کچھ بھیجا یا وصول نہیں ہوا", "Nothing has been received yet" to "ابھی کچھ وصول نہیں ہوا", "Received" to "وصول شدہ", "Received files" to "وصول شدہ فائلیں", "Clear" to "صاف کریں", "Transfers" to "ٹرانسفر", "selected" to "منتخب", "Select what to send" to "بھیجنے کے لیے چیزیں چنیں", "Nothing found" to "کچھ نہیں ملا", "The file is no longer there" to "فائل اب موجود نہیں", "Device name…" to "ڈیوائس کا نام…", "Device name" to "ڈیوائس کا نام", "Connect by IP address…" to "IP ایڈریس سے جوڑیں…", "Connect" to "جوڑیں", "Permission not given" to "اجازت نہیں دی گئی", "Nothing to send" to "بھیجنے کو کچھ نہیں", "This file type can't be opened as a folder" to "اس فائل کو فولڈر کی طرح نہیں کھولا جا سکتا", "Opening " to "کھولا جا رہا ہے ", "Could not open the archive" to "آرکائیو نہیں کھل سکا", "Create archive" to "آرکائیو بنائیں", "Archive:" to "آرکائیو:", "Archive format:" to "آرکائیو کی قسم:", "Compression level:" to "کمپریشن کا درجہ:", "Encryption:" to "خفیہ کاری:", "Password:" to "پاس ورڈ:",
        "Split into volumes:" to "حصوں میں تقسیم:", "Enter password" to "پاس ورڈ لکھیں", "Delete source files after compression" to "کمپریس کے بعد اصل فائلیں ہٹا دیں", "Create separate archive" to "ہر چیز کا الگ آرکائیو بنائیں",
        "7z archive solid mode" to "7z آرکائیو سالڈ موڈ", "Save in:" to "محفوظ کریں:", "Current folder" to "موجودہ فولڈر", "Other pane" to "دوسرا پین", "Other size…" to "دوسرا سائز…", "No" to "نہیں",
        "No compression" to "بغیر کمپریشن", "Fastest" to "سب سے تیز", "Fast" to "تیز", "Normal" to "عام", "Maximum" to "زیادہ سے زیادہ", "Ultra" to "الٹرا",
        "No encryption" to "خفیہ کاری نہیں", "Data (ZipCrypto)" to "ڈیٹا (ZipCrypto)", "Data (AES-128)" to "ڈیٹا (AES-128)", "Data (AES-192)" to "ڈیٹا (AES-192)", "Data (AES-256)" to "ڈیٹا (AES-256)", "Only for ZIP" to "صرف ZIP کے لیے",
        "Generate a password" to "پاس ورڈ بنائیں", "Split size in MB" to "حصے کا سائز (MB)", "Enter a number" to "عدد لکھیں", "Enter a name" to "نام لکھیں", "Enter a password" to "پاس ورڈ لکھیں",
        "Select the items to compress" to "کمپریس کرنے کی چیزیں چنیں", "Select the items to compress, then press Compress" to "کمپریس کرنے کی چیزیں چنیں، پھر کمپریس دبائیں", "Compress" to "کمپریس",
         "Install app" to "ایپ انسٹال کریں", "Could not open this file" to "یہ فائل کھل نہیں سکی", "Customize installation" to "انسٹالیشن اپنی مرضی سے", "Installing" to "انسٹال ہو رہی ہے", "Install" to "انسٹال کریں", "Details" to "تفصیل", "Close" to "بند کریں",
        "You can select what split APK parts to install, best matching parts were selected automatically" to "آپ چن سکتے ہیں کہ کون سے split APK حصے انسٹال ہوں، سب سے مناسب حصے خود چنے جا چکے ہیں",
        "Base APK" to "بنیادی APK", "Configuration: Architecture" to "ترتیب: پروسیسر کی قسم", "Configuration: DPI Resources" to "ترتیب: اسکرین DPI وسائل", "Configuration: Language" to "ترتیب: زبان", "Optional features" to "اختیاری فیچرز",
        "Your device supports (from most to least preferred):" to "آپ کی ڈیوائس (زیادہ سے کم ترجیح کے مطابق) سپورٹ کرتی ہے:", "Your device DPI is" to "آپ کی ڈیوائس کا DPI ہے",
        "code for base APK" to "بنیادی APK کے لیے کوڈ", "resources for base APK" to "بنیادی APK کے لیے وسائل", "Another APK" to "ایک اور APK", "Feature" to "فیچر",
        "Reading the app…" to "ایپ پڑھی جا رہی ہے…", "This file has no app to install" to "اس فائل میں انسٹال کرنے کے لیے ایپ نہیں ہے", "Allow this app to install apps, then try again" to "اس ایپ کو ایپس انسٹال کرنے کی اجازت دیں، پھر دوبارہ کوشش کریں",
         "Searching…" to "تلاش ہو رہی ہے…",
        "That name already exists" to "یہ نام پہلے سے موجود ہے", "Could not rename" to "نام نہیں بدلا جا سکا", "Could not create it" to "بنایا نہیں جا سکا",
        "Nothing to paste" to "پیسٹ کرنے کے لیے کچھ نہیں", "Only files can be shared" to "صرف فائلیں شیئر ہو سکتی ہیں", "Could not share" to "شیئر نہیں ہو سکا",
        "No app can open this file" to "کوئی ایپ یہ فائل نہیں کھول سکتی", "This folder cannot be opened" to "یہ فولڈر کھولا نہیں جا سکتا",
        "This folder is empty" to "یہ فولڈر خالی ہے", "Root access is needed to read this folder" to "یہ فولڈر پڑھنے کے لیے روٹ رسائی درکار ہے",
        "The shortcut's target no longer exists" to "شارٹ کٹ کا ہدف اب موجود نہیں", "Shortcut created on Desktop" to "ڈیسک ٹاپ پر شارٹ کٹ بن گیا",
        "Could not create the shortcut" to "شارٹ کٹ نہیں بن سکا", "That path does not exist" to "یہ راستہ موجود نہیں", "No history yet" to "ابھی کوئی ہسٹری نہیں",
        "Could not open a new window" to "نئی ونڈو نہیں کھل سکی", "Could not read the file" to "فائل پڑھی نہیں جا سکی",
        "Could not read the permissions" to "اجازتیں پڑھی نہیں جا سکیں", "Permissions changed" to "اجازتیں بدل گئیں",
        "Could not change the permissions" to "اجازتیں نہیں بدلی جا سکیں", "This app has no screen to open" to "اس ایپ کی کوئی اسکرین نہیں جو کھولی جا سکے",
        "Could not open it" to "کھولا نہیں جا سکا", "Could not uninstall" to "ان انسٹال نہیں ہو سکا", "No items have this tag yet" to "ابھی کسی آئٹم پر یہ ٹیگ نہیں",
        "Filter apps" to "ایپس فلٹر کریں", "App info" to "ایپ کی معلومات", "Share APK" to "APK شیئر کریں",
        "Copy APK to Downloads" to "APK کو ڈاؤن لوڈز میں کاپی کریں", "Uninstall" to "ان انسٹال کریں", "Tag name" to "ٹیگ کا نام",
        "Read" to "پڑھنا", "Write" to "لکھنا", "Execute" to "چلانا", "Owner" to "مالک", "Group" to "گروپ", "Others" to "دیگر",
        "Apply to everything inside" to "اندر کی ہر چیز پر لاگو کریں", "File folder" to "فائل فولڈر", "Shortcut" to "شارٹ کٹ", "File" to "فائل",
        "These items are outside your storage and cannot go to the Trash." to "یہ آئٹم آپ کی اسٹوریج سے باہر ہیں اور ٹریش میں نہیں جا سکتے۔",
        "Copy here" to "یہاں کاپی کریں", "Move here" to "یہاں منتقل کریں", "Create shortcut here" to "یہاں شارٹ کٹ بنائیں",
        "A folder cannot be dropped into itself" to "فولڈر کو اسی میں نہیں ڈالا جا سکتا", "Done, nothing to do" to "مکمل، کچھ کرنا نہیں تھا",
        "USB phone (MTP)" to "USB فون (MTP)", "The phone is not connected" to "فون جڑا ہوا نہیں", "USB permission was not given" to "USB کی اجازت نہیں دی گئی",
        "Add a cloud account (WebDAV)" to "کلاؤڈ اکاؤنٹ شامل کریں (WebDAV)", "Add cloud account…" to "کلاؤڈ اکاؤنٹ شامل کریں…", "Cloud" to "کلاؤڈ", "Cloud (WebDAV)" to "کلاؤڈ (WebDAV)",
        "Name (for example My Nextcloud)" to "نام (مثلاً My Nextcloud)", "Address (https://server/remote.php/dav/files/user/)" to "پتہ (https://server/remote.php/dav/files/user/)",
        "User name" to "صارف کا نام", "Password" to "پاس ورڈ", "Trust this server's certificate (self-signed)" to "اس سرور کا سرٹیفکیٹ قبول کریں (self-signed)",
        "Type the address" to "پتہ لکھیں", "Add" to "شامل کریں", "Remove account" to "اکاؤنٹ ہٹائیں", "Remove" to "ہٹائیں",
        "This account was removed" to "یہ اکاؤنٹ ہٹا دیا گیا ہے", "Wrong user name or password (401)" to "صارف نام یا پاس ورڈ غلط ہے (401)", "Wrong user name or password (403)" to "صارف نام یا پاس ورڈ غلط ہے (403)",
        "Not found (404)" to "نہیں ملا (404)", "Not allowed (405)" to "اجازت نہیں (405)", "No space left on the server" to "سرور پر جگہ ختم ہے",
        "Cloud (WebDAV) and Windows share (SMB) accounts can be added below." to "کلاؤڈ (WebDAV) اور Windows شیئر (SMB) کے اکاؤنٹ نیچے سے شامل کریں۔",
        "Folder from another app (Google Drive, OneDrive, Dropbox…)" to "دوسری ایپ کا فولڈر (Google Drive، OneDrive، Dropbox…)", "Could not open the picker" to "فولڈر چننے والا نہیں کھل سکا", "App folder" to "ایپ کا فولڈر", "Name" to "نام", "Add an account" to "اکاؤنٹ شامل کریں", "WebDAV (Nextcloud, ownCloud, NAS)" to "WebDAV (Nextcloud، ownCloud، NAS)", "Windows share (SMB)" to "Windows شیئر (SMB)",
        "Add a Windows share (SMB)" to "Windows شیئر شامل کریں (SMB)", "Name (for example Office PC)" to "نام (مثلاً Office PC)",
        "Server and share (192.168.1.5/Documents)" to "سرور اور شیئر (192.168.1.5/Documents)", "User name (empty = guest)" to "صارف نام (خالی = مہمان)",
        "Type the server address" to "سرور کا پتہ لکھیں", "Wrong user name or password" to "صارف نام یا پاس ورڈ غلط ہے",
        "Could not connect" to "جڑ نہیں سکا", "Could not open the phone" to "فون کھولا نہیں جا سکا", "Connecting…" to "جڑ رہا ہے…",
        "The phone did not accept that" to "فون نے یہ قبول نہیں کیا",
        "The phone did not answer. Set its USB mode to File transfer." to "فون نے جواب نہیں دیا۔ اس کا USB موڈ فائل ٹرانسفر پر کریں۔",
        "Could not open the phone. Unlock it and set the USB mode to File transfer." to "فون کھولا نہیں جا سکا۔ اسے ان لاک کریں اور USB موڈ فائل ٹرانسفر پر کریں۔",
        "Phone" to "فون", "Storage" to "اسٹوریج", "Copy to Downloads" to "ڈاؤن لوڈز میں کاپی کریں",
        "Enabled" to "فعال", "English" to "English", "Urdu" to "اردو")

    private class Rule(val re: Regex, val f: (MatchResult) -> String)

    private fun r(p: String, f: (MatchResult) -> String) = Rule(Regex(p, RegexOption.DOT_MATCHES_ALL), f)
    private fun g(m: MatchResult, i: Int) = m.groupValues[i]

    private val rules: List<Rule> = listOf(
        r("^(\\d+) $IT$") { g(it, 1) + " آئٹم" },
        r("^(\\d+) selected(.*)$") { g(it, 1) + " منتخب" + g(it, 2) },
        r("^kept for (\\d+) days$") { g(it, 1) + " دن تک محفوظ" },
        r("^(\\d+) days? left$") { g(it, 1) + " دن باقی" },
        r("^(\\d+) $IT selected$") { g(it, 1) + " آئٹم منتخب" },
        r("^(\\d+) operations running$") { g(it, 1) + " کام چل رہے ہیں" },
        r("^Moving (\\d+) $IT to Trash$") { g(it, 1) + " آئٹم ٹریش میں منتقل ہو رہے ہیں" },
        r("^Moving (\\d+) $IT to Desktop$") { g(it, 1) + " آئٹم ڈیسک ٹاپ پر منتقل ہو رہے ہیں" },
        r("^Copying (\\d+) $IT to Desktop$") { g(it, 1) + " آئٹم ڈیسک ٹاپ پر کاپی ہو رہے ہیں" },
        r("^Moving (.+) to Trash$") { g(it, 1) + " ٹریش میں منتقل ہو رہا ہے" },
        r("^Copying (.+) to Desktop$") { g(it, 1) + " ڈیسک ٹاپ پر کاپی ہو رہا ہے" },
        r("^Copying (\\d+) $IT to (.+)$") { g(it, 1) + " آئٹم " + g(it, 2) + " میں کاپی ہو رہے ہیں" },
        r("^Moving (\\d+) $IT to (.+)$") { g(it, 1) + " آئٹم " + g(it, 2) + " میں منتقل ہو رہے ہیں" },
        r("^Compressing (\\d+) $IT to (.+)$") { g(it, 1) + " آئٹم " + g(it, 2) + " میں سمیٹے جا رہے ہیں" },
        r("^Extracting (.+)$") { g(it, 1) + " نکالا جا رہا ہے" },
        r("^Deleting (\\d+) $IT permanently$") { g(it, 1) + " آئٹم ہمیشہ کے لیے ڈیلیٹ ہو رہے ہیں" },
        r("^Deleting (\\d+) $IT$") { g(it, 1) + " آئٹم ڈیلیٹ ہو رہے ہیں" },
        r("^Restoring (\\d+) $IT$") { g(it, 1) + " آئٹم بحال ہو رہے ہیں" },
        r("^Renaming (\\d+) $IT$") { g(it, 1) + " آئٹم کا نام بدلا جا رہا ہے" },
        r("^Preparing (.+)$") { g(it, 1) + " تیار کیا جا رہا ہے" },
        r("^Copying APK of (.+)$") { g(it, 1) + " کی APK کاپی ہو رہی ہے" },
        r("^Cut (\\d+)$") { g(it, 1) + " کاٹے گئے" },
        r("^Copied (\\d+)$") { g(it, 1) + " کاپی ہوئے" },
        r("^Saved: (.+)$") { "محفوظ: " + g(it, 1) },
        r("^Delete (\\d+) $IT permanently\\? This cannot be undone\\.$") { "کیا " + g(it, 1) + " آئٹم ہمیشہ کے لیے ڈیلیٹ کریں؟ یہ واپس نہیں ہو سکتا۔" },
        r("^Delete the tag \"(.+)\"\\? The files themselves stay\\.$") { "ٹیگ «" + g(it, 1) + "» ڈیلیٹ کریں؟ فائلیں محفوظ رہیں گی۔" },
        r("^Search: (.+)$") { "تلاش: " + g(it, 1) },
        r("^No results for \"(.+)\"$") { "«" + g(it, 1) + "» کے لیے کوئی نتیجہ نہیں" },
        r("^Permissions: (.+)$") { "اجازتیں: " + g(it, 1) },
        r("^Deleted from (.+)$") { "یہاں سے ڈیلیٹ ہوا: " + g(it, 1) },
        r("^No (documents|pictures|music|videos) found$") { "کوئی " + t(g(it, 1)) + " نہیں ملا" },
        r("^Folders on this device that contain (.+)$") { "اس ڈیوائس کے وہ فولڈر جن میں " + t(g(it, 1)) + " ہیں" },
        r("^Remounted (.+) as (rw|ro)$") { g(it, 1) + " کو " + g(it, 2) + " کے طور پر ری ماؤنٹ کر دیا" },
        r("^Remount failed: (.*)$") { "ری ماؤنٹ ناکام: " + g(it, 1) },
        r("^Done, (\\d+) could not be processed$") { "مکمل، " + g(it, 1) + " پر کارروائی نہ ہو سکی" },
        r("^(.+) free of (.+)$") { g(it, 2) + " میں سے " + g(it, 1) + " خالی" },
        r("^(.+)  ·  system$") { g(it, 1) + "  ·  سسٹم" },
        r("^Type: (.+)$") { "قسم: " + t(g(it, 1)) },
        r("^Location: (.+)$") { "مقام: " + g(it, 1) },
        r("^Size: (.+)$") { "سائز: " + t(g(it, 1)) },
        r("^Modified: (.+)$") { "تبدیلی: " + g(it, 1) },
        r("^(\\S+) File$") { g(it, 1) + " فائل" }
    )

    fun t(s: String): String {
        if (!ur || s.isEmpty()) return s
        words[s]?.let { return it }
        if (s.contains('\n')) return s.split('\n').joinToString("\n") { t(it) }
        if (s.contains("   |   ")) return s.split("   |   ").joinToString("   |   ") { t(it.trim()) }
        for (ru in rules) { val m = ru.re.matchEntire(s) ?: continue; return ru.f(m) }
        return s
    }
}
