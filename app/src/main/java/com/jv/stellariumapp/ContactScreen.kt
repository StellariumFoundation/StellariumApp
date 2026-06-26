package com.jv.stellariumapp

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Composable
fun ContactScreen() {
    // UI State
    var contact by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    
    // Dialog State
    var showOrbotDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // --- LOGIC: START TRANSMISSION ---
    fun startTransmission() {
        isSending = true
        showOrbotDialog = false
        
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { 
                statusMessage = "Establishing secure Tor circuit..." 
            }

            // Send via our native SOCKS5 zero-leak implementation
            val (isSuccess, resultMessage) = sendViaTorSecurely(contact, message)

            // Final Status Update
            withContext(Dispatchers.Main) {
                isSending = false
                if (isSuccess) {
                    statusMessage = "Transmission Complete.\nSecure Message Sent."
                    Toast.makeText(context, "Secure Transmission Complete", Toast.LENGTH_LONG).show()
                    contact = ""
                    message = ""
                } else {
                    statusMessage = "Secure channel failed.\nReason: $resultMessage"
                    Toast.makeText(context, "Connection Failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- UI LAYOUT ---
    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Secure Comms", 
            style = MaterialTheme.typography.headlineMedium, 
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Send Intelligence or Directives to the Stellarium Foundation.\n(Routes via Tor Network)",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = contact,
            onValueChange = { contact = it },
            label = { Text("Contact Email (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Intel / Message") },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            minLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        if (statusMessage.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusMessage,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                if (message.isNotBlank()) {
                    scope.launch(Dispatchers.IO) {
                        val isTorAvailable = checkOrbotConnection()
                        withContext(Dispatchers.Main) {
                            if (isTorAvailable) {
                                startTransmission()
                            } else {
                                showOrbotDialog = true
                            }
                        }
                    }
                } else {
                    Toast.makeText(context, "Message content is required.", Toast.LENGTH_SHORT).show()
                }
            },
            enabled = !isSending,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Anonymizing...")
            } else {
                Text("Broadcast via Tor")
            }
        }
    }

    // --- DIALOG: TOR MISSING ---
    if (showOrbotDialog) {
        AlertDialog(
            onDismissRequest = { showOrbotDialog = false },
            title = { Text("Tor Network Not Detected") },
            text = { 
                Text("This transmission requires the Tor network. Please install and connect Orbot to securely route your message.") 
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://orbot.app/"))
                        context.startActivity(intent)
                        showOrbotDialog = false
                    }
                ) {
                    Text("Install Orbot")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOrbotDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =========================================================================
// ====================     NETWORKING LAYER     ===========================
// =========================================================================

// --- 1. TOR CHECKER ---
fun checkOrbotConnection(): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", 9050), 500)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}

// --- 2. RAW SOCKS5 HTTPS REQUEST (Static Forms via Tor) ---
fun sendViaTorSecurely(contact: String, message: String): Pair<Boolean, String> {
    var socket: Socket? = null
    var sslSocket: SSLSocket? = null

    try {
        // 1. Construct JSON Payload for Static Forms
        val jsonPayload = JSONObject()
        jsonPayload.put("apiKey", "sf_0491b9b3fbb2f4f489b6a319")
        
        // Static forms standard fields: name, email, message
        jsonPayload.put("email", if (contact.contains("@")) contact else "no-reply@stellarium.app")
        jsonPayload.put("name", if (contact.isNotBlank() && !contact.contains("@")) contact else "Anonymous")
        jsonPayload.put("message", message)
        
        val payloadBytes = jsonPayload.toString().toByteArray(Charsets.UTF_8)
        
        // Static Forms Endpoint Data
        val targetHost = "api.staticforms.dev"
        val targetPort = 443
        val path = "/submit"

        // 2. Create Raw SOCKS5 Socket directly to Orbot
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
        socket = Socket(proxy)
        socket.soTimeout = 20000 
        
        // CRITICAL: createUnresolved() prevents local DNS lookups. 
        socket.connect(InetSocketAddress.createUnresolved(targetHost, targetPort), 20000)

        // 3. Wrap SOCKS socket in an SSL/TLS Handshake
        val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        sslSocket = sslSocketFactory.createSocket(socket, targetHost, targetPort, true) as SSLSocket
        
        // CRITICAL FIX: Force SNI (Server Name Indication)
        try {
            val setHostnameMethod = sslSocket.javaClass.getMethod("setHostname", String::class.java)
            setHostnameMethod.invoke(sslSocket, targetHost)
        } catch (e: Exception) {
            Log.w("SecureComms", "Could not set SNI via reflection: ${e.message}")
        }
        
        // Start SSL Handshake
        sslSocket.startHandshake()

        // 4. Write Raw HTTP POST Request directly to the secure stream
        val out = sslSocket.outputStream
        val writer = out.bufferedWriter(Charsets.UTF_8)
        
        writer.write("POST $path HTTP/1.1\r\n")
        writer.write("Host: $targetHost\r\n")
        // Disguise as a standard Firefox browser
        writer.write("User-Agent: Mozilla/5.0 (Windows NT 10.0; rv:102.0) Gecko/20100101 Firefox/102.0\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Accept: application/json\r\n")
        writer.write("Content-Length: ${payloadBytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n") // End of headers
        writer.flush()
        
        // Write the body
        out.write(payloadBytes)
        out.flush()

        // 5. Read HTTP Response
        val reader = sslSocket.inputStream.bufferedReader(Charsets.UTF_8)
        val responseLine = reader.readLine() ?: "Empty Response from Server"
        
        Log.d("SecureComms", "Tor Response: $responseLine")
        
        // Static Forms should return 200 OK on success
        return if (responseLine.contains("200")) {
            Pair(true, responseLine)
        } else {
            // Returns exactly why it was rejected
            Pair(false, responseLine)
        }

    } catch (e: Exception) {
        Log.e("SecureComms", "Tor Transmission Failed: ${e.javaClass.simpleName} - ${e.message}")
        return Pair(false, "${e.javaClass.simpleName}: ${e.message}")
    } finally {
        // Clean up connections
        try { sslSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}