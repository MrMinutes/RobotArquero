package com.robotarquero.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Robot arquero - panel de control y marcador.
 *
 * Protocolo (texto, cada trama termina en salto de línea):
 *   app -> robot:  $V,<0-100>   velocidad
 *                  $R           reiniciar distancia y goles
 *   robot -> app:  $S,<distancia_mm>,<goles>,<velocidad>
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        const val LARGO_MM = 300        // largo del carril en mm: AJUSTAR al real
        const val TOTAL_TIROS = 5
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var socket: BluetoothSocket? = null
    private val envio = Executors.newSingleThreadExecutor()

    private var jugando = false
    private var tiro = 0
    private var goles = 0

    private lateinit var tvEstado: TextView
    private lateinit var tvVel: TextView
    private lateinit var tvMensaje: TextView
    private lateinit var tvGoles: TextView
    private lateinit var tvTiro: TextView
    private lateinit var tvDist: TextView
    private lateinit var barraPos: ProgressBar

    // ---------------- Interfaz ----------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun etiqueta(texto: String, size: Float, negrita: Boolean): TextView =
        TextView(this).apply {
            text = texto
            textSize = size
            setPadding(0, dp(6), 0, dp(6))
            if (negrita) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun boton(texto: String, accion: () -> Unit): Button =
        Button(this).apply {
            text = texto
            isAllCaps = false
            setOnClickListener { accion() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        raiz.addView(etiqueta("Robot arquero", 22f, true))
        raiz.addView(boton("Conectar Bluetooth") { elegirDispositivo() })
        tvEstado = etiqueta("Desconectado", 15f, false)
        raiz.addView(tvEstado)

        tvVel = etiqueta("Velocidad: 50", 15f, false)
        raiz.addView(tvVel)
        val barra = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tvVel.text = "Velocidad: $p"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (!enviar("\$V,${sb.progress}\n")) estado("Conecta primero el robot")
                }
            })
        }
        raiz.addView(barra)

        raiz.addView(boton("Iniciar juego") {
            jugando = true
            tiro = 1
            tvMensaje.text = "¡Tira ahora! Tiro $tiro de $TOTAL_TIROS"
            refrescar()
        })
        raiz.addView(boton("Terminó el tiro") { if (jugando) finTiro() })
        raiz.addView(boton("Reiniciar marcador y posición") {
            enviar("\$R\n")
            jugando = false
            tiro = 0
            goles = 0
            barraPos.progress = 0
            tvDist.text = "Distancia recorrida: 0 mm"
            tvMensaje.text = "Marcador y posición reiniciados"
            refrescar()
        })

        tvMensaje = etiqueta("Conecta el robot para empezar", 18f, true)
        raiz.addView(tvMensaje)
        tvGoles = etiqueta("Goles: 0", 18f, false)
        raiz.addView(tvGoles)
        tvTiro = etiqueta("Tiro: 0 de $TOTAL_TIROS", 18f, false)
        raiz.addView(tvTiro)
        tvDist = etiqueta("Distancia recorrida: 0 mm", 15f, false)
        raiz.addView(tvDist)

        barraPos = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        raiz.addView(barraPos)

        setContentView(ScrollView(this).apply { addView(raiz) })
    }

    override fun onDestroy() {
        super.onDestroy()
        cerrar()
    }

    private fun estado(texto: String) {
        tvEstado.text = texto
    }

    private fun refrescar() {
        tvGoles.text = "Goles: $goles"
        tvTiro.text = "Tiro: $tiro de $TOTAL_TIROS"
    }

    // ---------------- Juego ----------------
    private fun finTiro() {
        if (tiro < TOTAL_TIROS) {
            tiro++
            tvMensaje.text = "¡Tira ahora! Tiro $tiro de $TOTAL_TIROS"
        } else {
            jugando = false
            tvMensaje.text = "Juego terminado"
            AlertDialog.Builder(this)
                .setTitle("Fin del juego")
                .setMessage("Marcador final: $goles goles de $TOTAL_TIROS tiros")
                .setPositiveButton("Aceptar", null)
                .show()
        }
        refrescar()
    }

    private fun procesar(linea: String) {
        val l = linea.trim()
        if (!l.startsWith("\$S,")) return
        val p = l.split(",")
        if (p.size < 4) return
        val dist = p[1].toIntOrNull() ?: return
        val g = p[2].toIntOrNull() ?: return
        tvDist.text = "Distancia recorrida: $dist mm"
        barraPos.progress = (dist * 1000L / LARGO_MM).toInt().coerceIn(0, 1000)
        val hayGolNuevo = jugando && g > goles
        goles = g
        if (hayGolNuevo) finTiro() else refrescar()
    }

    // ---------------- Bluetooth ----------------
    private fun tienePermiso(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            elegirDispositivo()
        } else {
            estado("Permiso denegado. Actívalo en Ajustes > Apps > Robot arquero > Permisos > Dispositivos cercanos")
        }
    }

    private fun elegirDispositivo() {
        if (!tienePermiso()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled) {
            estado("Enciende el Bluetooth del celular")
            return
        }
        val lista = a.bondedDevices.toList()
        if (lista.isEmpty()) {
            estado("No hay dispositivos emparejados. Empareja el HC-06 en Ajustes > Bluetooth (código 1234)")
            return
        }
        val nombres = lista.map { "${it.name}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige el módulo")
            .setItems(nombres) { _, i -> conectar(lista[i]) }
            .show()
    }

    private fun conectar(d: BluetoothDevice) {
        cerrar()
        estado("Conectando a ${d.name}...")
        Thread {
            // Tres formas de abrir el canal serie; si una falla se prueba la siguiente.
            val formas = listOf<() -> BluetoothSocket>(
                { d.createRfcommSocketToServiceRecord(SPP) },
                { d.createInsecureRfcommSocketToServiceRecord(SPP) },
                {
                    d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(d, 1) as BluetoothSocket
                }
            )
            var s: BluetoothSocket? = null
            var errores = ""
            for ((n, crear) in formas.withIndex()) {
                var cand: BluetoothSocket? = null
                try {
                    cand = crear()
                    adapter?.cancelDiscovery()
                    cand.connect()
                    s = cand
                    break
                } catch (e: Exception) {
                    errores += "Forma ${n + 1}: ${e.javaClass.simpleName}: ${e.message}\n"
                    try { cand?.close() } catch (_: Exception) {}
                }
            }
            if (s == null) {
                runOnUiThread { estado("No se pudo conectar.\n$errores") }
                return@Thread
            }
            socket = s
            runOnUiThread { estado("Conectado a ${d.name}") }
            try {
                val lector = BufferedReader(InputStreamReader(s.inputStream))
                while (true) {
                    val linea = lector.readLine() ?: break
                    runOnUiThread { procesar(linea) }
                }
            } catch (_: Exception) {
            }
            if (socket === s) runOnUiThread { estado("Desconectado") }
        }.start()
    }

    private fun enviar(texto: String): Boolean {
        val s = socket ?: return false
        envio.execute {
            try {
                s.outputStream.write(texto.toByteArray())
                s.outputStream.flush()
            } catch (e: Exception) {
                runOnUiThread { estado("Error al enviar: ${e.message}") }
            }
        }
        return true
    }

    private fun cerrar() {
        val s = socket
        socket = null
        try { s?.close() } catch (_: Exception) {}
    }
}
