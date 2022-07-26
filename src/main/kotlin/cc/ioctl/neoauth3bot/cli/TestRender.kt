package cc.ioctl.neoauth3bot.cli

import cc.ioctl.neoauth3bot.chiral.MdlMolParser
import cc.ioctl.neoauth3bot.chiral.MoleculeRender
import cc.ioctl.neoauth3bot.dat.ChemTableIndex
import cc.ioctl.neoauth3bot.util.SdfUtils
import cc.ioctl.telebot.util.IoUtils
import com.vivimice.bgzfrandreader.RandomAccessBgzFile
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 6 || args.contains("-h") || args.contains("--help")) {
        println("Usage: java [OPTIONS...] -c CID -s <SDF file> -i <index file> [-f] [-o <output image>]")
        println("Options:")
        println("   -c INT  : Compound ID, required")
        println("   -s FILE : input SDF file, may be uncompressed or BGZF compressed, required")
        println("   -i FILE : input index file, required")
        println("   -o FILE : output image file, optional")
        println("   -f      : force overwrite, optional")
        println("   -h      : help, print this message")
        exitProcess(1)
    }
    val sdfFile = if (args.contains("-s")) File(args[args.indexOf("-s") + 1]) else {
        println("error: SDF file not specified, use -s <SDF file>")
        exitProcess(1)
    }
    if (!sdfFile.exists()) {
        println("error: SDF file not found: ${sdfFile.absolutePath}")
        exitProcess(1)
    }
    val indexFile = if (args.contains("-i")) File(args[args.indexOf("-i") + 1]) else {
        println("error: index file not specified, use -i <index file>")
        exitProcess(1)
    }
    if (!indexFile.exists()) {
        println("error: index file not found: ${indexFile.absolutePath}")
        exitProcess(1)
    }
    val outputFile = if (args.contains("-o")) File(args[args.indexOf("-o") + 1]) else null
    if (outputFile != null && outputFile.exists() && !args.contains("-f")) {
        println("error: output file already exists: ${outputFile.absolutePath}")
        exitProcess(1)
    }
    if (outputFile == null && args.contains("-f")) {
        println("error: -f option requires -o <output image>")
        exitProcess(1)
    }
    val cid = if (args.contains("-c")) args[args.indexOf("-c") + 1].toInt() else {
        println("error: compound ID not specified, use -c <compound ID>")
        exitProcess(1)
    }
//    for (cid in 1..1000) {
    // check if SDF file is compressed
    val isCompressed = SdfUtils.isBgzFile(sdfFile)
    val sdfString: String?
    val indexItem = ChemTableIndex(ByteArray(ChemTableIndex.OBJECT_SIZE)).also { idx ->
        RandomAccessFile(indexFile, "r").use { file ->
            file.seek((cid * ChemTableIndex.OBJECT_SIZE).toLong())
            IoUtils.readExact(file, idx.ptrBytes, 0, ChemTableIndex.OBJECT_SIZE)
        }
    }

    if (indexItem.size == 0 || indexItem.cid == 0) {
        println("error: Compound ID $cid not found in index file")
        exitProcess(1)
//        continue
    }
    if (isCompressed) {
        sdfString = RandomAccessBgzFile(sdfFile).use {
            it.seek(indexItem.offset)
            val buf = ByteArray(indexItem.size)
            var remaining = indexItem.size
            var read = 0
            while (remaining > 0) {
                read = it.read(buf, read, remaining)
                remaining -= read
            }
            String(buf)
        }
    } else {
        sdfString = FileInputStream(sdfFile).use {
            IoUtils.skipExact(it, indexItem.offset)
            val buf = ByteArray(indexItem.size)
            IoUtils.readExact(it, buf, 0, indexItem.size)
            String(buf)
        }
    }
    println("info: SDF string size: ${sdfString.length}")
    val molecule = MdlMolParser.parseString(sdfString)
    println("info: atom count: ${molecule.atomCount()}, bond count: ${molecule.bondCount()}")
    val cfg = MoleculeRender.calculateRenderRect(molecule, 720)
    println("info: render rect: ${cfg.width}x${cfg.height}, scale: ${cfg.scaleFactor}, fontSize: ${cfg.fontSize}")
    val image = MoleculeRender.renderMoleculeAsImage(molecule, cfg)
    val imgData = image.encodeToData() ?: throw Exception("error: image encoding failed")
    image.close()
    println("info: image data size: ${imgData.size}")
    if (outputFile != null) {
//            IoUtils.writeFile(outputFile, imgData.bytes)
        IoUtils.writeFile(File("/tmp/419/cid_${cid}.png"), imgData.bytes)
    } else {
        println("info: no output file specified, not writing image")
    }
    imgData.close()
//    }
}
