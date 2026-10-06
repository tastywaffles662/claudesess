package io.github.tastywaffles662.palisade.core.capability

/** How much effort a grant takes. Each tier widens what Palisade can see. */
enum class Tier(val level: Int) {
    /** Standard permissions, granted with a tap inside the app. */
    APP(0),

    /** Special access the user switches on in system Settings. */
    SETTINGS(1),

    /** Development permissions granted once from a computer with `adb shell pm grant`. */
    ADB(2),
}

/** One piece of access Palisade can be given. [id] is stored, so it must never change. */
enum class Capability(
    val id: String,
    val tier: Tier,
    val label: String,
    /** What Palisade can do once it has this access. */
    val unlocks: String,
    /** The permission to pass to `adb shell pm grant`, for [Tier.ADB] capabilities. */
    val adbPermission: String? = null,
) {
    NOTIFICATIONS(
        id = "notifications",
        tier = Tier.APP,
        label = "Notifications",
        unlocks = "Alerts you as soon as Palisade finds something.",
    ),
    USAGE_ACCESS(
        id = "usage_access",
        tier = Tier.SETTINGS,
        label = "Usage access",
        unlocks = "Which app was in the foreground and how much data each app used, " +
            "so traffic and battery drain can be traced to an app.",
    ),
    ALL_FILES_ACCESS(
        id = "all_files_access",
        tier = Tier.SETTINGS,
        label = "All files access",
        unlocks = "Inspecting shared storage, including media that messaging apps receive, " +
            "for files crafted to exploit the phone.",
    ),
    UNRESTRICTED_BATTERY(
        id = "unrestricted_battery",
        tier = Tier.SETTINGS,
        label = "Unrestricted battery use",
        unlocks = "Stops Android from pausing Palisade's background checks to save power.",
    ),
    DUMP(
        id = "dump",
        tier = Tier.ADB,
        label = "DUMP permission",
        unlocks = "Crash and exit records for every app, plus system state such as " +
            "which apps used the microphone, camera or location.",
        adbPermission = "android.permission.DUMP",
    ),
    READ_LOGS(
        id = "read_logs",
        tier = Tier.ADB,
        label = "READ_LOGS permission",
        unlocks = "Crash reports from native system services, kernel crashes from the " +
            "previous boot, and unexpected restarts.",
        adbPermission = "android.permission.READ_LOGS",
    ),
    BATTERY_STATS(
        id = "battery_stats",
        tier = Tier.ADB,
        label = "BATTERY_STATS permission",
        unlocks = "Per-app power use, wakelocks and sensor time, to explain abnormal battery drain.",
        adbPermission = "android.permission.BATTERY_STATS",
    );

    companion object {
        fun fromId(id: String): Capability? = entries.firstOrNull { it.id == id }
    }
}
