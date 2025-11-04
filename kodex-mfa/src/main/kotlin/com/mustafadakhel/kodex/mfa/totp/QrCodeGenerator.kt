package com.mustafadakhel.kodex.mfa.totp

import qrcode.QRCode
import java.util.Base64

public class QrCodeGenerator {

    public fun generateDataUri(otpauthUri: String, size: Int = 300): String {
        require(size in 100..1000) { "QR code size must be between 100 and 1000 pixels" }

        val cellSize = calculateCellSize(size)
        val qrCode = QRCode.ofSquares()
            .withSize(cellSize)
            .build(otpauthUri)

        val pngBytes = qrCode.render().getBytes()

        val base64 = Base64.getEncoder().encodeToString(pngBytes)
        return "data:image/png;base64,$base64"
    }

    public fun generatePngBytes(otpauthUri: String, size: Int = 300): ByteArray {
        require(size in 100..1000) { "QR code size must be between 100 and 1000 pixels" }

        val cellSize = calculateCellSize(size)
        val qrCode = QRCode.ofSquares()
            .withSize(cellSize)
            .build(otpauthUri)

        return qrCode.render().getBytes()
    }

    private fun calculateCellSize(targetSize: Int): Int {
        return (targetSize / 33).coerceAtLeast(8)
    }
}
