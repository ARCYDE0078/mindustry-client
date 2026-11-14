package helium.ui.dialogs.mods

import arc.Core
import arc.util.Log
import arc.util.serialization.Jval
import helium.HeVars
import mindustry.mod.ModListing

/**
 * Порт helium.ui.dialogs.mods.AbstractFavorites / LocalFavorites / LocalFavoritesStore (Helium
 * beta-1.7, EB-wilson): избранное в beta-1.7 - не один плоский список, а набор именованных папок.
 *
 * Не перенесена GitHubStarFavorites (папка, синхронизируемая со звёздами GitHub-аккаунта): она
 * требует входа через OAuth device flow и хранения токена, то есть внешнего сервера и секретов;
 * sonka от входа в GitHub отказался. Поэтому у папки нет флагов deletable/renamable - они у
 * оригинала нужны только чтобы защитить эту звёздную папку, а локальные удаляются и переименовываются всегда.
 */
abstract class AbstractFavorites(val name: String){
    protected val mods = ArrayList<Name>()

    val modList: List<Name> get() = mods

    val isEmpty: Boolean get() = mods.isEmpty()

    fun contains(mod: Name): Boolean = mods.any{ it == mod }

    fun contains(mod: ModListing): Boolean = contains(Name(mod))

    fun addMod(mod: Name){
        if(contains(mod)) return

        mods.add(mod)
        saveFavorite()
    }

    fun removeMod(mod: Name){
        if(!mods.removeAll{ it == mod }) return

        saveFavorite()
    }

    abstract fun loadFavorite()
    abstract fun saveFavorite()

    /** JSON, который принимает импорт: {"name": ..., "mods": [{"author": ..., "name": ...}]}. */
    fun toSerial(): String{
        val json = Jval.newObject()
        json.put("name", name)
        json.put("mods", LocalFavoritesStore.modsToJson(mods))

        return json.toString(Jval.Jformat.formatted)
    }

    protected fun replaceMods(values: Collection<Name>){
        mods.clear()
        values.forEach{ value -> if(!contains(value)) mods.add(value) }
    }
}

class LocalFavorites(name: String) : AbstractFavorites(name){
    override fun loadFavorite(){
        replaceMods(LocalFavoritesStore.loadModList(name))
    }

    override fun saveFavorite(){
        LocalFavoritesStore.upsert(this)
    }

    /** Заменяет состав целиком одной записью в хранилище (импорт, слияние, переименование). */
    fun replaceAll(values: Collection<Name>){
        replaceMods(values)
        saveFavorite()
    }
}

/**
 * Хранилище папок избранного: один JSON-массив [{name, mods:[{author, name}]}] в ключе
 * `favorite-folders` общего с оригиналом файла mods/data/he/global_vars.bin.
 */
object LocalFavoritesStore{
    private const val KEY = "favorite-folders"
    /** Плоский список beta-1.6: [{author, name}]. */
    private const val LEGACY_KEY = "favorite-mods"

    fun loadAll(): ArrayList<LocalFavorites>{
        migrateLegacy()

        val result = ArrayList<LocalFavorites>()
        read().asArray().forEach{ entry ->
            val name = entry.getString("name") ?: ""
            if(name.isBlank()) return@forEach

            result.add(LocalFavorites(name).also{ it.loadFavorite() })
        }

        return result
    }

    fun loadModList(name: String): List<Name>{
        val entry = read().asArray().firstOrNull{ it.getString("name") == name } ?: return emptyList()

        return parseMods(entry.get("mods"))
    }

    fun upsert(folder: AbstractFavorites){
        replace(folder.name, folder)
    }

    /**
     * Записывает [folder] на место записи с именем [oldName] (при переименовании папка остаётся на
     * своём месте в списке, а не уезжает в конец), а если такой записи нет - добавляет в конец.
     */
    fun replace(oldName: String, folder: AbstractFavorites){
        val json = read()
        val array = json.asArray()
        val entry = toJson(folder)
        val index = array.indexOfFirst{ it.getString("name") == oldName }

        if(index >= 0) array.set(index, entry)
        else array.add(entry)

        write(json)
    }

    fun delete(name: String){
        val json = read()
        val array = json.asArray()
        val index = array.indexOfFirst{ it.getString("name") == name }
        if(index < 0) return

        array.remove(index)
        write(json)
    }

    fun modsToJson(mods: Collection<Name>): Jval{
        val list = Jval.newArray()
        mods.forEach{ mod ->
            val entry = Jval.newObject()
            entry.put("author", mod.author)
            entry.put("name", mod.name)

            list.add(entry)
        }

        return list
    }

    /** Разбор массива [{author, name}] (хранилище, импорт); пустые имена и повторы отбрасываются. */
    fun parseMods(array: Jval?): List<Name>{
        if(array == null || !array.isArray) return emptyList()

        val result = ArrayList<Name>()
        array.asArray().forEach{ entry ->
            val name = entry.getString("name") ?: ""
            if(name.isBlank()) return@forEach

            val mod = Name(entry.getString("author") ?: "", name)
            if(!result.any{ it == mod }) result.add(mod)
        }

        return result
    }

    private fun read(): Jval{
        val raw = HeVars.global.getString(KEY, "")
        if(raw.isBlank()) return Jval.newArray()

        return try{
            val json = Jval.read(raw)
            if(json.isArray) json else Jval.newArray()
        }catch(error: Exception){
            Log.err("[helium] failed to read the local favorites, they will be reset", error)
            Jval.newArray()
        }
    }

    private fun write(array: Jval){
        HeVars.global.put(KEY, array.toString())
    }

    private fun toJson(folder: AbstractFavorites): Jval{
        val entry = Jval.newObject()
        entry.put("name", folder.name)
        entry.put("mods", modsToJson(folder.modList))

        return entry
    }

    /**
     * Один раз переносит плоский список beta-1.6 в папку «Избранное». Старый ключ НЕ удаляется:
     * откат на 1.6 не потеряет избранное (после миграции оба списка живут независимо).
     */
    private fun migrateLegacy(){
        if(HeVars.global.has(KEY)) return
        if(!HeVars.global.has(LEGACY_KEY)) return

        val legacy = try{
            Jval.read(HeVars.global.getString(LEGACY_KEY, ""))
        }catch(error: Exception){
            Log.err("[helium] failed to migrate the legacy favorite mods", error)
            return
        }

        if(!legacy.isArray || legacy.asArray().size <= 0) return

        val entry = Jval.newObject()
        entry.put("name", Core.bundle["dialog.mods.favorites"])
        entry.put("mods", legacy)

        val array = Jval.newArray()
        array.add(entry)

        write(array)
        Log.info("[helium] migrated the legacy favorite mods into a local favorites folder")
    }
}
