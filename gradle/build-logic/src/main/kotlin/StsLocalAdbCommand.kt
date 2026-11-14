/** Keep explicit Gradle targets intact until the local manager validates them. */
internal fun localAdbPrefix(pythonExecutable: String, deviceSerial: String): List<String> = buildList {
    addAll(listOf(pythonExecutable, "-m", "scripts.tools.local_adb"))
    if (deviceSerial.isNotEmpty()) {
        add("--expected-serial")
        add(deviceSerial)
    }
}
