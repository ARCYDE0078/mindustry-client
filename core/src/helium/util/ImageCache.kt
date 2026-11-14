package helium.util

import arc.Core
import arc.graphics.g2d.TextureRegion
import arc.scene.style.Drawable
import arc.struct.ObjectMap

/**
 * Порт helium.util.ImageCache (Helium beta-1.7, EB-wilson): одна ленивая drawable-иконка на URL.
 *
 * Браузер модов перестраивает карточки при каждой смене фильтра/сортировки; без кэша каждая
 * перестройка заново качала бы PNG всех видимых иконок. Неудавшаяся загрузка выбрасывает запись
 * из кэша, чтобы следующий [resolve] попробовал ещё раз, а не вечно показывал заглушку.
 */
class ImageCache{
    private val lock = Any()
    private val cache = ObjectMap<String, Drawable>()

    fun resolve(url: String, def: TextureRegion = Core.atlas.find("nomap")): Drawable = synchronized(lock){
        cache.get(url) ?: Downloader.downloadLazyDrawable(url, def){ evict(url) }.also{ cache.put(url, it) }
    }

    fun clear() = synchronized(lock){ cache.clear() }

    private fun evict(url: String) = synchronized(lock){ cache.remove(url) }
}
