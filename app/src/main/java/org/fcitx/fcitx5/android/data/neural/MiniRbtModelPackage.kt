/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.neural

/** Immutable package identity. All bytes, including the manifest, are pinned before loading. */
internal object MiniRbtModelPackage {
    const val BUNDLED_ASSET = "neural/axiang-minirbt-h256-mlm-fp32.zip"
    val spec = MiniRbtPackageSpec(
        url = "https://github.com/xisungod/fcitx5-android/releases/download/axiang-minirbt-h256-mlm-v1/axiang-minirbt-h256-mlm-fp32.zip",
        archiveSha256 = "d88fde3cff6ab399eaca90f360f2668d8be60925537d4c7a403a8b35da0498aa",
        archiveBytes = 38567708,
        files = mapOf(
            "LICENSE" to MiniRbtExpectedFile(11357L, "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
            "MODEL_CARD.md" to MiniRbtExpectedFile(974L, "667be60e0b89d63d308d3d1e76f906d056ad4a2c56e6a2f785544098e220f3eb"),
            "manifest.json" to MiniRbtExpectedFile(2218L, "ad0eb37d7492023da83c5812a8e4851201b994b3f33dc4631d0ad388cbd1fcd4"),
            "model.onnx" to MiniRbtExpectedFile(41591359L, "b9db720f94a4c949eb8b9197b13e046b1aba320155ac2537543249e8d95f6a90"),
            "vocab.txt" to MiniRbtExpectedFile(109540L, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c")
        )
    )
}

internal data class MiniRbtExpectedFile(val bytes: Long, val sha256: String)

internal data class MiniRbtPackageSpec(
    val url: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val files: Map<String, MiniRbtExpectedFile>
) {
    fun validate() {
        require(url.startsWith("https://"))
        require(archiveBytes in 1..MAX_ARCHIVE_BYTES)
        require(archiveSha256.matches(Regex("[0-9a-f]{64}")))
        require(files.keys == setOf("model.onnx", "vocab.txt", "LICENSE", "MODEL_CARD.md", "manifest.json"))
        require(files.values.all { it.bytes in 1..MAX_FILE_BYTES && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        require(files.values.sumOf { it.bytes } <= MAX_TOTAL_BYTES)
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    }
}
