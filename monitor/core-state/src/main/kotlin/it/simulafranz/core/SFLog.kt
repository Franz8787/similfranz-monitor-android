// Portato da src/SFLog.m (monitor iPad)
// SimulaFranz_Monitor — log diagnostico su file:
//   <directory>/SimulaFranz_Monitor.log  (max ~256 KB, poi ruota)
package it.simulafranz.core

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object SFLog {
    /** Al posto di NSLog: di serie scrive sullo standard output; l'app Android lo sostituisce. */
    @Volatile var sink: ((String) -> Unit)? = { line -> println(line) }

    /** Cartella del file di log (sull'iPad: Caches). null = nessun file. */
    @Volatile var directory: File? = null
    /** ANDROID: nome del file (il controller usa "SimulaFranz_Controller.log") e prefisso delle righe di sistema */
    @Volatile var fileName: String = "SimulaFranz_Monitor.log"
    @Volatile var prefix: String = "[SimulaFranz] "

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val q: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "it.simulafranz.log").apply { isDaemon = true }
    }

    fun log(msg: String) {
        sink?.invoke(prefix + msg)

        val dir = directory ?: return
        val path = File(dir, fileName)
        val line = synchronized(fmt) { fmt.format(Date()) } + " " + msg + "\n"
        q.execute {
            try {
                if (path.exists() && path.length() > 256 * 1024) {
                    val old = File(path.path + ".1")
                    old.delete()
                    path.renameTo(old)
                }
                FileOutputStream(path, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                // come sull'iPad: gli errori di scrittura del log si ignorano
            }
        }
    }
}
