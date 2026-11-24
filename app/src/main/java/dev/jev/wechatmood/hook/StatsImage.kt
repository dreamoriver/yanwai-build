package dev.jev.wechatmood.hook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.jev.wechatmood.reply.GroupStats
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把统计和 AI 观察画成一张分享用的图片，保存到相册。 */
object StatsImage {
    private const val WIDTH = 1080
    private const val PAD = 72f
    /** 每 dp 的像素数：分享图和界面共用 360dp 宽度基准，圆角看起来才一致。 */
    private val DP = WIDTH / 360f
    // 固定深色配色，取值与 ReplyTheme 的深色一套相同；不跟随宿主主题。
    private val BG = Color.parseColor("#111B17")
    private val CARD = Color.parseColor("#1C2A24")
    private val INK = Color.parseColor("#E1EEE6")
    private val MUTED = Color.parseColor("#A6B9AF")
    /** 长图的行高（px）与高度上下限：单张 bitmap 太大，低端机上会直接 OOM。 */
    private const val LINE = 40f
    private const val MIN_HEIGHT = 900
    private const val MAX_HEIGHT = 12000

    /** 长图的保存结果：成功给相册 uri，失败给一句能直接显示给用户的原因。 */
    internal data class Saved(val uri: Uri?, val reason: String) {
        val ok: Boolean get() = uri != null
    }

    fun render(stats: GroupStats, names: Map<String, String>, analysis: String?, title: String): Bitmap {
        val titlePaint = text(48f, INK, Typeface.BOLD)
        val subPaint = text(24f, MUTED)
        val numberPaint = text(56f, INK, Typeface.BOLD)
        val labelPaint = text(22f, MUTED)
        val rowPaint = text(28f, INK)
        val bodyPaint = text(28f, INK).apply { isAntiAlias = true }
        val metaPaint = text(20f, MUTED)

        val bodyLines = analysis?.takeIf { it.isNotBlank() }?.let { wrap(it, bodyPaint, WIDTH - PAD * 2 - 48) } ?: emptyList()
        val height = (PAD + 60 + 40 + 130 + 40 + stats.topSenders.size * 52 + 40 +
            (if (bodyLines.isEmpty()) 0 else bodyLines.size * 44 + 90) + 60 + PAD).toInt().coerceAtLeast(900)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)

        var y = PAD + 40
        canvas.drawText(title, PAD, y, titlePaint)
        y += 44
        canvas.drawText(spanLabel(stats), PAD, y, subPaint)
        y += 46

        // 和面板一致：大字只放消息类型，条数人数写在副标题里。
        val known = dev.jev.wechatmood.reply.GroupStatsCalculator.KIND_LABELS
            .sumOf { stats.types[it.first] ?: 0 }
        val cells = dev.jev.wechatmood.reply.GroupStatsCalculator.KIND_LABELS.map { (type, label) ->
            (stats.types[type] ?: 0).toString() to label
        } + ((stats.types.values.sum() - known).coerceAtLeast(0).toString() to "其它")
        val cellWidth = (WIDTH - PAD * 2) / cells.size
        cells.forEachIndexed { index, (value, label) ->
            val cx = PAD + cellWidth * index + cellWidth / 2
            canvas.drawText(value, cx - numberPaint.measureText(value) / 2, y + 50, numberPaint)
            canvas.drawText(label, cx - labelPaint.measureText(label) / 2, y + 84, labelPaint)
        }
        y += 130

        if (stats.topSenders.isNotEmpty()) {
            canvas.drawText("最活跃", PAD, y, labelPaint)
            y += 36
            stats.topSenders.forEach { (name, count) ->
                val shown = names[name] ?: fallbackName(name)
                canvas.drawText(shown.take(14) + "（" + count + " 条）", PAD, y, rowPaint)
                y += 52
            }
            y += 12
        }

        if (bodyLines.isNotEmpty()) {
            canvas.drawRoundRect(PAD, y, WIDTH - PAD, y + bodyLines.size * 44 + 64, 14 * DP, 14 * DP, fillPaint(CARD))
            y += 46
            bodyLines.forEach { line ->
                canvas.drawText(line, PAD + 24, y, bodyPaint)
                y += 44
            }
            y += 24
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        canvas.drawText("言外 · $stamp", PAD, height - PAD / 2, metaPaint)
        return bitmap
    }

    fun save(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val name = "yanwai-stats-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/言外")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        uri
    }.getOrNull()

    /**
     * 纪要汇总长图：标题 + 副标题 + 正文 + 落款。
     *
     * [render] 的正文只留 14 行、版面固定，那是给一段 AI 观察用的；周报可能有几百行，
     * 这里按内容算高度、不裁行，配色、字号与折行方式仍沿用分享图那一套。
     */
    fun renderReport(title: String, subtitle: String, body: String): Bitmap {
        val titlePaint = text(44f, INK, Typeface.BOLD)
        val subPaint = text(24f, MUTED)
        val bodyPaint = text(26f, INK)
        val metaPaint = text(20f, MUTED)
        val contentWidth = WIDTH - PAD * 2
        // 标题区与落款各占多少像素，正文能用的高度就靠它倒推。
        val head = 44f + 48f + 36f + 28f
        val footer = 70f
        var lines = body.split('\n').flatMap { raw ->
            if (raw.isBlank()) listOf("") else wrapAll(raw, bodyPaint, contentWidth)
        }
        val capacity = ((MAX_HEIGHT - PAD * 2 - head - footer) / LINE).toInt()
        if (lines.size > capacity) {
            // 文字上限本身压得住，这层只是兜底：宁可明说画不下，也别让画布悄悄裁掉半句话。
            lines = lines.take(capacity) + "…（图片到这儿画不下了，完整内容请用复制全文）"
        }
        val height = (PAD * 2 + head + lines.size * LINE + footer).toInt()
            .coerceIn(MIN_HEIGHT, MAX_HEIGHT)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)

        var y = PAD + 44
        // 44px 标题在 1080 宽里只放得下约 20 个汉字；超出的部分会被画布裁掉且没有省略号。
        val heading = if (title.codePointCount(0, title.length) > 18) {
            title.substring(0, title.offsetByCodePoints(0, 18)) + "…"
        } else title
        canvas.drawText(heading, PAD, y, titlePaint)
        y += 48
        canvas.drawText(subtitle, PAD, y, subPaint)
        y += 36
        canvas.drawRect(PAD, y, WIDTH - PAD, y + 2 * DP, fillPaint(CARD))
        y += 28
        lines.forEach { line ->
            if (line.isNotEmpty()) canvas.drawText(line, PAD, y, bodyPaint)
            y += LINE
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        canvas.drawText("言外 · $stamp", PAD, height - PAD / 2, metaPaint)
        return bitmap
    }

    /**
     * 把长图存进相册，并把失败原因带回来。
     *
     * Android 10 起走 MediaStore + RELATIVE_PATH，不用申请存储权限；
     * Android 9 没有分区存储，只能写进公共图片目录再让媒体扫描登记。
     * 用调用方给的 Context（宿主 Activity）：写文件要的是微信这个身份的权限。
     */
    internal fun saveReport(context: Context, bitmap: Bitmap, prefix: String = "yanwai-report"): Saved {
        val name = prefix + "-" + System.currentTimeMillis() + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/言外")
                    // 先挂 pending：写失败时相册里不会留一条 0 字节或半张的坏图。
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return Saved(null, "相册不接受这次写入，可能是存储空间不足")
                var written = false
                try {
                    val out = resolver.openOutputStream(uri)
                    if (out == null) {
                        runCatching { resolver.delete(uri, null, null) }
                        return Saved(null, "打不开刚建好的图片文件")
                    }
                    out.use { stream -> written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                } finally {
                    if (!written) runCatching { resolver.delete(uri, null, null) }
                }
                if (!written) return Saved(null, "图片数据没写成功，可能是存储空间不足")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                runCatching { resolver.update(uri, done, null, null) }
                Saved(uri, "")
            }.getOrElse { Saved(null, "存图片出错：" + it.javaClass.simpleName) }
        }
        return runCatching {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "言外")
            if (!dir.exists() && !dir.mkdirs()) return Saved(null, "建不了相册目录：" + dir.absolutePath)
            val file = File(dir, name)
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    return Saved(null, "图片数据没写成功，可能是存储空间不足")
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            Saved(Uri.fromFile(file), "")
        }.getOrElse {
            Saved(null, "存图片出错：" + it.javaClass.simpleName + "，可能是系统没给微信存储权限")
        }
    }

    /** 标题下面只写这次统计的时间范围，条数和人数在四块数字里已经有。 */
    /** 副标题：时间范围 + 条数、人数、人均，和面板上面那一行说同一件事。 */
    private fun spanLabel(stats: GroupStats): String {
        val span = if (stats.earliest > 0 && stats.latest > 0) {
            val format = SimpleDateFormat("HH:mm", Locale.CHINA)
            format.format(Date(stats.earliest)) + "–" + format.format(Date(stats.latest)) + " · "
        } else ""
        return span + stats.total + " 条 · " + stats.senders + " 人发言 · 人均 " + stats.perCapita + " 条"
    }

    /** 名字不可见时用占位，避免图片里出现空行。 */
    private fun fallbackName(raw: String): String {
        val visible = raw.trim().any { ch ->
            !ch.isWhitespace() && !ch.isISOControl() && Character.getType(ch) != Character.FORMAT.toInt()
        }
        if (!visible) return "未获取昵称"
        return if (raw.length > 10) raw.take(10) + "…" else raw
    }

    private fun text(size: Float, color: Int, style: Int = Typeface.NORMAL) = Paint().apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, style)
        isAntiAlias = true
    }

    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    /** 按像素宽度折行，中文按字符断，英文按空格优先；分享图只留前 14 行。 */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> =
        wrapAll(text, paint, maxWidth).take(14)

    /** 同上，但不限行数：长图要把整段正文画完。 */
    private fun wrapAll(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                lines += current.toString()
                current = StringBuilder()
                continue
            }
            if (paint.measureText(current.toString() + ch) > maxWidth) {
                lines += current.toString()
                current = StringBuilder()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
