package helium.util

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.scene.style.TextureRegionDrawable
import arc.util.Http
import arc.util.Log
import arc.util.Threads
import arc.util.io.Streams.OptimizedByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import kotlin.math.max

/**
 * Порт helium.util.Downloader (Helium beta-1.7, EB-wilson) без списка зеркал (Downloader.setMirror мод
 * нигде не использовал) и без корутин: в клиенте нет kotlinx.coroutines, поэтому оригинальный
 * suspend-API заменён БЛОКИРУЮЩИМ - функции зовутся только из фонового потока (пул [exec]) и
 * отменяются через [java.util.concurrent.Future.cancel] (флаг interrupt).
 *
 * Что перенесено из переписывания на корутины в beta-1.7:
 *  - ретраи с нарастающей паузой вместо мгновенных повторов (при rate-limit они только злили сервер);
 *  - постоянные ошибки 4xx (404, 403 и т.п.; кроме 408/429) не повторяются - ретрай бессмыслен;
 *  - копирование буфером 8 КБ вместо побайтового чтения (на jar в десятки МБ это разница в разы);
 *  - иконки: текстура грузится один раз на URL (см. [ImageCache]), а неудачная загрузка не залипает.
 */
object Downloader{
    const val MAX_RETRY: Int = 5

    private const val RETRY_DELAY_MS: Long = 250L
    private const val BUFFER_SIZE: Int = 8192

    /** Общий пул фоновых загрузок; тот же "HTTP"-пул, что раньше был у листинга модов. */
    val exec: ExecutorService = Threads.unboundedExecutor("HTTP", 1)

    /**
     * Блокирующий GET с ретраями: [handler] получает ответ и возвращает результат.
     * После [maxRetry] повторов (или сразу при постоянной ошибке/прерывании) бросает последнюю ошибку.
     */
    fun <T> request(url: String, maxRetry: Int = MAX_RETRY, handler: (Http.HttpResponse) -> T): T{
        for(attempt in 0..maxRetry){
            if(Thread.currentThread().isInterrupted) throw InterruptedException()

            try{
                return getRequest(url, handler)
            }catch(e: InterruptedException){
                throw e
            }catch(e: Throwable){
                if(attempt >= maxRetry || e.isPermanentHttpError()) throw e

                //пауза растёт с номером попытки: 250, 500, 750 мс...
                Thread.sleep(RETRY_DELAY_MS*(attempt + 1))
            }
        }

        throw IllegalStateException("download failed: $url")
    }

    /** 4xx, кроме "таймаут" и "слишком часто", - сервер ответил окончательно, повторять нечего. */
    private fun Throwable.isPermanentHttpError(): Boolean =
        this is Http.HttpStatusException && status.code in 400..499 && status.code != 408 && status.code != 429

    private fun <T> getRequest(url: String, handler: (Http.HttpResponse) -> T): T{
        var result: T? = null
        var done = false
        var failure: Throwable? = null

        //handler обёрнут сам: исключение внутри block() Arc отдаёт в error-колбэк, но так надёжнее
        //сохранить исходный тип (InterruptedException не должен превратиться в обычную ошибку)
        Http.get(url).error{ failure = it }.block{ res ->
            try{
                result = handler(res)
                done = true
            }catch(e: Throwable){
                failure = e
            }
        }

        failure?.let{ throw it }
        if(!done) throw IllegalStateException("request returned no response: $url")

        @Suppress("UNCHECKED_CAST")
        val value = result as T
        return value
    }

    private fun copyBody(res: Http.HttpResponse, out: OutputStream, progressBack: Cons<Float>?){
        val input = res.resultAsStream ?: return
        val total = res.contentLength
        val buffer = ByteArray(BUFFER_SIZE)

        var curr = 0L
        while(true){
            if(Thread.currentThread().isInterrupted) throw InterruptedException()

            val read = input.read(buffer)
            if(read == -1) break

            out.write(buffer, 0, read)
            curr += read
            //у ответа без Content-Length total = -1: прогресс тогда отрицательный (= принятые байты, см. UI)
            progressBack?.get(if(total > 0) curr.toFloat()/total else -curr.toFloat())
        }
    }

    fun getString(url: String, maxRetry: Int = MAX_RETRY): String = request(url, maxRetry){ it.resultAsString }

    fun downloadToStream(
        url: String,
        stream: OutputStream,
        progressBack: Cons<Float>? = null,
        maxRetry: Int = MAX_RETRY,
    ){
        stream.use{ out ->
            request(url, maxRetry){ res -> copyBody(res, out, progressBack) }
        }
    }

    fun downloadToFile(
        url: String,
        file: Fi,
        progressBack: Cons<Float>? = null,
        maxRetry: Int = MAX_RETRY,
    ) = downloadToStream(url, file.write(), progressBack, maxRetry)

    /**
     * Иконка, которая качается при ПЕРВОЙ отрисовке (в списке из сотен модов не нужно грузить
     * невидимые). До загрузки рисуется [errDef]; [errHandler] зовётся из фонового потока, когда
     * все попытки провалились.
     */
    fun downloadLazyDrawable(
        url: String,
        errDef: TextureRegion,
        errHandler: Cons<Throwable>? = null,
    ): Drawable{
        val result = TextureRegion(errDef)
        var started = false

        return object : TextureRegionDrawable(result){
            override fun draw(x: Float, y: Float, width: Float, height: Float){
                if(!started){
                    started = true
                    exec.submit{ downloadInto(url, result, errHandler) }
                }
                super.draw(x, y, width, height)
            }
        }
    }

    private fun downloadInto(url: String, result: TextureRegion, errHandler: Cons<Throwable>?){
        try{
            val bytes = request(url){ res ->
                val out = OptimizedByteArrayOutputStream(max(0L, res.contentLength).toInt())
                out.use{ copyBody(res, it, null) }
                out.toByteArray()
            }

            val pix = Pixmap(bytes)
            //Texture можно создавать только в GL-потоке
            Core.app.post{
                try{
                    val tex = Texture(pix)
                    tex.setFilter(Texture.TextureFilter.linear)
                    result.set(tex)
                }catch(e: Exception){
                    Log.err(e)
                }finally{
                    pix.dispose()
                }
            }
        }catch(e: InterruptedException){
            //отмена - не ошибка
        }catch(e: Throwable){
            errHandler?.get(e)
        }
    }
}
