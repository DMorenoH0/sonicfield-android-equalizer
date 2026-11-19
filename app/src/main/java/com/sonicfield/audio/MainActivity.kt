package com.sonicfield.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val Bg=Color(0xFF090B10); private val Card=Color(0xFF141923); private val Mint=Color(0xFF69E6C1)
private val freqs=listOf("31","62","125","250","500","1k","2k","4k","8k","16k")
class MainActivity : ComponentActivity() {
    private val output = mutableStateOf("Altavoces del teléfono")
    private val effectStatus = mutableStateOf("Reproduce audio para comprobar compatibilidad")
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var player: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private val sessionEffects = SessionEffects()
    private val listeningTest = ListeningTestPlayer()
    private var processingEnabled = true
    private var activeBands = List(10) { 0f }
    private var bassWanted = false
    private var spatialWanted = false
    private var limiterWanted = true
    private var effectStrength = 450
    private var requestedPreampDb = 0f

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshOutput()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshOutput()
    }
    private val routeListener = AudioRouting.OnRoutingChangedListener { route ->
        route.routedDevice?.let { output.value = describe(it) } ?: refreshOutput()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshOutput()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Mint)) {
                SonicApp(output.value, ::play, ::playTestTone, ::applyPreamp, { values, on -> applyBands(values, on) },
                    { bass, spatial, limiter, strength -> configureEffects(bass, spatial, limiter, strength) }, effectStatus.value,
                    { openNowPlayingSettings(this) })
            }
        }
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        releasePlayer()
        super.onDestroy()
    }

    private fun refreshOutput() {
        player?.routedDevice?.let { output.value = describe(it); return }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        val external = outputs.firstOrNull { it.type in setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        ) }
        output.value = external?.let(::describe) ?: "Altavoz integrado"
    }

    private fun describe(device: AudioDeviceInfo) =
        "${kind(device.type)} · ${device.productName.toString().ifBlank { "Salida detectada" }}"

    private fun kind(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Salida Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Auriculares Bluetooth"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Altavoz Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "Audio USB"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz integrado"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular del teléfono"
        else -> "Salida de audio"
    }

    private fun play(uri: Uri) {
        releasePlayer()
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.addOnRoutingChangedListener(routeListener, null)
            mediaPlayer.setDataSource(this, uri)
            mediaPlayer.setOnPreparedListener { prepared ->
                try { equalizer = Equalizer(0, prepared.audioSessionId).apply { enabled = processingEnabled } }
                catch (_: Exception) { equalizer = null }
                applyBands(activeBands, processingEnabled)
                effectStatus.value = sessionEffects.attach(prepared.audioSessionId, bassWanted && processingEnabled, spatialWanted && processingEnabled, limiterWanted && processingEnabled, effectStrength)
                prepared.routedDevice?.let { output.value = describe(it) }
                prepared.start()
            }
            mediaPlayer.setOnErrorListener { _, _, _ -> effectStatus.value = "No se pudo reproducir este archivo"; true }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            effectStatus.value = "No se pudo abrir este archivo de audio"
            releasePlayer()
        }
    }

    private fun applyBands(values: List<Float>, enabled: Boolean) {
        processingEnabled = enabled
        activeBands = values.map { it.coerceIn(-6f, 4f) }
        applyPlaybackGain()
        val effect = equalizer ?: return
        try {
            effect.enabled = enabled
            val range = effect.bandLevelRange
            val used = mutableSetOf<Short>()
            activeBands.forEachIndexed { index, gainDb ->
                val band = effect.getBand(PresetCatalog.centerFrequenciesHz[index] * 1000)
                if (used.add(band)) {
                    val millibels = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    effect.setBandLevel(band, millibels)
                }
            }
        } catch (_: Exception) { effectStatus.value = "El ecualizador nativo no está disponible para esta sesión" }
    }

    private fun applyPreamp(value: Float) { requestedPreampDb = value.coerceIn(-12f, 0f); applyPlaybackGain() }
    private fun applyPlaybackGain() {
        val db = PresetCatalog.safePreampDb(activeBands, requestedPreampDb)
        val volume = Math.pow(10.0, db / 20.0).toFloat()
        player?.setVolume(volume, volume)
    }

    private fun playTestTone(frequencyHz: Int) = listeningTest.play(frequencyHz, activeBands, processingEnabled, requestedPreampDb)

    private fun configureEffects(bass: Boolean, spatial: Boolean, limiter: Boolean, strength: Int) {
        bassWanted = bass
        spatialWanted = spatial
        effectStrength = strength.coerceIn(0, 1000)
        if (player == null) {
            effectStatus.value = "Abre y reproduce audio para probar compatibilidad"
            return
        }
        player?.let { effectStatus.value = sessionEffects.attach(it.audioSessionId, bassWanted && processingEnabled, spatialWanted && processingEnabled, limiterWanted && processingEnabled, effectStrength) }
    }

    private fun releasePlayer() {
        sessionEffects.release()
        try { equalizer?.release() } catch (_: Exception) { }
        equalizer = null
        try { player?.removeOnRoutingChangedListener(routeListener); player?.release() } catch (_: Exception) { }
        player = null
        refreshOutput()
    }
}
private val presets=PresetCatalog.names
@Composable private fun SonicApp(device:String,play:(Uri)->Unit,playTone:(Int)->Unit,setPreamp:(Float)->Unit,apply:(List<Float>,Boolean)->Unit,configureEffects:(Boolean,Boolean,Boolean,Int)->Unit,effectStatus:String,onEnableMusicDetection:()->Unit){val ctx=androidx.compose.ui.platform.LocalContext.current;val prefs=remember{ctx.getSharedPreferences("sonicfield",0)};var enabled by remember{mutableStateOf(prefs.getBoolean("enabled",true))};var selected by remember{mutableStateOf(prefs.getString("preset","Equilibrado")?:"Equilibrado")};var page by remember{mutableIntStateOf(0)};var mega by remember{mutableStateOf(prefs.getBoolean("bass",false))};var space by remember{mutableStateOf(prefs.getBoolean("space",false))};var effectStrength by remember{mutableIntStateOf(prefs.getInt("effect_strength",450))};var preampDb by remember{mutableFloatStateOf(prefs.getFloat("preamp_db",0f))};var limiter by remember{mutableStateOf(prefs.getBoolean("limiter",true))};var playback by remember{mutableStateOf("Selecciona un archivo de audio para reproducir y ecualizar")};val bands=remember{mutableStateListOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f)}
 val exportPreset=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)try{val data=org.json.JSONObject().put("schemaVersion",1).put("name",selected).put("centerFrequenciesHz",org.json.JSONArray(PresetCatalog.centerFrequenciesHz.toList())).put("gainsDb",org.json.JSONArray(bands.toList()));ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(data.toString(2))}}catch(_:Exception){playback="No se pudo exportar el preset"}}
 val importPreset=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)try{val text=ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}?:error("empty");require(text.length<16384);val data=org.json.JSONObject(text);require(data.optInt("schemaVersion")==1);val a=data.getJSONArray("gainsDb");require(a.length()==10);val values=(0 until 10).map{a.getDouble(it).toFloat().also{v->require(v.isFinite())}.coerceIn(-6f,4f)};values.forEachIndexed{i,v->bands[i]=v};selected=data.optString("name","Importado").take(48).ifBlank{"Importado"};apply(bands,enabled);prefs.edit().putString("preset",selected).putString("bands",org.json.JSONArray(bands.toList()).toString()).apply()}catch(_:Exception){playback="El archivo no contiene un preset SonicField válido"}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){playback=uri.lastPathSegment?:"Audio cargado";play(uri)}}
 LaunchedEffect(enabled){apply(bands.toList(),enabled);configureEffects(mega,space,limiter,effectStrength)};LaunchedEffect(preampDb){prefs.edit().putFloat("preamp_db",preampDb).apply();setPreamp(preampDb)};LaunchedEffect(mega,space,limiter,effectStrength){prefs.edit().putBoolean("bass",mega).putBoolean("space",space).putBoolean("limiter",limiter).putInt("effect_strength",effectStrength).apply();configureEffects(mega,space,limiter,effectStrength)};LaunchedEffect(Unit){val saved=prefs.getString("bands",null);if(saved==null)setPreset(selected,bands) else try{val a=org.json.JSONArray(saved);for(i in 0 until minOf(10,a.length()))bands[i]=a.getDouble(i).toFloat()}catch(_:Exception){setPreset(selected,bands)};apply(bands,enabled)}
 Scaffold(containerColor=Bg,bottomBar={NavigationBar(containerColor=Card){listOf("Inicio","EQ","Efectos","Perfil").forEachIndexed{i,s->NavigationBarItem(selected=page==i,onClick={page=i},icon={Text(when(i){0->"⌂";1->"≋";2->"◉";else->"◖"},color=if(page==i) Mint else Color.Gray)},label={Text(s)})}}}){pad->Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp)){
 Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=22.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("SONICFIELD",color=Mint,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold);Text(listOf("Tu sonido","Ecualizador","Espacio y claridad","Ajuste personal")[page],color=Color.White,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)};Image(painterResource(R.drawable.sonicfield_mark),contentDescription="Logo de SonicField",modifier=Modifier.size(44.dp))}
 when(page){0->{Panel{Text("SALIDA DETECTADA",color=Mint,style=MaterialTheme.typography.labelMedium);Text(device,color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text("Android informa los dispositivos conectados y, durante la reproducción, la ruta que usa esta sesión.",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)};NowPlayingCard(playback,onEnableMusicDetection);Panel{Text("REPRODUCTOR DE ESTA APP",color=Mint,style=MaterialTheme.typography.labelMedium);Text(playback,color=Color.White);Button(onClick={picker.launch(arrayOf("audio/*"))}){Text("↥  Abrir audio")};Text("La ecualización solo afecta audio abierto aquí.",color=Color.Gray,style=MaterialTheme.typography.bodySmall)};Panel{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column{Text("Procesamiento",color=Color.White,style=MaterialTheme.typography.titleLarge);Text("Efecto Equalizer del dispositivo",color=Color.Gray)};Switch(enabled,{enabled=it;prefs.edit().putBoolean("enabled",it).apply();apply(bands,it)})}};Panel{Text("PRESET ACTIVO",color=Mint,style=MaterialTheme.typography.labelMedium);var expanded by remember{mutableStateOf(false)};Box{TextButton(onClick={expanded=true}){Text(selected,color=Color.White,style=MaterialTheme.typography.titleLarge)};DropdownMenu(expanded,{expanded=false}){presets.forEach{DropdownMenuItem(text={Text(it)},onClick={selected=it;expanded=false;setPreset(it,bands);apply(bands,enabled);prefs.edit().putString("preset",it).putString("bands",org.json.JSONArray(bands.toList()).toString()).apply()})}}}};Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={page=1},modifier=Modifier.weight(1f)){Text("≋",color=Color.White);Text("  Ecualizador")};OutlinedButton(onClick={page=2},modifier=Modifier.weight(1f)){Text("◉",color=Mint);Text("  Efectos")}}}
 1->{Panel{Text("10 CONTROLES · −6 a +4 dB",color=Mint,style=MaterialTheme.typography.labelMedium);Text("El efecto nativo puede exponer menos bandas; controles cercanos se asignan a bandas disponibles.",color=Color.Gray,style=MaterialTheme.typography.bodySmall)};Panel{Text("Preamplificación segura · ${"%.1f".format(preampDb)} dB",color=Color.White);Slider(value=preampDb,valueRange=-12f..0f,onValueChange={preampDb=it;setPreamp(it)},colors=SliderDefaults.colors(thumbColor=Mint,activeTrackColor=Mint));Text("La salida se atenúa automáticamente según el mayor realce del EQ.",color=Color.Gray,style=MaterialTheme.typography.bodySmall)};freqs.forEachIndexed{i,f->Row(verticalAlignment=Alignment.CenterVertically){Text(f,color=Color.LightGray,modifier=Modifier.width(42.dp));Slider(value=bands[i],valueRange=-6f..4f,onValueChange={bands[i]=it;apply(bands,enabled)},modifier=Modifier.weight(1f),colors=SliderDefaults.colors(thumbColor=Mint,activeTrackColor=Mint));Text("${bands[i].toInt()} dB",color=Color.White,modifier=Modifier.width(44.dp))}};OutlinedButton(onClick={for(i in bands.indices)bands[i]=0f;apply(bands,enabled)}){Text("Restablecer")};OutlinedButton(onClick={prefs.edit().putString("bands",org.json.JSONArray(bands.toList()).toString()).apply()}){Text("Guardar EQ")};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={exportPreset.launch("sonicfield-preset.json")}){Text("Exportar")};OutlinedButton(onClick={importPreset.launch(arrayOf("application/json"))}){Text("Importar")}}}
 2->{Effect("Espacio 360","Virtualizador estéreo de Android ligado a la sesión de este reproductor.",space,{space=it;configureEffects(mega,space,limiter,effectStrength)},"Compatible cuando el dispositivo lo admita");Effect("Protección de picos","Limitador de muestra del procesador dinámico de Android (API 28+), si el dispositivo lo admite.",limiter,{limiter=it;configureEffects(mega,space,limiter,effectStrength)},"No es un limitador de pico verdadero");Effect("Mega bajos","Refuerzo mediante BassBoost nativo en la sesión del reproductor.",mega,{mega=it;configureEffects(mega,space,limiter,effectStrength)},"Compatibilidad según fabricante");Effect("Voz clara","Curva de ecualización centrada en presencia vocal; no separa voces de la mezcla.",selected=="Voz clara",{voice->if(voice){selected="Voz clara";setPreset("Voz clara",bands);apply(bands,enabled);prefs.edit().putString("preset",selected).putString("bands",org.json.JSONArray(bands.toList()).toString()).apply()}else if(selected=="Voz clara"){selected="Plano";setPreset("Plano",bands);apply(bands,enabled);prefs.edit().putString("preset",selected).putString("bands",org.json.JSONArray(bands.toList()).toString()).apply()}},"Curva EQ");Text("Los efectos usan la sesión del reproductor de SonicField; no procesan otras apps.",color=Color.Gray,style=MaterialTheme.typography.bodySmall)}
 else->{HearingPanel(bands.toList(),enabled,playTone,{index,gain->bands[index]=gain;apply(bands,enabled)},{prefs.edit().putString("bands",org.json.JSONArray(bands.toList()).toString()).putString("preset","Personalizado").apply();selected="Personalizado"})}
 };Spacer(Modifier.height(30.dp))
 }} }
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth().padding(bottom=14.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Card)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)}}
@Composable private fun Effect(name:String,desc:String,checked:Boolean,change:(Boolean)->Unit,note:String){Panel{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(name,color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);Text(desc,color=Color.LightGray,style=MaterialTheme.typography.bodySmall);Text(note,color=Mint,style=MaterialTheme.typography.labelSmall)};Switch(checked,change)}}}
private fun setPreset(name:String,b:MutableList<Float>){PresetCatalog.gains(name).forEachIndexed{i,x->b[i]=x}}







@Composable private fun HearingPanel(
    gains: List<Float>,
    enabled: Boolean,
    playTone: (Int) -> Unit,
    update: (Int, Float) -> Unit,
    save: () -> Unit,
) {
    val testFrequencies = listOf(125, 250, 500, 1_000, 2_000, 4_000, 8_000)
    var selectedIndex by remember { mutableIntStateOf(5) }
    val frequency = PresetCatalog.centerFrequenciesHz[selectedIndex]
    Panel {
        Text("PERFIL AUDITIVO PERSONAL", color = Mint, style = MaterialTheme.typography.labelMedium)
        Text("Ajuste subjetivo por tonos", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("Tonos cortos y atenuados. Mantén el volumen general bajo y detén la prueba si resulta molesta. No es una evaluación clínica.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
    testFrequencies.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { hz ->
                val index = PresetCatalog.centerFrequenciesHz.indexOf(hz)
                OutlinedButton(onClick = { selectedIndex = index; playTone(hz) }, modifier = Modifier.weight(1f)) { Text(if (hz >= 1_000) "${hz / 1_000} kHz" else "$hz Hz") }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Text("${frequency} Hz · ${"%.1f".format(gains[selectedIndex])} dB", color = Color.White)
    Slider(value = gains[selectedIndex], valueRange = -6f..4f,
        onValueChange = { update(selectedIndex, it) },
        colors = SliderDefaults.colors(thumbColor = Mint, activeTrackColor = Mint))
    OutlinedButton(onClick = save) { Text("Guardar perfil personal") }
    Text("El sistema no estima umbrales auditivos: solo guarda tus ajustes preferidos.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
}






