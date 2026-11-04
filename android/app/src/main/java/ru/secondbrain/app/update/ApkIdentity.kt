package ru.secondbrain.app.update

/** What the installer would see in an APK, read with `PackageManager.getPackageArchiveInfo`. */
data class ApkIdentity(val packageName: String?, val longVersionCode: Long, val signerSha256: List<String>)

/**
 * The downloaded APK must be *this* app, newer than what is installed, and signed with the key the installed
 * app is signed with — otherwise Android would refuse it anyway, but a mismatch here means the release feed
 * or the file was tampered with, and we must not even hand it to the installer. Returns a Russian error
 * message for the owner, or null when the APK may be installed. Pure: both digest lists come from the caller.
 */
fun verifyApkIdentity(apk: ApkIdentity, installedPackage: String, installedVersionCode: Long,
                      installedSigners: List<String>): String? {
    if (apk.packageName != installedPackage) {
        return "Файл обновления — не это приложение (${apk.packageName ?: "пакет не прочитан"})"
    }
    if (apk.longVersionCode <= installedVersionCode) {
        return "Файл обновления не новее установленной версии (${apk.longVersionCode} ≤ $installedVersionCode)"
    }
    val expected = installedSigners.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
    val actual = apk.signerSha256.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
    if (expected.isEmpty()) return "Не удалось прочитать подпись установленного приложения"
    if (actual.isEmpty()) return "Файл обновления не подписан или подпись не прочитана"
    if (actual != expected) return "Подпись файла обновления не совпадает с подписью установленного приложения"
    return null
}
