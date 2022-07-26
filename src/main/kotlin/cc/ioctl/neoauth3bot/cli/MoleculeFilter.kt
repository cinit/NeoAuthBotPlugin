package cc.ioctl.neoauth3bot.cli

import cc.ioctl.neoauth3bot.chiral.ChiralCarbonHelper
import cc.ioctl.neoauth3bot.chiral.MdlMolParser
import cc.ioctl.neoauth3bot.dat.ChemTableIndex
import cc.ioctl.neoauth3bot.util.BinaryUtils
import cc.ioctl.telebot.util.IoUtils
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 6 || args.contains("-h") || args.contains("--help")) {
        println("Usage: java [OPTIONS...] [-b BEGIN] -e END -s <SDF file> -i <index file> [-f] -o <output file>")
        println("Options:")
        println("   -s FILE : input SDF file(uncompressed), required")
        println("   -i FILE : input index file, required")
        println("   -o FILE : output file, required")
        println("   -f      : force overwrite, optional")
        println("   -b INT  : begin index, inclusive, optional, default 0")
        println("   -e INT  : end index, inclusive, required")
        println("   -h      : help, print this message")
        exitProcess(1)
    }
    val sdfFilePath = if (args.contains("-s")) args[args.indexOf("-s") + 1] else {
        println("error: -s is required")
        exitProcess(1)
    }
    val indexFilePath = if (args.contains("-i")) args[args.indexOf("-i") + 1] else {
        println("error: -i is required")
        exitProcess(1)
    }
    val outputFilePath = if (args.contains("-o")) args[args.indexOf("-o") + 1] else {
        println("error: -o is required")
        exitProcess(1)
    }
    val forceOverwrite = args.contains("-f")
    val sdfFile = File(sdfFilePath)
    val indexFile = File(indexFilePath)
    val outputFile = File(outputFilePath)
    if (!sdfFile.exists()) {
        println("error: SDF file not found: $sdfFilePath")
        exitProcess(1)
    }
    if (!indexFile.exists()) {
        println("error: index file not found: $indexFilePath")
        exitProcess(1)
    }
    if (outputFile.exists() && !forceOverwrite) {
        println("error: output file already exists: $outputFilePath")
        exitProcess(1)
    }
    val startIndex = if (args.contains("-b")) args[args.indexOf("-b") + 1].toInt() else 0
    val endIndex = if (args.contains("-e")) args[args.indexOf("-e") + 1].toInt() else {
        println("error: -e is required")
        exitProcess(1)
    }
    val indexByteBuffer = RandomAccessFile(indexFile, "r")
    val sdfByteBuffer = RandomAccessFile(sdfFile, "r")
    outputFile.createNewFile()
    val resultIndexArray: ArrayList<Int> = ArrayList(10000)
    var validCounter = 0
    val chemIndex = ChemTableIndex(ByteArray(40))
    var buf: ByteArray? = null
    println("info: start index: $startIndex, end index: $endIndex")
    val startTime = System.currentTimeMillis()
    for (i in startIndex until endIndex + 1) {
        val objOffset = ChemTableIndex.OBJECT_SIZE * i
        // set the index to the object offset
        indexByteBuffer.seek(objOffset.toLong())
        IoUtils.readExact(indexByteBuffer, chemIndex.ptrBytes, 0, ChemTableIndex.OBJECT_SIZE)
        if (chemIndex.cid != 0) {

            if (chemIndex.isChiral) {
                val strlen = chemIndex.size
                val strOffset = chemIndex.offset

                if (buf == null || buf.size < strlen) {
                    buf = ByteArray(strlen)
                }
                sdfByteBuffer.seek(strOffset)
                IoUtils.readExact(sdfByteBuffer, buf, 0, strlen)
                val sdf = String(buf, 0, strlen)


                try {
                    val mol = MdlMolParser.parseString(sdf)

                    val cc = ChiralCarbonHelper.getMoleculeChiralCarbons(mol)

                    val isCandidate = cc.size > 3 || cc.size * mol.atomCount() > 200

                    if (isCandidate) {
                        resultIndexArray.add(i)
                    }
                } catch (e: Exception) {
                    println("cid: ${chemIndex.cid}, error: ${e.message}")
                    e.printStackTrace()
                    exitProcess(1)
                }
            }
            validCounter++
        }

        if (i % 10000 == 0) {
            println("$validCounter/${endIndex - startIndex + 1}, valid: ${validCounter}, candidate: ${resultIndexArray.size}")
        }
    }

    val endTime = System.currentTimeMillis()

    // write the result to the output file
    val resultData = ByteArray(4 * (resultIndexArray.size + 1))
    BinaryUtils.writeLe32(resultData, 0, resultIndexArray.size)
    for (i in 0 until resultIndexArray.size) {
        BinaryUtils.writeLe32(resultData, 4 * (i + 1), resultIndexArray[i])
    }
    val outputFileOutputStream = FileOutputStream(outputFile)
    outputFileOutputStream.write(resultData)
    outputFileOutputStream.close()
    val ns = endIndex - startIndex + 1
    val valid = validCounter
    val candidate = resultIndexArray.size
    println("info: candidate: $candidate(${candidate * 100.0 / valid}%), valid: $valid(${valid * 100.0 / ns}%), ns: $ns")
    println("info: time: ${endTime - startTime}ms, ${valid * 1000.0 / (endTime - startTime)}/s")
}
