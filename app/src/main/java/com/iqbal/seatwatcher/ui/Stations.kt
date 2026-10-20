package com.iqbal.seatwatcher.ui

/**
 * Suggestions only. The site's full station list is not verified, so any text is allowed;
 * "Load trains" confirms whether the site accepts it. Spelling follows the site's style
 * (e.g. "Chattogram", not "Chittagong").
 */
val STATION_SUGGESTIONS = listOf(
    "Dhaka", "Chattogram", "Cox's Bazar", "Sylhet", "Rajshahi", "Khulna", "Mymensingh", "Rangpur",
    "Dinajpur", "Cumilla", "Feni", "Laksam", "Noakhali", "Chandpur", "Akhaura", "Brahmanbaria",
    "Bhairab Bazar", "Narsingdi", "Srimangal", "Kulaura", "Kishoreganj", "Jamalpur Town", "Tangail",
    "Jashore", "Benapole", "Kushtia Court", "Ishwardi Bypass", "Natore", "Bogura", "Santahar",
    "Saidpur", "Parbatipur", "Nilphamari", "Thakurgaon Road", "Panchagarh", "Lalmonirhat", "Kurigram",
    "Gaibandha", "Joypurhat", "Chapainawabganj", "Sirajganj", "Rajbari", "Faridpur", "Bhanga",
    "Netrakona", "Mohanganj", "Dhaka Cantonment", "Biman Bandar", "Joydebpur", "Gafargaon",
)

/** Common class labels as shown on the site, used before "Load trains" has run. */
val KNOWN_CLASSES = listOf("AC_S", "AC_B", "SNIGDHA", "S_CHAIR", "F_SEAT", "F_BERTH", "F_CHAIR", "AC_CHAIR", "SHOVAN", "SHULOV")
