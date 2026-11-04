package com.example.muzerion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PcQrPairingButton(
    accessKey: String?
) {
    val scope = rememberCoroutineScope()

    var busy by remember {
        mutableStateOf(false)
    }

    var message by remember {
        mutableStateOf("")
    }

    val scanner = rememberLauncherForActivityResult(
        ScanContract()
    ) { result ->
        val contents = result.contents

        if (contents != null) {
            val key = accessKey

            if (key == null) {
                message = tr(R.string.msg_9b7b21b50e)
            } else {
                busy = true
                message = tr(R.string.msg_072b0b4738)

                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            sendMuzerionPairing(
                                contents = contents,
                                key = key
                            )
                        }

                        message =
                            tr(R.string.msg_e64e219907)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        message = failure.message
                            ?: tr(R.string.msg_140a6eae85)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    Column {
        TextButton(
            enabled = accessKey != null && !busy,
            onClick = {
                message = ""

                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(
                            ScanOptions.QR_CODE
                        )
                        .setPrompt(
                            tr(R.string.msg_1fd6425a48) +
                                    tr(R.string.msg_259b67a245)
                        )
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                )
            }
        ) {
            Text(
                if (busy) {
                    tr(R.string.msg_279db93ad9)
                } else {
                    tr(R.string.msg_24acf7b07b)
                }
            )
        }

        if (message.isNotEmpty()) {
            Text(
                text = message,
                color = MuzerionStyle.text
            )
        }
    }
}

private fun sendMuzerionPairing(
    contents: String,
    key: String
) {
    if (contents.length > 2048) {
        throw IOException(tr(R.string.msg_f869f82f28))
    }

    val qr = try {
        JSONObject(contents)
    } catch (_: Exception) {
        throw IOException(tr(R.string.msg_f869f82f28))
    }

    val host = qr.optString("host")
    val port = qr.optInt("port", -1)
    val token = qr.optString("token")

    val parts = host.split('.')

    val numbers = parts.mapNotNull {
        it.toIntOrNull()
    }

    val valid =
        qr.optInt("muzerion") == 1 &&
                parts.size == 4 &&
                numbers.size == 4 &&
                numbers.all { it in 0..255 } &&
                port in 1..65535 &&
                token.matches(Regex("[0-9a-f]{32}"))

    if (!valid) {
        throw IOException(tr(R.string.msg_c352343af9))
    }

    val address = InetAddress.getByAddress(
        numbers.map { it.toByte() }.toByteArray()
    )

    if (!address.isSiteLocalAddress) {
        throw IOException(
            tr(R.string.msg_da2f0f1fdf)
        )
    }

    try {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(address, port),
                5000
            )

            socket.soTimeout = 5000

            val payload = JSONObject()
                .put("token", token)
                .put("key", key)
                .toString() + "\n"

            val output = socket.getOutputStream()

            output.write(
                payload.toByteArray(Charsets.UTF_8)
            )

            output.flush()

            val input = socket.getInputStream()
            val answer = StringBuilder()

            for (index in 0 until 32) {
                val value = input.read()

                if (value < 0 || value == 10) break

                answer.append(value.toChar())
            }

            if (answer.toString() != "OK") {
                throw IOException(
                    tr(R.string.msg_64a290eb47)
                )
            }
        }
    } catch (_: java.net.ConnectException) {
        throw IOException(
            tr(R.string.msg_2315e045ff) +
                    tr(R.string.msg_c4cbf02b54)
        )
    } catch (_: java.net.SocketTimeoutException) {
        throw IOException(
            tr(R.string.msg_24c50b508a) +
                    tr(R.string.msg_2dcbed089a)
        )
    }
}