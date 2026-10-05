package io.github.mangi.eta.agent.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * 离屏虚拟屏守护进程。
 *
 * Eta 以 root 身份通过 `app_process` 启动它：uid 0 能通过 `CAPTURE_VIDEO_OUTPUT` 的校验，
 * 普通应用进程不能。守护进程自己持有 ImageReader 的离屏 Surface，因此这块屏幕不会被
 * 合成到主屏上（系统里依然存在，只是不显示），也就没有系统浮窗需要最小化或关闭。
 *
 * 控制面只用文件，避免跨 uid 的套接字与 SELinux 约束：
 * - `<dir>/status.json`：进程与显示信息
 * - `<dir>/cmd`：命令 FIFO（`shot`、`info`、`quit`）
 * - `<dir>/frame.png`：最近一次画面
 * - `<dir>/daemon.log`：诊断日志
 */
internal object VirtualDisplayDaemon {
    private const val DEFAULT_DIR = "/data/local/tmp/eta/vdisplay"
    private const val DEFAULT_NAME = "Eta Virtual Display"
    private const val DEFAULT_WIDTH = 720
    private const val DEFAULT_HEIGHT = 1280
    private const val DEFAULT_DENSITY = 320
    private const val FLAG_PUBLIC = 0x1
    private const val FLAG_SUPPORTS_TOUCH = 0x40
    private const val FIRST_FRAME_TIMEOUT_MS = 6_000L
    private const val MAX_IMAGES = 3
    private const val FIFO_MODE = 438 // 0666

    const val STATUS_FILE = "status.json"
    const val COMMAND_FIFO = "cmd"
    const val FRAME_FILE = "frame.png"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        val controlDir = File(options.dir)
        controlDir.mkdirs()
        val log = DaemonLog(File(controlDir, "daemon.log"))
        log.write("daemon start pid=${Process.myPid()} size=${options.width}x${options.height} density=${options.density}")
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        var frameThread: HandlerThread? = null
        try {
            bypassHiddenApiRestrictions()
            val context = systemContext()
            val latest = AtomicReference<Bitmap?>(null)
            val imageReader = ImageReader.newInstance(
                options.width,
                options.height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES,
            )
            reader = imageReader
            frameThread = HandlerThread("eta-virtual-display").apply { start() }
            imageReader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    latest.set(image.toBitmap())
                } catch (error: Throwable) {
                    log.write("frame failed: ${error.javaClass.simpleName}")
                } finally {
                    image.close()
                }
            }, Handler(frameThread.looper))

            val displayManager = context.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("DisplayManager unavailable")
            val virtualDisplay = displayManager.createVirtualDisplay(
                options.name,
                options.width,
                options.height,
                options.density,
                imageReader.surface,
                FLAG_PUBLIC or FLAG_SUPPORTS_TOUCH,
            ) ?: throw IllegalStateException("createVirtualDisplay returned null")
            display = virtualDisplay
            val displayId = virtualDisplay.display.displayId
            log.write("display created id=$displayId")
            writeStatus(controlDir, displayId, options, state = "running")
            awaitFirstFrame(latest, log)

            val commandFile = File(controlDir, COMMAND_FIFO)
            if (commandFile.exists()) commandFile.delete()
            android.system.Os.mkfifo(commandFile.absolutePath, FIFO_MODE)
            commandFile.setReadable(true, false)
            commandFile.setWritable(true, false)
            log.write("command fifo ready")

            while (true) {
                val command = readCommand(commandFile) ?: continue
                log.write("command=$command")
                when (command) {
                    "shot" -> writeFrame(controlDir, latest, log)
                    "info" -> writeStatus(controlDir, displayId, options, state = "running")
                    "quit" -> break
                    else -> log.write("unknown command")
                }
            }
            log.write("daemon stopping")
        } catch (error: Throwable) {
            log.write("daemon failed: ${error.javaClass.name}: ${error.message}")
        } finally {
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { frameThread?.quitSafely() }
            runCatching { File(controlDir, STATUS_FILE).delete() }
            runCatching { File(controlDir, COMMAND_FIFO).delete() }
            log.write("daemon exited")
        }
    }

    private fun awaitFirstFrame(latest: AtomicReference<Bitmap?>, log: DaemonLog) {
        val deadline = SystemClock.elapsedRealtime() + FIRST_FRAME_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (latest.get() != null) return
            Thread.sleep(100)
        }
        log.write("no frame arrived within ${FIRST_FRAME_TIMEOUT_MS}ms")
    }

    private fun writeFrame(controlDir: File, latest: AtomicReference<Bitmap?>, log: DaemonLog) {
        val bitmap = latest.get()
        if (bitmap == null) {
            log.write("shot skipped: no frame yet")
            return
        }
        val target = File(controlDir, FRAME_FILE)
        val temporary = File(controlDir, "$FRAME_FILE.tmp")
        runCatching {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException("png encode failed")
                }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.setReadable(true, false)
        }.onFailure { error -> log.write("shot failed: ${error.javaClass.simpleName}") }
    }

    private fun writeStatus(controlDir: File, displayId: Int, options: Options, state: String) {
        val status = JSONObject()
            .put("ok", true)
            .put("state", state)
            .put("pid", Process.myPid())
            .put("display_id", displayId)
            .put("width", options.width)
            .put("height", options.height)
            .put("density", options.density)
            .put("name", options.name)
        val target = File(controlDir, STATUS_FILE)
        val temporary = File(controlDir, "$STATUS_FILE.tmp")
        temporary.writeText(status.toString())
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target.setReadable(true, false)
    }

    /** FIFO 每次由写入方打开、写完关闭；读到 EOF 就重新打开等待下一条命令。 */
    private fun readCommand(commandFile: File): String? = runCatching {
        FileInputStream(commandFile).use { input ->
            BufferedReader(InputStreamReader(input)).readLine()?.trim()?.lowercase()
        }
    }.getOrNull()

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = if (pixelStride > 0) rowStride / pixelStride else width
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun bypassHiddenApiRestrictions() {
        runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    /** app_process 进程没有 Application，需要 system_server 之外的系统 Context。 */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
            .getMethod("systemMain")
            .invoke(null)
        return activityThread.javaClass
            .getMethod("getSystemContext")
            .invoke(activityThread) as Context
    }

    private class DaemonLog(private val file: File) {
        fun write(line: String) {
            runCatching {
                file.appendText("[${SystemClock.elapsedRealtime()}] $line\n")
                file.setReadable(true, false)
            }
        }
    }

    private data class Options(
        val width: Int,
        val height: Int,
        val density: Int,
        val dir: String,
        val name: String,
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                var width = DEFAULT_WIDTH
                var height = DEFAULT_HEIGHT
                var density = DEFAULT_DENSITY
                var dir = DEFAULT_DIR
                var name = DEFAULT_NAME
                var index = 0
                while (index < args.size) {
                    val value = args.getOrNull(index + 1)
                    when (args[index]) {
                        "--width" -> value?.toIntOrNull()?.let { width = it }
                        "--height" -> value?.toIntOrNull()?.let { height = it }
                        "--density" -> value?.toIntOrNull()?.let { density = it }
                        "--dir" -> value?.takeIf { it.isNotBlank() }?.let { dir = it }
                        "--name" -> value?.takeIf { it.isNotBlank() }?.let { name = it }
                    }
                    index += 2
                }
                return Options(
                    width = width.coerceIn(200, 2_400),
                    height = height.coerceIn(200, 2_400),
                    density = density.coerceIn(120, 640),
                    dir = dir,
                    name = name,
                )
            }
        }
    }
}
