package com.example.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class ContactMatch(
    val id: String,
    val name: String,
    val number: String
)

data class ActionResult(
    val success: Boolean,
    val actionName: String,
    val message: String,
    val details: JSONObject = JSONObject()
)

class ActionBridge(private val context: Context) {

    /**
     * Opens WhatsApp application or deep link.
     */
    fun openWhatsApp(): ActionResult {
        val pm = context.packageManager
        val packagesToTry = listOf("com.whatsapp", "com.whatsapp.w4b")
        
        for (pkg in packagesToTry) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val details = JSONObject().apply {
                    put("package", pkg)
                    put("launched", true)
                }
                return ActionResult(
                    success = true,
                    actionName = "openWhatsApp",
                    message = "WhatsApp opened successfully on the device.",
                    details = details
                )
            }
        }

        // Try WhatsApp URL deep link
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (webIntent.resolveActivity(pm) != null) {
                context.startActivity(webIntent)
                return ActionResult(
                    success = true,
                    actionName = "openWhatsApp",
                    message = "WhatsApp opened via deep link.",
                    details = JSONObject().put("method", "deep_link")
                )
            }
        } catch (_: Exception) {}

        // Fallback to web WhatsApp / Play Store
        try {
            val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playStoreIntent)
            return ActionResult(
                success = true,
                actionName = "openWhatsApp",
                message = "WhatsApp is not installed. Opened WhatsApp on Google Play Store.",
                details = JSONObject().put("method", "play_store")
            )
        } catch (e: Exception) {
            return ActionResult(
                success = false,
                actionName = "openWhatsApp",
                message = "Could not open WhatsApp: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    /**
     * Opens an application or system setting by natural name.
     */
    fun openApp(appName: String): ActionResult {
        val trimmed = appName.trim()
        val lower = trimmed.lowercase()

        // Specific System Intents & Common Apps
        if (lower.contains("setting")) {
            return openSettings()
        }
        if (lower == "whatsapp" || lower.contains("whats app")) {
            return openWhatsApp()
        }
        if (lower.contains("camera")) {
            return openCamera()
        }
        if (lower.contains("calc")) {
            return openCalculator()
        }

        val knownPackages = mapOf(
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "linkedin" to "com.linkedin.android",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer"
        )

        val targetPackage = knownPackages[lower] ?: knownPackages.entries.firstOrNull { lower.contains(it.key) }?.value

        val pm = context.packageManager
        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return ActionResult(
                    success = true,
                    actionName = "openApp",
                    message = "Opened $trimmed ($targetPackage) successfully.",
                    details = JSONObject().put("packageName", targetPackage)
                )
            }
        }

        // Search installed applications for matching label
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in apps) {
                val label = resolveInfo.loadLabel(pm).toString()
                if (label.equals(trimmed, ignoreCase = true) || label.lowercase().contains(lower) || lower.contains(label.lowercase())) {
                    val pkgName = resolveInfo.activityInfo.packageName
                    val intent = pm.getLaunchIntentForPackage(pkgName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        return ActionResult(
                            success = true,
                            actionName = "openApp",
                            message = "Opened $label ($pkgName) successfully.",
                            details = JSONObject().put("packageName", pkgName).put("label", label)
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback for Chrome / Browser
        if (lower.contains("chrome") || lower.contains("browser") || lower.contains("web")) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                return ActionResult(
                    success = true,
                    actionName = "openApp",
                    message = "Opened browser for web search.",
                    details = JSONObject().put("type", "browser_fallback")
                )
            } catch (_: Exception) {}
        }

        // Fallback: Open Google Play Store search for the app
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$trimmed")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (marketIntent.resolveActivity(pm) != null) {
                context.startActivity(marketIntent)
                return ActionResult(
                    success = true,
                    actionName = "openApp",
                    message = "$trimmed is not currently installed. Opened search in Google Play Store.",
                    details = JSONObject().put("searchQuery", trimmed)
                )
            }
        } catch (_: Exception) {}

        return ActionResult(
            success = false,
            actionName = "openApp",
            message = "Could not find or open app '$trimmed' on this device."
        )
    }

    private fun openSettings(): ActionResult {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(
                success = true,
                actionName = "openApp",
                message = "Device settings opened.",
                details = JSONObject().put("target", "settings")
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                actionName = "openApp",
                message = "Unable to open device settings: ${e.localizedMessage}"
            )
        }
    }

    private fun openCamera(): ActionResult {
        return try {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(
                success = true,
                actionName = "openApp",
                message = "Camera opened.",
                details = JSONObject().put("target", "camera")
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                actionName = "openApp",
                message = "Unable to open camera: ${e.localizedMessage}"
            )
        }
    }

    private fun openCalculator(): ActionResult {
        val calcPackages = listOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator"
        )
        val pm = context.packageManager
        for (pkg in calcPackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return ActionResult(
                    success = true,
                    actionName = "openApp",
                    message = "Calculator opened.",
                    details = JSONObject().put("packageName", pkg)
                )
            }
        }
        return ActionResult(
            success = false,
            actionName = "openApp",
            message = "Calculator application not found on device."
        )
    }

    /**
     * Initiates a direct phone call (if CALL_PHONE permission is granted)
     * or opens the system phone dialer pre-filled with the number.
     */
    fun makeCall(phoneNumber: String): ActionResult {
        val sanitized = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (sanitized.isEmpty()) {
            return ActionResult(
                success = false,
                actionName = "makeCall",
                message = "Invalid phone number provided: '$phoneNumber'."
            )
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)
            ActionResult(
                success = true,
                actionName = "makeCall",
                message = if (hasCallPermission) {
                    "Initiating direct phone call to $sanitized."
                } else {
                    "Opened phone dialer with number $sanitized."
                },
                details = JSONObject().apply {
                    put("phoneNumber", sanitized)
                    put("directCall", hasCallPermission)
                }
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                actionName = "makeCall",
                message = "Failed to initiate call to $sanitized: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Searches Android Contacts for [contactName].
     * Normalizes common family/relation aliases (Mom/Mummy/Mother/Maa, Dad/Papa/Father).
     * Returns:
     * - 0 matches -> reports contact not found
     * - 1 match -> executes call
     * - >1 matches -> returns list of candidates for user clarification
     */
    fun callContact(contactName: String): ActionResult {
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return ActionResult(
                success = false,
                actionName = "callContact",
                message = "Contacts permission is not granted. Please allow Contacts permission to call by name.",
                details = JSONObject().put("permissionRequired", "READ_CONTACTS")
            )
        }

        val matches = queryContacts(contactName)

        if (matches.isEmpty()) {
            return ActionResult(
                success = false,
                actionName = "callContact",
                message = "No contact found matching '$contactName' in your phone contacts.",
                details = JSONObject().apply {
                    put("contactName", contactName)
                    put("matchCount", 0)
                }
            )
        }

        if (matches.size == 1) {
            val single = matches.first()
            val callRes = makeCall(single.number)
            return ActionResult(
                success = callRes.success,
                actionName = "callContact",
                message = "Found contact ${single.name} (${single.number}). ${callRes.message}",
                details = JSONObject().apply {
                    put("contactName", single.name)
                    put("phoneNumber", single.number)
                    put("matchCount", 1)
                }
            )
        }

        // Multiple matches -> provide candidate names and numbers for Gemini to ask clarification
        val candidatesArray = JSONArray()
        val candidateStrings = mutableListOf<String>()
        matches.take(5).forEach {
            val obj = JSONObject().apply {
                put("name", it.name)
                put("number", it.number)
            }
            candidatesArray.put(obj)
            candidateStrings.add("${it.name} (${it.number})")
        }

        val clarificationPrompt = "I found ${matches.size} contacts matching '$contactName': ${candidateStrings.joinToString(", ")}. Which one would you like to call?"

        return ActionResult(
            success = true,
            actionName = "callContact",
            message = clarificationPrompt,
            details = JSONObject().apply {
                put("contactName", contactName)
                put("matchCount", matches.size)
                put("candidates", candidatesArray)
                put("clarificationNeeded", true)
            }
        )
    }

    private fun queryContacts(query: String): List<ContactMatch> {
        val results = mutableListOf<ContactMatch>()
        val queryLower = query.trim().lowercase()

        // Aliases expansion
        val searchKeywords = mutableSetOf(queryLower)
        when (queryLower) {
            "mom", "mummy", "mother", "maa", "mum", "amma" -> {
                searchKeywords.addAll(listOf("mom", "mummy", "mother", "maa", "mum", "amma", "matashri"))
            }
            "dad", "papa", "father", "daddy", "pitaji", "bapu", "appa" -> {
                searchKeywords.addAll(listOf("dad", "papa", "father", "daddy", "pitaji", "bapu", "appa"))
            }
            "bro", "brother", "bhai", "bhaiya" -> {
                searchKeywords.addAll(listOf("bro", "brother", "bhai", "bhaiya"))
            }
            "sis", "sister", "didi", "behen" -> {
                searchKeywords.addAll(listOf("sis", "sister", "didi", "behen"))
            }
            "wife", "patni", "biwi" -> {
                searchKeywords.addAll(listOf("wife", "patni", "biwi", "darling", "jaan"))
            }
            "husband", "pati" -> {
                searchKeywords.addAll(listOf("husband", "pati", "hubby"))
            }
        }

        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            val cursor = contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val seenNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""

                    if (name.isBlank() || number.isBlank()) continue

                    val nameLower = name.lowercase()
                    val isMatch = searchKeywords.any { kw ->
                        nameLower == kw || nameLower.startsWith("$kw ") || nameLower.endsWith(" $kw") || nameLower.contains(kw)
                    }

                    val normalizedNum = number.replace(Regex("[^0-9+]"), "")
                    if (isMatch && !seenNumbers.contains(normalizedNum)) {
                        seenNumbers.add(normalizedNum)
                        results.add(ContactMatch(id = id, name = name, number = number))
                    }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    /**
     * Opens a given URL in the web browser.
     */
    fun openUrl(url: String): ActionResult {
        var parsed = url.trim()
        if (!parsed.startsWith("http://") && !parsed.startsWith("https://")) {
            parsed = "https://$parsed"
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(parsed)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult(
                success = true,
                actionName = "openUrl",
                message = "Opened URL: $parsed",
                details = JSONObject().put("url", parsed)
            )
        } catch (e: Exception) {
            ActionResult(
                success = false,
                actionName = "openUrl",
                message = "Could not open URL $parsed: ${e.localizedMessage}"
            )
        }
    }
}
