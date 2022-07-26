package cc.ioctl.neoauth3bot.dat

import cc.ioctl.neoauth3bot.util.BinaryUtils

class ChemTableIndex(val ptrBytes: ByteArray, ptrStartOffset: Int = 0) {

    //struct ChemTableIndex {
    //    uint32_t cid; +0
    //    uint32_t _totalCount; +4
    //    uint64_t oechem; +8
    //    uint64_t offset; +16
    //    uint32_t size; +24
    //    uint32_t unused4_1; +28
    //    uint16_t atomCount; +32
    //    uint16_t bondCount; +34
    //    uint16_t cactvsComplexity; +36
    //    uint8_t isChiral; +38
    //    uint8_t _unused0_1; +39
    //};

    var ptrStartOffset: Int = ptrStartOffset
        set(value) {
            checkBounds(start = value)
            field = value
        }

    init {
        checkBounds()
    }

    private fun checkBounds(bytes: ByteArray = this.ptrBytes, start: Int = this.ptrStartOffset) {
        if (start < 0) {
            throw IllegalArgumentException("start < 0")
        }
        if (start + OBJECT_SIZE > bytes.size) {
            throw IllegalArgumentException("start + size > bytes.size")
        }
    }

    var cid: Int
        get() = BinaryUtils.readLe32(ptrBytes, ptrStartOffset + 0)
        set(value) = BinaryUtils.writeLe32(ptrBytes, ptrStartOffset + 0, value)

    var _totalCount: Int
        get() = BinaryUtils.readLe32(ptrBytes, ptrStartOffset + 4)
        set(value) = BinaryUtils.writeLe32(ptrBytes, ptrStartOffset + 4, value)

    var oechem: Long
        get() = BinaryUtils.readLe64(ptrBytes, ptrStartOffset + 8)
        set(value) = BinaryUtils.writeLe64(ptrBytes, ptrStartOffset + 8, value)

    var offset: Long
        get() = BinaryUtils.readLe64(ptrBytes, ptrStartOffset + 16)
        set(value) = BinaryUtils.writeLe64(ptrBytes, ptrStartOffset + 16, value)

    var size: Int
        get() = BinaryUtils.readLe32(ptrBytes, ptrStartOffset + 24)
        set(value) = BinaryUtils.writeLe32(ptrBytes, ptrStartOffset + 24, value)

    var atomCount: Int
        get() = BinaryUtils.readLe16(ptrBytes, ptrStartOffset + 32)
        set(value) = BinaryUtils.writeLe16(ptrBytes, ptrStartOffset + 32, value)

    var bondCount: Int
        get() = BinaryUtils.readLe16(ptrBytes, ptrStartOffset + 34)
        set(value) = BinaryUtils.writeLe16(ptrBytes, ptrStartOffset + 34, value)

    var cactvsComplexity: Int
        get() = BinaryUtils.readLe16(ptrBytes, ptrStartOffset + 36)
        set(value) = BinaryUtils.writeLe16(ptrBytes, ptrStartOffset + 36, value)

    var isChiral: Boolean
        get() = ptrBytes[ptrStartOffset + 38] != 0.toByte()
        set(value) {
            ptrBytes[ptrStartOffset + 38] = (if (value) 1.toByte() else 0.toByte())
        }

    override fun toString(): String {
        return "ChemTableIndex(cid=$cid, _totalCount=$_totalCount, oechem=$oechem, offset=$offset, size=$size, atomCount=$atomCount, bondCount=$bondCount, cactvsComplexity=$cactvsComplexity, isChiral=$isChiral)"
    }

    companion object {
        const val OBJECT_SIZE = 40
    }

}
