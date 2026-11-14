package mindustry.client.communication

import arc.util.io.*
import mindustry.world.blocks.logic.*
import java.io.*
import kotlin.random.*

/**
 * Запрос/ответ с КОДОМ ПРОЦЕССОРА (sonka): превью чужих планов (clientPlanSnapshot) не несёт `byte[]`-конфигов вовсе,
 * а очередь юнита отдаёт их только в узком бюджете (~500 байт на пакет) и не отдаёт совсем, если у владельца включено
 * `processorconfigs` (configLocal). Поэтому код процессора между клиентами Monolith ходит ОТДЕЛЬНО, по клиентскому
 * каналу форка (сервер лишь ретранслирует его как обычный трафик Block/Plugin/BuildPlan-системы):
 *  - запрос (`response == false`): "дай код процессора, который у тебя в очереди по [pos] с блоком [block]";
 *  - ответ (`response == true`): тот же ключ + сжатый конфиг процессора (формат [LogicBlock.compress]).
 *
 * Кто спросил/кто ответил, здесь НЕ пишется: его id приходит от канала (сервер/игра ставят его сами), и подделать его
 * нельзя - именно по нему приёмник проверяет, что ответ пришёл от владельца плана.
 */
class ProcessorConfigTransmission : Transmission {
    override var id = Random.nextLong()
    override val secureOnly: Boolean = false

    val response: Boolean
    /** Point2.pack(x, y) плана. */
    val pos: Int
    /** Имя блока (а не id: у модовых блоков порядок id зависит от набора модов, имя - нет). */
    val block: String
    /** Только в ответе: сжатый конфиг, длина не больше [LogicBlock.maxCompressedLen]. */
    val config: ByteArray?

    /** Запрос. */
    constructor(pos: Int, block: String) {
        this.response = false
        this.pos = pos
        this.block = block
        this.config = null
    }

    /** Ответ. */
    constructor(pos: Int, block: String, config: ByteArray) {
        require(config.size <= LogicBlock.maxCompressedLen) { "Processor config too long: ${config.size}" }
        this.response = true
        this.pos = pos
        this.block = block
        this.config = config
    }

    constructor(input: ByteArray, id: Long, @Suppress("UNUSED_PARAMETER") senderID: Int) {
        this.id = id
        val reads = Reads(DataInputStream(input.inputStream()))
        // Читаем в локальные, а val присваиваем после: присваивание val внутри try/finally Kotlin считает хрупким.
        val isResponse: Boolean
        val packedPos: Int
        val blockName: String
        var data: ByteArray? = null
        try {
            isResponse = reads.bool()
            packedPos = reads.i()
            blockName = reads.str() ?: throw IllegalArgumentException("No block name!")
            if (isResponse) {
                val size = reads.i()
                // Длину проверяем ДО выделения массива: иначе чужой пакет с size = 2^31 - 1 роняет клиента OOM-ом.
                if (size < 0 || size > LogicBlock.maxCompressedLen) throw IllegalArgumentException("Invalid processor config length: $size")
                data = reads.b(size)
            }
        } finally {
            reads.close()
        }
        this.response = isResponse
        this.pos = packedPos
        this.block = blockName
        this.config = data
    }

    override fun serialize(): ByteArray {
        val stream = ByteArrayOutputStream()
        val writes = Writes(DataOutputStream(stream))
        writes.bool(response)
        writes.i(pos)
        writes.str(block)
        if (response) {
            val cfg = config!!
            writes.i(cfg.size)
            writes.b(cfg)
        }
        val array = stream.toByteArray()
        writes.close()
        return array
    }
}
