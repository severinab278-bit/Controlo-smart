package com.controleuniversal.smartpro

import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1B2940)
private val Cyan = Color(0xFF28D7E5)
private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

private enum class TvBrand(val label: String) {
    ROKU("Roku"), SAMSUNG("Samsung Smart TV"), LG("LG webOS"), ANDROID("Android TV / Google TV"), FIRE("Amazon Fire TV"), OTHER("Outro / TV Box")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel)) { RemoteApp() } }
    }
}

@Composable
private fun RemoteApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var brand by remember { mutableStateOf(TvBrand.ROKU) }
    var ip by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Digite o IP da TV ou procure aparelhos na rede Wi-Fi.") }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var samsungSocket by remember { mutableStateOf<WebSocket?>(null) }
    var lgSocket by remember { mutableStateOf<WebSocket?>(null) }
    var samsungReady by remember { mutableStateOf(false) }
    var lgReady by remember { mutableStateOf(false) }
    val ir = remember { context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager }
    val hasIr = remember { ir?.hasIrEmitter() == true }
    val prefs = remember { context.getSharedPreferences("tv_pairing", Context.MODE_PRIVATE) }

    fun report(message: String) { status = message }
    fun runIo(block: suspend () -> Unit) { scope.launch { busy = true; try { block() } catch (e: Exception) { report("Falha: ${e.message ?: "não foi possível conectar"}") } finally { busy = false } } }

    fun connect() {
        val host = ip.trim().removePrefix("http://").removePrefix("https://").substringBefore(":")
        if (host.isBlank()) { report("Digite o endereço IP da TV, por exemplo 192.168.1.50."); return }
        ip = host
        when (brand) {
            TvBrand.ROKU -> runIo {
                val result = withContext(Dispatchers.IO) { rokuRequest(host, "GET", "/query/device-info") }
                connected = result.first in 200..299
                report(if (connected) "Conectado ao Roku. Os botões agora enviam comandos reais pela rede local." else "Roku não respondeu (${result.first}). Ative Controle por aplicativos móveis / acesso à rede nas configurações do Roku.")
            }
            TvBrand.SAMSUNG -> {
                val token = prefs.getString("samsung_$host", null)
                val name = Base64.getEncoder().encodeToString("ControleUniversalSmartPro".toByteArray())
                val scheme = "ws"
                val port = 8001
                val tokenPart = token?.let { "&token=$it" } ?: ""
                val req = Request.Builder().url("$scheme://$host:$port/api/v2/channels/samsung.remote.control?name=$name$tokenPart").build()
                samsungSocket?.close(1000, "reconectar")
                samsungReady = false
                samsungSocket = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { report("Solicitação enviada à Samsung. Aceite o controle na tela da TV, se aparecer.") }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val root = JSONObject(text)
                            if (root.optString("event") == "ms.channel.connect") {
                                val newToken = root.optJSONObject("data")?.optString("token")
                                if (!newToken.isNullOrBlank() && newToken != "null") prefs.edit().putString("samsung_$host", newToken).apply()
                                samsungReady = true; connected = true
                                report("Samsung conectada. Comandos de navegação, volume e mídia disponíveis.")
                            } else if (root.optString("event").contains("error", true)) report("A TV Samsung recusou a conexão. Confira se o controle por IP está permitido.")
                        } catch (_: Exception) { }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { samsungReady = false; connected = false; report("Samsung: conexão não concluída. Confirme IP, mesma rede e aceite o pedido na TV.") }
                })
            }
            TvBrand.LG -> {
                val savedKey = prefs.getString("lg_$host", null)
                val scheme = "ws://$host:3000"
                val req = Request.Builder().url(scheme).build()
                lgSocket?.close(1000, "reconectar"); lgReady = false
                lgSocket = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val manifest = JSONObject().put("manifest", JSONObject().put("manifestVersion", 1).put("appVersion", "1.1").put("signed", JSONObject().put("created", "20240101").put("appId", "com.controleuniversal.smartpro").put("vendorId", "OpenAI").put("localizedAppNames", JSONObject().put("", "Controle Universal Smart Pro")).put("permissions", org.json.JSONArray(listOf("CONTROL_AUDIO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST"))))).put("type", "register").put("id", "register_0")
                        if (savedKey != null) manifest.put("client-key", savedKey)
                        webSocket.send(manifest.toString())
                        report("Pedido de pareamento enviado à LG. Confirme na tela da TV.")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val root = JSONObject(text)
                            if (root.optString("type") == "registered") {
                                val key = root.optJSONObject("payload")?.optString("client-key") ?: ""
                                if (key.isNotBlank()) prefs.edit().putString("lg_$host", key).apply()
                                lgReady = true; connected = true; report("LG webOS conectada. Comandos de volume e energia disponíveis.")
                            } else if (root.optString("type") == "response" && root.optString("id") == "register_0") report("Aguardando autorização na TV LG.")
                        } catch (_: Exception) { }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lgReady = false; connected = false; report("LG: não conectou. Confira se o controle por rede está habilitado e aceite o pareamento.") }
                })
            }
            TvBrand.ANDROID -> report("Android TV / Google TV usa pareamento seguro próprio (código exibido na TV). Esta versão ainda não inclui o protocolo Remote v2; não vou fingir que os botões funcionam.")
            TvBrand.FIRE -> report("Fire TV exige ADB pela rede e autorização na própria TV. Esta versão ainda não implementa o pareamento ADB; ative Depuração ADB apenas se for usar um cliente compatível.")
            TvBrand.OTHER -> report("TV Box genérica: o protocolo varia por fabricante. Informe o modelo e veja se oferece Roku ECP, Android TV Remote ou ADB.")
        }
    }

    fun send(key: String) {
        val host = ip.trim()
        if (!connected) { report("Conecte à TV primeiro."); return }
        runIo {
            when (brand) {
                TvBrand.ROKU -> {
                    val path = "/keypress/${rokuKey(key)}"
                    val result = withContext(Dispatchers.IO) { rokuRequest(host, "POST", path) }
                    report(if (result.first in 200..299) "Comando $key enviado ao Roku." else "Roku recusou $key (HTTP ${result.first}).")
                }
                TvBrand.SAMSUNG -> {
                    val code = samsungKey(key)
                    val payload = JSONObject().put("method", "ms.remote.control").put("params", JSONObject().put("Cmd", "Click").put("DataOfCmd", code).put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
                    val ok = samsungSocket?.send(payload.toString()) == true
                    report(if (ok) "Comando $key enviado à Samsung." else "Samsung desconectada. Conecte novamente.")
                }
                TvBrand.LG -> {
                    val uri = when (key) { "VOL+" -> "audio/volumeUp"; "VOL-" -> "audio/volumeDown"; "MUTE" -> "audio/setMute"; "POWER" -> "system/turnOff"; else -> "" }
                    if (uri.isEmpty()) report("A LG não oferece esse comando genérico por esta conexão. Navegação precisa do canal de entrada de controle webOS.")
                    else {
                        val payload = JSONObject().put("type", "request").put("id", "cmd_${System.currentTimeMillis()}").put("uri", "ssap://$uri")
                        if (key == "MUTE") payload.put("payload", JSONObject().put("mute", true))
                        val ok = lgSocket?.send(payload.toString()) == true
                        report(if (ok) "Comando $key enviado à LG." else "LG desconectada. Conecte novamente.")
                    }
                }
                else -> report("Este protocolo ainda não está disponível para ${brand.label} nesta versão.")
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(Navy).padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("CONTROLE UNIVERSAL", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Smart Pro", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            Text("Controle por rede local • Roku, Samsung e LG", color = Color.LightGray, fontSize = 13.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MARCA / SISTEMA", color = Cyan, fontWeight = FontWeight.Bold)
                    TvBrand.values().forEach { b ->
                        FilterChip(selected = brand == b, onClick = { brand = b; connected = false; status = "Selecionado: ${b.label}. Informe o IP e conecte." }, label = { Text(b.label) })
                    }
                    OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP da TV (ex.: 192.168.1.50)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { connect() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("CONECTAR À TV") }
                    Text(status, color = if (connected) Color(0xFF8CE99A) else Color.LightGray, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("CONTROLE REMOTO", color = Cyan, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("▲") { send("UP") } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("◀") { send("LEFT") }; RemoteButton("OK", true) { send("OK") }; RemoteButton("▶") { send("RIGHT") } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("▼") { send("DOWN") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("BACK", modifier = Modifier.weight(1f)) { send("BACK") }; RemoteButton("HOME", modifier = Modifier.weight(1f)) { send("HOME") }; RemoteButton("POWER", modifier = Modifier.weight(1f)) { send("POWER") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("VOL−", modifier = Modifier.weight(1f)) { send("VOL-") }; RemoteButton("MUTE", modifier = Modifier.weight(1f)) { send("MUTE") }; RemoteButton("VOL+", modifier = Modifier.weight(1f)) { send("VOL+") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { RemoteButton("CH−", modifier = Modifier.weight(1f)) { send("CH-") }; RemoteButton("CH+", modifier = Modifier.weight(1f)) { send("CH+") }; RemoteButton("PLAY/PAUSE", modifier = Modifier.weight(1f)) { send("PLAY") } }
                    Text("Os botões só enviam comandos quando a conexão do protocolo selecionado estiver ativa.", color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OUTROS MÉTODOS", color = Cyan, fontWeight = FontWeight.Bold)
                    Text(if (hasIr) "Este celular informa ter emissor infravermelho, mas ainda é necessário selecionar códigos IR exatos para a marca/modelo." else "Este celular não informa emissor infravermelho integrado. O A17 normalmente precisa de acessório externo compatível para IR." , color = Color.White, fontSize = 12.sp)
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text("Abrir configurações Bluetooth") }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text("Abrir configurações Wi-Fi") }
                }
            }
        }
        item { Text("Compatibilidade real nesta edição: Roku ECP; Samsung Smart TV por WebSocket com aprovação na TV; LG webOS por WebSocket com aprovação na TV (volume e desligar). Android/Google TV, Fire TV, Bluetooth HID universal e códigos IR específicos ainda precisam dos seus protocolos/pareamento. Não existe um comando único que funcione em todas as marcas.", color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable
private fun RemoteButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(46.dp), colors = ButtonDefaults.buttonColors(containerColor = if (primary) Cyan else Color(0xFF30415A), contentColor = if (primary) Navy else Color.White), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
}

private fun rokuRequest(host: String, method: String, path: String): Pair<Int, String> {
    val request = Request.Builder().url("http://$host:8060$path").apply { if (method == "POST") post(okhttp3.RequestBody.create(null, ByteArray(0))) }.build()
    http.newCall(request).execute().use { return it.code to (it.body?.string() ?: "") }
}

private fun rokuKey(key: String): String = when (key) {
    "UP" -> "Up"; "DOWN" -> "Down"; "LEFT" -> "Left"; "RIGHT" -> "Right"; "OK" -> "Select"; "BACK" -> "Back"; "HOME" -> "Home"; "POWER" -> "Power"; "VOL+" -> "VolumeUp"; "VOL-" -> "VolumeDown"; "MUTE" -> "VolumeMute"; "PLAY" -> "Play"; "CH+" -> "ChannelUp"; "CH-" -> "ChannelDown"; else -> key
}

private fun samsungKey(key: String): String = when (key) {
    "UP" -> "KEY_UP"; "DOWN" -> "KEY_DOWN"; "LEFT" -> "KEY_LEFT"; "RIGHT" -> "KEY_RIGHT"; "OK" -> "KEY_ENTER"; "BACK" -> "KEY_RETURN"; "HOME" -> "KEY_HOME"; "POWER" -> "KEY_POWER"; "VOL+" -> "KEY_VOLUP"; "VOL-" -> "KEY_VOLDOWN"; "MUTE" -> "KEY_MUTE"; "PLAY" -> "KEY_PLAY"; "CH+" -> "KEY_CHUP"; "CH-" -> "KEY_CHDOWN"; else -> "KEY_$key"
}
